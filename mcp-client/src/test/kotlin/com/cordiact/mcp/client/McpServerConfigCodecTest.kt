package com.cordiact.mcp.client

import com.cordiact.core.mcp.McpConfigException
import com.cordiact.core.mcp.McpReconnectPolicy
import com.cordiact.core.mcp.McpServerConfig
import com.cordiact.core.mcp.McpServerConfigCodec
import com.cordiact.core.mcp.mcpPluginSpec
import com.cordiact.core.mcp.mcpToolId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class McpServerConfigCodecTest {

    @Test
    fun roundTripsEveryField() {
        val original = McpServerConfig(
            serverName = "demo",
            url = "https://example.test/mcp",
            headers = mapOf("X-Tenant" to "acme", "X-Quoted" to "a\"b\\c"),
            toolCallTimeoutMs = 12_000,
            connectTimeoutMs = 4_000,
            reconnect = McpReconnectPolicy(enabled = false, initialDelayMs = 500, maxDelayMs = 5_000, maxAttempts = 3),
        )
        val decoded = McpServerConfigCodec.decode(McpServerConfigCodec.encode(original))
        assertEquals(original, decoded)
    }

    @Test
    fun omittedOptionalFieldsFallBackToDefaults() {
        val decoded = McpServerConfigCodec.decode("""{"serverName":"demo","url":"https://example.test/mcp"}""")
        assertEquals(McpServerConfig.DEFAULT_TOOL_CALL_TIMEOUT_MS, decoded.toolCallTimeoutMs)
        assertEquals(McpServerConfig.DEFAULT_CONNECT_TIMEOUT_MS, decoded.connectTimeoutMs)
        assertEquals(McpReconnectPolicy(), decoded.reconnect)
        assertTrue(decoded.headers.isEmpty())
    }

    @Test
    fun malformedConfigsNameTheField() {
        assertFailsWith<McpConfigException> { McpServerConfigCodec.decode("""{"url":"https://x"}""") }
        assertFailsWith<McpConfigException> { McpServerConfigCodec.decode("""{"serverName":"demo"}""") }
        assertFailsWith<McpConfigException> { McpServerConfigCodec.decode("""{"serverName":"demo","url":42}""") }
        assertFailsWith<McpConfigException> { McpServerConfigCodec.decode("""{"serverName":"demo","url":"https://x"} trailing""") }
        assertFailsWith<McpConfigException> { McpServerConfigCodec.decode("") }
    }

    @Test
    fun plainHttpIsOnlyAllowedOnLoopback() {
        assertFailsWith<McpConfigException> {
            McpServerConfig.validated(McpServerConfig(serverName = "demo", url = "http://example.test/mcp"))
        }
        // A server hosted on the device itself is the one case where TLS buys nothing.
        McpServerConfig.validated(McpServerConfig(serverName = "demo", url = "http://127.0.0.1:8787/mcp"))
    }

    @Test
    fun serverNameAndTimeoutsAreBounded() {
        assertFailsWith<McpConfigException> {
            McpServerConfig.validated(McpServerConfig(serverName = "has space", url = "https://x.test/mcp"))
        }
        assertFailsWith<McpConfigException> {
            McpServerConfig.validated(McpServerConfig(serverName = "a".repeat(33), url = "https://x.test/mcp"))
        }
        assertFailsWith<McpConfigException> {
            McpServerConfig.validated(McpServerConfig(serverName = "demo", url = "https://x.test/mcp", toolCallTimeoutMs = 0))
        }
        assertFailsWith<McpConfigException> {
            McpServerConfig.validated(McpServerConfig(serverName = "demo", url = "https://x.test/mcp", connectTimeoutMs = 0))
        }
    }

    @Test
    fun reconnectPolicyRejectsInvertedBounds() {
        assertFailsWith<McpConfigException> {
            McpReconnectPolicy(initialDelayMs = 5_000, maxDelayMs = 1_000).validated()
        }
        assertFailsWith<McpConfigException> { McpReconnectPolicy(maxAttempts = 0).validated() }
    }

    @Test
    fun toolIdsFollowTheRegistryAlphabetAndStayBounded() {
        assertEquals("core.mcp.demo.echo", mcpToolId("demo", "echo"))
        assertEquals("core.mcp.demo.do_it", mcpToolId("demo", "do it"))
        // The `core.mcp.` prefix supplies the leading letter, so a digit-led tool name is fine.
        assertEquals("core.mcp.demo.9lives", mcpToolId("demo", "9lives"))
        // Overlong names are truncated with a stable hash suffix, so two long names stay distinct.
        val longA = mcpToolId("demo", "x".repeat(200) + "a")
        val longB = mcpToolId("demo", "x".repeat(200) + "b")
        assertTrue(longA.length <= 128)
        assertTrue(longA != longB)
        assertEquals(longA, mcpToolId("demo", "x".repeat(200) + "a"))
    }

    @Test
    fun thePluginSpecCarriesTheEncodedConfig() {
        val config = McpServerConfig(serverName = "demo", url = "https://example.test/mcp")
        val spec = mcpPluginSpec(config)
        assertEquals("core.mcp.demo", spec.id)
        assertEquals(config, McpServerConfigCodec.decode(spec.config))
    }
}
