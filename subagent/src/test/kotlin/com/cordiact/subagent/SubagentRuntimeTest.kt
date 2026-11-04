package com.cordiact.subagent

import com.cordiact.core.conversation.ConversationMessage
import com.cordiact.core.conversation.ConversationSession
import com.cordiact.core.conversation.ConversationSessionStore
import com.cordiact.core.conversation.MessageRole
import com.cordiact.core.conversation.ModelId
import com.cordiact.core.conversation.ModelToolSpec
import com.cordiact.core.conversation.SessionKind
import com.cordiact.core.conversation.SessionManagerPlugin
import com.cordiact.core.subagent.ChildSpec
import com.cordiact.core.subagent.ChildToolPlanner
import com.cordiact.core.subagent.ChildTurnRequest
import com.cordiact.core.subagent.ChildTurnRunner
import com.cordiact.core.subagent.SUBAGENT_TOOL_ID
import com.cordiact.core.subagent.SubagentLimits
import com.cordiact.core.tool.GrantedScope
import com.cordiact.core.tool.ToolInvocationContext
import com.cordiact.core.toolloop.ToolLoopStop
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

private const val WS = "ws-1"
private const val OTHER_WS = "ws-2"

/** In-memory Session store: the delegation rules are what is under test, not SQLite. */
private class FakeStore : ConversationSessionStore {
    val sessions = linkedMapOf<String, ConversationSession>()
    private val messages = mutableMapOf<String, MutableList<ConversationMessage>>()
    private var next = 0

    override fun create(
        id: String,
        workspaceId: String,
        title: String,
        nowMillis: Long,
        parentSessionId: String?,
        kind: SessionKind,
        agentLabel: String?,
        depth: Int,
    ): ConversationSession {
        val value = ConversationSession(
            id, workspaceId, title, nowMillis, nowMillis, parentSessionId, kind, agentLabel, depth,
        )
        sessions[id] = value
        return value
    }

    override fun get(sessionId: String) = sessions[sessionId]
    override fun list(workspaceId: String) =
        sessions.values.filter { it.workspaceId == workspaceId && it.kind == SessionKind.USER }
    override fun listChildren(parentSessionId: String) =
        sessions.values.filter { it.parentSessionId == parentSessionId }

    override fun append(
        sessionId: String,
        role: MessageRole,
        content: String,
        nowMillis: Long,
        metadataJson: String?,
    ): ConversationMessage {
        val message = ConversationMessage("m-${++next}", sessionId, role, content, nowMillis, metadataJson)
        messages.getOrPut(sessionId) { mutableListOf() }.add(message)
        return message
    }

    override fun messages(sessionId: String) = messages[sessionId].orEmpty().toList()
}

private class Harness(
    val limits: SubagentLimits = SubagentLimits(),
    val tools: List<ModelToolSpec> = emptyList(),
    val outcomeText: String = "done",
    val outcomeStop: ToolLoopStop = ToolLoopStop.COMPLETED,
) {
    val store = FakeStore()
    val sessions = SessionManagerPlugin(
        store,
        com.cordiact.core.workspace.WorkspaceSessionResolver { id ->
            if (id == WS || id == OTHER_WS) com.cordiact.core.workspace.Workspace(id, id, 1L, 1L) else null
        },
    ).apply { onEnable() }
    val requests = mutableListOf<ChildTurnRequest>()
    var throwOnRun = false

    val runtime = SubagentRuntime(
        sessions = sessions,
        runner = ChildTurnRunner { request ->
            requests += request
            if (throwOnRun) error("transport exploded")
            com.cordiact.core.subagent.ChildTurnOutcome(outcomeText, outcomeStop, 11, 22)
        },
        toolPlanner = ChildToolPlanner { _, _ -> tools },
        limits = limits,
    ).apply { onEnable() }

    fun rootSession(): ConversationSession = sessions.createSession(WS, "root")

    fun context(sessionId: String?, workspaceId: String = WS, scopes: List<GrantedScope> = emptyList()) =
        ToolInvocationContext(
            workspaceId = workspaceId,
            sessionId = sessionId,
            modelProviderId = "deepseek",
            modelId = "deepseek-flash",
            grantedScopes = scopes,
        )
}

