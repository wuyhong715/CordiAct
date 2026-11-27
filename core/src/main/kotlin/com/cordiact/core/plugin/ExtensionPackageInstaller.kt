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

/** Historical attribution, never proof of current trust or permission to execute. */
data class ExtensionPackageSourceReceipt(
    val sourceId: String,
    val publisherId: String,
    val keyId: String,
    val trustRevision: Long,
    val indexRevision: Long,
) {
    init {
        require(listOf(sourceId, publisherId, keyId).all { identity.matches(it) })
        require(trustRevision in 1..MAX_REVISION && indexRevision in 1..MAX_REVISION)
    }

    fun samePublisher(other: ExtensionPackageSourceReceipt): Boolean = sourceId == other.sourceId && publisherId == other.publisherId

    private companion object {
        val identity = Regex("[a-z][a-z0-9-]{0,31}")
        const val MAX_REVISION = 9_999_999_999_999_999L
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
    val sourceReceipt: ExtensionPackageSourceReceipt? = null,
    val previousSourceReceipt: ExtensionPackageSourceReceipt? = null,
) {
    init {
        require(sourceReceipt == null || artifact.format == ExtensionPackageArtifact.Format.DECLARATIVE)
        if (previous == null) require(previousSourceReceipt == null)
        else {
            require(previousSourceReceipt == null || previous.format == ExtensionPackageArtifact.Format.DECLARATIVE)
            require(if (sourceReceipt == null) previousSourceReceipt == null
                else previousSourceReceipt != null && sourceReceipt.samePublisher(previousSourceReceipt))
        }
    }
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
        STALE, QUOTA_EXCEEDED, STORE_FAILURE, COMMIT_UNKNOWN, SOURCE_CONFLICT, SIGNED_SOURCE_REQUIRED, TRUST_UNAVAILABLE,
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
    private val signedCatalog: SignedPackageCatalog? = null,
    private val reservedSourceId: (String) -> Boolean = { false },
    private val signedCatalogProvider: () -> SignedPackageCatalog? = { signedCatalog },
    private val signedCatalogBySource: (String) -> SignedPackageCatalog? = { signedCatalogProvider() },
    private val openedSignedCatalogs: () -> List<SignedPackageCatalog> = { listOfNotNull(signedCatalogProvider()) },
) {
    /** Host namespaces can remain reserved even while no trusted catalog can be opened. */
    internal fun requiresSignedSource(id: String): Boolean = reservedSourceId(id) || openedSignedCatalogs().any { it.controlsPackageId(id) }

    internal fun signedDownload(selection: SignedPackageCatalog.Selection): SignedPackageDownload = SignedPackageDownload(catalog(selection.sourceId))

    internal fun signedRefresh(trustSourceId: String): SignedCatalogRefresh = SignedCatalogRefresh(catalog(trustSourceId), trustSourceId)

    internal fun signedPublishers(trustSourceId: String?) = (if (trustSourceId == null) {
        checkNotNull(signedCatalogProvider()) { "CATALOG_UNTRUSTED" }
    } else catalog(trustSourceId)).publishers()

    internal fun signedVersions(sourceId: String) = catalog(sourceId).versions(sourceId)

    internal fun selectSignedVersion(sourceId: String, packageId: String, version: String) =
        catalog(sourceId).select(sourceId, packageId, version)

    internal fun requireCurrent(candidate: SignedPackageCatalog.Verified) =
        catalog(candidate.sourceReceipt.sourceId).withCurrent(candidate) {}

    private fun catalog(sourceId: String) = checkNotNull(signedCatalogBySource(sourceId)) { "CATALOG_UNTRUSTED" }

    internal fun <T> withRuntimeTrust(record: ExtensionPackageRecord, admit: () -> T): T =
        withArtifactTrust(record.artifact, record.sourceReceipt, admit)

    internal fun canStart(record: ExtensionPackageRecord): Boolean = trustAllows { withRuntimeTrust(record) {} }

    internal fun lease(record: ExtensionPackageRecord): SignedPackageCatalog.RuntimeLease? {
        val receipt = record.sourceReceipt
        if (receipt == null) {
            check(!requiresSignedSource(record.manifest.id)) { "CATALOG_UNTRUSTED" }
            return null
        }
        return catalog(receipt.sourceId).runtimeLease(record.artifact, receipt)
    }

    internal fun pollRuntimeTrust() { openedSignedCatalogs().forEach { runCatching { it.pollRuntimeTrust() } } }

    private fun <T> withArtifactTrust(artifact: ExtensionPackageArtifact, receipt: ExtensionPackageSourceReceipt?, proceed: () -> T): T =
        if (receipt == null) {
            check(!requiresSignedSource(artifact.manifest.id)) { "CATALOG_UNTRUSTED" }
            proceed()
        } else catalog(receipt.sourceId).withInstalled(artifact, receipt, proceed)

    private fun <T> publishWithTrust(check: (() -> T) -> T, commit: () -> T): T {
        var entered = false
        return try {
            check { entered = true; commit() }
        } catch (failure: Exception) {
            if (entered || failure is CancellationException) throw failure
            throw TrustUnavailable()
        }
    }

    private class TrustUnavailable : IllegalStateException("CATALOG_UNTRUSTED")
    /** The immutable preview is the exact candidate; no path or mutable buffer is used to commit it. */
    fun inspect(bytes: ByteArray): ExtensionPackageArtifact =
        ExtensionPackageArtifact.decode(bytes, hostVersion = hostVersion, hostApi = hostApi)

    fun inspectLegacy(pluginId: String, bytes: ByteArray): ExtensionPackageArtifact =
        ExtensionPackageArtifact.decode(bytes, ExtensionPackageArtifact.Format.LEGACY_AUTHORED, pluginId, hostVersion, hostApi)

    fun list(workspaceId: String): List<ExtensionPackageRecord> = store.list(workspaceId)

    internal fun get(workspaceId: String, packageId: String): ExtensionPackageRecord? = store.get(workspaceId, packageId)

    fun setEnableIntent(
        workspaceId: String, packageId: String, expectedInstallationId: String, enabled: Boolean,
        publish: (() -> Unit) -> Unit = { it() },
    ): ExtensionPackageChange = storageOperation {
        val current = store.get(workspaceId, packageId) ?: return@storageOperation notFound()
        if (current.installationId != expectedInstallationId) return@storageOperation stale()
        if (enabled && !canStart(current)) return@storageOperation trustUnavailable()
        if (current.enableIntent == enabled) return@storageOperation ExtensionPackageChange.Unchanged(current)
        // An exact state-changing confirmation cannot be reused after an off/on cycle.
        val updated = current.copy(installationId = newInstallationId(), enableIntent = enabled)
        write(workspaceId, packageId, expectedInstallationId, updated, ExtensionPackageChange.Applied(updated, upgraded = false)) { commit ->
            publish {
                if (enabled) publishWithTrust({ withRuntimeTrust(current, it) }, commit) else commit()
            }
        }
    }

    /** A null expected generation requests a fresh install only. */
    fun install(
        workspaceId: String,
        candidate: ExtensionPackageArtifact,
        expectedInstallationId: String?,
        granted: Set<ExtensionPackageCapability>,
        publish: (() -> Unit) -> Unit = { it() },
    ): ExtensionPackageChange = installArtifact(workspaceId, candidate, null, expectedInstallationId, granted, publish)

    internal fun installVerified(
        workspaceId: String,
        candidate: SignedPackageCatalog.Verified,
        expectedInstallationId: String?,
        granted: Set<ExtensionPackageCapability>,
        publish: (() -> Unit) -> Unit,
    ): ExtensionPackageChange {
        val catalog = signedCatalogBySource(candidate.sourceReceipt.sourceId) ?: return trustUnavailable()
        if (!trustAllows { catalog.withCurrent(candidate) {} }) return trustUnavailable()
        return installArtifact(workspaceId, candidate.artifact, candidate.sourceReceipt, expectedInstallationId, granted) { commit ->
            publish { publishWithTrust({ catalog.withCurrent(candidate, it) }, commit) }
        }
    }

    private fun installArtifact(
        workspaceId: String,
        candidate: ExtensionPackageArtifact,
        source: ExtensionPackageSourceReceipt?,
        expectedInstallationId: String?,
        granted: Set<ExtensionPackageCapability>,
        publish: (() -> Unit) -> Unit,
    ): ExtensionPackageChange = storageOperation {
        val manifest = candidate.manifest
        if (!manifest.compatibleWith(hostApi, hostVersion)) {
            return@storageOperation rejected(ExtensionPackageChange.Code.INVALID_MANIFEST, "Incompatible package")
        }
        val current = store.get(workspaceId, manifest.id)
        if (current?.installationId != expectedInstallationId) return@storageOperation stale()
        if (source == null && requiresSignedSource(manifest.id)) {
            return@storageOperation rejected(ExtensionPackageChange.Code.SIGNED_SOURCE_REQUIRED, "A signed source is required")
        }
        if (current != null && !sameOrigin(current.sourceReceipt, source)) {
            return@storageOperation rejected(ExtensionPackageChange.Code.SOURCE_CONFLICT, "Installed source has a different owner")
        }
        val missing = manifest.capabilities - granted
        if (missing.isNotEmpty()) return@storageOperation ExtensionPackageChange.ConsentRequired(manifest, missing)
        if (current != null) {
            val order = ExtensionPackageSource.compareVersions(manifest.version, current.manifest.version)
            if (order < 0) return@storageOperation rejected(ExtensionPackageChange.Code.VERSION_NOT_NEWER, "The installed version is newer")
            if (order == 0) return@storageOperation if (manifest.sha256 == current.manifest.sha256 && candidate.format == current.artifact.format) {
                ExtensionPackageChange.Unchanged(current)
            } else rejected(ExtensionPackageChange.Code.CONFLICT, "The same version is installed with different content")
        }
        val record = ExtensionPackageRecord(workspaceId, candidate, newInstallationId(), clock(), current?.artifact,
            sourceReceipt = source, previousSourceReceipt = current?.sourceReceipt)
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
        val receipt = current.previousSourceReceipt
        if (!trustAllows { withArtifactTrust(previous, receipt) {} }) return@storageOperation trustUnavailable()
        val record = ExtensionPackageRecord(workspaceId, previous, newInstallationId(), clock(), sourceReceipt = receipt)
        write(workspaceId, packageId, expectedInstallationId, record, ExtensionPackageChange.Applied(record, upgraded = false)) { commit ->
            publish { publishWithTrust({ withArtifactTrust(previous, receipt, it) }, commit) }
        }
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
    } catch (_: TrustUnavailable) {
        trustUnavailable()
    } catch (failure: ExtensionPackageStoreException) {
        if (failure.commitUnknown) rejected(ExtensionPackageChange.Code.COMMIT_UNKNOWN, "Refresh the installed list before another change")
        else rejected(ExtensionPackageChange.Code.STORE_FAILURE, "Could not access package storage")
    }

    private fun sameOrigin(old: ExtensionPackageSourceReceipt?, next: ExtensionPackageSourceReceipt?) =
        if (old == null) next == null else next != null && old.samePublisher(next)

    private inline fun trustAllows(block: () -> Unit): Boolean = try { block(); true }
    catch (cancellation: CancellationException) { throw cancellation }
    catch (_: Exception) { false }

    private fun trustUnavailable() = rejected(ExtensionPackageChange.Code.TRUST_UNAVAILABLE, "Current signed source is unavailable")

    private fun stale() = rejected(ExtensionPackageChange.Code.STALE, "The installed generation has changed; refresh before retrying")
    private fun notFound() = rejected(ExtensionPackageChange.Code.NOT_FOUND, "Package is not installed in this workspace")
    private fun rejected(code: ExtensionPackageChange.Code, message: String) = ExtensionPackageChange.Rejected(code, message)
}

private fun ExtensionPackageManifest.compatibleWith(api: Int, version: String): Boolean =
    hostApi == api && ExtensionPackageSource.compareVersions(minHostVersion, version) <= 0
