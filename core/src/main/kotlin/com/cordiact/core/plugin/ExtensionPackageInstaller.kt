package com.cordiact.core.plugin

import java.util.UUID

/** One installed package version plus the single version it can roll back to. */
data class ExtensionPackageRecord(
    val workspaceId: String,
    val manifest: ExtensionPackageManifest,
    val grantedCapabilities: Set<ExtensionPackageCapability>,
    val installationId: String,
    val installedAtEpochMillis: Long,
    val previous: ExtensionPackageSnapshot? = null,
)

/** Immutable snapshot of a prior version, retained for one explicit rollback. */
data class ExtensionPackageSnapshot(
    val manifest: ExtensionPackageManifest,
    val grantedCapabilities: Set<ExtensionPackageCapability>,
    val installationId: String,
)

/** Host-owned durable storage. Implementations bind workspaces to trusted keys, never paths. */
interface ExtensionPackageStore {
    fun list(workspaceId: String): List<ExtensionPackageRecord>
    fun get(workspaceId: String, packageId: String): ExtensionPackageRecord?
    fun put(record: ExtensionPackageRecord)
    fun remove(workspaceId: String, packageId: String): Boolean
}

/** JVM implementation for tests and host adapters that own their durability. */
class InMemoryExtensionPackageStore : ExtensionPackageStore {
    private val values = linkedMapOf<Pair<String, String>, ExtensionPackageRecord>()

    @Synchronized
    override fun list(workspaceId: String): List<ExtensionPackageRecord> =
        values.entries.filter { it.key.first == workspaceId }.map { it.value }.sortedBy { it.manifest.id }

    @Synchronized
    override fun get(workspaceId: String, packageId: String): ExtensionPackageRecord? = values[workspaceId to packageId]

    @Synchronized
    override fun put(record: ExtensionPackageRecord) {
        values[record.workspaceId to record.manifest.id] = record
    }

    @Synchronized
    override fun remove(workspaceId: String, packageId: String): Boolean =
        values.remove(workspaceId to packageId) != null
}

/** Result of validating, installing, upgrading, rolling back or removing one package. */
sealed interface ExtensionPackageChange {
    data class Applied(val record: ExtensionPackageRecord, val upgraded: Boolean) : ExtensionPackageChange

    data class Unchanged(val record: ExtensionPackageRecord) : ExtensionPackageChange

    data class ConsentRequired(
        val manifest: ExtensionPackageManifest,
        val missing: Set<ExtensionPackageCapability>,
    ) : ExtensionPackageChange

    data class Rejected(val code: Code, val message: String) : ExtensionPackageChange

    data object Removed : ExtensionPackageChange

    enum class Code {
        INVALID_MANIFEST,
        VERSION_NOT_NEWER,
        CONFLICT,
        NOT_FOUND,
        NO_PREVIOUS,
        STORE_FAILURE,
    }
}

/**
 * Validates and records declarative packages. It never activates a package or runs its code:
 * publication stays with the kernel and the host's capability gate. Upgrades keep exactly one
 * previous version; rolling back consumes that slot. A version that adds a capability is refused
 * until the caller supplies consent for the complete new capability set.
 */
