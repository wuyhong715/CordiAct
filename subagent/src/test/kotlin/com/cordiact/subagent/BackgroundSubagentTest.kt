package com.cordiact.subagent

import com.cordiact.core.conversation.ConversationMessage
import com.cordiact.core.conversation.ConversationSession
import com.cordiact.core.conversation.ConversationSessionStore
import com.cordiact.core.conversation.MessageRole
import com.cordiact.core.conversation.SessionKind
import com.cordiact.core.conversation.SessionManagerPlugin
import com.cordiact.core.subagent.AgentRunRecord
import com.cordiact.core.subagent.AgentRunStore
import com.cordiact.core.subagent.BackgroundLimits
import com.cordiact.core.subagent.ChildRunState
import com.cordiact.core.subagent.ChildSpec
import com.cordiact.core.subagent.ChildToolPlanner
import com.cordiact.core.subagent.ChildTurnOutcome
import com.cordiact.core.subagent.ChildTurnRunner
import com.cordiact.core.tool.ToolInvocationContext
import com.cordiact.core.toolloop.ToolLoopStop
import com.cordiact.core.workspace.Workspace
import com.cordiact.core.workspace.WorkspaceSessionResolver
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

private const val WS = "ws-1"

private class BackgroundStore : ConversationSessionStore {
    private val sessions = Collections.synchronizedMap(linkedMapOf<String, ConversationSession>())
    private val counter = AtomicInteger()

    override fun create(
        id: String, workspaceId: String, title: String, nowMillis: Long,
        parentSessionId: String?, kind: SessionKind, agentLabel: String?, depth: Int,
    ): ConversationSession = ConversationSession(
        id, workspaceId, title, nowMillis, nowMillis, parentSessionId, kind, agentLabel, depth,
    ).also { synchronized(sessions) { sessions[id] = it } }

    override fun get(sessionId: String) = synchronized(sessions) { sessions[sessionId] }
    override fun list(workspaceId: String) = synchronized(sessions) {
        sessions.values.filter { it.workspaceId == workspaceId && it.kind == SessionKind.USER }
    }
    override fun listChildren(parentSessionId: String) = synchronized(sessions) {
        sessions.values.filter { it.parentSessionId == parentSessionId }
    }
    override fun append(sessionId: String, role: MessageRole, content: String, nowMillis: Long, metadataJson: String?) =
        ConversationMessage("m-${counter.incrementAndGet()}", sessionId, role, content, nowMillis, metadataJson)
    override fun messages(sessionId: String) = emptyList<ConversationMessage>()
}

private class MemoryRunStore : AgentRunStore {
    val rows = Collections.synchronizedMap(linkedMapOf<String, AgentRunRecord>())

    override fun put(run: AgentRunRecord) { rows[run.sessionId] = run }

    override fun updateState(sessionId: String, state: ChildRunState, diagnostic: String?) {
        val existing = rows[sessionId] ?: return
        rows[sessionId] = existing.copy(state = state, diagnostic = diagnostic)
    }

    override fun runOf(sessionId: String) = rows[sessionId]
    override fun runsOf(parentSessionId: String) = synchronized(rows) {
        rows.values.filter { it.parentSessionId == parentSessionId }.toList()
    }

    override fun abortRunning(): List<AgentRunRecord> = synchronized(rows) {
        val abandoned = rows.values.filter { it.state == ChildRunState.RUNNING }.toList()
        abandoned.forEach { rows[it.sessionId] = it.copy(state = ChildRunState.ABORTED) }
        abandoned
    }
}

private class BackgroundHarness(
    val limits: BackgroundLimits = BackgroundLimits(
        maxRunningChildren = 4,
        minWaitMillis = 50,
        maxWaitMillis = 5_000,
        defaultWaitMillis = 200,
        runTimeoutMillis = 10_000,
    ),
) {
    val store = BackgroundStore()
    val runStore = MemoryRunStore()
    val sessions = SessionManagerPlugin(
        store,
        WorkspaceSessionResolver { id -> if (id == WS) Workspace(id, id, 1L, 1L) else null },
    ).apply { onEnable() }

    val prompts: MutableList<String> = Collections.synchronizedList(mutableListOf())
    /** Released by the test to let a child finish. */
    val gate = CountDownLatch(1)
    @Volatile var gateEnabled = false
    @Volatile var outcome: (String) -> Pair<String, ToolLoopStop> = { p -> "answer $p" to ToolLoopStop.COMPLETED }

    val runtime = SubagentRuntime(
        sessions = sessions,
        runner = ChildTurnRunner { request ->
            prompts += request.prompt
            if (gateEnabled) gate.await(10, TimeUnit.SECONDS)
            val (text, stop) = outcome(request.prompt)
            ChildTurnOutcome(text, stop, 1, 2)
        },
        toolPlanner = ChildToolPlanner { _, _ -> emptyList() },
    ).apply { onEnable() }

    val background = BackgroundSubagentRuntime(
        runtime = runtime,
        runs = runStore,
        limits = limits,
    ).apply { onEnable() }

    val root = sessions.createSession(WS, "root")

    fun context() = ToolInvocationContext(
        workspaceId = WS,
        sessionId = root.id,
        modelProviderId = "deepseek",
        modelId = "deepseek-flash",
    )

    fun spawn(label: String = "task", prompt: String = "do it") =
        background.spawn(ChildSpec(WS, root.id, label, prompt), context())

    @AfterEach
    fun tearDown() {
        gate.countDown()
    }
}

