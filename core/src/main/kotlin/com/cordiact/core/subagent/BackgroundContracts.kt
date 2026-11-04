package com.cordiact.core.subagent

import com.cordiact.core.plugin.PluginSpec
import com.cordiact.core.plugin.PluginTarget
import com.cordiact.core.tool.ToolInvocationContext

const val BACKGROUND_PLUGIN_ID = "core.subagent-background"
const val BACKGROUND_VERSION = "0.1.0"
const val BACKGROUND_CAPABILITY = "core.agents.delegate.background"

const val SPAWN_CHILD_TOOL_ID = "core.subagent.spawn"
const val WAIT_CHILDREN_TOOL_ID = "core.subagent.wait"
const val CLOSE_CHILD_TOOL_ID = "core.subagent.close"
const val SEND_CHILD_TOOL_ID = "core.subagent.send"

fun backgroundSubagentPluginSpec() = PluginSpec(
    id = BACKGROUND_PLUGIN_ID,
    version = BACKGROUND_VERSION,
    target = PluginTarget.ANDROID_NATIVE,
    requestedCapabilities = setOf(BACKGROUND_CAPABILITY),
    // A background child is still an ordinary delegated child: same admission, same depth
    // ceiling, same grant copy. Only the waiting changes.
    dependencies = setOf("core.subagent"),
)

/**
 * A child's lifecycle as the parent can observe it.
 *
 * [UNTRACKED] exists because a child Session outlives the run that created it: after the process
 * is killed nothing in memory remains, and reporting "unknown" is the only honest answer for a
 * child the current process never started.
 */
enum class ChildRunState {
    RUNNING,

    COMPLETED,

    /** Finished without a usable answer: the turn stopped early or the provider failed. */
    FAILED,

    /** Released by the parent; no further messages accepted and it no longer occupies a slot. */
    CLOSED,

    /** The process died while this run was in flight. Detected at startup, never in-memory. */
    ABORTED,

    /** No live run record: finished in an earlier process, or the id is wrong. */
    UNTRACKED,
}

/** One background run as the parent sees it. */
data class ChildRun(
    val sessionId: String,
    val parentSessionId: String,
    val label: String,
    val state: ChildRunState,
    /** Present once [state] is terminal. */
    val result: ChildResult? = null,
    val startedAtMillis: Long = 0,
    /** Cleared when acknowledged; kept so the model can be told a child never finished. */
    val diagnostic: String? = null,
)

/**
 * What the parent's next turn is told about a child.
 *
 * This is the mailbox. A synchronous turn has nowhere to deliver an event, so a settled child's
 * outcome waits here until the parent's next turn reads it — the smallest equivalent of an
 * inbox in an architecture that has no event loop.
 */
data class ChildNotice(
    val id: String,
    val parentSessionId: String,
    val childSessionId: String,
    val label: String,
    val state: ChildRunState,
    /** One line the model reads first; the whole notice is this plus [body]. */
    val summary: String,
    val body: String? = null,
)

/** Fixed bounds for background delegation. Host constants, never plan arguments. */
data class BackgroundLimits(
    /** Children allowed to run at once across the whole app. */
    val maxRunningChildren: Int = 4,
    /** Floor for [BackgroundSubagentService.waitFor]: a tight poll loop burns the turn budget. */
    val minWaitMillis: Long = 1_000,
    val maxWaitMillis: Long = 120_000,
    val defaultWaitMillis: Long = 30_000,
    /**
     * A background child's own wall clock.
     *
     * Not inherited from the spawning turn: the whole point is that it may outlive it, so the
     * budget has to come from somewhere other than the parent's remaining time.
     */
    val runTimeoutMillis: Long = 600_000,
) {
    init {
        require(maxRunningChildren > 0) { "maxRunningChildren must be positive" }
        require(minWaitMillis in 1..maxWaitMillis) { "minWaitMillis must be within 1..maxWaitMillis" }
        require(defaultWaitMillis in minWaitMillis..maxWaitMillis) {
            "defaultWaitMillis must be within minWaitMillis..maxWaitMillis"
        }
        require(runTimeoutMillis > 0) { "runTimeoutMillis must be positive" }
    }
}

/**
 * Durable run bookkeeping.
 *
 * Separate from the in-memory registry because its job is to answer a question the registry
 * cannot: what happened to the runs of a process that no longer exists. A row that is still
 * RUNNING at startup is the only evidence a child turn was killed with the process.
 */
interface AgentRunStore {
    fun put(run: AgentRunRecord)
    fun updateState(sessionId: String, state: ChildRunState, diagnostic: String? = null)
    fun get(sessionId: String): AgentRunRecord?
    fun runsOf(parentSessionId: String): List<AgentRunRecord>

    /**
     * Marks every RUNNING row [ChildRunState.ABORTED] and returns them.
     *
     * Called once per process start. Without it a child killed with the process would read as
     * RUNNING forever and the parent would wait on it indefinitely.
     */
    fun abortRunning(): List<AgentRunRecord>
}

data class AgentRunRecord(
    val sessionId: String,
    val parentSessionId: String,
    val label: String,
    val state: ChildRunState,
    val startedAtMillis: Long,
    val diagnostic: String? = null,
)

interface BackgroundSubagentService {
    /**
     * Starts a child and returns immediately.
     *
     * The child Session is created synchronously, so the returned id is usable even if the run
     * never produces an answer: the user can still open a transcript that says what was asked.
     */
    fun spawn(spec: ChildSpec, parent: ToolInvocationContext): ChildRun

    /** Blocks until any of [childIds] settles or [timeoutMillis] elapses (clamped by limits). */
    fun waitFor(parent: ToolInvocationContext, childIds: List<String>, timeoutMillis: Long): List<ChildRun>

    /** Current state of every child of [parent], including ones from an earlier process. */
    fun states(parent: ToolInvocationContext): List<ChildRun>

    /** Releases a settled child's slot and refuses further messages. */
    fun close(parent: ToolInvocationContext, childId: String): ChildRun

    /** Starts another turn in an idle child. Refused while it is running. */
    fun send(parent: ToolInvocationContext, childId: String, message: String): ChildRun

    /** Notices the parent has not been told yet, oldest first. */
    fun pendingNotices(parentSessionId: String): List<ChildNotice>

    /** Marks [ids] delivered. Called only after the parent's turn actually started. */
    fun acknowledgeNotices(parentSessionId: String, ids: List<String>)

    /**
     * Answers what became of runs left RUNNING by a previous process and queues a notice for
     * each, so the parent learns a child died instead of waiting for it forever.
     */
    fun recoverAbandonedRuns(): List<AgentRunRecord>
}
