package com.cordiact.core.plugin

import java.util.UUID
import java.util.concurrent.CancellationException

/** Validated declarations and their exact immutable source, never a runtime grant. */
class ExtensionPackageArtifact private constructor(
    val manifest: ExtensionPackageManifest,
    val format: Format,
    private val bytes: ByteArray,
) {
    enum class Format { DECLARATIVE, LEGACY_AUTHORED }

    fun copySource(): ByteArray = bytes.copyOf()

    companion object {
        fun decode(
            bytes: ByteArray,
            format: Format = Format.DECLARATIVE,
            legacyId: String? = null,
            hostVersion: String = EXTENSION_PACKAGE_HOST_VERSION,
            hostApi: Int = EXTENSION_PACKAGE_HOST_API,
        ): ExtensionPackageArtifact {
            require(bytes.size in 1..ExtensionPackageSource.MAX_SOURCE_BYTES) { "Invalid package source size" }
            val source = bytes.copyOf()
            val manifest = when (format) {
                Format.DECLARATIVE -> ExtensionPackageSource.compile(source, hostVersion, hostApi)
                Format.LEGACY_AUTHORED -> {
                    val authored = AuthoredPluginSource.compile(requireNotNull(legacyId), source)
                    ExtensionPackageManifest(
                        schemaVersion = 1,
                        id = authored.id,
                        version = "0.0.1",
                        title = authored.title,
                        description = "Migrated from an authored plugin.",
                        // Fixed migration format; a newer host must not reinterpret old bytes.
                        hostApi = 1,
                        minHostVersion = "0.1.0",
                        capabilities = setOf(ExtensionPackageCapability.TOOL_PUBLISH),
                        tools = java.util.Collections.unmodifiableList(authored.tools.map {
                            ExtensionPackageTool(it.name, it.description, it.text, it.operation)
                        }),
                        commands = emptyList(),
                        sha256 = authored.sha256,
                        sizeBytes = source.size,
                    ).also { require(it.compatibleWith(hostApi, hostVersion)) { "Incompatible authored package" } }
                }
            }
            return ExtensionPackageArtifact(manifest, format, source)
        }
    }
}

/** One installed generation, its enable intent and an optional rollback artifact. */
data class ExtensionPackageRecord(
    val workspaceId: String,
    val artifact: ExtensionPackageArtifact,
    val installationId: String,
    val installedAtEpochMillis: Long,
    val previous: ExtensionPackageArtifact? = null,
    val enableIntent: Boolean = false,
) {
    val manifest get() = artifact.manifest
    // A receipt is written only after consent for the complete set, never for a subset.
    val grantedCapabilities get() = manifest.capabilities
}

enum class ExtensionPackageStoreWrite { CHANGED, STALE, QUOTA_EXCEEDED }

/** Reads fail explicitly. The store never treats unreadable data as an empty row. */
class ExtensionPackageStoreException(val commitUnknown: Boolean = false, cause: Throwable? = null) :
    IllegalStateException(if (commitUnknown) "PACKAGE_COMMIT_UNKNOWN" else "PACKAGE_STORAGE_UNAVAILABLE", cause)

/** The generation comparison, quota and write must share one storage transaction. */
interface ExtensionPackageStore {
    fun list(workspaceId: String): List<ExtensionPackageRecord>
    fun get(workspaceId: String, packageId: String): ExtensionPackageRecord?

    /** A null [expectedInstallationId] requires absence. A null [replacement] removes a row. */
    fun compareAndSet(
        workspaceId: String,
        packageId: String,
        expectedInstallationId: String?,
        replacement: ExtensionPackageRecord?,
        publish: (() -> Unit) -> Unit = { it() },
    ): ExtensionPackageStoreWrite

    companion object { const val MAX_PER_WORKSPACE = 32 }
}

class InMemoryExtensionPackageStore : ExtensionPackageStore {
    private val values = linkedMapOf<Pair<String, String>, ExtensionPackageRecord>()

    @Synchronized
    override fun list(workspaceId: String): List<ExtensionPackageRecord> =
        values.entries.filter { it.key.first == workspaceId }.map { it.value }.sortedBy { it.manifest.id }

    @Synchronized
    override fun get(workspaceId: String, packageId: String): ExtensionPackageRecord? = values[workspaceId to packageId]

