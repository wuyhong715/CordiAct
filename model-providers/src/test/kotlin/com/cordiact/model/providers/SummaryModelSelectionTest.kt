package com.cordiact.model.providers

import com.cordiact.core.context.ContextPolicy
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

class SummaryModelSelectionTest {

    private val turnModel = ModelId("p", "chat")
    private val summaryModel = ModelId("p", "summary")

    private val sevenSections = """
## 1. 任务与目标
重构模块并保持测试通过

## 2. 已完成与关键决策
已改动调用点，选择保持公开 API 稳定

## 3. 当前状态
工作区干净，测试全绿

## 4. 重要事实与约束
用户要求不破坏兼容性

## 5. 工具产出索引
无

## 6. 下一步
补文档并回归

## 7. 必须保留的原文
"keep the public API stable"
""".trimIndent()

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

    /** Answers turn requests with a short reply and summary requests with the seven sections. */
    private class RoutingAdapter(private val summary: String) : ModelAdapterService {
        val summaryRequests = mutableListOf<ModelGenerationRequest>()
        override fun models() = listOf(ModelDescriptor(turnModel0, "Chat", contextWindowTokens = 400))
        override fun generate(request: ModelGenerationRequest, onEvent: (ModelEvent) -> Unit): ModelCall {
            val isSummary = request.messages.firstOrNull()?.content?.contains("## 1. 任务与目标") == true
            if (isSummary) {
                summaryRequests += request
                onEvent(ModelEvent.Delta(summary))
            } else {
                onEvent(ModelEvent.Delta("ok"))
            }
            onEvent(ModelEvent.Completed(inputTokens = 10L, outputTokens = 2L))
            return ModelCall {}
        }
        companion object {
            val turnModel0 = ModelId("p", "chat")
        }
    }

    private fun longHistory(sessions: Sessions) {
        sessions.appendMessage("s1", MessageRole.SYSTEM, "You are CordiAct.")
        sessions.appendMessage("s1", MessageRole.USER, "Refactor the module and keep tests green.")
        repeat(14) { index ->
            sessions.appendMessage(
                "s1",
                MessageRole.ASSISTANT,
                "Step $index: adjusted call sites, re-ran the suite, noted remaining edge cases for later.",
            )
            sessions.appendMessage("s1", MessageRole.USER, "Continue with step ${index + 1}, keep the API stable.")
        }
    }

    @Test
    fun `a user-chosen model produces a model summary rather than a rule-based one`() {
        val sessions = Sessions()
        longHistory(sessions)
        val store = InMemoryContextCompactionStore()
        val adapter = RoutingAdapter(sevenSections)

        val service = ConversationTurnService(
            sessions = sessions,
            models = adapter,
            compactionStore = store,
            contextPolicy = ContextPolicy(enabled = true),
            summaryModelProvider = { summaryModel },
        )

        service.submit("s1", turnModel, "One more step please.") {}

        assertEquals(1, adapter.summaryRequests.size, "the chosen model was asked exactly once")
        assertEquals(summaryModel, adapter.summaryRequests.single().model)
        assertTrue(adapter.summaryRequests.single().tools.isEmpty())

        val record = store.listActive("s1").single()
        assertEquals(0, record.fallbackLevel, "a valid model summary is level 0, not a rules fallback")
        assertTrue(record.summary.contains("## 6. 下一步"))
    }

    @Test
    fun `with no model chosen compaction still runs, on the deterministic summary`() {
        val sessions = Sessions()
        longHistory(sessions)
        val store = InMemoryContextCompactionStore()
        val adapter = RoutingAdapter(sevenSections)

        val service = ConversationTurnService(
            sessions = sessions,
            models = adapter,
            compactionStore = store,
            contextPolicy = ContextPolicy(enabled = true),
        )

        service.submit("s1", turnModel, "One more step please.") {}

        assertTrue(adapter.summaryRequests.isEmpty(), "no model was chosen, so none was called")
        val record = store.listActive("s1").single()
        assertTrue(record.fallbackLevel >= 1, "rules-only summaries are level 1 or 2")
        assertTrue(record.summary.contains("## 1. 任务与目标"))
    }

    @Test
    fun `a model that answers badly degrades to the rules instead of failing the turn`() {
        val sessions = Sessions()
        longHistory(sessions)
        val store = InMemoryContextCompactionStore()
        // Answers the summary request, but not in the required shape.
        val adapter = RoutingAdapter("just a short paragraph with no headers")

        val service = ConversationTurnService(
            sessions = sessions,
            models = adapter,
            compactionStore = store,
            contextPolicy = ContextPolicy(enabled = true),
            summaryModelProvider = { summaryModel },
        )

        service.submit("s1", turnModel, "One more step please.") {}

        assertEquals(1, adapter.summaryRequests.size)
        val record = store.listActive("s1").single()
        assertTrue(record.fallbackLevel >= 1, "an unusable model answer falls back to the rules")
        assertTrue(
            sessions.stored.any { it.role == MessageRole.ASSISTANT && it.content == "ok" },
            "the turn itself still completed",
        )
    }
}
