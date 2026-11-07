package com.cordiact.model.providers

import com.cordiact.core.context.ContextPolicy
import com.cordiact.core.context.InMemoryContextCompactionStore
import com.cordiact.core.context.WeightedTokenEstimator
import com.cordiact.core.conversation.ConversationMessage
import com.cordiact.core.conversation.ConversationSession
import com.cordiact.core.conversation.MessageRole
import com.cordiact.core.conversation.ModelAdapterService
import com.cordiact.core.conversation.ModelCall
import com.cordiact.core.conversation.ModelDescriptor
import com.cordiact.core.conversation.ModelEvent
import com.cordiact.core.conversation.ModelGenerationRequest
import com.cordiact.core.conversation.ModelId
import com.cordiact.core.conversation.ModelMessage
import com.cordiact.core.conversation.SessionKind
import com.cordiact.core.conversation.SessionManagerService
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ContextCompactionTurnTest {

    private class FakeSessionManager : SessionManagerService {
        val messagesList = mutableListOf<ConversationMessage>()

        override fun createSession(workspaceId: String, title: String): ConversationSession {
            return ConversationSession("s1", workspaceId, title, 1000L, 1000L)
        }
        override fun createChildSession(workspaceId: String, parentSessionId: String, label: String, kind: SessionKind, depth: Int): ConversationSession {
            return ConversationSession("c1", workspaceId, label, 1000L, 1000L)
        }
        override fun getSession(sessionId: String): ConversationSession? =
            ConversationSession(sessionId, "ws1", "Test Title", 1000L, 1000L)
        override fun listSessions(workspaceId: String): List<ConversationSession> = emptyList()
        override fun listChildSessions(parentSessionId: String): List<ConversationSession> = emptyList()
        override fun messages(sessionId: String): List<ConversationMessage> = messagesList.toList()
        override fun appendMessage(sessionId: String, role: MessageRole, content: String, metadataJson: String?): ConversationMessage {
            val msg = ConversationMessage("m-${messagesList.size + 1}", sessionId, role, content, 1000L + messagesList.size, metadataJson)
            messagesList.add(msg)
            return msg
        }
    }

    private class FakeModelAdapter(val onGenerate: (ModelGenerationRequest) -> Unit) : ModelAdapterService {
        override fun models(): List<ModelDescriptor> = listOf(
            ModelDescriptor(ModelId("deepseek", "deepseek-chat"), "DeepSeek Chat", contextWindowTokens = 128_000),
        )

        override fun generate(request: ModelGenerationRequest, onEvent: (ModelEvent) -> Unit): ModelCall {
            onGenerate(request)
            onEvent(ModelEvent.Delta("OK, continued."))
            onEvent(ModelEvent.Completed(inputTokens = 100L, outputTokens = 10L))
            return ModelCall {}
        }
    }

    @Test
    fun `test turn service automatically compacts history when compactAtTokens is reached`() {
        val sessions = FakeSessionManager()
        val compactionStore = InMemoryContextCompactionStore()

        // Preload history with enough turns to hit a small window threshold
        sessions.appendMessage("s1", MessageRole.SYSTEM, "You are a test assistant.")
        sessions.appendMessage("s1", MessageRole.USER, "First command: explore project.")
        for (i in 1..8) {
            sessions.appendMessage("s1", MessageRole.ASSISTANT, "Step $i completed with intermediate output details.")
            sessions.appendMessage("s1", MessageRole.USER, "Next step $i: continue.")
        }

        var dispatchedMessages: List<ModelMessage> = emptyList()
        val adapter = FakeModelAdapter { req ->
            dispatchedMessages = req.messages
        }

        val policy = ContextPolicy(
            enabled = true,
            contextWindowOverride = 100, // Small window so compactAtTokens is low (~40 tokens)
            maxOutputTokens = 20,
            thresholdRatio = 0.5,
            retainRatio = 0.2,
            minRetainTurns = 1,
        )

        val turnService = ConversationTurnService(
            sessions = sessions,
            models = adapter,
            compactionStore = compactionStore,
            contextPolicy = policy,
        )

        // Submit new turn
        turnService.submit("s1", ModelId("deepseek", "deepseek-chat"), "Final step: wrap up.") {}

        // Verify that auto-compaction ran and stored a record
        val activeRecords = compactionStore.listActive("s1")
        assertEquals(1, activeRecords.size)
        val record = activeRecords[0]
        assertNotNull(record)
        assertTrue(record.summary.contains("## 1. 任务与目标"))

        // Verify that dispatched messages contained the summary replacement node instead of full raw history
        val summaryNode = dispatchedMessages.firstOrNull { it.content.contains("[上下文压缩摘要") }
        assertNotNull(summaryNode, "Expected a summary node in projected prompt messages")
        // Initial goal should be preserved in Head
        assertEquals("First command: explore project.", dispatchedMessages.firstOrNull { it.role == MessageRole.USER }?.content)
    }
}