    @Synchronized
    override fun compareAndSet(
        workspaceId: String, packageId: String, expectedInstallationId: String?, replacement: ExtensionPackageRecord?,
        publish: (() -> Unit) -> Unit,
    ): ExtensionPackageStoreWrite {
        val key = workspaceId to packageId
        val current = values[key]
        if (current?.installationId != expectedInstallationId) return ExtensionPackageStoreWrite.STALE
        if (replacement == null) require(current != null) else {
            require(replacement.workspaceId == workspaceId && replacement.manifest.id == packageId &&
                replacement.installationId != expectedInstallationId)
            if (current == null && values.keys.count { it.first == workspaceId } >= ExtensionPackageStore.MAX_PER_WORKSPACE) {
                return ExtensionPackageStoreWrite.QUOTA_EXCEEDED
            }
        }
        var published = false
        val thread = Thread.currentThread()
        try {
            publish {
                check(Thread.currentThread() === thread && Thread.holdsLock(this) && !published)
                if (replacement == null) values.remove(key) else values[key] = replacement
                published = true
            }
            check(published)
        } catch (failure: Exception) {
            if (published) throw ExtensionPackageStoreException(commitUnknown = true, cause = failure)
            throw failure
        }
        return ExtensionPackageStoreWrite.CHANGED
    }
}

sealed interface ExtensionPackageChange {
    data class Applied(val record: ExtensionPackageRecord, val upgraded: Boolean) : ExtensionPackageChange
    data class Unchanged(val record: ExtensionPackageRecord) : ExtensionPackageChange
    data class ConsentRequired(val manifest: ExtensionPackageManifest, val missing: Set<ExtensionPackageCapability>) : ExtensionPackageChange
    data class Rejected(val code: Code, val message: String) : ExtensionPackageChange
    data object Removed : ExtensionPackageChange

    enum class Code {
        INVALID_MANIFEST, VERSION_NOT_NEWER, CONFLICT, NOT_FOUND, NO_PREVIOUS,
        STALE, QUOTA_EXCEEDED, STORE_FAILURE, COMMIT_UNKNOWN,
    }
}

/**
 * Stores approved content without activating it. Callers must be trusted host code that checks the
 * workspace and consent at the operation boundary. All edits name the generation shown to the user.
 */
