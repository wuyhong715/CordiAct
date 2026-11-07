package com.cordiact.subagent

import com.cordiact.core.conversation.SessionKind
import com.cordiact.core.conversation.TurnNotice
import com.cordiact.core.conversation.TurnNoticeSource
import com.cordiact.core.plugin.ManagedPlugin
import com.cordiact.core.plugin.PluginSpec
import com.cordiact.core.subagent.AgentRunRecord
import com.cordiact.core.subagent.AgentRunStore
import com.cordiact.core.subagent.BackgroundLimits
import com.cordiact.core.subagent.BackgroundSubagentService
import com.cordiact.core.subagent.ChildNotice
import com.cordiact.core.subagent.ChildResult
import com.cordiact.core.subagent.ChildRun
import com.cordiact.core.subagent.ChildRunState
import com.cordiact.core.subagent.ChildSpec
import com.cordiact.core.subagent.backgroundSubagentPluginSpec
import com.cordiact.core.tool.ToolInvocationContext
import com.cordiact.core.toolloop.ToolLoopStop
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit

/**
 * Background delegation: same admission as [SubagentRuntime], different waiting.
 *
 * Three things make this different from `delegate_task` rather than a flag on it:
 *
 * 1. **A run outlives the turn that started it.** It therefore needs its own wall clock, its own
 *    concurrency budget, and durable bookkeeping — a run killed with the process must be
 *    discoverable afterwards, or the parent waits forever.
 * 2. **Results reach the parent at two points, not by interruption.** A settled child queues a
 *    notice that the parent's *next* turn reads, and `onReportQueued` lets the host also hand the
 *    same report to a tool loop that is already running, at its next round boundary. A model
 *    request in flight is never interrupted.
 * 3. **A finished child still holds a slot.** Slots are the scarce resource; `close` is how the
 *    parent says it is done reading.
 */
