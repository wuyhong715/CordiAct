package com.cordiact.mcp.client

import com.cordiact.core.mcp.MCP_PLUGIN_ID
import com.cordiact.core.mcp.McpClientFactory
import com.cordiact.core.mcp.McpConnection
import com.cordiact.core.mcp.McpCredential
import com.cordiact.core.mcp.McpServerConfig
import com.cordiact.core.mcp.McpServerInfo
import com.cordiact.core.mcp.McpServerConfigCodec
import com.cordiact.core.plugin.ManagedPlugin
import com.cordiact.core.plugin.PluginSpec
import com.cordiact.core.plugin.PluginScope
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
 * server" outcome this needs. The one exception is a reconnect policy — a server that was up
 * and then dropped keeps its tools until the retry budget is exhausted, mirroring what the
 * upstream mcp-client does.
 */
class McpPlugin(
    override val spec: PluginSpec,
    private val registry: ToolDefinitionRegistry,
    private val factory: McpClientFactory,
    private val credential: McpCredential? = null,
    private val credentialProvider: ((String) -> McpCredential?)? = null,
) : ManagedPlugin {

    private val config: McpServerConfig = McpServerConfigCodec.decode(spec.config)
    private val bridge = McpToolBridge(
        serverName = config.serverName,
        registry = registry,
        ownerPluginId = spec.id,
        toolTimeoutMs = config.toolCallTimeoutMs,
    )

    private var resources: PluginScope? = null

    @Volatile
    private var connection: McpConnection? = null

    @Volatile
    var serverInfo: McpServerInfo? = null
        private set

    @Volatile
    var lastError: String? = null
        private set

    /** Tool ids currently published by this plugin. */
    fun publishedTools(): Map<String, String> = bridge.publishedTools()

    @Synchronized
    override fun onEnable() {
        if (connection != null && resources?.isOpen == true) return
        // Never reconnect over a connection/tool generation whose cleanup failed.
        resources?.let { it.close(); resources = null }
        val scope = PluginScope()
        resources = scope
        try {
            val live = factory.connect(config, credentialProvider?.invoke(config.serverName) ?: credential)
            scope.effect { live.close() }
            // Reverse release order: withdraw tools, then close the transport. Register before
            // sync so a partial publication is also withdrawn if initialization fails.
            scope.effect { bridge.dispose() }
            serverInfo = live.initialize()
            bridge.sync(live)
            connection = live
            lastError = null
        } catch (failure: Exception) {
            try { scope.close(); resources = null } catch (cleanup: Exception) { failure.addSuppressed(cleanup) }
            connection = null
            serverInfo = null
            lastError = failure.message
            throw failure
        }
    }

    @Synchronized
    override fun onDisable() {
        try {
            resources?.close()
            resources = null
            lastError = null
        } catch (failure: Exception) {
            lastError = failure.message
            throw failure
        } finally {
            connection = null
            serverInfo = null
        }
    }

    @Synchronized
    override fun onUninstall() {
        onDisable()
    }
}