class ExtensionPackageInstaller(
    private val store: ExtensionPackageStore,
    private val hostVersion: String = EXTENSION_PACKAGE_HOST_VERSION,
    private val hostApi: Int = EXTENSION_PACKAGE_HOST_API,
    private val clock: () -> Long = System::currentTimeMillis,
    private val newInstallationId: () -> String = { UUID.randomUUID().toString() },
) {
    /** The immutable preview is the exact candidate; no path or mutable buffer is used to commit it. */
    fun inspect(bytes: ByteArray): ExtensionPackageArtifact =
        ExtensionPackageArtifact.decode(bytes, hostVersion = hostVersion, hostApi = hostApi)

    fun inspectLegacy(pluginId: String, bytes: ByteArray): ExtensionPackageArtifact =
        ExtensionPackageArtifact.decode(bytes, ExtensionPackageArtifact.Format.LEGACY_AUTHORED, pluginId, hostVersion, hostApi)

    fun list(workspaceId: String): List<ExtensionPackageRecord> = store.list(workspaceId)

    fun setEnableIntent(
        workspaceId: String, packageId: String, expectedInstallationId: String, enabled: Boolean,
        publish: (() -> Unit) -> Unit = { it() },
    ): ExtensionPackageChange = storageOperation {
        val current = store.get(workspaceId, packageId) ?: return@storageOperation notFound()
        if (current.installationId != expectedInstallationId) return@storageOperation stale()
        if (current.enableIntent == enabled) return@storageOperation ExtensionPackageChange.Unchanged(current)
        // An exact state-changing confirmation cannot be reused after an off/on cycle.
        val updated = current.copy(installationId = newInstallationId(), enableIntent = enabled)
        write(workspaceId, packageId, expectedInstallationId, updated, ExtensionPackageChange.Applied(updated, upgraded = false), publish)
    }

    /** A null expected generation requests a fresh install only. */
    fun install(
        workspaceId: String,
        candidate: ExtensionPackageArtifact,
        expectedInstallationId: String?,
        granted: Set<ExtensionPackageCapability>,
        publish: (() -> Unit) -> Unit = { it() },
    ): ExtensionPackageChange = storageOperation {
        val manifest = candidate.manifest
        if (!manifest.compatibleWith(hostApi, hostVersion)) {
            return@storageOperation rejected(ExtensionPackageChange.Code.INVALID_MANIFEST, "Incompatible package")
        }
        val current = store.get(workspaceId, manifest.id)
        if (current?.installationId != expectedInstallationId) return@storageOperation stale()
        val missing = manifest.capabilities - granted
        if (missing.isNotEmpty()) return@storageOperation ExtensionPackageChange.ConsentRequired(manifest, missing)
        if (current != null) {
            val order = ExtensionPackageSource.compareVersions(manifest.version, current.manifest.version)
            if (order < 0) return@storageOperation rejected(ExtensionPackageChange.Code.VERSION_NOT_NEWER, "The installed version is newer")
            if (order == 0) return@storageOperation if (manifest.sha256 == current.manifest.sha256 && candidate.format == current.artifact.format) {
                ExtensionPackageChange.Unchanged(current)
            } else rejected(ExtensionPackageChange.Code.CONFLICT, "The same version is installed with different content")
        }
        val record = ExtensionPackageRecord(workspaceId, candidate, newInstallationId(), clock(), current?.artifact)
        write(workspaceId, manifest.id, expectedInstallationId, record, ExtensionPackageChange.Applied(record, upgraded = current != null), publish)
    }

    fun rollback(
        workspaceId: String, packageId: String, expectedInstallationId: String,
        publish: (() -> Unit) -> Unit = { it() },
    ): ExtensionPackageChange = storageOperation {
        val current = store.get(workspaceId, packageId) ?: return@storageOperation notFound()
        if (current.installationId != expectedInstallationId) return@storageOperation stale()
        val previous = current.previous ?: return@storageOperation rejected(ExtensionPackageChange.Code.NO_PREVIOUS, "No retained previous version")
        if (!previous.manifest.compatibleWith(hostApi, hostVersion)) {
            return@storageOperation rejected(ExtensionPackageChange.Code.INVALID_MANIFEST, "Incompatible previous version")
        }
        val record = ExtensionPackageRecord(workspaceId, previous, newInstallationId(), clock())
        write(workspaceId, packageId, expectedInstallationId, record, ExtensionPackageChange.Applied(record, upgraded = false), publish)
    }

    fun remove(
        workspaceId: String, packageId: String, expectedInstallationId: String,
        publish: (() -> Unit) -> Unit = { it() },
    ): ExtensionPackageChange = storageOperation {
        write(workspaceId, packageId, expectedInstallationId, null, ExtensionPackageChange.Removed, publish)
    }

    private fun write(
        workspaceId: String, packageId: String, expected: String?, replacement: ExtensionPackageRecord?, success: ExtensionPackageChange,
        publish: (() -> Unit) -> Unit,
    ): ExtensionPackageChange = when (store.compareAndSet(workspaceId, packageId, expected, replacement, publish)) {
        ExtensionPackageStoreWrite.CHANGED -> success
        ExtensionPackageStoreWrite.STALE -> stale()
        ExtensionPackageStoreWrite.QUOTA_EXCEEDED -> rejected(ExtensionPackageChange.Code.QUOTA_EXCEEDED, "Workspace package quota reached")
    }

    private inline fun storageOperation(operation: () -> ExtensionPackageChange): ExtensionPackageChange = try {
        operation()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: ExtensionPackageStoreException) {
        if (failure.commitUnknown) rejected(ExtensionPackageChange.Code.COMMIT_UNKNOWN, "Refresh the installed list before another change")
        else rejected(ExtensionPackageChange.Code.STORE_FAILURE, "Could not access package storage")
    }

    private fun stale() = rejected(ExtensionPackageChange.Code.STALE, "The installed generation has changed; refresh before retrying")
    private fun notFound() = rejected(ExtensionPackageChange.Code.NOT_FOUND, "Package is not installed in this workspace")
    private fun rejected(code: ExtensionPackageChange.Code, message: String) = ExtensionPackageChange.Rejected(code, message)
}

private fun ExtensionPackageManifest.compatibleWith(api: Int, version: String): Boolean =
    hostApi == api && ExtensionPackageSource.compareVersions(minHostVersion, version) <= 0
