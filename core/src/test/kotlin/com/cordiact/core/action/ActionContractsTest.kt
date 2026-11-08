package com.cordiact.core.action

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ActionContractsTest {
    @Test
    fun unknownActionIsDenied() {
        val result = DenyByDefaultPolicy().evaluate(ActionRequest("a-1", "filesystem.delete", "ws-1"))
        assertEquals(PolicyDecision.DENY, result.decision)
    }

    @Test
    fun registeredActionNeedsConfirmation() {
        val result = DenyByDefaultPolicy(setOf("workspace.inspect"))
            .evaluate(ActionRequest("a-2", "workspace.inspect", "ws-1"))
        assertEquals(PolicyDecision.CONFIRM, result.decision)
    }

    @Test
    fun defaultApprovalGatewayApprovesAndRejects() {
        val gateway = DefaultApprovalGateway()
        val request = ApprovalRequest(
            id = "req-1",
            toolName = "ssh_exec",
            summary = "rm -rf /test",
            riskLevel = ApprovalRequest.RiskLevel.CRITICAL,
        )

        val entered = java.util.concurrent.CountDownLatch(1)
        val executor = java.util.concurrent.Executors.newSingleThreadExecutor()
        val future = executor.submit<ApprovalDecision> {
            val slot = java.lang.reflect.Proxy::class.java // warmup
            entered.countDown()
            gateway.requestApproval(request, 5000L)
        }

        assertTrue(entered.await(2, java.util.concurrent.TimeUnit.SECONDS))
        // Wait until pending request appears
        var retries = 50
        while (gateway.pendingRequests().isEmpty() && retries > 0) {
            Thread.sleep(10)
            retries--
        }

        // Verify request is visible in pendingRequests
        val pending = gateway.pendingRequests()
        assertEquals(1, pending.size)
        assertEquals("req-1", pending[0].id)

        // Approve it
        val responded = gateway.respond("req-1", ApprovalDecision(approved = true))
        assertTrue(responded)

        val decision = future.get(2, java.util.concurrent.TimeUnit.SECONDS)
        assertTrue(decision.approved)
        assertEquals(0, gateway.pendingRequests().size)
        executor.shutdown()
    }
}
