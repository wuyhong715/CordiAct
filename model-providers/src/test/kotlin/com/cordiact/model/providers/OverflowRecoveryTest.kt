package com.cordiact.model.providers

import com.cordiact.core.context.InMemoryContextCompactionStore
import com.cordiact.core.conversation.ConversationMessage
import com.cordiact.core.conversation.ConversationSession
import com.cordiact.core.conversation.MessageRole
import com.cordiact.core.conversation.ModelAdapterService
import com.cordiact.core.conversation.ModelCall
import com.cordiact.core.conversation.ModelDescriptor
import com.cordiact.core.conversation.ModelEvent
import com.cordiact.core.conversation.ModelGenerationRequest
import com.cordiact.core.conversation.ModelId
import com.cordiact.core.conversation.SessionKind
import com.cordiact.core.conversation.SessionManagerService
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class OverflowRecoveryTest {

    private class Sessions : SessionManagerService {
        val stored = mutableListOf<ConversationMessage>()
        override fun createSession(workspaceId: String, title: String) =
            ConversationSession("s1", workspaceId, title, 1L, 1L)
        override fun createChildSession(workspaceId: String, parentSessionId: String, label: String, kind: SessionKind, depth: Int) =
            ConversationSession("c1", workspaceId, label, 1L, 1L)
        override fun getSession(sessionId: String) = ConversationSession(sessionId, "ws", "t", 1L, 1L)
        override fun listSessions(workspaceId: String) = emptyList<ConversationSession>()
        override fun listChildSessions(parentSessionId: String) = emptyList<ConversationSession>()
        override fun messages(sessionId: String) = stored.toList()
        override fun appendMessage(sessionId: String, role: MessageRole, content: String, metadataJson: String?): ConversationMessage {
            val message = ConversationMessage("m${stored.size}", sessionId, role, content, stored.size.toLong(), metadataJson)
            stored += message
            return message
        }
    }

    /** Fails the first request as "prompt too long", then behaves. */
    private class OverflowOnceAdapter(private val onRequest: (Int, ModelGenerationRequest) -> Unit) : ModelAdapterService {
        var attempts = 0
        override fun models() = listOf(ModelDescriptor(ModelId("openai", "gpt-4o"), "GPT-4o", contextWindowTokens = 128_000))
        override fun generate(request: ModelGenerationRequest, onEvent: (ModelEvent) -> Unit): ModelCall {
            attempts++
            onRequest(attempts, request)
            if (attempts == 1) {
                onEvent(ModelEvent.Failed("context_length_exceeded", "This model's maximum context length is 128000 tokens."))
            } else {
                onEvent(ModelEvent.Delta("recovered answer"))
                onEvent(ModelEvent.Completed(inputTokens = 100L, outputTokens = 5L))
            }
            return ModelCall {}
        }
    }

    @Test
    fun `an overflow failure compacts and retries once instead of failing the turn`() {
        val sessions = Sessions()
        sessions.appendMessage("s1", MessageRole.SYSTEM, "You are CordiAct.")
        sessions.appendMessage("s1", MessageRole.USER, "Original goal: refactor the module.")
        repeat(10) { index ->
            sessions.appendMessage("s1", MessageRole.ASSISTANT, "Step $index explained at some length.")
            sessions.appendMessage("s1", MessageRole.USER, "Keep going with step ${index + 1}.")
        }

        val store = InMemoryContextCompactionStore()
        val sizes = mutableListOf<Int>()
        val adapter = OverflowOnceAdapter { attempt, request ->
            sizes += request.messages.sumOf { it.content.length }
            if (attempt == 1) {
                // The provider refuses even though our own estimate says it fits; the emergency
                // path must still make progress rather than repeat the same request.
                assertTrue(request.messages.isNotEmpty())
            }
        }

        val service = ConversationTurnService(
            sessions = sessions,
            models = adapter,
            compactionStore = store,
        )

        val events = mutableListOf<ModelEvent>()
        service.submit("s1", ModelId("openai", "gpt-4o"), "One more question.") { events += it }

        assertEquals(2, adapter.attempts, "exactly one retry")
        assertEquals(1, store.listActive("s1").size, "the retry ran against a compacted prompt")
        assertTrue(sizes.size == 2 && sizes[1] < sizes[0], "the second request must be smaller")
        assertTrue(
            sessions.stored.any { it.role == MessageRole.ASSISTANT && it.content == "recovered answer" },
            "the recovered answer is persisted like any other reply",
        )
        // The user is not told the turn failed; they got an answer.
        assertTrue(events.none { it is ModelEvent.Failed })
    }

    @Test
    fun `a non-overflow failure is reported and not retried`() {
        val sessions = Sessions()
        sessions.appendMessage("s1", MessageRole.USER, "hi")

        val adapter = object : ModelAdapterService {
            var attempts = 0
            override fun models() = listOf(ModelDescriptor(ModelId("openai", "gpt-4o"), "GPT-4o"))
            override fun generate(request: ModelGenerationRequest, onEvent: (ModelEvent) -> Unit): ModelCall {
                attempts++
                onEvent(ModelEvent.Failed("400", "Invalid value for 'temperature': must be <= 2"))
                return ModelCall {}
            }
        }

        val events = mutableListOf<ModelEvent>()
        ConversationTurnService(sessions, adapter).submit("s1", ModelId("openai", "gpt-4o"), "hello") { events += it }

        assertEquals(1, adapter.attempts, "a bad request is not a context problem")
        assertTrue(events.any { it is ModelEvent.Failed })
    }
}
