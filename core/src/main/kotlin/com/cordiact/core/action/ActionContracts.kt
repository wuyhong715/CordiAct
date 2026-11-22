package com.cordiact.core.action

import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.LockSupport

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
    /** Display/routing metadata from the trusted caller, never inferred from tool arguments. */
    val owner: ApprovalOwner? = null,
    /** Trusted liveness guard: nonblocking and lock-free; once false, stays false. Must not call the gateway. */
    @Transient val isCurrent: (() -> Boolean)? = null,
) {
    enum class RiskLevel { LOW, MEDIUM, HIGH, CRITICAL }
}

data class ApprovalOwner(val workspaceId: String, val sessionId: String? = null)

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
    /**
     * Submit a request for user confirmation and block until decided or timed out.
     * Each logical request must use a fresh, one-time ID: [respond] cannot distinguish generations of an ID.
     */
    fun requestApproval(request: ApprovalRequest, timeoutMillis: Long = 60000L): ApprovalDecision

    /** Returns true if the decision was accepted for this pending ID, not if an operation executed. */
    fun respond(requestId: String, decision: ApprovalDecision): Boolean

    /** Lists currently pending approval requests for UI consumption. */
    fun pendingRequests(): List<ApprovalRequest>

    /** Clears / cancels all pending requests. */
    fun clear()
}

/** Default thread-safe implementation. [nanoTime] must be a nonthrowing, monotonic nanosecond clock. */
class DefaultApprovalGateway internal constructor(private val nanoTime: () -> Long) : ApprovalGateway {
    constructor() : this(System::nanoTime)

    // Registration, deadline checks, and terminal decisions all share this monitor.
    // No thread holds it while waiting on an approval.
    private val lock = Any()
    private val pending = mutableMapOf<String, ApprovalSlot>()

    private class ApprovalSlot(
        val request: ApprovalRequest,
        val waiter: Thread,
        val deadlineNanos: Long,
        val timeoutMillis: Long,
    ) {
        var decision: ApprovalDecision? = null
    }

    override fun requestApproval(request: ApprovalRequest, timeoutMillis: Long): ApprovalDecision {
        val budgetNanos = TimeUnit.MILLISECONDS.toNanos(timeoutMillis.coerceAtLeast(0))
        val deadline = nanoTime() + budgetNanos
        val waiter = Thread.currentThread()
        val slot = synchronized(lock) {
            checkInterrupted()
            if (request.id in pending) return ApprovalDecision(false, reason = "Approval request ID is already pending")
            ApprovalSlot(request, waiter, deadline, timeoutMillis).also { registered ->
                pending[request.id] = registered
                closeIfInactive(registered)
            }
        }

        while (true) {
            val remainingNanos = synchronized(lock) {
                val remaining = if (slot.decision == null) closeIfInactive(slot) else 0L
                if (remaining <= 0) {
                    checkInterrupted()
                    return checkNotNull(slot.decision)
                }
                remaining
            }
            // Unlike interruptible latch waits, parking leaves the interrupt flag set until the
            // waiter can retire its slot under the same lock as respond. Recheck after every wake.
            LockSupport.parkNanos(this, remainingNanos)
        }
    }

    override fun respond(requestId: String, decision: ApprovalDecision): Boolean = synchronized(lock) {
        val slot = pending[requestId] ?: return false
        if (closeIfInactive(slot) <= 0) return false
        settle(slot, decision)
        true
    }

    override fun pendingRequests(): List<ApprovalRequest> = synchronized(lock) {
        pending.values.toList().mapNotNull { slot -> slot.request.takeIf { closeIfInactive(slot) > 0 } }.sortedBy { it.id }
    }

    override fun clear() = synchronized(lock) {
        for (slot in pending.values.toList()) {
            settle(slot, ApprovalDecision(false, reason = "Approval gateway cleared"))
        }
    }

    private fun checkInterrupted() {
        // Preserve the conventional interruptible-wait contract, after retiring any active slot.
        if (Thread.interrupted()) throw InterruptedException("Approval wait interrupted")
    }

    /** Must hold [lock] and operate on a pending slot. Returns a positive budget only while active. */
    private fun closeIfInactive(slot: ApprovalSlot): Long {
        val guard = slot.request.isCurrent
        val current = guard == null || try { guard() } catch (_: Exception) { false }
        // Subtract the monotonic deadline to remain correct even if the counter wraps.
        val remaining = slot.deadlineNanos - nanoTime()
        val reason = when {
            slot.waiter.isInterrupted -> "Approval wait interrupted"
            remaining <= 0 -> "Approval timed out after ${slot.timeoutMillis}ms"
            !current -> "Approval request is no longer current"
            else -> return remaining
        }
        settle(slot, ApprovalDecision(false, reason = reason))
        return 0
    }

    /** Must hold [lock]; never remove or complete a different registration of the same ID. */
    private fun settle(slot: ApprovalSlot, decision: ApprovalDecision) {
        if (pending[slot.request.id] !== slot) return
        slot.decision = decision
        pending.remove(slot.request.id)
        if (slot.waiter !== Thread.currentThread()) LockSupport.unpark(slot.waiter)
    }
}

