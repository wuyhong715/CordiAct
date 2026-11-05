package com.cordiact.model.providers

import com.cordiact.core.conversation.ConversationMessage
import com.cordiact.core.conversation.ConversationSession
import com.cordiact.core.conversation.ConversationSessionStore
import com.cordiact.core.conversation.MessageRole
import com.cordiact.core.conversation.ModelAdapterService
import com.cordiact.core.conversation.ModelCall
import com.cordiact.core.conversation.ModelDescriptor
import com.cordiact.core.conversation.ModelEvent
import com.cordiact.core.conversation.ModelGenerationRequest
import com.cordiact.core.conversation.ModelId
import com.cordiact.core.conversation.ModelToolSpec
import com.cordiact.core.conversation.SessionKind
import com.cordiact.core.conversation.SessionManagerPlugin
import com.cordiact.core.conversation.TurnNotice
import com.cordiact.core.conversation.TurnNoticeSource
import com.cordiact.core.tool.ToolInvocationContext
import com.cordiact.core.toolloop.ToolDispatcher
import com.cordiact.core.toolloop.ToolLoopPlugin
import com.cordiact.core.workspace.Workspace
import com.cordiact.core.workspace.WorkspaceSessionResolver
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

private const val WS = "ws-1"

/** A store that keeps messages, so a second turn can see the first one. */
private class RecordingStore : ConversationSessionStore {
    private val sessions = linkedMapOf<String, ConversationSession>()
    private val messages = mutableMapOf<String, MutableList<ConversationMessage>>()
    private var next = 0

    override fun create(
        id: String, workspaceId: String, title: String, nowMillis: Long,
        parentSessionId: String?, kind: SessionKind, agentLabel: String?, depth: Int,
    ): ConversationSession = ConversationSession(
        id, workspaceId, title, nowMillis, nowMillis, parentSessionId, kind, agentLabel, depth,
    ).also {
        sessions[id] = it
        messages[id] = mutableListOf()
    }

    override fun get(sessionId: String) = sessions[sessionId]
    override fun list(workspaceId: String) =
        sessions.values.filter { it.workspaceId == workspaceId && it.kind == SessionKind.USER }
    override fun listChildren(parentSessionId: String) =
        sessions.values.filter { it.parentSessionId == parentSessionId }

    override fun append(sessionId: String, role: MessageRole, content: String, nowMillis: Long, metadataJson: String?) =
        ConversationMessage("m-${++next}", sessionId, role, content, nowMillis, metadataJson)
            .also { messages.getOrPut(sessionId) { mutableListOf() }.add(it) }

    override fun messages(sessionId: String) = messages[sessionId].orEmpty().toList()
}

private class FakeAdapter : ModelAdapterService {
    /** Every request the service asked for, so the test can inspect what the model saw. */
    val requests = mutableListOf<ModelGenerationRequest>()

    override fun models() = listOf(ModelDescriptor(ModelId("p", "m"), "m"))

    override fun generate(request: ModelGenerationRequest, onEvent: (ModelEvent) -> Unit): ModelCall {
        requests += request
        onEvent(ModelEvent.Delta("ok"))
        onEvent(ModelEvent.Completed())
        return ModelCall {}
    }
}

private class FakeNotices(
    private val queued: MutableList<TurnNotice> = mutableListOf(),
) : TurnNoticeSource {
    val acknowledged = mutableListOf<String>()

    override fun pending(sessionId: String): List<TurnNotice> = queued.toList()

    override fun acknowledge(sessionId: String, ids: List<String>) {
        acknowledged += ids
        queued.removeAll { it.id in ids }
    }
}

class TurnNoticeTest {

    private fun service(
        store: RecordingStore,
        adapter: FakeAdapter,
        notices: TurnNoticeSource?,
    ): ConversationTurnService {
        val sessions = SessionManagerPlugin(
            store,
            WorkspaceSessionResolver { id -> if (id == WS) Workspace(id, id, 1L, 1L) else null },
        ).apply { onEnable() }
        return ConversationTurnService(sessions, adapter, notices)
    }

    @Test
    fun `a pending notice is put in front of the model as a system block`() {
        val store = RecordingStore()
        val adapter = FakeAdapter()
        val session = store.create("s1", WS, "chat", 1L, null, SessionKind.USER, null, 0)
        val notices = FakeNotices(
            mutableListOf(TurnNotice("child-1", "Delegated task \"pricing\" finished.", "three prices")),
        )
        val service = service(store, adapter, notices)

        service.submit(session.id, ModelId("p", "m"), "what did they find?", onEvent = {})

        val sent = adapter.requests.single().messages
        val system = sent.first()
        assertEquals(MessageRole.SYSTEM, system.role)
        assertTrue(system.content.contains("pricing"), "heading missing: ${system.content}")
        assertTrue(system.content.contains("three prices"), "body missing: ${system.content}")
        // And the notice is consumed exactly once.
        assertEquals(listOf("child-1"), notices.acknowledged)
    }