class BackgroundSubagentTest {

    @Test
    fun `spawn returns immediately while the child keeps running`() {
        val h = BackgroundHarness()
        h.gateEnabled = true

        val run = h.spawn()

        // The whole point: the parent has an id before the child has an answer.
        assertTrue(run.sessionId.isNotBlank())
        assertEquals(ChildRunState.RUNNING, run.state)
        assertNotNull(h.sessions.getSession(run.sessionId))
    }

    @Test
    fun `a settled child queues exactly one notice for the parent`() {
        val h = BackgroundHarness()
        val run = h.spawn("pricing", "find prices")

        h.background.waitFor(h.context(), listOf(run.sessionId), 5_000)
        val notices = h.background.pendingNotices(h.root.id)

        assertEquals(1, notices.size)
        assertEquals("pricing", notices.single().label)
        assertEquals(ChildRunState.COMPLETED, notices.single().state)
        assertTrue(notices.single().body!!.contains("answer find prices"))
    }

    @Test
    fun `acknowledging a notice removes it`() {
        val h = BackgroundHarness()
        val run = h.spawn()
        h.background.waitFor(h.context(), listOf(run.sessionId), 5_000)

        val notices = h.background.pendingNotices(h.root.id)
        h.background.acknowledgeNotices(h.root.id, notices.map { it.id })

        assertTrue(h.background.pendingNotices(h.root.id).isEmpty())
    }

    @Test
    fun `an unacknowledged notice survives until a turn reads it`() {
        val h = BackgroundHarness()
        h.spawn()
        h.background.waitFor(h.context(), emptyList(), 5_000)
        // Read twice: a turn that failed to start must not consume it, so pending is not a drain.
        assertEquals(1, h.background.pendingNotices(h.root.id).size)
        assertEquals(1, h.background.pendingNotices(h.root.id).size)
    }

    @Test
    fun `waitFor returns after a child settles rather than burning the full timeout`() {
        val h = BackgroundHarness()
        val run = h.spawn()
        val started = System.currentTimeMillis()

        h.background.waitFor(h.context(), listOf(run.sessionId), 5_000)

        val elapsed = System.currentTimeMillis() - started
        assertTrue(elapsed < 4_000, "wait took ${elapsed}ms; it should have returned when the child settled")
    }

    @Test
    fun `waitFor honours the minimum wait so a tight poll loop cannot spin`() {
        val h = BackgroundHarness(limits = BackgroundLimits(minWaitMillis = 300, maxWaitMillis = 5_000, defaultWaitMillis = 300))
        h.gateEnabled = true
        val run = h.spawn()
        val started = System.currentTimeMillis()

        h.background.waitFor(h.context(), listOf(run.sessionId), 0)

        val elapsed = System.currentTimeMillis() - started
        assertTrue(elapsed >= 250, "wait returned after ${elapsed}ms, below the 300ms floor")
        h.gate.countDown()
    }

    @Test
    fun `the concurrency limit fails a spawn instead of queueing it`() {
        val h = BackgroundHarness(limits = BackgroundLimits(maxRunningChildren = 1, minWaitMillis = 50, maxWaitMillis = 2_000, defaultWaitMillis = 100))
        h.gateEnabled = true
        val first = h.spawn("first", "one")
        assertEquals(ChildRunState.RUNNING, first.state)

        // Give the first task time to take the only slot.
        Thread.sleep(150)
        val second = h.spawn("second", "two")

        assertEquals(ChildRunState.FAILED, second.state)
        assertTrue(
            second.diagnostic!!.contains("too many"),
            "expected the slot diagnosis, got: ${second.diagnostic}",
        )
        h.gate.countDown()
    }

