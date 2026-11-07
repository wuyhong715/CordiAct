package com.cordiact.core.inbox

/**
 * Where one queued item came from, and therefore who is speaking.
 *
 * The distinction is not cosmetic: `CHILD_MESSAGE` is something a child agent chose to say,
 * while `CHILD_SETTLED`/`JOB_SETTLED` are the runtime's account of how something ended. Merging
 * them into one undifferentiated "notice" makes the transcript credit a child with words it never
 * wrote.
 */
enum class InboxItemKind {
    CHILD_MESSAGE,
    CHILD_SETTLED,
    JOB_SETTLED,
}

/**
 * One item waiting to reach a parent Session.
 *
 * [seq] is assigned by the inbox on enqueue and is the only ordering authority. A single drain
 * path consumes items in [seq] order, so a settlement can never overtake a message that was
 * produced before it — the failure mode that made deepseek-harness split two queues.
 */
data class InboxItem(
    val seq: Long,
    val kind: InboxItemKind,
    val sessionId: String,
    /** Child run id or job id; identifies the source for rendering and replacement. */
    val sourceId: String,
    val heading: String,
    val body: String? = null,
    val createdAtMillis: Long = 0L,
)

/**
 * Per-Session FIFO of pending reports.
 */
interface AgentInbox {
    /** Appends an item, assigning its [seq]. Returns the stored item. */
    fun enqueue(
        kind: InboxItemKind,
        sessionId: String,
        sourceId: String,
        heading: String,
        body: String? = null,
        createdAtMillis: Long = System.currentTimeMillis(),
    ): InboxItem

    /** Oldest-first snapshot without consuming. */
    fun peek(sessionId: String): List<InboxItem>

    /**
     * Removes and returns up to [limit] oldest items, in [seq] order.
     *
     * Callers acknowledge by consuming: an item handed to a model request must not be delivered
     * twice. If the request never reaches the model, the caller must [requeue] instead.
     */
    fun drain(sessionId: String, limit: Int = Int.MAX_VALUE): List<InboxItem>

    /** Puts items back at their original position; used when a turn fails to start. */
    fun requeue(items: List<InboxItem>)

    fun size(sessionId: String): Int
}

/** How many automatic wakes a Session may receive, and for which kinds. */
data class WakePolicy(
    val enabled: Boolean = true,
    /** Only these kinds may start a turn on their own. */
    val wakeOnKinds: Set<InboxItemKind> = setOf(InboxItemKind.CHILD_SETTLED, InboxItemKind.JOB_SETTLED),
    val maxAutoWakesPerHour: Int = 4,
    val windowMillis: Long = 60 * 60 * 1000L,
) {
    init {
        require(maxAutoWakesPerHour >= 0) { "maxAutoWakesPerHour must not be negative" }
        require(windowMillis > 0) { "windowMillis must be positive" }
    }
}

/** Sliding-window budget for automatic wakes, so a chatty child cannot spend the user's money. */
interface WakeLedger {
    /** True when a wake is allowed and has been recorded. */
    fun tryAcquire(sessionId: String, nowMillis: Long = System.currentTimeMillis()): Boolean
    fun recentWakes(sessionId: String, nowMillis: Long = System.currentTimeMillis()): Int
}

/** Whether a Session currently owns a running turn. */
fun interface SessionActivity {
    fun isRunning(sessionId: String): Boolean
}
