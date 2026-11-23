package com.cordiact.core.plugin

import com.cordiact.core.file.WorkspaceFileException
import com.cordiact.core.file.WorkspaceFileService
import java.security.MessageDigest
import java.time.Clock
import java.util.Collections
import java.util.UUID

/** Version manifest, not an activation, permission grant, or a reference to a mutable draft. */
data class AuthoredPluginInstalledRecord(
    val workspaceId: String,
    val id: String,
    val sha256: String,
    val schemaVersion: Int,
    val formatVersion: Int,
    val sizeBytes: Int,
    /** Informational wall clock only; not proof of approval or a version ordering rule. */
    val installedAtEpochMillis: Long,
    val title: String,
    val toolIds: List<String>,
    /** The durable generation of this installation, independent of its bytes and wall clock. */
    val installationId: String = "",
)

enum class AuthoredPluginInstallationStatus { READY, CORRUPT, UNSUPPORTED }

/** Only READY exposes a validated manifest. READY means readable and intact, never running. */
data class AuthoredPluginInstallationEntry(
    val id: String,
    val status: AuthoredPluginInstallationStatus,
    val record: AuthoredPluginInstalledRecord?,
)

sealed interface AuthoredPluginStoredInstallation {
    val id: String

    /** Exact source bytes, without reserialization; the Store must not expose shared mutable buffers. */
    data class Snapshot(val record: AuthoredPluginInstalledRecord, val sourceBytes: ByteArray) : AuthoredPluginStoredInstallation {
        override val id: String get() = record.id
    }

    /** A row with a known canonical key and malformed, missing, or oversized data. */
    data class Broken(override val id: String) : AuthoredPluginStoredInstallation

    /** A row with a known canonical key but a newer/unknown storage envelope. */
    data class Unsupported(override val id: String) : AuthoredPluginStoredInstallation
}

sealed interface AuthoredPluginInstallationWriteResult {
    data object Inserted : AuthoredPluginInstallationWriteResult
    data class Existing(val installation: AuthoredPluginStoredInstallation) : AuthoredPluginInstallationWriteResult
    data object QuotaExceeded : AuthoredPluginInstallationWriteResult
}

enum class AuthoredPluginInstallationChangeResult { CHANGED, NOT_FOUND, STALE }

class AuthoredPluginInstallationStoreException(val code: Code, message: String) : IllegalStateException(message) {
    enum class Code { WORKSPACE_UNAVAILABLE, ACCESS_DENIED, STORAGE_FAILURE, COMMIT_UNKNOWN }
}

class AuthoredPluginInstallationException(val code: Code, message: String) : IllegalStateException(message) {
    enum class Code { CALLER_UNAVAILABLE, DENIED, INVALID_SOURCE, VERSION_CHANGED, CONFLICT, QUOTA, CORRUPT, UNSUPPORTED, NOT_FOUND }
}

/**
 * Host-private durable storage: neither workspace content, an Agent, nor a portable source import
 * may create or rewrite approved records. Workspace IDs are opaque keys (never paths), and IDs
 * must be canonical [AuthoredPluginSource.path] slugs. The adapter binds these to trusted keys and
 * checks the requested workspace's availability/access; Core checks the host's foreground policy.
 * Approval comes from the trusted manifest written by this service, not from a digest by itself.
 * Digest checks protect against inconsistent/changed content, not a rewrite of the trusted Store.
 */
interface AuthoredPluginInstallationStore {
    /**
     * Return a consistent, detached view of just this workspace. Use Broken/Unsupported for an
     * individual unreadable row with a known key, without hiding its peers. Bounds must be enforced
     * before reading/decoding an untrusted payload: at most [AuthoredPluginInstallations.MAX_PER_WORKSPACE]
     * rows, [AuthoredPluginSource.MAX_SOURCE_BYTES] source bytes per row, and bounded manifest data.
     * A failed query, denied/unavailable workspace, unknown workspace-wide format, invalid row key,
     * or overflow is a typed [AuthoredPluginInstallationStoreException], not an empty list.
     */
    fun list(workspaceId: String): List<AuthoredPluginStoredInstallation>

