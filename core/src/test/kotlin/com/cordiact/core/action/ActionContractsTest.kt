package com.cordiact.core.action

import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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

    @Test
    fun defaultApprovalGatewayAcceptsOnlyOneAnswer() {
        val gateway = DefaultApprovalGateway()
        val request = ApprovalRequest(id = "req-2", toolName = "credential_read", summary = "read")
        val executor = java.util.concurrent.Executors.newSingleThreadExecutor()
        val future = executor.submit<ApprovalDecision> { gateway.requestApproval(request, 5000L) }
        var retries = 50
        while (gateway.pendingRequests().isEmpty() && retries > 0) {
            Thread.sleep(10)
            retries--
        }
        assertTrue(gateway.respond("req-2", ApprovalDecision(approved = true)))
        assertTrue(!gateway.respond("req-2", ApprovalDecision(approved = false)))
        assertTrue(future.get(2, java.util.concurrent.TimeUnit.SECONDS).approved)
        executor.shutdown()
    }

    @Test
    fun defaultApprovalGatewayRejectsFirstResponseAtOrAfterDeadlineWithoutWaitingForWaiter() {
        for (offsetNanos in listOf(0L, 1L)) {
            val clock = AtomicLong(Long.MAX_VALUE - 1)
            val gateway = DefaultApprovalGateway(clock::get)
            val executor = Executors.newSingleThreadExecutor()
            try {
                val request = ApprovalRequest("deadline-$offsetNanos", "test", "test deadline")
                val waiter = executor.submit<ApprovalDecision> { gateway.requestApproval(request, 60_000) }
                awaitPending(gateway, request)

                // Only the fake clock advances; the real timed wait still has almost its full budget.
                // Don't poll pendingRequests before responding: its expiry path must not drive this test.
                clock.addAndGet(TimeUnit.MINUTES.toNanos(1) + offsetNanos)
                assertFalse(gateway.respond(request.id, ApprovalDecision(approved = true)))
                assertEquals(ApprovalDecision(false, reason = "Approval timed out after 60000ms"), waiter.get(2, TimeUnit.SECONDS))
                assertTrue(gateway.pendingRequests().isEmpty())
            } finally {
                gateway.clear()
                executor.shutdownNow()
            }
        }
    }

    @Test
    fun defaultApprovalGatewayAcceptsAResponseJustBeforeDeadline() {
        val clock = AtomicLong()
        val gateway = DefaultApprovalGateway(clock::get)
        val executor = Executors.newSingleThreadExecutor()
        try {
            val request = ApprovalRequest("before-deadline", "test", "test deadline")
            val choice = ApprovalDecision(approved = true, rememberChoice = true)
            val waiter = executor.submit<ApprovalDecision> { gateway.requestApproval(request, 60_000) }
            awaitPending(gateway, request)

            clock.set(TimeUnit.MINUTES.toNanos(1) - 1)
            assertTrue(gateway.respond(request.id, choice))
            clock.incrementAndGet()
            assertEquals(choice, waiter.get(2, TimeUnit.SECONDS))
            assertFalse(gateway.respond(request.id, ApprovalDecision(approved = false)))
        } finally {
            gateway.clear()
            executor.shutdownNow()
        }
    }

    @Test
    fun defaultApprovalGatewayWaitCanTimeOutWithoutAResponder() {
        val gateway = DefaultApprovalGateway()
        val request = ApprovalRequest("wait-timeout", "test", "no responder")
        assertEquals(ApprovalDecision(false, reason = "Approval timed out after 10ms"), gateway.requestApproval(request, 10))
        assertFalse(gateway.respond(request.id, ApprovalDecision(approved = true)))
        assertTrue(gateway.pendingRequests().isEmpty())
    }

    @Test
    fun defaultApprovalGatewayDoesNotReplaceADuplicateOrCleanUpAnotherRegistration() {
        val gateway = DefaultApprovalGateway()
        val executor = Executors.newFixedThreadPool(2)
        try {
            val request = ApprovalRequest("duplicate", "test", "original request")
            val original = executor.submit<ApprovalDecision> { gateway.requestApproval(request, 60_000) }
            awaitPending(gateway, request)

            val replacement = request.copy(summary = "different request")
            assertFalse(gateway.requestApproval(replacement, 10).approved)
            assertEquals(listOf(request), gateway.pendingRequests())

            gateway.clear()
            // Deliberate reuse to exercise the old waiter's cleanup, not a supported caller pattern.
            val next = executor.submit<ApprovalDecision> { gateway.requestApproval(replacement, 60_000) }
            awaitPending(gateway, replacement)
            assertEquals(ApprovalDecision(false, reason = "Approval gateway cleared"), original.get(2, TimeUnit.SECONDS))
            assertEquals(listOf(replacement), gateway.pendingRequests())
            assertTrue(gateway.respond(replacement.id, ApprovalDecision(approved = true)))
            assertTrue(next.get(2, TimeUnit.SECONDS).approved)
        } finally {
            gateway.clear()
            executor.shutdownNow()
        }
    }

    @Test
    fun defaultApprovalGatewayRejectsAnAnswerAfterInterruptingTheWaiter() {
        val gateway = DefaultApprovalGateway()
        val executor = Executors.newSingleThreadExecutor()
        try {
            val request = ApprovalRequest("interrupted", "test", "interrupted request")
            val waiterThread = AtomicReference<Thread>()
            val interrupted = executor.submit<Boolean> {
                waiterThread.set(Thread.currentThread())
                try {
                    gateway.requestApproval(request, 60_000)
                    false
                } catch (_: InterruptedException) {
                    true
                }
            }
            awaitPending(gateway, request)
            waiterThread.get().interrupt()
            assertFalse(gateway.respond(request.id, ApprovalDecision(approved = true)))
            assertTrue(interrupted.get(2, TimeUnit.SECONDS))
            assertTrue(gateway.pendingRequests().isEmpty())
        } finally {
            gateway.clear()
            executor.shutdownNow()
        }
    }

    @Test
    fun defaultApprovalGatewayRejectsRequestsAlreadyInvalidatedAtRegistration() {
        val gateway = DefaultApprovalGateway()
        val request = ApprovalRequest("invalid-on-arrival", "test", "invalid request", isCurrent = { false })
        // An invalidation's best-effort reply can run before registration.
        assertFalse(gateway.respond(request.id, ApprovalDecision(approved = false)))
        val executor = Executors.newSingleThreadExecutor()
        try {
            val waiter = executor.submit<ApprovalDecision> { gateway.requestApproval(request, 60_000) }
            assertEquals(ApprovalDecision(false, reason = "Approval request is no longer current"), waiter.get(2, TimeUnit.SECONDS))
            assertFalse(gateway.respond(request.id, ApprovalDecision(approved = true)))
            assertTrue(gateway.pendingRequests().isEmpty())
        } finally {
            gateway.clear()
            executor.shutdownNow()
        }
    }

    @Test
    fun defaultApprovalGatewayRetiresInvalidatedRequestsOnResponseAndOnPolling() {
        val gateway = DefaultApprovalGateway()
        val executor = Executors.newSingleThreadExecutor()
        try {
            for (checkByResponse in listOf(true, false)) {
                val live = AtomicBoolean(true)
                val request = ApprovalRequest("invalid-$checkByResponse", "test", "invalid request", isCurrent = live::get)
                val waiter = executor.submit<ApprovalDecision> { gateway.requestApproval(request, 60_000) }
                awaitPending(gateway, request)
                live.set(false)
                if (checkByResponse) {
                    assertFalse(gateway.respond(request.id, ApprovalDecision(approved = true)))
                } else {
                    assertTrue(gateway.pendingRequests().isEmpty())
                }
                assertEquals(ApprovalDecision(false, reason = "Approval request is no longer current"), waiter.get(2, TimeUnit.SECONDS))
            }
        } finally {
            gateway.clear()
            executor.shutdownNow()
        }
    }

    private fun awaitPending(gateway: DefaultApprovalGateway, request: ApprovalRequest) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
        while (request !in gateway.pendingRequests() && deadline - System.nanoTime() > 0) Thread.sleep(1)
        assertTrue(request in gateway.pendingRequests(), "Request was not registered: ${request.id}")
    }
}
