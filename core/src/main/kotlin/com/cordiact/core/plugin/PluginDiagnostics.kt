package com.cordiact.core.plugin

import java.util.concurrent.CancellationException

/** Display-only snapshot. Nothing here can be passed back as an installation or operation token. */
data class PluginDiagnosticSnapshot(
    val workspaceId: String,
    val packages: WorkspacePackageDiagnostics,
    val bundled: List<BundledPluginDiagnostic>,
    val signedCatalog: SignedCatalogDiagnosticState? = null,
    val observedRootRevision: Long? = null,
    val globalMcp: McpPluginDiagnostics = McpPluginDiagnostics.Unavailable,
)

sealed interface WorkspacePackageDiagnostics {
    data object StorageNotCreated : WorkspacePackageDiagnostics
    data object StorageUnavailable : WorkspacePackageDiagnostics
    data class Entries(val items: List<WorkspacePackageDiagnostic>) : WorkspacePackageDiagnostics
}

data class WorkspacePackageDiagnostic(
    val id: String,
    val version: String,
    val format: ExtensionPackageArtifact.Format,
    val enableIntent: Boolean,
    val runtime: WorkspacePackageRuntime.Status?,
    /** Install-time receipt for this artifact; not a runtime, workspace or OS permission. */
    val approvedContributions: List<ExtensionPackageCapability>,
    val hasRollback: Boolean,
    val sourceReceipt: ExtensionPackageSourceReceipt? = null,
    val signedRelease: SignedReleaseDiagnostic? = null,
)

data class BundledPluginDiagnostic(val id: String, val version: String, val runtime: PluginInstanceState?)

sealed interface McpPluginDiagnostics {
    data object StorageNotCreated : McpPluginDiagnostics
    data object Unavailable : McpPluginDiagnostics
    data class Entries(val items: List<McpPluginDiagnostic>) : McpPluginDiagnostics

    companion object {
        const val MAX_ENTRIES = 256
        internal fun acceptsId(id: String) = id.matches(Regex("core\\.mcp\\.[a-z0-9_-]{1,32}"))
    }
}

/** Global installed connection, not its endpoint, config, workspace consent or credential. */
data class McpPluginDiagnostic(
    val id: String,
    val state: PluginState,
    val runtime: PluginInstanceState?,
)

/** Host supplies passive reads only; notably no lifecycle manager, credential or effect port. */
class PluginDiagnostics(
    private val isCurrentWorkspace: (String) -> Boolean,
    private val readExistingPackages: (String) -> List<ExtensionPackageRecord>?,
    private val packageRuntime: (ExtensionPackageRecord) -> WorkspacePackageRuntime.Status?,
    private val bundled: List<ManagedPlugin>,
    private val globalMcpObservation: () -> McpPluginDiagnostics = { McpPluginDiagnostics.Unavailable },
    private val signedObservation: () -> SignedCatalogObservation? = { null },
) {
    fun snapshot(workspaceId: String): PluginDiagnosticSnapshot {
        check(workspaceId.isNotBlank() && isCurrentWorkspace(workspaceId)) { "PLUGIN_DIAGNOSTICS_WORKSPACE_UNAVAILABLE" }
        val trust = readObservation(signedObservation)
        check(isCurrentWorkspace(workspaceId)) { "PLUGIN_DIAGNOSTICS_WORKSPACE_UNAVAILABLE" }
        val packages = try {
            val records = readExistingPackages(workspaceId)
            check(isCurrentWorkspace(workspaceId)) { "PLUGIN_DIAGNOSTICS_WORKSPACE_UNAVAILABLE" }
            if (records == null) WorkspacePackageDiagnostics.StorageNotCreated
            else {
                check(records.all { it.workspaceId == workspaceId })
                check(records.size <= ExtensionPackageStore.MAX_PER_WORKSPACE && records.distinctBy { it.manifest.id }.size == records.size)
                WorkspacePackageDiagnostics.Entries(records.sortedBy { it.manifest.id }.map {
                    WorkspacePackageDiagnostic(it.manifest.id, it.manifest.version, it.artifact.format, it.enableIntent,
                        readObservation { packageRuntime(it) },
                        it.grantedCapabilities.sortedBy(ExtensionPackageCapability::wireName), it.previous != null,
                        it.sourceReceipt, readObservation { trust?.release(it) })
                })
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            WorkspacePackageDiagnostics.StorageUnavailable
        }
        check(isCurrentWorkspace(workspaceId)) { "PLUGIN_DIAGNOSTICS_WORKSPACE_UNAVAILABLE" }
        val builtins = bundled.map { plugin ->
            val spec = plugin.spec
            val runtime = readObservation {
                (plugin as? RuntimeAwarePlugin)?.runtimeStatus()?.takeIf {
                    it.id == PluginInstanceId(spec.id)
                }?.state
            }
            BundledPluginDiagnostic(spec.id, spec.version, runtime)
        }.sortedBy { it.id }
        check(isCurrentWorkspace(workspaceId)) { "PLUGIN_DIAGNOSTICS_WORKSPACE_UNAVAILABLE" }
        val mcp = readObservation(globalMcpObservation) ?: McpPluginDiagnostics.Unavailable
        check(isCurrentWorkspace(workspaceId)) { "PLUGIN_DIAGNOSTICS_WORKSPACE_UNAVAILABLE" }
        return PluginDiagnosticSnapshot(workspaceId, packages, builtins, trust?.state, trust?.rootRevision, mcp)
    }

    private fun <T> readObservation(read: () -> T?): T? = try { read() }
    catch (cancelled: CancellationException) { throw cancelled }
    catch (_: Exception) { null }
}