    /**
     * Atomic, durable insert-if-absent across all instances/processes sharing this storage.
     * Under the same transaction/lock, check the exact key first (including broken/unsupported rows),
     * then enforce the workspace row cap, then insert both manifest and bytes or neither. Existing
     * rows occupy their slot even if invalid; this operation never upserts, repairs, or evicts.
     * The Store must return Existing for the exact key whether or not its digest matches. A new
     * installation must have a canonical UUID, which the Store preserves exactly.
     *
     * For a new insert only, after invisible preparation, invoke [commit] exactly once, synchronously
     * on the caller's thread. Put only the final durable publication in its block (e.g. the actual
     * transaction commit, not just marking it successful). Do not defer, replay, publish outside
     * this gate, or wait for user input. An exception from the gate before publication aborts all
     * staging and is propagated unchanged. Inserted means publication completed durably; if publication
     * may have occurred but durability/outcome cannot be determined, throw COMMIT_UNKNOWN and do not
     * retry. Other typed Store errors mean no new record was published. Existing/QuotaExceeded do not
     * call the gate. No read or write may take a user-supplied path.
     */
    fun insertIfAbsent(
        workspaceId: String,
        installation: AuthoredPluginStoredInstallation.Snapshot,
        commit: (() -> Unit) -> Unit,
    ): AuthoredPluginInstallationWriteResult

    /**
     * Atomic compare-and-change at the exact workspace/plugin key. Under one write transaction or
     * lock shared across instances/processes, return NOT_FOUND for a missing key, or STALE unless
     * the row is a valid Snapshot with the exact [expected] manifest (including its canonical
     * installation UUID and source SHA-256, with intact source bytes). Broken/Unsupported rows
     * cannot be changed. Empty UUIDs never match. A non-null [replacement] must have the same
     * workspace/plugin key and a fresh, distinct canonical installation UUID. Null deletes the
     * existing row. This operation cannot insert a missing row or increase the workspace row count.
     *
     * Only CHANGED uses [commit], with the same synchronous durable publication contract as
     * [insertIfAbsent]. A veto aborts all staging, and an uncertain publication is COMMIT_UNKNOWN:
     * inspect the durable state before making any new confirmation; never replay the old request.
     */
    fun changeIfCurrent(
        workspaceId: String,
        expected: AuthoredPluginInstalledRecord,
        replacement: AuthoredPluginStoredInstallation.Snapshot?,
        commit: (() -> Unit) -> Unit,
    ): AuthoredPluginInstallationChangeResult
}

/**
 * Explicit foreground changes and read-only review of stored snapshots. This service never
 * registers, enables, restores, or executes a plugin. Only save/replace read a draft.
 */