class BackgroundSubagentRuntime(
    private val runtime: SubagentRuntime,
    private val runs: AgentRunStore,
    private val limits: BackgroundLimits = BackgroundLimits(),
    private val clockMillis: () -> Long = System::currentTimeMillis,
    /**
     * Called the moment a child's report is queued, before any parent turn reads it.
     *
     * This is what lets the host deliver a report *inside* a running turn instead of only at the
     * head of the next one, and decide whether an idle Session should be woken.
     */
    private val onReportQueued: ((ChildNotice) -> Unit)? = null,
    private val executor: ExecutorService = Executors.newCachedThreadPool { runnable ->
        Thread(runnable, "cordiact-subagent-bg").apply { isDaemon = true }
    },
) : ManagedPlugin, BackgroundSubagentService, TurnNoticeSource {

    override val spec: PluginSpec = backgroundSubagentPluginSpec()

    @Volatile
    private var enabled = false

    override fun onEnable() {
        enabled = true
    }

    override fun onDisable() {
        enabled = false
    }

    override fun onUninstall() = onDisable()

    /** In-flight work for this process only; the durable record is what survives a restart. */
    private class LiveRun(val childSessionId: String, val label: String, val parentSessionId: String) {
        @Volatile var result: ChildResult? = null
        @Volatile var diagnostic: String? = null
        @Volatile var closed = false
        @Volatile var future: Future<*>? = null
        @Volatile private var settled = java.util.concurrent.CountDownLatch(1)

        fun awaitSettled(millis: Long): Boolean = settled.await(millis, TimeUnit.MILLISECONDS)
        fun markSettled() = settled.countDown()
        fun resetSettled() { settled = java.util.concurrent.CountDownLatch(1) }

        @Volatile var state: ChildRunState = ChildRunState.RUNNING
    }

    private val live = ConcurrentHashMap<String, LiveRun>()

    /** Notices the parent has not been told yet, keyed by parent Session. */
    private val pending = ConcurrentHashMap<String, MutableList<ChildNotice>>()

    /** Bounds the children actually running, not the ones merely still open. */
    private val slots = Semaphore(limits.maxRunningChildren, true)

    override fun spawn(spec: ChildSpec, parent: ToolInvocationContext): ChildRun {
        check(enabled) { "Background delegation is not enabled" }
        val parentSessionId = parent.sessionId
            ?: error("Delegation requires a calling Session")

        // Admission is synchronous: the parent needs an id it can hold on to, even if the run
        // never produces an answer. A child that cannot be admitted therefore fails here, before
        // anything is queued.
        val admission = runtime.admit(spec.copy(kind = SessionKind.SUBAGENT), parent)
        if (admission is SubagentRuntime.Admission.Refused) {
            val record = AgentRunRecord(
                sessionId = "",
                parentSessionId = parentSessionId,
                label = spec.label,
                state = ChildRunState.FAILED,
                startedAtMillis = clockMillis(),
                diagnostic = admission.reason,
            )
            return ChildRun(
                sessionId = "",
                parentSessionId = parentSessionId,
                label = spec.label,
                state = ChildRunState.FAILED,
                diagnostic = admission.reason,
                startedAtMillis = record.startedAtMillis,
            )
        }
        val admitted = (admission as SubagentRuntime.Admission.Admitted).child

        // The slot is taken here, on the caller's thread, not inside the task: a spawn that
        // cannot run must fail while the parent can still react, rather than queue behind work
        // whose duration nobody knows.
        if (!slots.tryAcquire()) {
            val diagnostic = "too many delegated tasks are already running " +
                "(max ${limits.maxRunningChildren}); wait for one, or close it"
            runs.put(
                AgentRunRecord(
                    sessionId = admitted.sessionId,
                    parentSessionId = parentSessionId,
                    label = admitted.label,
                    state = ChildRunState.FAILED,
                    startedAtMillis = clockMillis(),
                    diagnostic = diagnostic,
                ),
            )
            return ChildRun(
                sessionId = admitted.sessionId,
                parentSessionId = parentSessionId,
                label = admitted.label,
                state = ChildRunState.FAILED,
                diagnostic = diagnostic,
                startedAtMillis = clockMillis(),
            )
        }

        val run = LiveRun(admitted.sessionId, admitted.label, parentSessionId)
        live[admitted.sessionId] = run
        runs.put(
            AgentRunRecord(
                sessionId = admitted.sessionId,
                parentSessionId = parentSessionId,
                label = admitted.label,
                state = ChildRunState.RUNNING,
                startedAtMillis = clockMillis(),
            ),
        )

        // A slot is taken for the run's whole life and released when it settles, so a child that
        // finished but was never closed does not block new work — only `close` frees the record.
        run.future = executor.submit { runToSettle(run, admitted) }
        return describe(run)
    }

    /**
     * Runs one child turn and records what happened.
     *
     * Shared by spawn and send so both settle identically: a failure path that only one of them
     * knew about would leave a run looking RUNNING forever.
     */
    private fun runToSettle(run: LiveRun, admitted: SubagentRuntime.AdmittedChild) {
        // The slot was taken by the caller before this task was submitted; only release it here.
        try {
            val result = runtime.execute(admitted, clockMillis() + limits.runTimeoutMillis)
            run.result = result
            run.state = if (result.stop == ToolLoopStop.COMPLETED) {
                ChildRunState.COMPLETED
            } else {
                ChildRunState.FAILED
            }
            runs.updateState(
                run.childSessionId,
                run.state,
                result.text.takeIf { run.state == ChildRunState.FAILED },
            )
            enqueueNotice(run, result)
        } catch (failure: InterruptedException) {
            run.state = ChildRunState.FAILED
            run.diagnostic = "the run was interrupted"
            runs.updateState(run.childSessionId, ChildRunState.FAILED, run.diagnostic)
            enqueueNotice(run, null)
            Thread.currentThread().interrupt()
        } catch (failure: Exception) {
            run.state = ChildRunState.FAILED
            run.diagnostic = failure.message ?: "the delegated turn failed"
            runs.updateState(run.childSessionId, ChildRunState.FAILED, run.diagnostic)
            enqueueNotice(run, null)
        } finally {
            slots.release()
            run.markSettled()
        }
    }

    override fun waitFor(
        parent: ToolInvocationContext,
        childIds: List<String>,
        timeoutMillis: Long,
    ): List<ChildRun> {
        check(enabled) { "Background delegation is not enabled" }
        val parentSessionId = parent.sessionId ?: error("Waiting requires a calling Session")
        // The floor is not politeness: 0 would spin the parent's turn against a latch that has
        // not finished, burning the round budget for no information.
        val timeout = timeoutMillis.coerceIn(limits.minWaitMillis, limits.maxWaitMillis)

        val targets = if (childIds.isEmpty()) {
            states(parent).filter { it.state == ChildRunState.RUNNING }.map { it.sessionId }
        } else {
            childIds
        }
        if (targets.isEmpty()) return states(parent)

        val deadline = clockMillis() + timeout
        for (id in targets) {
            val run = live[id] ?: continue
            val remaining = deadline - clockMillis()
            if (remaining <= 0) break
            run.awaitSettled(remaining)
        }
        return states(parent)
    }

    override fun states(parent: ToolInvocationContext): List<ChildRun> {
        check(enabled) { "Background delegation is not enabled" }
        val parentSessionId = parent.sessionId ?: error("Listing requires a calling Session")

        // Durable rows first: they cover children this process never started (an earlier process
        // finished or abandoned them), which the in-memory map cannot know about.
        val durable = runs.runsOf(parentSessionId).associateBy { it.sessionId }
        val ordered = linkedMapOf<String, ChildRun>()
        durable.forEach { (sessionId, record) ->
            ordered[sessionId] = ChildRun(
                sessionId = sessionId,
                parentSessionId = parentSessionId,
                label = record.label,
                state = record.state,
                result = live[sessionId]?.result,
                startedAtMillis = record.startedAtMillis,
                diagnostic = record.diagnostic,
            )
        }
        live.values.filter { it.parentSessionId == parentSessionId }.forEach { run ->
            ordered[run.childSessionId] = describe(run)
        }
        return ordered.values.toList()
    }

    override fun close(parent: ToolInvocationContext, childId: String): ChildRun {
        check(enabled) { "Background delegation is not enabled" }
        val parentSessionId = parent.sessionId ?: error("Closing requires a calling Session")
        val run = live[childId]
        // Ownership is checked for live runs too: living in this process is not the same as
        // belonging to the conversation that is asking, and an id is not a capability.
        if (run != null && run.parentSessionId != parentSessionId) {
            throw IllegalArgumentException("Child '$childId' belongs to another conversation")
        }
        if (run == null) {
            val record = requireOwnership(parentSessionId, childId)
            return ChildRun(
                sessionId = childId,
                parentSessionId = parentSessionId,
                label = record.label,
                state = ChildRunState.CLOSED,
                startedAtMillis = record.startedAtMillis,
            )
        }
        // Closing a running child aborts it: leaving a turn running that nobody will ever read
        // is the same as leaking a slot.
        if (run.state == ChildRunState.RUNNING) {
            run.future?.cancel(true)
        }
        run.closed = true
        run.state = ChildRunState.CLOSED
        runs.updateState(childId, ChildRunState.CLOSED)
        return describe(run)
    }

    /**
     * Drops a released child's retained result from memory.
     *
     * A run keeps its result text (up to `maxResultChars`) for as long as the process lives, even
     * after the parent closed it. Only a closed run may be forgotten: an open child can still be
     * continued, and dropping it would turn "continue this child" into "that child never existed".
     *
     * The database record is untouched, so the transcript still opens.
     */
    fun forget(childSessionId: String): Boolean {
        val run = live[childSessionId] ?: return false
        if (!run.closed) return false
        return live.remove(childSessionId) != null
    }

    /** Whether [forget] would succeed right now; used by the residency policy. */
    fun isForgettable(childSessionId: String): Boolean = live[childSessionId]?.closed == true

    override fun send(parent: ToolInvocationContext, childId: String, message: String): ChildRun {
        check(enabled) { "Background delegation is not enabled" }
        val parentSessionId = parent.sessionId ?: error("Sending requires a calling Session")
        val run = live[childId]
            ?: throw IllegalStateException(
                "Child $childId is not a live run in this process; only a child started here accepts messages",
            )
        require(!run.closed) { "Child $childId was closed and no longer accepts messages" }
        // Only an idle child can be given another turn. Steering a running one would need the
        // child's turn to accept mid-flight input, which the turn service does not offer.
        check(run.state != ChildRunState.RUNNING) {
            "Child $childId is still running; wait_children for it to finish before sending"
        }
        val admission = runtime.admitExisting(childId, message, parent)
        if (admission is SubagentRuntime.Admission.Refused) {
            throw IllegalStateException(admission.reason)
        }
        val admitted = (admission as SubagentRuntime.Admission.Admitted).child
        if (!slots.tryAcquire()) {
            throw IllegalStateException(
                "too many delegated tasks are already running (max ${limits.maxRunningChildren})",
            )
        }
        run.result = null
        run.diagnostic = null
        run.state = ChildRunState.RUNNING
        runs.updateState(childId, ChildRunState.RUNNING)
        // A fresh latch: the previous one is already open, and waitFor must be able to block on
        // the follow-up turn as it did on the first.
        run.resetSettled()
        run.future = executor.submit { runToSettle(run, admitted) }
        return describe(run)
    }

    override fun pendingNotices(parentSessionId: String): List<ChildNotice> =
        synchronized(pending) { pending[parentSessionId]?.toList().orEmpty() }

    override fun acknowledgeNotices(parentSessionId: String, ids: List<String>) {
        if (ids.isEmpty()) return
        synchronized(pending) {
            pending[parentSessionId]?.removeAll { it.id in ids }
            if (pending[parentSessionId]?.isEmpty() == true) pending.remove(parentSessionId)
        }
    }

    override fun recoverAbandonedRuns(): List<AgentRunRecord> {
        val abandoned = runs.abortRunning()
        abandoned.forEach { record ->
            enqueue(
                ChildNotice(
                    id = "abandoned:${record.sessionId}",
                    parentSessionId = record.parentSessionId,
                    childSessionId = record.sessionId,
                    label = record.label,
                    state = ChildRunState.ABORTED,
                    summary = "A delegated task did not finish: the app was closed while " +
                        "\"${record.label}\" was still running.",
                    body = "Its Session still holds everything it wrote before the app stopped.",
                ),
            )
        }
        return abandoned
    }

    // --- TurnNoticeSource: how a settled child reaches the parent's next turn ---

    override fun pending(sessionId: String): List<TurnNotice> =
        pendingNotices(sessionId).map { notice ->
            TurnNotice(
                id = notice.id,
                heading = notice.summary,
                body = notice.body,
            )
        }

    override fun acknowledge(sessionId: String, ids: List<String>) = acknowledgeNotices(sessionId, ids)

    // --- internals ---

    private fun describe(run: LiveRun) = ChildRun(
        sessionId = run.childSessionId,
        parentSessionId = run.parentSessionId,
        label = run.label,
        state = run.state,
        result = run.result,
        startedAtMillis = 0,
        diagnostic = run.diagnostic,
    )

    private fun requireOwnership(parentSessionId: String, childId: String): AgentRunRecord {
        val record = runs.runOf(childId)
            ?: throw IllegalArgumentException("Unknown child '$childId'")
        // A child id from another conversation must not be closable: the id is not a capability.
        require(record.parentSessionId == parentSessionId) {
            "Child '$childId' belongs to another conversation"
        }
        return record
    }

    private fun enqueueNotice(run: LiveRun, result: ChildResult?) {
        val summary = when (run.state) {
            ChildRunState.COMPLETED -> "Delegated task \"${run.label}\" finished."
            ChildRunState.FAILED -> "Delegated task \"${run.label}\" failed."
            else -> "Delegated task \"${run.label}\" ended (${run.state})."
        }
        enqueue(
            ChildNotice(
                id = run.childSessionId,
                parentSessionId = run.parentSessionId,
                childSessionId = run.childSessionId,
                label = run.label,
                state = run.state,
                summary = summary,
                // The body is what the parent would otherwise have received as a tool result.
                body = result?.text ?: run.diagnostic,
            ),
        )
    }

    private fun enqueue(notice: ChildNotice) {
        synchronized(pending) {
            val list = pending.getOrPut(notice.parentSessionId) { mutableListOf() }
            // A second notice for the same child replaces the first: the newest outcome is the
            // true one, and a queue that keeps both would report a failure that was later fixed.
            list.removeAll { it.id == notice.id }
            list += notice
        }
        // Outside the lock: the host may start a turn from here, and that must not run while the
        // queue is held.
        runCatching { onReportQueued?.invoke(notice) }
    }
}
