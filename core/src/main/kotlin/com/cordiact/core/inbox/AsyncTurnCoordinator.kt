package com.cordiact.core.inbox

/** Why an item did or did not start a turn. Recorded so the behaviour is explainable, not magic. */
enum class WakeDecision {
    /** A new turn was started for this Session. */
    WOKE,

    /** The Session is already running a turn; the item waits for its next round boundary. */
    QUEUED_SESSION_BUSY,

    /** Automatic waking is switched off; the item waits for the user's next message. */
    QUEUED_DISABLED,

    /** This kind is not allowed to wake a Session on its own. */
    QUEUED_KIND,

    /** The hourly wake budget is spent; the item waits rather than spending more. */
    QUEUED_QUOTA,
}

/**
 * Decides whether a newly queued report should start a turn for its parent Session.
 *
 * Three rules, each with a reason:
 *  - a busy Session is never interrupted — the tool loop drains its inbox at round boundaries;
 *  - only settlement-class kinds wake by default, because intermediate messages arrive in bursts
 *    and waking on each would turn one long child into a dozen paid parent turns;
 *  - the model cannot choose to wake anyone: this policy is host configuration, so "say it
 *    silently" is not an option a tool call can pick.
 */
class AsyncTurnCoordinator(
    private val policy: WakePolicy,
    private val ledger: WakeLedger,
    private val activity: SessionActivity,
    private val startTurn: (sessionId: String) -> Unit,
    private val clockMillis: () -> Long = System::currentTimeMillis,
) {

    fun onEnqueued(item: InboxItem): WakeDecision {
        if (!policy.enabled) return WakeDecision.QUEUED_DISABLED
        if (item.kind !in policy.wakeOnKinds) return WakeDecision.QUEUED_KIND
        if (activity.isRunning(item.sessionId)) return WakeDecision.QUEUED_SESSION_BUSY
        if (!ledger.tryAcquire(item.sessionId, clockMillis())) return WakeDecision.QUEUED_QUOTA

        // Re-check after taking the budget: the user may have sent a message in between, and a
        // turn that started on its own while the user was typing is exactly the wrong outcome.
        if (activity.isRunning(item.sessionId)) return WakeDecision.QUEUED_SESSION_BUSY

        startTurn(item.sessionId)
        return WakeDecision.WOKE
    }
}
