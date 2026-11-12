package com.cordiact.app.experience.settings

import com.cordiact.core.mcp.McpToolDescriptor
import java.util.concurrent.CancellationException
import org.junit.Assert.*
import org.junit.Test

class McpReconnectPolicyTest {
    private val tools = listOf(McpToolDescriptor("lookup", "Read-only lookup"))
    @Test fun enabledServerRefreshesTransportAndReturnsOnlyDescriptors() {
        val events = mutableListOf<String>()
        val result = reconnectMcpTools({ true }, { events += "list"; tools }, { events += "restart" })
        assertEquals(listOf("list", "restart"), events)
        assertEquals(McpReconnectOutcome.Connected(tools), result)
    }
    @Test fun disabledServerIsProbedButNeverEnabled() {
        var restarts = 0
        assertEquals(McpReconnectOutcome.Connected(tools), reconnectMcpTools({ false }, { tools }, { restarts++ }))
        assertEquals(0, restarts)
    }
    @Test fun concurrentDisableDuringProbeIsRespected() {
        var enabled = true
        var restarts = 0
        val result = reconnectMcpTools({ enabled }, { enabled = false; tools }, { restarts++ })
        assertTrue(result is McpReconnectOutcome.Connected)
        assertEquals(0, restarts)
    }
    @Test fun failedProbeDoesNotDisturbPublishedPluginOrLeakException() {
        var restarts = 0
        val result = reconnectMcpTools({ true }, { error("Authorization: very-secret https://private") }, { restarts++ })
        assertEquals(McpReconnectOutcome.Failed(McpReconnectFailure.FAILED), result)
        assertFalse(result.toString().contains("very-secret"))
        assertEquals(0, restarts)
    }
    @Test fun failedRestartIsNotReportedAsSuccessfulConnection() {
        val result = reconnectMcpTools({ true }, { tools }, { error("secret") })
        assertEquals(McpReconnectOutcome.Failed(McpReconnectFailure.FAILED), result)
    }
    @Test fun coroutineCancellationIsNotConvertedIntoFailure() {
        try {
            reconnectMcpTools({ true }, { throw CancellationException() }, { fail("must not restart") })
            fail("must propagate cancellation")
        } catch (_: CancellationException) { }
    }
}
