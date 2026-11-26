package com.cordiact.mcp.client

import com.cordiact.core.mcp.McpClientFactory
import com.cordiact.core.mcp.McpConnection
import com.cordiact.core.mcp.McpCredential
import com.cordiact.core.mcp.McpProtocolException
import com.cordiact.core.mcp.McpServerConfig
import com.cordiact.core.mcp.McpServerInfo
import com.cordiact.core.mcp.McpToolDescriptor
import java.util.concurrent.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame

class McpToolProbeTest {
    private val config = McpServerConfig("sample", "https://example.test/mcp", toolCallTimeoutMs = 3210)
    private val credential = McpCredential("Authorization", "synthetic-only")

    @Test fun handshakeFailureClosesWithoutListingAndPreservesThePrimaryFailure() {
        val handshake = McpProtocolException("Synthetic handshake failed")
        val cleanup = IllegalStateException("Synthetic close failed")
        val events = mutableListOf<String>()
        val factory = McpClientFactory { selected, key ->
            assertSame(config, selected)
            assertSame(credential, key)
            object : McpConnection {
                override fun initialize(): McpServerInfo { events += "initialize"; throw handshake }
                override fun listTools(timeoutMs: Long): List<McpToolDescriptor> = error("Cannot list without handshake")
                override fun callTool(name: String, argumentsJson: String, timeoutMs: Long) = error("Probes must never call tools")
                override fun close() { events += "close"; throw cleanup }
            }
        }
        val failure = assertFailsWith<McpProtocolException> { factory.probeTools(config, credential) }
        assertSame(handshake, failure)
        assertEquals(listOf(cleanup), failure.suppressed.toList())
        assertEquals(listOf("initialize", "close"), events)
    }

    @Test fun listingCancellationUsesConfiguredBudgetAndClosesWithoutRetry() {
        val cancellation = CancellationException("Synthetic cancellation")
        val events = mutableListOf<String>()
        val factory = McpClientFactory { _, _ ->
            object : McpConnection {
                override fun initialize(): McpServerInfo { events += "initialize"; return McpServerInfo("2025-06-18") }
                override fun listTools(timeoutMs: Long): List<McpToolDescriptor> { events += "list:$timeoutMs"; throw cancellation }
                override fun callTool(name: String, argumentsJson: String, timeoutMs: Long) = error("Probes must never call tools")
                override fun close() { events += "close" }
            }
        }
        assertSame(cancellation, assertFailsWith<CancellationException> { factory.probeTools(config, null) })
        assertEquals(listOf("initialize", "list:3210", "close"), events)
    }
}