    @Test
    fun `a refused admission creates no Session`() {
        val h = BackgroundHarness()
        // No model route on the context: admission must refuse before a child Session exists.
        val context = ToolInvocationContext(workspaceId = WS, sessionId = h.root.id)

        val run = h.background.spawn(ChildSpec(WS, h.root.id, "task", "do it"), context)

        assertEquals(ChildRunState.FAILED, run.state)
        assertTrue(run.sessionId.isBlank())
        assertTrue(h.sessions.listChildSessions(h.root.id).isEmpty())
    }

    @Test
    fun `closing a settled child marks it closed and frees future work`() {
        val h = BackgroundHarness()
        val run = h.spawn()
        h.background.waitFor(h.context(), listOf(run.sessionId), 5_000)

        val closed = h.background.close(h.context(), run.sessionId)

        assertEquals(ChildRunState.CLOSED, closed.state)
        assertEquals(ChildRunState.CLOSED, h.runStore.runOf(run.sessionId)?.state)
    }

    @Test
    fun `a closed child refuses further messages`() {
        val h = BackgroundHarness()
        val run = h.spawn()
        h.background.waitFor(h.context(), listOf(run.sessionId), 5_000)
        h.background.close(h.context(), run.sessionId)

        val failure = assertFailsWith<IllegalArgumentException> {
            h.background.send(h.context(), run.sessionId, "one more thing")
        }
        assertTrue(failure.message!!.contains("closed"))
    }

    @Test
    fun `send continues the existing child Session instead of creating a new one`() {
        val h = BackgroundHarness()
        val run = h.spawn("pricing", "first pass")
        h.background.waitFor(h.context(), listOf(run.sessionId), 5_000)

        val resumed = h.background.send(h.context(), run.sessionId, "second pass")
        h.background.waitFor(h.context(), listOf(run.sessionId), 5_000)

        assertEquals(run.sessionId, resumed.sessionId)
        assertEquals(1, h.sessions.listChildSessions(h.root.id).size)
        assertEquals(listOf("first pass", "second pass"), h.prompts.toList())
    }

    @Test
    fun `a running child cannot be steered`() {
        val h = BackgroundHarness()
        h.gateEnabled = true
        val run = h.spawn()

        val failure = assertFailsWith<IllegalStateException> {
            h.background.send(h.context(), run.sessionId, "steer")
        }
        assertTrue(failure.message!!.contains("still running"))
        h.gate.countDown()
    }

    @Test
    fun `one conversation cannot close another's child`() {
        val h = BackgroundHarness()
        val other = h.sessions.createSession(WS, "other")
        val run = h.spawn()
        h.background.waitFor(h.context(), listOf(run.sessionId), 5_000)

        val otherContext = h.context().copy(sessionId = other.id)
        assertFailsWith<IllegalArgumentException> { h.background.close(otherContext, run.sessionId) }
    }

    @Test
    fun `states reports a child this process never started`() {
        val h = BackgroundHarness()
        // A row from a previous process: durable, with no live run.
        h.runStore.put(
            AgentRunRecord("old-child", h.root.id, "from before", ChildRunState.COMPLETED, 1L),
        )

        val states = h.background.states(h.context())

        assertEquals(ChildRunState.COMPLETED, states.single { it.sessionId == "old-child" }.state)
    }

    @Test
    fun `a run left RUNNING by a dead process is aborted and reported`() {
        val h = BackgroundHarness()
        h.runStore.put(
            AgentRunRecord("killed-child", h.root.id, "interrupted", ChildRunState.RUNNING, 1L),
        )

        val abandoned = h.background.recoverAbandonedRuns()

        assertEquals(listOf("killed-child"), abandoned.map { it.sessionId })
        assertEquals(ChildRunState.ABORTED, h.runStore.runOf("killed-child")?.state)
        // And the parent learns about it, instead of waiting forever.
        val notice = h.background.pendingNotices(h.root.id).single()
        assertEquals(ChildRunState.ABORTED, notice.state)
        assertTrue(notice.summary.contains("did not finish"))
    }

    @Test
    fun `a failed child is reported as FAILED with its diagnostic`() {
        val h = BackgroundHarness()
        h.outcome = { _ -> "half an answer" to ToolLoopStop.ROUND_LIMIT }
        val run = h.spawn()
        h.background.waitFor(h.context(), listOf(run.sessionId), 5_000)

        val state = h.background.states(h.context()).single { it.sessionId == run.sessionId }

        assertEquals(ChildRunState.FAILED, state.state)
    }

    @Test
    fun `notices are exposed per parent conversation`() {
        val h = BackgroundHarness()
        val other = h.sessions.createSession(WS, "other")
        val run = h.spawn()
        h.background.waitFor(h.context(), listOf(run.sessionId), 5_000)

        assertEquals(1, h.background.pendingNotices(h.root.id).size)
        assertTrue(h.background.pendingNotices(other.id).isEmpty())
    }
}
