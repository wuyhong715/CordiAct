package com.cordiact.mcp.client

import com.cordiact.core.mcp.McpClientFactory
import com.cordiact.core.mcp.McpConnection
import com.cordiact.core.mcp.McpCredential
import com.cordiact.core.mcp.McpServerConfig
import com.cordiact.core.mcp.McpServerInfo
import com.cordiact.core.mcp.McpServerConfigCodec
import com.cordiact.core.plugin.PluginInstanceId
import com.cordiact.core.plugin.PluginInstanceState
import com.cordiact.core.plugin.PluginInstanceStatus
import com.cordiact.core.plugin.PluginScope
import com.cordiact.core.plugin.PluginSpec
import com.cordiact.core.plugin.RuntimeAwarePlugin
import com.cordiact.core.tool.ToolDefinitionRegistry

/**
 * One MCP server as a CordiAct plugin.
 *
 * Enabling connects and publishes tools; disabling withdraws them and drops the connection.
 * Both directions are idempotent because the lifecycle manager can call a hook twice after a
 * crash between the durable state write and the hook itself.
 *
 * Failures during [onEnable] are intentionally not swallowed: the manager turns a thrown hook
 * into a `FAILED` record and revokes capabilities, which is exactly the "no half-connected
 * server" outcome this needs. This lifecycle does not reconnect or replay tool calls.
 */
class McpPlugin(
    override val spec: PluginSpec,
    private val registry: ToolDefinitionRegistry,
    private val factory: McpClientFactory,
    private val credential: McpCredential? = null,
    private val credentialProvider: ((String) -> McpCredential?)? = null,
) : RuntimeAwarePlugin {

    private val config: McpServerConfig = McpServerConfigCodec.decode(spec.config)
    private val bridge = McpToolBridge(
        serverName = config.serverName,
        registry = registry,
        ownerPluginId = spec.id,
        toolTimeoutMs = config.toolCallTimeoutMs,
    )

    private var resources: PluginScope? = null

    private data class Runtime(
        val state: PluginInstanceState,
        val connection: McpConnection? = null,
        val serverInfo: McpServerInfo? = null,
        val reason: String? = null,
    )

    @Volatile private var runtime = Runtime(PluginInstanceState.DISABLED)

    val serverInfo: McpServerInfo? get() = runtime.let { if (it.connection?.isRetired == false) it.serverInfo else null }
    val lastError: String? get() = runtimeStatus().lastError

    /** Called under the lifecycle manager's lock: never locks the plugin or accesses the network. */
    override fun runtimeStatus(): PluginInstanceStatus {
        val current = runtime
        val retired = current.connection?.isRetired == true
        return PluginInstanceStatus(PluginInstanceId(spec.id),
            if (retired) PluginInstanceState.FAILED else current.state, emptySet(),
            if (retired) "MCP_CONNECTION_RETIRED" else current.reason)
    }

    /** Tool ids currently published by this plugin. */
    fun publishedTools(): Map<String, String> {
        val current = runtime
        if (current.state != PluginInstanceState.ACTIVE || current.connection?.isRetired != false) return emptyMap()
        return bridge.publishedTools().takeIf { runtime === current }.orEmpty()
    }

    @Synchronized
    override fun onEnable() {
        if (runtime.connection != null && resources?.isOpen == true) return
        // Never reconnect over a connection/tool generation whose cleanup failed.
        resources?.let { it.close(); resources = null }
        runtime = Runtime(PluginInstanceState.STARTING)
        val scope = PluginScope()
        resources = scope
        try {
            val live = factory.connect(config, credentialProvider?.invoke(config.serverName) ?: credential)
            scope.effect { live.close() }
            // Reverse release order: withdraw tools, then close the transport. Register before
            // sync so a partial publication is also withdrawn if initialization fails.
            scope.effect { bridge.dispose() }
            val info = live.initialize()
            bridge.sync(live)
            runtime = Runtime(PluginInstanceState.ACTIVE, live, info)
        } catch (failure: Exception) {
            try { scope.close(); resources = null } catch (cleanup: Exception) { failure.addSuppressed(cleanup) }
            runtime = Runtime(PluginInstanceState.FAILED, reason = "MCP_START_FAILED")
            throw failure
        }
    }

    @Synchronized
    override fun onDisable() {
        runtime = Runtime(PluginInstanceState.DISABLED)
        try {
            resources?.close()
            resources = null
        } catch (failure: Exception) {
            runtime = Runtime(PluginInstanceState.FAILED, reason = "MCP_CLEANUP_PENDING")
            throw failure
        }
    }

    @Synchronized
    override fun onUninstall() {
        onDisable()
    }
}
