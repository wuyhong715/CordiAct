package com.cordiact.mcp.client

import com.cordiact.core.mcp.*
import com.cordiact.core.tool.InMemoryToolDefinitionRegistry
import kotlin.test.*

class McpPluginCredentialTest {
    private class Connection : McpConnection {
        var closed = false
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
    @Test fun credentialFailurePublishesNoTools() {
        val registry = InMemoryToolDefinitionRegistry()
        val plugin = McpPlugin(mcpPluginSpec(McpServerConfig("demo", "https://example.test/mcp")), registry,
            McpClientFactory { _, _ -> error("MUST_NOT_CONNECT") }, credentialProvider = { error("VAULT_UNAVAILABLE") })
        assertFailsWith<IllegalStateException> { plugin.onEnable() }
        assertNull(registry.get("core.mcp.demo.ping"))
    }
}