class ExtensionPackageInstaller(
    private val store: ExtensionPackageStore,
    private val hostVersion: String = EXTENSION_PACKAGE_HOST_VERSION,
    private val hostApi: Int = EXTENSION_PACKAGE_HOST_API,
    private val clock: () -> Long = System::currentTimeMillis,
    private val newInstallationId: () -> String = { UUID.randomUUID().toString() },
) {
    /** Validates one source snapshot without writing anything. */
    fun inspect(bytes: ByteArray): ExtensionPackageManifest =
        ExtensionPackageSource.compile(bytes, hostVersion, hostApi)

    /** Installs a new package or upgrades an existing ID. [granted] must cover every capability. */
    fun install(
        workspaceId: String,
        bytes: ByteArray,
        granted: Set<ExtensionPackageCapability>,
    ): ExtensionPackageChange {
        val manifest = try {
            inspect(bytes)
        } catch (failure: IllegalArgumentException) {
            return ExtensionPackageChange.Rejected(
                ExtensionPackageChange.Code.INVALID_MANIFEST,
                failure.message ?: "Invalid package manifest",
            )
        }
        return installManifest(workspaceId, manifest, granted)
    }

    /** Explicitly install an already-validated manifest, used by the host's bundled catalog. */
    fun install(
        workspaceId: String,
        manifest: ExtensionPackageManifest,
        granted: Set<ExtensionPackageCapability>,
    ): ExtensionPackageChange {
        if (manifest.hostApi != hostApi) {
            return ExtensionPackageChange.Rejected(
                ExtensionPackageChange.Code.INVALID_MANIFEST,
                "Package requires host API ${manifest.hostApi}",
            )
        }
        if (ExtensionPackageSource.compareVersions(manifest.minHostVersion, hostVersion) > 0) {
            return ExtensionPackageChange.Rejected(
                ExtensionPackageChange.Code.INVALID_MANIFEST,
                "Package requires host version ${manifest.minHostVersion} or newer",
            )
        }
        return installManifest(workspaceId, manifest, granted)
    }

    fun rollback(workspaceId: String, packageId: String): ExtensionPackageChange {
        val current = store.get(workspaceId, packageId)
            ?: return ExtensionPackageChange.Rejected(
                ExtensionPackageChange.Code.NOT_FOUND,
                "Package is not installed in this workspace",
            )
        val previous = current.previous
            ?: return ExtensionPackageChange.Rejected(
                ExtensionPackageChange.Code.NO_PREVIOUS,
                "This package has no retained previous version",
            )
        if (previous.manifest.hostApi != hostApi ||
            ExtensionPackageSource.compareVersions(previous.manifest.minHostVersion, hostVersion) > 0
        ) {
            return ExtensionPackageChange.Rejected(
                ExtensionPackageChange.Code.INVALID_MANIFEST,
                "The previous version is not compatible with this host",
            )
        }
        val record = ExtensionPackageRecord(
            workspaceId = workspaceId,
            manifest = previous.manifest,
            grantedCapabilities = previous.grantedCapabilities,
            installationId = newInstallationId(),
            installedAtEpochMillis = clock(),
            previous = null,
        )
        return try {
            store.put(record)
            ExtensionPackageChange.Applied(record, upgraded = false)
        } catch (failure: Exception) {
            ExtensionPackageChange.Rejected(
                ExtensionPackageChange.Code.STORE_FAILURE,
                failure.message ?: "Could not persist the rollback",
            )
        }
    }

    fun remove(workspaceId: String, packageId: String): ExtensionPackageChange =
        try {
            if (store.remove(workspaceId, packageId)) ExtensionPackageChange.Removed
            else ExtensionPackageChange.Rejected(ExtensionPackageChange.Code.NOT_FOUND, "Package is not installed")
        } catch (failure: Exception) {
            ExtensionPackageChange.Rejected(
                ExtensionPackageChange.Code.STORE_FAILURE,
                failure.message ?: "Could not remove the package",
            )
        }

    fun list(workspaceId: String): List<ExtensionPackageRecord> = store.list(workspaceId)

    /**
     * Promotes a legacy Agent-authored plugin (schemaVersion 1/2) into a durable package. The tool
     * declarations and digest are preserved; commands are not invented. This is an explicit,
     * user-triggered migration, never an automatic replacement of the authored trial.
     */
    fun importLegacyAuthored(
        workspaceId: String,
        pluginId: String,
        bytes: ByteArray,
        granted: Set<ExtensionPackageCapability>,
    ): ExtensionPackageChange {
        val authored = try {
            AuthoredPluginSource.compile(pluginId, bytes)
        } catch (failure: IllegalArgumentException) {
            return ExtensionPackageChange.Rejected(
                ExtensionPackageChange.Code.INVALID_MANIFEST,
                failure.message ?: "Invalid authored plugin source",
            )
        }
        val manifest = ExtensionPackageManifest(
            schemaVersion = 1,
            id = authored.id,
            version = "0.0.1",
            title = authored.title,
            description = "Migrated from an authored plugin.",
            hostApi = hostApi,
            minHostVersion = hostVersion,
            capabilities = setOf(ExtensionPackageCapability.TOOL_PUBLISH),
            tools = authored.tools.map {
                ExtensionPackageTool(it.name, it.description, it.text, it.operation)
            },
            commands = emptyList(),
            sha256 = authored.sha256,
            sizeBytes = bytes.size,
        )
        return installManifest(workspaceId, manifest, granted)
    }

    private fun installManifest(
        workspaceId: String,
        manifest: ExtensionPackageManifest,
        granted: Set<ExtensionPackageCapability>,
    ): ExtensionPackageChange {
        val missing = manifest.capabilities - granted
        if (missing.isNotEmpty()) return ExtensionPackageChange.ConsentRequired(manifest, missing)
        val current = store.get(workspaceId, manifest.id)
        if (current == null) {
            val record = ExtensionPackageRecord(
                workspaceId = workspaceId,
                manifest = manifest,
                grantedCapabilities = manifest.capabilities,
                installationId = newInstallationId(),
                installedAtEpochMillis = clock(),
            )
            return try {
                store.put(record)
                ExtensionPackageChange.Applied(record, upgraded = false)
            } catch (failure: Exception) {
                ExtensionPackageChange.Rejected(
                    ExtensionPackageChange.Code.STORE_FAILURE,
                    failure.message ?: "Could not persist the package",
                )
            }
        }
        val order = ExtensionPackageSource.compareVersions(manifest.version, current.manifest.version)
        if (order < 0) {
            return ExtensionPackageChange.Rejected(
                ExtensionPackageChange.Code.VERSION_NOT_NEWER,
                "Package version ${manifest.version} is older than the installed ${current.manifest.version}",
            )
        }
        if (order == 0) {
            return if (manifest.sha256 == current.manifest.sha256) {
                ExtensionPackageChange.Unchanged(current)
            } else {
                ExtensionPackageChange.Rejected(
                    ExtensionPackageChange.Code.CONFLICT,
                    "The same version is already installed with different content",
                )
            }
        }
        val record = ExtensionPackageRecord(
            workspaceId = workspaceId,
            manifest = manifest,
            grantedCapabilities = manifest.capabilities,
            installationId = newInstallationId(),
            installedAtEpochMillis = clock(),
            previous = ExtensionPackageSnapshot(
                manifest = current.manifest,
                grantedCapabilities = current.grantedCapabilities,
                installationId = current.installationId,
            ),
        )
        return try {
            store.put(record)
            ExtensionPackageChange.Applied(record, upgraded = true)
        } catch (failure: Exception) {
            ExtensionPackageChange.Rejected(
                ExtensionPackageChange.Code.STORE_FAILURE,
                failure.message ?: "Could not persist the upgrade",
            )
        }
    }
}
