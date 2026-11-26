package com.cordiact.mcp.client

import com.cordiact.core.mcp.*
import com.cordiact.core.plugin.PluginInstanceId
import com.cordiact.core.plugin.PluginInstanceState
import com.cordiact.core.tool.InMemoryToolDefinitionRegistry
import kotlin.test.*

class McpPluginCredentialTest {
    private class Connection : McpConnection {
        var closed = false
        override val isRetired get() = closed
        override fun initialize() = McpServerInfo(protocolVersion = "2025-06-18", serverName = "fake")
        override fun listTools(timeoutMs: Long) = listOf(McpToolDescriptor("ping", "Ping"))
        override fun callTool(name: String, argumentsJson: String, timeoutMs: Long) = McpToolCallResult("ok", false)
        override fun close() { closed = true }
    }
    @Test fun reconnectResolvesCurrentVaultCredentialAndNeverPutsItInSpec() {
        val registry = InMemoryToolDefinitionRegistry()
        val spec = mcpPluginSpec(McpServerConfig("demo", "https://example.test/mcp"))
        var current = McpCredential("Authorization", "first-secret")
        val seen = mutableListOf<McpCredential?>(); val connections = mutableListOf<Connection>()
        val plugin = McpPlugin(spec, registry, McpClientFactory { _, key -> seen += key; Connection().also { connections += it } }, credentialProvider = { current })
        plugin.onEnable(); assertEquals("first-secret", seen.single()!!.value)
        assertNotNull(registry.get("core.mcp.demo.ping"))
        plugin.onDisable(); assertTrue(connections.single().closed); assertNull(registry.get("core.mcp.demo.ping"))
        current = McpCredential("Authorization", "replacement")
        plugin.onEnable(); assertEquals("replacement", seen.last()!!.value)
        assertFalse(spec.config.contains("first-secret")); assertFalse(spec.config.contains("replacement"))
        plugin.onDisable()
    }
    @Test fun passiveRuntimeDistinguishesRetiredConnectionFromDisabledAndDoesNotRestart() {
        val registry = InMemoryToolDefinitionRegistry()
        val connections = mutableListOf<Connection>()
        val spec = mcpPluginSpec(McpServerConfig("demo", "https://example.test/mcp"))
        val plugin = McpPlugin(spec, registry, McpClientFactory { _, _ -> Connection().also { connections += it } })
        assertEquals(PluginInstanceState.DISABLED, plugin.runtimeStatus().state)
        plugin.onEnable()
        assertEquals(PluginInstanceId(spec.id), plugin.runtimeStatus().id)
        assertEquals(PluginInstanceState.ACTIVE, plugin.runtimeStatus().state)
        assertEquals(1, plugin.publishedTools().size)
        connections.single().close()
        assertEquals(PluginInstanceState.FAILED, plugin.runtimeStatus().state)
        assertEquals("MCP_CONNECTION_RETIRED", plugin.runtimeStatus().lastError)
        assertNull(plugin.serverInfo)
        assertTrue(plugin.publishedTools().isEmpty())
        plugin.onEnable() // Repeated lifecycle hook does not implicitly reconnect a retired transport.
        assertEquals(1, connections.size)
        plugin.onDisable()
        assertEquals(PluginInstanceState.DISABLED, plugin.runtimeStatus().state)
        plugin.onEnable()
        assertEquals(2, connections.size)
        assertEquals(PluginInstanceState.ACTIVE, plugin.runtimeStatus().state)
        plugin.onDisable()
    }

    @Test fun failedTransportCleanupIsVisibleAndRetriedBeforeReconnect() {
        val registry = InMemoryToolDefinitionRegistry()
        var fail = true
        var connects = 0
        var closes = 0
        val plugin = McpPlugin(mcpPluginSpec(McpServerConfig("demo", "https://example.test/mcp")), registry,
            McpClientFactory { _, _ ->
                connects++
                object : McpConnection {
                    override fun initialize() = McpServerInfo(protocolVersion = "2025-06-18", serverName = "fake")
                    override fun listTools(timeoutMs: Long) = listOf(McpToolDescriptor("ping", "Ping"))
                    override fun callTool(name: String, argumentsJson: String, timeoutMs: Long) = McpToolCallResult("ok", false)
                    override fun close() { closes++; if (fail) error("transport cleanup failed") }
                }
            })
        plugin.onEnable()
        assertFailsWith<IllegalStateException> { plugin.onDisable() }
        assertNull(registry.get("core.mcp.demo.ping"))
        assertFailsWith<IllegalStateException> { plugin.onEnable() }
        assertEquals(1, connects)
        fail = false
        plugin.onEnable()
        assertEquals(2, connects)
        assertEquals(3, closes)
        plugin.onDisable()
    }

    @Test fun credentialFailurePublishesNoTools() {
        val registry = InMemoryToolDefinitionRegistry()
        val plugin = McpPlugin(mcpPluginSpec(McpServerConfig("demo", "https://example.test/mcp")), registry,
            McpClientFactory { _, _ -> error("MUST_NOT_CONNECT") }, credentialProvider = { error("VAULT_UNAVAILABLE") })
        assertFailsWith<IllegalStateException> { plugin.onEnable() }
        assertNull(registry.get("core.mcp.demo.ping"))
    }
}