    @Test
    fun `a notice is a system message, never attributed to the user`() {
        val store = RecordingStore()
        val adapter = FakeAdapter()
        val session = store.create("s1", WS, "chat", 1L, null, SessionKind.USER, null, 0)
        val notices = FakeNotices(mutableListOf(TurnNotice("c1", "child finished", "body")))
        val service = service(store, adapter, notices)

        service.submit(session.id, ModelId("p", "m"), "hello", onEvent = {})

        val sent = adapter.requests.single().messages
        assertTrue(
            sent.none { it.role == MessageRole.USER && it.content.contains("child finished") },
            "a runtime report must not be attributed to the user",
        )
    }

    @Test
    fun `the user message appears once in a tool-using turn`() {
        val store = RecordingStore()
        val adapter = FakeAdapter()
        val session = store.create("s1", WS, "chat", 1L, null, SessionKind.USER, null, 0)
        val service = service(store, adapter, null)
        val sessions = SessionManagerPlugin(
            store,
            WorkspaceSessionResolver { id -> if (id == WS) Workspace(id, id, 1L, 1L) else null },
        ).apply { onEnable() }

        service.submitWithTools(
            sessionId = session.id,
            model = ModelId("p", "m"),
            text = "count the prices",
            tools = listOf(ModelToolSpec("t", "d", "{}")),
            toolLoop = ToolLoopPlugin().apply { onEnable() },
            dispatcher = ToolDispatcher { _, _ -> error("no tool should be called") },
            toolContext = ToolInvocationContext(workspaceId = WS, sessionId = session.id),
            onEvent = {},
        )

        val userMessages = adapter.requests.first().messages.filter { it.role == MessageRole.USER }
        assertEquals(1, userMessages.size, "the prompt must not be duplicated: $userMessages")
        assertEquals("count the prices", userMessages.single().content)
        assertEquals(1, sessions.messages(session.id).count { it.role == MessageRole.USER })
    }

    @Test
    fun `no notice means no injected block`() {
        val store = RecordingStore()
        val adapter = FakeAdapter()
        val session = store.create("s1", WS, "chat", 1L, null, SessionKind.USER, null, 0)
        val service = service(store, adapter, FakeNotices())

        service.submit(session.id, ModelId("p", "m"), "hello", onEvent = {})

        val sent = adapter.requests.single().messages
        assertTrue(sent.none { it.role == MessageRole.SYSTEM }, "got: $sent")
    }

    @Test
    fun `a turn with no notice source still works`() {
        val store = RecordingStore()
        val adapter = FakeAdapter()
        val session = store.create("s1", WS, "chat", 1L, null, SessionKind.USER, null, 0)
        val service = service(store, adapter, null)

        service.submit(session.id, ModelId("p", "m"), "hello", onEvent = {})

        assertEquals(1, adapter.requests.size)
    }

    @Test
    fun `an unacknowledged notice is re-offered on the next turn`() {
        val store = RecordingStore()
        val adapter = FakeAdapter()
        val session = store.create("s1", WS, "chat", 1L, null, SessionKind.USER, null, 0)
        // A source that never acknowledges: the notice must keep being offered rather than
        // silently disappearing after one turn.
        val notices = object : TurnNoticeSource {
            override fun pending(sessionId: String) = listOf(TurnNotice("c1", "child finished", "body"))
            override fun acknowledge(sessionId: String, ids: List<String>) = Unit
        }
        val service = service(store, adapter, notices)

        service.submit(session.id, ModelId("p", "m"), "first", onEvent = {})
        service.submit(session.id, ModelId("p", "m"), "second", onEvent = {})

        assertEquals(2, adapter.requests.size)
        assertTrue(adapter.requests.all { it.messages.first().content.contains("child finished") })
    }

    @Test
    fun `the user message appears once in runTurnToCompletion`() {
        val store = RecordingStore()
        val adapter = FakeAdapter()
        val session = store.create("s1", WS, "chat", 1L, null, SessionKind.USER, null, 0)
        val service = service(store, adapter, null)
        val sessions = SessionManagerPlugin(
            store,
            WorkspaceSessionResolver { id -> if (id == WS) Workspace(id, id, 1L, 1L) else null },
        ).apply { onEnable() }

        service.runTurnToCompletion(
            sessionId = session.id,
            model = ModelId("p", "m"),
            text = "analyze the subtask",
            tools = listOf(ModelToolSpec("t", "d", "{}")),
            toolLoop = ToolLoopPlugin().apply { onEnable() },
            dispatcher = ToolDispatcher { _, _ -> error("no tool should be called") },
            toolContext = ToolInvocationContext(workspaceId = WS, sessionId = session.id),
            onEvent = {},
        )

        val userMessages = adapter.requests.first().messages.filter { it.role == MessageRole.USER }
        assertEquals(1, userMessages.size, "prompt must not be duplicated in runTurnToCompletion: $userMessages")
        assertEquals("analyze the subtask", userMessages.single().content)
        assertEquals(1, sessions.messages(session.id).count { it.role == MessageRole.USER })
    }
}