class AuthoredPluginInstallations(
    private val files: WorkspaceFileService,
    private val store: AuthoredPluginInstallationStore,
    private val clock: Clock = Clock.systemUTC(),
) {
    /**
     * Trusted UI only, after the user has confirmed this exact ID and SHA-256 for installation.
     * [confirmation] supplies the owner of the formal read and write; a normal Agent turn is not a
     * confirmation. [maySave] must check that owner's live authorization, not just a remembered UI
     * selection or model input. It may be called while a Store transaction is open; it must not call
     * the installation Store or wait for user input. The host must invalidate the confirmation when
     * its foreground authority is revoked. A later draft edit cannot affect the frozen bytes read
     * here or otherwise change what is committed.
     */
    fun saveConfirmed(
        confirmation: AuthoredPluginTurn,
        pluginId: String,
        expectedSha256: String,
        maySave: () -> Boolean,
    ): AuthoredPluginInstalledRecord {
        if (!confirmation.claimConfirmation()) unavailable()
        try {
            checkAccess(confirmation, maySave)
            val workspaceId = confirmation.workspaceId
            val snapshot = prepareSnapshot(workspaceId, pluginId, expectedSha256)
            val candidate = snapshot.record
            checkAccess(confirmation, maySave)
            val result = publishConfirmed<AuthoredPluginInstallationWriteResult>(
                confirmation, maySave, AuthoredPluginInstallationWriteResult.Inserted,
            ) { commit ->
                store.insertIfAbsent(workspaceId, snapshot, commit)
            }
            return when (result) {
                AuthoredPluginInstallationWriteResult.Inserted -> candidate
                is AuthoredPluginInstallationWriteResult.Existing -> {
                    val old = result.installation
                    if (old.id != pluginId || !belongsTo(old, workspaceId)) storageFailure()
                    val verified = verify(workspaceId, old)
                    val sameBytes = old is AuthoredPluginStoredInstallation.Snapshot && old.sourceBytes.contentEquals(snapshot.sourceBytes)
                    checkAccess(confirmation, maySave)
                    withTurnGate(confirmation) {
                        requireVerified(verified).record.also {
                            if (it.sha256 != expectedSha256 || !sameBytes) {
                                refuse(AuthoredPluginInstallationException.Code.CONFLICT, "A different approved version is already installed")
                            }
                        }
                    }
                }
                AuthoredPluginInstallationWriteResult.QuotaExceeded -> {
                    checkAccess(confirmation, maySave)
                    withTurnGate(confirmation) {
                        refuse(AuthoredPluginInstallationException.Code.QUOTA, "This workspace has reached the installed plugin limit")
                    }
                }
            }
        } finally {
            confirmation.close()
        }
    }

    /**
     * Trusted UI only, after confirming removal of this exact durable [expected] version for the
     * confirmation's owner. [mayRemove] follows the same live authorization rules as `maySave`.
     * This only removes the stored snapshot, not a draft or any active instance.
     */
    fun removeConfirmed(
        confirmation: AuthoredPluginTurn,
        expected: AuthoredPluginInstalledRecord,
        mayRemove: () -> Boolean,
    ): Unit {
        changeConfirmed(confirmation, expected, mayRemove) { null }
    }

    /**
     * Trusted UI only, after reviewing the exact durable [expected] version and the new raw source
     * digest. [mayReplace] follows the same live authorization rules as `maySave`. The replacement
     * is a new durable generation, even when its bytes are unchanged. Nothing is activated.
     */
    fun replaceConfirmed(
        confirmation: AuthoredPluginTurn,
        expected: AuthoredPluginInstalledRecord,
        expectedNewSha256: String,
        mayReplace: () -> Boolean,
    ): AuthoredPluginInstalledRecord {
        val replacement = changeConfirmed(confirmation, expected, mayReplace) { current ->
            prepareSnapshot(current.workspaceId, current.id, expectedNewSha256)
        }
        return requireNotNull(replacement)
    }

    private fun changeConfirmed(
        confirmation: AuthoredPluginTurn,
        expected: AuthoredPluginInstalledRecord,
        mayChange: () -> Boolean,
        prepare: (AuthoredPluginInstalledRecord) -> AuthoredPluginStoredInstallation.Snapshot?,
    ): AuthoredPluginInstalledRecord? {
        if (!confirmation.claimConfirmation()) unavailable()
        try {
            if (confirmation.workspaceId.isBlank() || confirmation.sessionId.isBlank()) unavailable()
            checkAccess(confirmation, mayChange)
            val workspaceId = confirmation.workspaceId
            if (expected.workspaceId != workspaceId) denied()
            val current = readVerified(workspaceId, expected.id, expected.sha256, expected.installationId) {
                checkAccess(confirmation, mayChange)
            }.record
            if (current != expected) installedChanged()
            val replacement = prepare(current)
            checkAccess(confirmation, mayChange)
            val result = publishConfirmed(confirmation, mayChange, AuthoredPluginInstallationChangeResult.CHANGED) { commit ->
                store.changeIfCurrent(workspaceId, expected, replacement, commit)
            }
            if (result != AuthoredPluginInstallationChangeResult.CHANGED) {
                checkAccess(confirmation, mayChange)
                withTurnGate(confirmation) {
                    if (result == AuthoredPluginInstallationChangeResult.NOT_FOUND) notFound()
                    installedChanged()
                }
            }
            return replacement?.record
        } finally {
            confirmation.close()
        }
    }

    /** Trusted host only: the caller authorizes this exact workspace's read before exposing results. */
    fun list(workspaceId: String): List<AuthoredPluginInstallationEntry> =
        readStored(workspaceId).groupBy { it.id }.toSortedMap().map { (id, copies) ->
            val verification = if (copies.size == 1) verify(workspaceId, copies.single()) else Verification.Corrupt
            AuthoredPluginInstallationEntry(id, verification.status, (verification as? Verification.Ready)?.record)
        }

    /**
     * Trusted host only: authorize this exact workspace's read before showing the review. This reads
     * only the durable installation, never a draft, and grants no authority to run the definition.
     * Pass [expectedInstallationId] when reviewing a selected record, to reject a newer generation
     * even if its bytes are unchanged.
     */
    fun previewSaved(
        workspaceId: String,
        pluginId: String,
        expectedSha256: String,
        expectedInstallationId: String? = null,
    ): AuthoredPluginDefinition = readVerified(workspaceId, pluginId, expectedSha256, expectedInstallationId).definition

    /** Returns the exact validated snapshot as a package candidate, without granting install or run. */
    fun previewSavedPackage(record: AuthoredPluginInstalledRecord): ExtensionPackageArtifact {
        val verified = readVerified(record.workspaceId, record.id, record.sha256, record.installationId)
        if (verified.record != record) installedChanged()
        return ExtensionPackageArtifact.decode(verified.source, ExtensionPackageArtifact.Format.LEGACY_AUTHORED, record.id)
    }

    private fun readVerified(
        workspaceId: String,
        pluginId: String,
        expectedSha256: String,
        expectedInstallationId: String?,
        checkAfterRead: () -> Unit = {},
    ): Verification.Ready {
        if (!canonicalId(pluginId)) invalidSource()
        if (!SHA256.matches(expectedSha256) ||
            (expectedInstallationId != null && !INSTALLATION_ID.matches(expectedInstallationId))
        ) installedChanged()
        val matches = readStored(workspaceId).filter { it.id == pluginId }
        val verification = when (matches.size) {
            0 -> null
            1 -> verify(workspaceId, matches.single())
            else -> Verification.Corrupt
        }
        checkAfterRead()
        val verified = requireVerified(verification ?: notFound())
        if (verified.record.sha256 != expectedSha256 ||
            (expectedInstallationId != null && verified.record.installationId != expectedInstallationId)
        ) installedChanged()
        return verified
    }

    private fun readStored(workspaceId: String): List<AuthoredPluginStoredInstallation> {
        if (workspaceId.isBlank()) storeFailure(AuthoredPluginInstallationStoreException.Code.WORKSPACE_UNAVAILABLE)
        val rows = try {
            store.list(workspaceId)
        } catch (failure: AuthoredPluginInstallationStoreException) {
            storeFailure(failure.code)
        } catch (failure: Exception) {
            if (failure is InterruptedException) Thread.currentThread().interrupt()
            storageFailure()
        }
        if (rows.size > MAX_PER_WORKSPACE || rows.any { !canonicalId(it.id) || !belongsTo(it, workspaceId) }) {
            storageFailure()
        }
        return rows
    }

    private fun readSource(workspaceId: String, path: String): ByteArray {
        val source = try {
            // A read clipped at exactly MAX_SOURCE_BYTES could accept a valid prefix of an oversized draft.
            files.read(workspaceId, path, offset = 0, maxBytes = AuthoredPluginSource.MAX_SOURCE_BYTES + 1).bytes
        } catch (failure: WorkspaceFileException) {
            throw WorkspaceFileException(failure.code, "Could not read the workspace plugin source")
        } catch (failure: Exception) {
            if (failure is InterruptedException) Thread.currentThread().interrupt()
            throw WorkspaceFileException(WorkspaceFileException.Code.STORAGE_FAILURE, "Could not read the workspace plugin source")
        }
        if (source.size > AuthoredPluginSource.MAX_SOURCE_BYTES) {
            throw WorkspaceFileException(WorkspaceFileException.Code.SIZE_LIMIT_EXCEEDED, "Plugin source size limit exceeded")
        }
        return source.copyOf()
    }

    private fun prepareSnapshot(
        workspaceId: String,
        pluginId: String,
        expectedSha256: String,
    ): AuthoredPluginStoredInstallation.Snapshot {
        val path = try {
            AuthoredPluginSource.path(pluginId)
        } catch (_: IllegalArgumentException) {
            invalidSource()
        }
        if (!SHA256.matches(expectedSha256)) changed()
        val raw = readSource(workspaceId, path)
        // An edit to an invalid document is still a version change, not the approved document.
        if (sha256(raw) != expectedSha256) changed()
        val definition = try {
            AuthoredPluginSource.compile(pluginId, raw)
        } catch (_: IllegalArgumentException) {
            invalidSource()
        }
        val record = manifest(workspaceId, definition, raw.size, clock.millis(), UUID.randomUUID().toString())
        return AuthoredPluginStoredInstallation.Snapshot(record, raw)
    }

    private fun <T> publishConfirmed(
        confirmation: AuthoredPluginTurn,
        mayPublish: () -> Boolean,
        changedResult: T,
        operation: (commit: (() -> Unit) -> Unit) -> T,
    ): T {
        val thread = Thread.currentThread()
        var acceptingCommit = true
        var gateUsed = false
        var publicationStarted = false
        var publicationFinished = false
        val result = try {
            operation { publish ->
                if (Thread.currentThread() !== thread || !acceptingCommit) storageFailure()
                if (gateUsed) {
                    if (publicationStarted) commitUnknown()
                    storageFailure()
                }
                gateUsed = true
                // Do potentially locking host policy reads outside the turn gate. Once inside,
                // foreground cancellation and publication are serialized against one another.
                checkAccess(confirmation, mayPublish)
                withTurnGate(confirmation) {
                    publicationStarted = true
                    publish()
                    publicationFinished = true
                }
            }
        } catch (failure: AuthoredPluginInstallationException) {
            if (publicationStarted) commitUnknown()
            throw failure
        } catch (failure: AuthoredPluginInstallationStoreException) {
            if (publicationFinished) commitUnknown()
            storeFailure(failure.code)
        } catch (failure: Exception) {
            if (failure is InterruptedException) Thread.currentThread().interrupt()
            if (publicationStarted) commitUnknown()
            storageFailure()
        } finally {
            acceptingCommit = false
        }
        if (result == changedResult) {
            if (!publicationFinished) commitUnknown()
        } else if (gateUsed) {
            if (publicationStarted) commitUnknown()
            storageFailure()
        }
        return result
    }

    private sealed class Verification(val status: AuthoredPluginInstallationStatus) {
        data class Ready(val record: AuthoredPluginInstalledRecord, val definition: AuthoredPluginDefinition, val source: ByteArray) :
            Verification(AuthoredPluginInstallationStatus.READY)
        data object Corrupt : Verification(AuthoredPluginInstallationStatus.CORRUPT)
        data object Unsupported : Verification(AuthoredPluginInstallationStatus.UNSUPPORTED)
    }

    private fun verify(workspaceId: String, stored: AuthoredPluginStoredInstallation): Verification {
        return when (stored) {
            is AuthoredPluginStoredInstallation.Broken -> Verification.Corrupt
            is AuthoredPluginStoredInstallation.Unsupported -> Verification.Unsupported
            is AuthoredPluginStoredInstallation.Snapshot -> {
                val record = stored.record
                if (record.formatVersion <= 0) return Verification.Corrupt
                if (record.formatVersion != FORMAT_VERSION) return Verification.Unsupported
                val bytes = stored.sourceBytes.copyOf()
                if (record.workspaceId != workspaceId || bytes.size !in 1..AuthoredPluginSource.MAX_SOURCE_BYTES ||
                    record.sizeBytes != bytes.size ||
                    !SHA256.matches(record.sha256) || record.schemaVersion <= 0 ||
                    !INSTALLATION_ID.matches(record.installationId)
                ) return Verification.Corrupt
                // The compiler freezes the bytes, returns immutable tools, and hashes those exact
                // bytes. The derived manifest checks that digest together with the other fields.
                val definition = try {
                    AuthoredPluginSource.compile(stored.id, bytes)
                } catch (_: IllegalArgumentException) {
                    // A supported source with a forged newer marker still fails the manifest check.
                    return if (record.schemaVersion > MAX_SUPPORTED_SCHEMA && sha256(bytes) == record.sha256) {
                        Verification.Unsupported
                    } else Verification.Corrupt
                }
                val verified = manifest(workspaceId, definition, bytes.size, record.installedAtEpochMillis, record.installationId)
                if (verified != record) return Verification.Corrupt
                if (definition.schemaVersion !in 1..MAX_SUPPORTED_SCHEMA) return Verification.Unsupported
                Verification.Ready(verified, definition, bytes)
            }
        }
    }

    private fun requireVerified(verification: Verification): Verification.Ready = when (verification) {
        is Verification.Ready -> verification
        Verification.Corrupt -> refuse(AuthoredPluginInstallationException.Code.CORRUPT, "The installed version is damaged")
        Verification.Unsupported -> refuse(AuthoredPluginInstallationException.Code.UNSUPPORTED, "The installed version is unsupported")
    }

    private fun manifest(
        workspaceId: String,
        definition: AuthoredPluginDefinition,
        sizeBytes: Int,
        timestamp: Long,
        installationId: String,
    ) = AuthoredPluginInstalledRecord(
        workspaceId = workspaceId,
        id = definition.id,
        sha256 = definition.sha256,
        schemaVersion = definition.schemaVersion,
        formatVersion = FORMAT_VERSION,
        sizeBytes = sizeBytes,
        installedAtEpochMillis = timestamp,
        title = definition.title,
        toolIds = Collections.unmodifiableList(definition.tools.map { AuthoredPluginNames.tool(definition.id, it.name) }),
        installationId = installationId,
    )

    private fun canonicalId(id: String): Boolean = try {
        AuthoredPluginSource.path(id)
        true
    } catch (_: IllegalArgumentException) {
        false
    }

    private fun belongsTo(stored: AuthoredPluginStoredInstallation, workspaceId: String): Boolean =
        stored !is AuthoredPluginStoredInstallation.Snapshot || stored.record.workspaceId == workspaceId

    private fun checkAccess(confirmation: AuthoredPluginTurn, mayChange: () -> Boolean) {
        requireLive(confirmation)
        val permitted = try {
            mayChange()
        } catch (failure: Exception) {
            if (failure is InterruptedException) Thread.currentThread().interrupt()
            false
        }
        requireLive(confirmation)
        if (!permitted) denied()
    }

    private fun requireLive(confirmation: AuthoredPluginTurn) {
        if (!confirmation.isOpen || Thread.currentThread().isInterrupted) unavailable()
    }

    private fun <T> withTurnGate(confirmation: AuthoredPluginTurn, block: () -> T): T = try {
        confirmation.commit(block)
    } catch (failure: AuthoredPluginException) {
        if (failure.code == AuthoredPluginException.Code.CALLER_UNAVAILABLE) unavailable()
        throw failure
    }

    private fun changed(): Nothing = refuse(AuthoredPluginInstallationException.Code.VERSION_CHANGED, "The source version changed; inspect and confirm it again")
    private fun installedChanged(): Nothing = refuse(AuthoredPluginInstallationException.Code.VERSION_CHANGED, "The installed version changed; inspect and confirm it again")
    private fun notFound(): Nothing = refuse(AuthoredPluginInstallationException.Code.NOT_FOUND, "The installed version was not found")
    private fun denied(): Nothing = refuse(AuthoredPluginInstallationException.Code.DENIED, "Installation is not authorized in this workspace")
    private fun invalidSource(): Nothing = refuse(AuthoredPluginInstallationException.Code.INVALID_SOURCE, "The plugin source is invalid or unsupported")
    private fun unavailable(): Nothing = refuse(AuthoredPluginInstallationException.Code.CALLER_UNAVAILABLE, "Select a live foreground session and confirm the installation")
    private fun refuse(code: AuthoredPluginInstallationException.Code, message: String): Nothing =
        throw AuthoredPluginInstallationException(code, message)

    private fun storageFailure(): Nothing = storeFailure(AuthoredPluginInstallationStoreException.Code.STORAGE_FAILURE)
    private fun commitUnknown(): Nothing = storeFailure(AuthoredPluginInstallationStoreException.Code.COMMIT_UNKNOWN)

    private fun storeFailure(code: AuthoredPluginInstallationStoreException.Code): Nothing =
        throw AuthoredPluginInstallationStoreException(code, when (code) {
            AuthoredPluginInstallationStoreException.Code.WORKSPACE_UNAVAILABLE -> "The installation workspace is unavailable"
            AuthoredPluginInstallationStoreException.Code.ACCESS_DENIED -> "Plugin installation storage access was denied"
            AuthoredPluginInstallationStoreException.Code.STORAGE_FAILURE -> "Could not access plugin installation storage"
            AuthoredPluginInstallationStoreException.Code.COMMIT_UNKNOWN -> "Installation outcome is unknown; reopen the installed versions list"
        })

    companion object {
        const val FORMAT_VERSION = 1
        const val MAX_PER_WORKSPACE = 32
        private const val MAX_SUPPORTED_SCHEMA = 2
        private val SHA256 = Regex("[0-9a-f]{64}")
        private val INSTALLATION_ID = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")

        private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).toHexString()
    }
}
