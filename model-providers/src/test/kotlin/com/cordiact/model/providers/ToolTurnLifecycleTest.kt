package com.cordiact.model.providers

import com.cordiact.core.conversation.*
import com.cordiact.core.tool.ToolInvocationContext
import com.cordiact.core.toolloop.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.*

class ToolTurnLifecycleTest {
    private class Sessions : SessionManagerService {
        val stored = mutableListOf<ConversationMessage>()
        override fun createChildSession(workspaceId: String, parentSessionId: String, label: String, kind: SessionKind, depth: Int) = error("unused")
        override fun createSession(workspaceId: String, title: String) = ConversationSession("s", workspaceId, title, 0, 0)
        override fun getSession(sessionId: String) = ConversationSession(sessionId, "ws", "test", 0, 0)
        override fun listSessions(workspaceId: String) = emptyList<ConversationSession>()
        override fun listChildSessions(parentSessionId: String) = emptyList<ConversationSession>()
        override fun messages(sessionId: String) = stored.toList()
        override fun appendMessage(sessionId: String, role: MessageRole, content: String, metadataJson: String?): ConversationMessage =
            ConversationMessage("m${stored.size}", sessionId, role, content, stored.size.toLong(), metadataJson).also { stored += it }
    }
    private val spec = listOf(ModelToolSpec("read", "read", "{}"))
    private fun loop() = ToolLoopPlugin().apply { onEnable() }
    private fun toolResult(call: ModelToolCall) = ModelMessage(MessageRole.TOOL, "ok", toolCallId = call.id, toolName = call.name)

    @Test fun `completed is emitted once after tools and persistence with summed usage`() {
        val sessions = Sessions()
        val events = mutableListOf<ModelEvent>()
        var rounds = 0
        val adapter = object : ModelAdapterService {
            override fun models() = emptyList<ModelDescriptor>()
            override fun generate(request: ModelGenerationRequest, onEvent: (ModelEvent) -> Unit): ModelCall {
                rounds++
                if (rounds == 1) {
                    onEvent(ModelEvent.ToolCallsRequested(listOf(ModelToolCall("c", "read", "{}"))))
                    onEvent(ModelEvent.Completed(inputTokens = 10, outputTokens = 2))
                } else { onEvent(ModelEvent.Delta("answer")); onEvent(ModelEvent.Completed(inputTokens = 20, outputTokens = 3)) }
                return ModelCall {}
            }
        }
        val service = ConversationTurnService(sessions, adapter)
        service.submitWithTools("s", ModelId("p", "m"), "prompt", spec, loop(), ToolDispatcher { _, call -> toolResult(call) }, ToolInvocationContext("ws")) { event ->
            if (event is ModelEvent.Completed) assertEquals("answer", sessions.stored.last().content)
            events += event
        }
        assertEquals(2, rounds)
        assertEquals(1, events.filterIsInstance<ModelEvent.Completed>().size)
        assertEquals(30L, events.filterIsInstance<ModelEvent.Completed>().single().inputTokens)
        assertEquals(5L, events.filterIsInstance<ModelEvent.Completed>().single().outputTokens)
        assertTrue(events.indexOfFirst { it is ModelEvent.ToolCallCompleted } < events.indexOfFirst { it is ModelEvent.Completed })
        assertFalse(service.isTurnActive("s"))
    }

    @Test fun `failure in a provider round cannot become successful empty completion`() {
        val events = mutableListOf<ModelEvent>()
        val adapter = object : ModelAdapterService {
            override fun models() = emptyList<ModelDescriptor>()
            override fun generate(request: ModelGenerationRequest, onEvent: (ModelEvent) -> Unit): ModelCall {
                onEvent(ModelEvent.Failed("offline", "disconnected")); return ModelCall {}
            }
        }
        val service = ConversationTurnService(Sessions(), adapter)
        service.submitWithTools("s", ModelId("p", "m"), "prompt", spec, loop(), ToolDispatcher { _, call -> toolResult(call) }, ToolInvocationContext("ws"), onEvent = events::add)
        assertEquals(1, events.filterIsInstance<ModelEvent.Failed>().size)
        assertTrue(events.none { it is ModelEvent.Completed })
        assertFalse(service.isTurnActive("s"))
    }

    @Test fun `cancel during in flight tool round releases wait and rejects later events`() {
        val entered = CountDownLatch(1)
        val providerCancelled = AtomicBoolean(false)
        val events = java.util.Collections.synchronizedList(mutableListOf<ModelEvent>())
        val adapter = object : ModelAdapterService {
            override fun models() = emptyList<ModelDescriptor>()
            override fun generate(request: ModelGenerationRequest, onEvent: (ModelEvent) -> Unit): ModelCall {
                entered.countDown()
                return ModelCall { providerCancelled.set(true); onEvent(ModelEvent.Delta("late")) }
            }
        }
        val service = ConversationTurnService(Sessions(), adapter)
        val executor = Executors.newSingleThreadExecutor()
        try {
            val result = executor.submit {
                service.submitWithTools("s", ModelId("p", "m"), "prompt", spec, loop(), ToolDispatcher { _, call -> toolResult(call) }, ToolInvocationContext("ws"), onEvent = events::add)
            }
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            assertTrue(service.cancelTurn("s"))
            result.get(2, TimeUnit.SECONDS)
            assertTrue(providerCancelled.get())
            assertEquals("turn_cancelled", events.filterIsInstance<ModelEvent.Failed>().single().code)
            assertTrue(events.none { it is ModelEvent.Delta || it is ModelEvent.Completed })
            assertFalse(service.isTurnActive("s"))
        } finally { executor.shutdownNow() }
    }
}
