package com.cordiact.app.plugin

import com.cordiact.core.mcp.MCP_PLUGIN_ID
import com.cordiact.core.mcp.McpServerConfig
import com.cordiact.core.mcp.McpServerConfigCodec
import com.cordiact.core.mcp.mcpPluginSpec
import com.cordiact.core.plugin.PluginChangeResult
import com.cordiact.core.plugin.PluginInventoryEntry
import com.cordiact.core.plugin.PluginLifecycleException
import com.cordiact.core.plugin.PluginLifecycleManager
import com.cordiact.core.plugin.PluginManagementException
import com.cordiact.core.plugin.PluginManagerService
import com.cordiact.core.plugin.PluginOrigin
import com.cordiact.core.plugin.PluginRecord
import com.cordiact.core.plugin.PluginSpec
import com.cordiact.core.plugin.PluginState

/**
 * The one implementation both the agent tool and the settings screen call.
 *
 * It is a thin policy layer over [PluginLifecycleManager]: it decides what is protected, what
 * a change means for the caller (`APPLIED` vs `RESTART_REQUIRED`), and it attaches the
 * `nextStep` sentence. The state machine itself stays in core and is not duplicated here.
 */
class AndroidPluginManagerService(
    private val manager: PluginLifecycleManager,
    private val publishedTools: (pluginId: String) -> List<String> = { emptyList() },
    private val onSpecInstalled: (PluginSpec) -> Boolean = { false },
    private val afterRemoved: (String) -> Unit = {},
    private val probeMcp: (McpServerConfig) -> Unit = { error("MCP_PROBE_UNAVAILABLE") },
    private val bundledInventory: () -> List<PluginInventoryEntry> = { emptyList() },
    private val workspaceProjection: (String, PluginInventoryEntry) -> PluginInventoryEntry = { _, entry -> entry },
) : PluginManagerService {

    override fun inventory(): List<PluginInventoryEntry> =
        (manager.all().map(::entryFor) + bundledInventory()).distinctBy { it.spec.id }.sortedBy { it.spec.id }

    override fun inventory(workspaceId: String): List<PluginInventoryEntry> =
        inventory().map { workspaceProjection(workspaceId, it) }

    private fun protectedBuiltin(pluginId: String): PluginChangeResult? =
        bundledInventory().find { it.spec.id == pluginId }?.let { entry ->
            PluginChangeResult(pluginId, PluginChangeResult.Application.OVERRIDDEN,
                reason = "Built-in capability lifecycle is owned by the runtime",
                nextStep = "Change the capability for the selected workspace in settings; built-ins cannot be globally disabled or removed.", entry = entry)
        }

    override fun setEnabled(pluginId: String, enabled: Boolean): PluginChangeResult {
        protectedBuiltin(pluginId)?.let { return it }
        val record = requireRecord(pluginId)
        if (record.origin == PluginOrigin.BUNDLED_SYSTEM) {
            return PluginChangeResult(
                pluginId = pluginId,
                application = PluginChangeResult.Application.OVERRIDDEN,
                reason = "Bundled system plugins are managed by the app bundle",
                nextStep = "Tell the user this plugin ships with the app and cannot be turned off.",
                entry = entryFor(record),
            )
        }
        if (record.state == PluginState.ENABLED && enabled) return applied(record, "already enabled")
        if (record.state == PluginState.DISABLED && !enabled) return applied(record, "already disabled")

        return try {
            if (enabled) manager.enable(pluginId) else manager.disable(pluginId)
            applied(requireRecord(pluginId), null)
        } catch (failure: PluginLifecycleException) {
            // A failed enable leaves a FAILED record; report it as a failure rather than throwing,
            // because the model must be able to tell the user what broke.
            failedResult(pluginId, failure)
        }
    }

    override fun install(spec: PluginSpec): PluginChangeResult {
        protectedBuiltin(spec.id)?.let { return it }
        val existing = manager.get(spec.id)
        if (existing != null) {
            if (existing.spec == spec) return applied(existing, "already installed")
            return PluginChangeResult(
                pluginId = spec.id,
                application = PluginChangeResult.Application.FAILED,
                reason = "A plugin with this id already exists with different configuration",
                nextStep = "Remove it first, then install the new configuration.",
                entry = entryFor(existing),
            )
        }
        val installed = try {
            if (spec.id.startsWith("$MCP_PLUGIN_ID.")) {
                val config = McpServerConfigCodec.decode(spec.config)
                require(config.headers.isEmpty()) { "MCP_HEADERS_MUST_USE_VAULT" }
                probeMcp(config)
            }
            onSpecInstalled(spec)
        } catch (failure: Exception) {
            return PluginChangeResult(
                pluginId = spec.id,
                application = PluginChangeResult.Application.FAILED,
                reason = "Installation failed; verify connection, configuration and credentials",
                nextStep = "Report the failure to the user; the plugin was not installed.",
            )
        }
        if (!installed) {
            return PluginChangeResult(
                pluginId = spec.id,
                application = PluginChangeResult.Application.FAILED,
                reason = "This build cannot host plugin '${spec.id}'",
                nextStep = "Tell the user the plugin target is unsupported by this build.",
            )
        }
        return applied(requireRecord(spec.id), null)
    }

    override fun remove(pluginId: String): PluginChangeResult {
        protectedBuiltin(pluginId)?.let { return it }
        val record = requireRecord(pluginId)
        if (record.origin == PluginOrigin.BUNDLED_SYSTEM) {
            return PluginChangeResult(
                pluginId = pluginId,
                application = PluginChangeResult.Application.OVERRIDDEN,
                reason = "Bundled system plugins cannot be removed",
                nextStep = "Tell the user this plugin ships with the app and cannot be uninstalled.",
                entry = entryFor(record),
            )
        }
        return try {
            manager.uninstall(pluginId)
            afterRemoved(pluginId)
            PluginChangeResult(
                pluginId = pluginId,
                application = PluginChangeResult.Application.APPLIED,
                nextStep = null,
            )
        } catch (failure: Exception) {
            PluginChangeResult(
                pluginId = pluginId,
                application = PluginChangeResult.Application.FAILED,
                reason = failure.message ?: "Removal failed",
                nextStep = "The plugin is still present; retry, or tell the user cleanup is incomplete.",
                entry = manager.get(pluginId)?.let(::entryFor),
            )
        }
    }

    /** Convenience for both front-ends: build and validate an MCP server spec from a config. */
    fun installMcpServer(config: McpServerConfig): PluginChangeResult {
        val spec = mcpPluginSpec(McpServerConfig.validated(config))
        return install(spec)
    }

    /** Parse the stored config of an MCP plugin, for a settings screen that wants its fields. */
    fun mcpConfig(pluginId: String): McpServerConfig? {
        val record = manager.get(pluginId) ?: return null
        if (!pluginId.startsWith(MCP_PLUGIN_ID)) return null
        return runCatching { McpServerConfigCodec.decode(record.spec.config) }.getOrNull()
    }

    private fun applied(record: PluginRecord, reason: String?) = PluginChangeResult(
        pluginId = record.spec.id,
        application = PluginChangeResult.Application.APPLIED,
        reason = reason,
        entry = entryFor(record),
    )

    private fun failedResult(pluginId: String, failure: Exception) = PluginChangeResult(
        pluginId = pluginId,
        application = PluginChangeResult.Application.FAILED,
        reason = failure.message ?: "The plugin host refused the change",
        nextStep = "Tell the user what the plugin reported; check its configuration before retrying.",
        entry = manager.get(pluginId)?.let(::entryFor),
    )

    private fun requireRecord(pluginId: String): PluginRecord =
        manager.get(pluginId)
            ?: throw PluginManagementException(
                PluginManagementException.Code.UNKNOWN_PLUGIN,
                "Plugin '$pluginId' is not installed",
            )

    private fun entryFor(record: PluginRecord) = PluginInventoryEntry(
        spec = record.spec,
        origin = record.origin,
        state = record.state,
        lastError = record.lastError,
        publishedToolIds = if (record.state == PluginState.ENABLED) publishedTools(record.spec.id) else emptyList(),
        readOnlyReason = if (record.origin == PluginOrigin.BUNDLED_SYSTEM) {
            PluginInventoryEntry.ReadOnlyReason.MANAGEMENT_REQUIRED
        } else {
            null
        },
    )
}
