package com.cordiact.core.action

import kotlin.test.*

class ObservingApprovalGatewayTest {
    private val request = ApprovalRequest("id", "tool", "summary")
    private fun delegate(fail: Boolean = false) = object : ApprovalGateway {
        override fun requestApproval(request: ApprovalRequest, timeoutMillis: Long): ApprovalDecision {
            if (fail) throw IllegalStateException("failure")
            return ApprovalDecision(false, reason = "denied")
        }
        override fun respond(requestId: String, decision: ApprovalDecision) = false
        override fun pendingRequests() = emptyList<ApprovalRequest>()
        override fun clear() {}
    }
    @Test fun reportsWaitAndPreservesDenial() {
        val gateway = ObservingApprovalGateway(delegate())
        val states = mutableListOf<Boolean>()
        val decision = gateway.observeWaiting({ states.add(it) }) { gateway.requestApproval(request) }
        assertFalse(decision.approved)
        assertEquals("denied", decision.reason)
        assertEquals(listOf(true, false), states)
        gateway.requestApproval(request)
        assertEquals(2, states.size)
    }
    @Test fun restoresObserverAfterException() {
        val gateway = ObservingApprovalGateway(delegate(true))
        val states = mutableListOf<Boolean>()
        assertFailsWith<IllegalStateException> {
            gateway.observeWaiting({ states.add(it) }) { gateway.requestApproval(request) }
        }
        assertEquals(listOf(true, false), states)
        assertFailsWith<IllegalStateException> { gateway.requestApproval(request) }
        assertEquals(2, states.size)
    }
    @Test fun observerFailureDoesNotChangeDecision() {
        val gateway = ObservingApprovalGateway(delegate())
        val decision = gateway.observeWaiting({ error("presentation failed") }) { gateway.requestApproval(request) }
        assertFalse(decision.approved)
    }
    @Test fun nestedScopeRestoresParent() {
        val gateway = ObservingApprovalGateway(delegate())
        val outer = mutableListOf<Boolean>(); val inner = mutableListOf<Boolean>()
        gateway.observeWaiting({ outer.add(it) }) {
            gateway.observeWaiting({ inner.add(it) }) { gateway.requestApproval(request) }
            gateway.requestApproval(request)
        }
        assertEquals(listOf(true, false), outer)
        assertEquals(listOf(true, false), inner)
    }
}
