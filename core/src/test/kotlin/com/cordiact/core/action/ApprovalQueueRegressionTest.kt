package com.cordiact.core.action

import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ApprovalQueueRegressionTest {
    @Test fun multiplePendingRequestsCanEachBeDecidedAndClearDeniesTheRest() {
        val gateway = DefaultApprovalGateway()
        val pool = Executors.newFixedThreadPool(2)
        try {
            val a = pool.submit<ApprovalDecision> { gateway.requestApproval(ApprovalRequest("a", "test", "a"), 5000) }
            val b = pool.submit<ApprovalDecision> { gateway.requestApproval(ApprovalRequest("b", "test", "b"), 5000) }
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
            while (gateway.pendingRequests().size != 2 && System.nanoTime() < deadline) Thread.sleep(5)
            assertEquals(listOf("a", "b"), gateway.pendingRequests().map { it.id })
            assertTrue(gateway.respond("b", ApprovalDecision(true)))
            assertTrue(b.get(1, TimeUnit.SECONDS).approved)
            assertEquals(listOf("a"), gateway.pendingRequests().map { it.id })
            gateway.clear()
            assertFalse(a.get(1, TimeUnit.SECONDS).approved)
            assertTrue(gateway.pendingRequests().isEmpty())
            assertFalse(gateway.respond("a", ApprovalDecision(true)))
        } finally {
            gateway.clear()
            pool.shutdownNow()
        }
    }
}
