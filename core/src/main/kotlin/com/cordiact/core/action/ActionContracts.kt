package com.cordiact.core.action

/** Stable, provider-neutral contract for an operation requested by a workspace. */
data class ActionRequest(
    val id: String,
    val type: String,
    val workspaceId: String,
    val input: Map<String, String> = emptyMap(),
    val capabilities: Set<String> = emptySet(),
)

enum class PolicyDecision { ALLOW, DENY, CONFIRM }

data class PolicyResult(val decision: PolicyDecision, val reason: String)

interface ActionPolicy {
    fun evaluate(request: ActionRequest): PolicyResult
}

class DenyByDefaultPolicy(
    private val allowedTypes: Set<String> = emptySet(),
) : ActionPolicy {
    override fun evaluate(request: ActionRequest): PolicyResult =
        if (request.type in allowedTypes) {
            PolicyResult(PolicyDecision.CONFIRM, "Action is registered and requires user confirmation")
        } else {
            PolicyResult(PolicyDecision.DENY, "Action type is not registered")
        }
}

/**
 * Human-in-the-loop approval request for operations that need user confirmation (CONFIRM).
 */
data class ApprovalRequest(
    val id: String,
    val toolName: String,
    val summary: String,
    val details: Map<String, String> = emptyMap(),
    val riskLevel: RiskLevel = RiskLevel.MEDIUM,
    val createdAtMillis: Long = System.currentTimeMillis(),
) {
    enum class RiskLevel { LOW, MEDIUM, HIGH, CRITICAL }
}

data class ApprovalDecision(
    val approved: Boolean,
    val rememberChoice: Boolean = false,
    val reason: String? = null,
)

/**
 * Gateway for human-in-the-loop approval requests.
 * The UI subscribes to pending requests, while execution awaits an approval decision.
 */
interface ApprovalGateway {
    /** Submit a request for user confirmation and block until decided or timed out. */
    fun requestApproval(request: ApprovalRequest, timeoutMillis: Long = 60000L): ApprovalDecision

    /** Submits a user decision for a pending request ID. Returns true if the request was active. */
    fun respond(requestId: String, decision: ApprovalDecision): Boolean

    /** Lists currently pending approval requests for UI consumption. */
    fun pendingRequests(): List<ApprovalRequest>

    /** Clears / cancels all pending requests. */
    fun clear()
}

/**
 * Default thread-safe implementation of ApprovalGateway.
 */
class DefaultApprovalGateway : ApprovalGateway {
    private val pending = java.util.concurrent.ConcurrentHashMap<String, ApprovalSlot>()

    private class ApprovalSlot(
        val request: ApprovalRequest,
        val latch: java.util.concurrent.CountDownLatch = java.util.concurrent.CountDownLatch(1),
        @Volatile var decision: ApprovalDecision? = null,
    )

    override fun requestApproval(request: ApprovalRequest, timeoutMillis: Long): ApprovalDecision {
        val slot = ApprovalSlot(request)
        pending[request.id] = slot
        try {
            val completed = slot.latch.await(timeoutMillis, java.util.concurrent.TimeUnit.MILLISECONDS)
            return if (completed) {
                slot.decision ?: ApprovalDecision(approved = false, reason = "No decision recorded")
            } else {
                ApprovalDecision(approved = false, reason = "Approval timed out after ${timeoutMillis}ms")
            }
        } finally {
            pending.remove(request.id)
        }
    }

    override fun respond(requestId: String, decision: ApprovalDecision): Boolean {
        val slot = pending[requestId] ?: return false
        // Sensitive requests must not accept two answers. The first responder wins; every later
        // response (double click, second window, late timer) is rejected.
        synchronized(slot) {
            if (slot.decision != null) return false
            slot.decision = decision
        }
        slot.latch.countDown()
        return true
    }

    override fun pendingRequests(): List<ApprovalRequest> =
        pending.values.map { it.request }

    override fun clear() {
        for (slot in pending.values) {
            slot.decision = ApprovalDecision(approved = false, reason = "Approval gateway cleared")
            slot.latch.countDown()
        }
        pending.clear()
    }
}