private fun tool(name: String) = ModelToolSpec(name = name, description = "", parametersJson = "{}")

class SubagentRuntimeTest {

    @Test
    fun `delegation creates a child Session linked to its parent`() {
        val h = Harness()
        val root = h.rootSession()

        val result = h.runtime.runChild(
            ChildSpec(WS, root.id, "competitor pricing", "find three prices"),
            h.context(root.id),
            deadlineAtMillis = System.currentTimeMillis() + 60_000,
        )

        assertEquals(ToolLoopStop.COMPLETED, result.stop)
        assertEquals("done", result.text)

        val child = assertNotNull(h.store.sessions[result.sessionId])
        assertEquals(root.id, child.parentSessionId)
        assertEquals(SessionKind.SUBAGENT, child.kind)
        assertEquals(1, child.depth)
        assertEquals("competitor pricing", child.agentLabel)
    }

    @Test
    fun `a delegated Session is not listed as a workspace conversation`() {
        val h = Harness()
        val root = h.rootSession()
        h.runtime.runChild(ChildSpec(WS, root.id, "task", "do it"), h.context(root.id), Long.MAX_VALUE)

        assertEquals(listOf(root.id), h.sessions.listSessions(WS).map { it.id })
        assertEquals(1, h.sessions.listChildSessions(root.id).size)
    }

    @Test
    fun `the child cannot delegate once it is at the depth ceiling`() {
        val h = Harness(limits = SubagentLimits(maxDepth = 1), tools = listOf(tool(SUBAGENT_TOOL_ID)))
        val root = h.rootSession()

        h.runtime.runChild(ChildSpec(WS, root.id, "task", "do it"), h.context(root.id), Long.MAX_VALUE)

        val childTools = h.requests.single().tools
        assertTrue(childTools.none { it.name == SUBAGENT_TOOL_ID }, "child must not see the delegation tool")
    }

    @Test
    fun `a nested delegation is refused at the depth ceiling`() {
        val h = Harness(limits = SubagentLimits(maxDepth = 1))
        val root = h.rootSession()
        h.runtime.runChild(ChildSpec(WS, root.id, "task", "do it"), h.context(root.id), Long.MAX_VALUE)
        val child = h.sessions.listChildSessions(root.id).single()

        val nested = h.runtime.runChild(
            ChildSpec(WS, child.id, "nested", "go deeper"),
            h.context(child.id),
            Long.MAX_VALUE,
        )

        assertEquals(ToolLoopStop.FAILED, nested.stop)
        assertTrue(nested.text.contains("depth"), "expected a depth diagnosis, got: ${nested.text}")
        assertFalse(h.runtime.canDelegate(h.context(child.id)))
    }

    @Test
    fun `a child gets the parent's grants verbatim, never a superset`() {
        val h = Harness()
        val root = h.rootSession()
        val scopes = listOf(GrantedScope(OTHER_WS, prefix = "memory/"))
        val parentContext = h.context(root.id, scopes = scopes)

        h.runtime.runChild(ChildSpec(WS, root.id, "task", "read the memory"), parentContext, Long.MAX_VALUE)

        val childContext = h.requests.single().context
        assertEquals(scopes, childContext.grantedScopes)
        assertEquals(root.workspaceId, childContext.workspaceId)
        assertEquals(h.requests.single().sessionId, childContext.sessionId)
    }

    @Test
    fun `the child's requestText is its own prompt, not the user's words`() {
        val h = Harness()
        val root = h.rootSession()
        val parentContext = h.context(root.id).copy(requestText = "the user's private instruction")

        h.runtime.runChild(ChildSpec(WS, root.id, "task", "summarise the doc"), parentContext, Long.MAX_VALUE)

        assertEquals("summarise the doc", h.requests.single().context.requestText)
    }

