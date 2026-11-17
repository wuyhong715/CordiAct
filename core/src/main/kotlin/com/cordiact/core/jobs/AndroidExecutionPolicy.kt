package com.cordiact.core.jobs

/** A shell/SSH/Termux/sub-agent job is not evidence of an Android dataSync use case. */
object AndroidExecutionPolicy {
    fun allowsDataSync(kind: JobKind): Boolean = when (kind) {
        JobKind.SHELL, JobKind.SSH, JobKind.TERMUX, JobKind.SUBAGENT -> false
    }
}

enum class ForegroundExecutionState { IDLE, REQUESTED, ACTIVE, REJECTED, TIMED_OUT, START_TIMEOUT, STOPPED }

/** Android-free receipt gate. Late service callbacks cannot resurrect obsolete requests. */
class ForegroundReceiptGate {
    var state: ForegroundExecutionState = ForegroundExecutionState.IDLE
        private set
    var requestId: String? = null
        private set
    fun request(id: String) { require(id.isNotBlank()); requestId = id; state = ForegroundExecutionState.REQUESTED }
    fun acknowledge(id: String, active: Boolean): Boolean {
        if (id != requestId || state !in setOf(ForegroundExecutionState.REQUESTED, ForegroundExecutionState.ACTIVE)) return false
        state = if (active) ForegroundExecutionState.ACTIVE else ForegroundExecutionState.REJECTED
        return true
    }
    fun expire(id: String): Boolean {
        if (id != requestId || state != ForegroundExecutionState.REQUESTED) return false
        requestId = null
        state = ForegroundExecutionState.START_TIMEOUT
        return true
    }
    fun stop(timedOut: Boolean = false) {
        requestId = null
        state = if (timedOut) ForegroundExecutionState.TIMED_OUT else ForegroundExecutionState.STOPPED
    }
}

data class AndroidExecutionConditions(
    val foreground: ForegroundExecutionState = ForegroundExecutionState.IDLE,
    val notificationsAllowed: Boolean = false,
    val foregroundEligible: Boolean = false,
    val budgetBlocked: Boolean = false,
    val networkValidated: Boolean? = null,
    val deviceLocked: Boolean? = null,
    val powerSave: Boolean? = null,
    val backgroundRestricted: Boolean? = null,
    val sampledAtMillis: Long = 0,

)
