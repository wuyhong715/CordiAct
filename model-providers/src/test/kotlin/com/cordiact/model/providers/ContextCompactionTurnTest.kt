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

    private class FakeModelAdapter(val reply: String = "OK, continued.", val onGenerate: (ModelGenerationRequest) -> Unit) : ModelAdapterService {
        override fun models(): List<ModelDescriptor> = listOf(
            ModelDescriptor(ModelId("deepseek", "deepseek-chat"), "DeepSeek Chat", contextWindowTokens = 128_000),
        )

        override fun generate(request: ModelGenerationRequest, onEvent: (ModelEvent) -> Unit): ModelCall {
            onGenerate(request)
            onEvent(ModelEvent.Delta(reply))
            onEvent(ModelEvent.Completed(inputTokens = 100L, outputTokens = 10L))
            return ModelCall {}
        }
    }

    @Test
    fun `overflow retries deliver the same context and acknowledge notices only on success`() {
        val model = ModelId("deepseek", "deepseek-chat")
        for (retrySucceeds in listOf(true, false)) {
            val sessions = FakeSessionManager()
            repeat(3) {
                sessions.appendMessage("s1", MessageRole.USER, "Earlier prompt $it")
                sessions.appendMessage("s1", MessageRole.ASSISTANT, "Earlier answer $it")
            }
            val requests = mutableListOf<ModelGenerationRequest>()
            val acknowledged = mutableListOf<String>()
            val notices = object : com.cordiact.core.conversation.TurnNoticeSource {
                override fun pending(sessionId: String) = listOf(
                    com.cordiact.core.conversation.TurnNotice("receipt-1", "pending receipt marker"),
                )
                override fun acknowledge(sessionId: String, ids: List<String>) { acknowledged += ids }
            }
            val failure = ModelEvent.Failed("context_length_exceeded", "maximum context length")
            val adapter = object : ModelAdapterService by FakeModelAdapter(onGenerate = {}) {
                override fun generate(request: ModelGenerationRequest, onEvent: (ModelEvent) -> Unit): ModelCall {
                    requests += request
                    assertTrue(acknowledged.isEmpty())
                    if (requests.size == 1) {
                        onEvent(failure)
                        onEvent(ModelEvent.Delta("discarded attempt"))
                    } else if (retrySucceeds) {
                        onEvent(ModelEvent.Delta("retried reply"))
                        onEvent(ModelEvent.Completed())
                    } else onEvent(failure)
                    return ModelCall {}
                }
            }
            val events = mutableListOf<ModelEvent>()
            ConversationTurnService(sessions, adapter, notices, InMemoryContextCompactionStore()).submit(
                "s1", model, "continue",
                transientContext = listOf("reference marker"), systemContext = "skill marker",
                onEvent = { events += it },
            )
            assertEquals(2, requests.size)
            for (request in requests) {
                val content = request.messages.joinToString("\n") { it.content }
                for (marker in listOf("reference marker", "skill marker", "pending receipt marker")) {
                    assertTrue(content.contains(marker), "Missing $marker")
                }
            }
            assertTrue(events.none { it is ModelEvent.Delta && it.text == "discarded attempt" })
            assertEquals(if (retrySucceeds) listOf("receipt-1") else emptyList<String>(), acknowledged)
            assertTrue(if (retrySucceeds) events.last() is ModelEvent.Completed else events.last() is ModelEvent.Failed)
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
    @Test
    fun `manual compression preserves transcript and uses configured summary model`() {
        val sessions = FakeSessionManager()
        sessions.appendMessage("s1", MessageRole.SYSTEM, "stable system prefix")
        sessions.appendMessage("s1", MessageRole.USER, "initial goal")
        repeat(12) {
            sessions.appendMessage("s1", MessageRole.ASSISTANT, "result $it " + "details ".repeat(60))
            sessions.appendMessage("s1", MessageRole.USER, "continue $it")
        }
        val before = sessions.messages("s1")
        val store = InMemoryContextCompactionStore()
        var prompt = ""
        val adapter = FakeModelAdapter(reply = """
            ## 1. 任务与目标
            保留接口约束
            ## 2. 已完成与关键决策
            原记录保留
            ## 3. 当前状态
            已定位接口
            ## 4. 重要事实与约束
            继续实现
            ## 5. 工具产出索引
            /workspace
            ## 6. 下一步
            从当前任务继续
            ## 7. 必须保留的原文
            接口约束
        """.trimIndent()) { prompt = it.messages.first().content }
        val service = ConversationTurnService(sessions, adapter,
            contextPolicy = ContextPolicy(enabled = true, contextWindowOverride = 400,
                maxOutputTokens = 40, thresholdRatio = 0.5, retainRatio = 0.2, minRetainTurns = 1),
            compactionStore = store,
            summaryModelProvider = { ModelId("deepseek", "deepseek-chat") },
        )
        val result = service.compactManually("s1", ModelId("deepseek", "deepseek-chat"), "保留接口约束")
        assertNotNull(result)
        assertEquals(before, sessions.messages("s1"))
        assertTrue(prompt.contains("保留接口约束"))
        assertEquals(1, store.listActive("s1").size)
    }

    @Test
    fun `manual compression requires configuration and releases lease on failure`() {
        val service = ConversationTurnService(FakeSessionManager(), FakeModelAdapter {},
            contextPolicy = ContextPolicy(enabled = true),
            compactionStore = InMemoryContextCompactionStore(),
        )
        repeat(2) {
            val failure = org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException::class.java) {
                service.compactManually("s1", ModelId("deepseek", "deepseek-chat"))
            }
            assertTrue(failure.message!!.contains("配置摘要模型"))
        }
    }

    @Test
    fun `failed manual summary does not install rules fallback`() {
        val sessions = FakeSessionManager()
        sessions.appendMessage("s1", MessageRole.SYSTEM, "stable prefix")
        repeat(8) {
            sessions.appendMessage("s1", MessageRole.USER, "goal $it")
            sessions.appendMessage("s1", MessageRole.ASSISTANT, "details ".repeat(150))
        }
        val before = sessions.messages("s1")
        val store = InMemoryContextCompactionStore()
        val service = ConversationTurnService(sessions, FakeModelAdapter {},
            contextPolicy = ContextPolicy(enabled = true, contextWindowOverride = 400,
                maxOutputTokens = 40, minRetainTurns = 1), compactionStore = store,
            summaryModelProvider = { ModelId("deepseek", "deepseek-chat") })
        repeat(2) {
            org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException::class.java) {
                service.compactManually("s1", ModelId("deepseek", "deepseek-chat"))
            }
            assertTrue(store.listActive("s1").isEmpty())
            assertEquals(before, sessions.messages("s1"))
        }
    }

}
