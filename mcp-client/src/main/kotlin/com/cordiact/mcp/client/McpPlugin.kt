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
) : ManagedPlugin {

    private val config: McpServerConfig = McpServerConfigCodec.decode(spec.config)
    private val bridge = McpToolBridge(
        serverName = config.serverName,
        registry = registry,
        ownerPluginId = MCP_PLUGIN_ID,
        toolTimeoutMs = config.toolCallTimeoutMs,
    )

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
        if (connection != null) return
        val live = factory.connect(config, credential)
        try {
            serverInfo = live.initialize()
            // sync both publishes the tools and takes the connection they dispatch through, so
            // a failure here must close the connection rather than leave tools pointing at a
            // server nobody will ever close.
            bridge.sync(live)
            connection = live
            lastError = null
        } catch (failure: Exception) {
            runCatching { bridge.dispose() }
            runCatching { live.close() }
            serverInfo = null
            lastError = failure.message
            throw failure
        }
    }

    @Synchronized
    override fun onDisable() {
        bridge.dispose()
        connection?.let { runCatching { it.close() } }
        connection = null
        serverInfo = null
    }

    @Synchronized
    override fun onUninstall() {
        onDisable()
    }
}