    @Test
    fun `cross-workspace delegation is refused without creating a Session`() {
        val h = Harness()
        val root = h.rootSession()

        val result = h.runtime.runChild(
            ChildSpec(OTHER_WS, root.id, "task", "read elsewhere"),
            h.context(root.id),
            Long.MAX_VALUE,
        )

        assertEquals(ToolLoopStop.FAILED, result.stop)
        assertTrue(result.text.contains("Workspace"), "expected a Workspace diagnosis, got: ${result.text}")
        assertTrue(h.sessions.listChildSessions(root.id).isEmpty())
    }

    @Test
    fun `a failing runner yields a FAILED result instead of throwing`() {
        val h = Harness()
        val root = h.rootSession()
        h.throwOnRun = true

        val result = h.runtime.runChild(ChildSpec(WS, root.id, "task", "do it"), h.context(root.id), Long.MAX_VALUE)

        assertEquals(ToolLoopStop.FAILED, result.stop)
        assertTrue(result.text.isNotBlank())
    }

    @Test
    fun `delegation without a calling Session is refused as a result`() {
        val h = Harness()
        h.rootSession()

        // Refusal is data, not an exception: the tool layer must always be able to hand the model
        // a structured answer, and a delegating turn that blew up would leave the parent with a
        // stack trace instead of a reason.
        val result = h.runtime.runChild(
            ChildSpec(WS, "root", "task", "do it"),
            h.context(null),
            Long.MAX_VALUE,
        )

        assertEquals(ToolLoopStop.FAILED, result.stop)
        assertTrue(
            result.text.contains("calling Session"),
            "expected the invariant named, got: ${result.text}",
        )
        assertTrue(result.sessionId.isBlank(), "a refusal must not have created a Session")
    }

    @Test
    fun `the per-session child budget is enforced`() {
        val h = Harness(limits = SubagentLimits(maxChildrenPerSession = 2))
        val root = h.rootSession()
        repeat(2) { h.runtime.runChild(ChildSpec(WS, root.id, "task$it", "do it"), h.context(root.id), Long.MAX_VALUE) }

        val third = h.runtime.runChild(ChildSpec(WS, root.id, "task-over", "one too many"), h.context(root.id), Long.MAX_VALUE)

        assertEquals(ToolLoopStop.FAILED, third.stop)
        assertTrue(third.text.contains("max 2"), "expected the budget in the message, got: ${third.text}")
        assertEquals(2, h.sessions.listChildSessions(root.id).size)
    }

    @Test
    fun `an oversized child result is marked truncated`() {
        val h = Harness(limits = SubagentLimits(maxResultChars = 10), outcomeText = "x".repeat(50))
        val root = h.rootSession()

        val result = h.runtime.runChild(ChildSpec(WS, root.id, "task", "do it"), h.context(root.id), Long.MAX_VALUE)

        assertTrue(result.truncated)
        assertEquals(10, result.text.length)
    }

    @Test
    fun `usage totals are carried back so a caller can show the cost`() {
        val h = Harness()
        val root = h.rootSession()
        val result = h.runtime.runChild(ChildSpec(WS, root.id, "task", "do it"), h.context(root.id), Long.MAX_VALUE)
        assertEquals(11, result.inputTokens)
        assertEquals(22, result.outputTokens)
    }

    @Test
    fun `depthOf reports zero for a root and minus one for an unknown Session`() {
        val h = Harness()
        val root = h.rootSession()
        assertEquals(0, h.runtime.depthOf(root.id))
        assertEquals(-1, h.runtime.depthOf("no-such-session"))
    }

    @Test
    fun `listChildren exposes only direct children with their depth`() {
        val h = Harness()
        val root = h.rootSession()
        h.runtime.runChild(ChildSpec(WS, root.id, "a", "do a"), h.context(root.id), Long.MAX_VALUE)
        h.runtime.runChild(ChildSpec(WS, root.id, "b", "do b"), h.context(root.id), Long.MAX_VALUE)

        val children = h.runtime.listChildren(root.id)

        assertEquals(listOf("a", "b"), children.map { it.label })
        assertTrue(children.all { it.depth == 1 })
        assertTrue(h.runtime.listChildren("no-such-session").isEmpty())
    }
}
