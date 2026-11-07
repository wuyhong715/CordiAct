package com.cordiact.core.context

import com.cordiact.core.conversation.ConversationMessage
import com.cordiact.core.conversation.MessageRole
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ContextP1Test {

    private val estimator = WeightedTokenEstimator()

    private fun msg(id: String, role: MessageRole, content: String, time: Long = 1000L) =
        ConversationMessage(id = id, sessionId = "sess-1", role = role, content = content, createdAtMillis = time)

    @Test
    fun `test compaction planner splits head body and tail correctly`() {
        val messages = listOf(
            msg("m1", MessageRole.SYSTEM, "You are a helpful assistant."),
            msg("m2", MessageRole.USER, "Initial task goal: build features"),
            msg("m3", MessageRole.ASSISTANT, "Starting task now"),
            msg("m4", MessageRole.USER, "Run tests"),
            msg("m5", MessageRole.ASSISTANT, "Tests passed"),
            msg("m6", MessageRole.USER, "Next step"),
            msg("m7", MessageRole.ASSISTANT, "Done"),
        )

        val budget = ContextBudget(
            contextWindowTokens = 100,
            maxOutputTokens = 20,
            safetyMarginTokens = 10,
            thresholdRatio = 0.8,
            retainRatio = 0.2, // ~20 tokens tail
        )

        val plan = CompactionPlanner.plan(messages, estimator, budget, minRetainTurns = 1)
        assertNotNull(plan)
        // Head must be m1 (system) + m2 (first user)
        assertEquals(listOf("m1", "m2"), plan!!.head.map { it.id })
        // Body must start with m3
        assertEquals("m3", plan.startMessageId)
        assertTrue(plan.body.isNotEmpty())
        // Tail must contain the latest interactions
        assertTrue(plan.tail.map { it.id }.contains("m7"))
    }

    @Test
    fun `test summary compactor L0 success, L1 fallback and secret redaction`() {
        val plan = CompactionPlan(
            startMessageId = "m3",
            endMessageId = "m5",
            head = listOf(msg("m1", MessageRole.SYSTEM, "sys"), msg("m2", MessageRole.USER, "initial goal")),
            body = listOf(
                msg("m3", MessageRole.ASSISTANT, "I found the key: sk-abcdefghijklmnopqrstuvwxyz123456"),
                msg("m4", MessageRole.USER, "Please commit with ghp_111122223333444455556666777788889999"),
                msg("m5", MessageRole.ASSISTANT, "Committed successfully"),
            ),
            tail = listOf(msg("m6", MessageRole.USER, "tail user"), msg("m7", MessageRole.ASSISTANT, "tail asst")),
            bodyTokens = 500,
            tailTokens = 100,
            reason = CompactionReason.AUTO,
        )

        // 1. L0 Success
        val validLlmSummary = """
## 1. 任务与目标
完成代码特性改造

## 2. 已完成与关键决策
已提交核心代码

## 3. 当前状态
工作区就绪

## 4. 重要事实与约束
测试已通过

## 5. 工具产出索引
无

## 6. 下一步
进行集成验收

## 7. 必须保留的原文
关键测试断言
""".trimIndent()

        val l0Result = SummaryCompactor.compact(plan, estimator) { _, _ -> validLlmSummary }
        assertEquals(0, l0Result.fallbackLevel)
        assertEquals("m3", l0Result.startMessageId)
        assertEquals("m5", l0Result.endMessageId)
        assertTrue(l0Result.tokensAfter < plan.bodyTokens)

        // 2. L1 Fallback when LLM returns invalid / incomplete sections
        val invalidLlmSummary = "Just a short summary without required headers"
        val l1Result = SummaryCompactor.compact(plan, estimator) { _, _ -> invalidLlmSummary }
        assertEquals(1, l1Result.fallbackLevel)
        assertTrue(l1Result.summary.contains("## 1. 任务与目标"))
        assertTrue(l1Result.summary.contains("## 5. 工具产出索引"))

        // 3. Secret Redaction
        val textWithSecrets = "Key: sk-123456789012345678901234 and token ghp_abcdefghijklmnopqrstuvwxyz123456"
        val redacted = SummaryCompactor.redactSecrets(textWithSecrets)
        assertFalse(redacted.contains("sk-123456"))
        assertFalse(redacted.contains("ghp_abcd"))
        assertTrue(redacted.contains("[REDACTED_SECRET]"))
    }

    @Test
    fun `test default context projector maps head, summary block, and tail`() {
        val projector = DefaultContextProjector()
        val history = listOf(
            msg("m1", MessageRole.SYSTEM, "System Prompt"),
            msg("m2", MessageRole.USER, "First user goal"),
            msg("m3", MessageRole.ASSISTANT, "Old intermediate step 1"),
            msg("m4", MessageRole.USER, "Old intermediate step 2"),
            msg("m5", MessageRole.ASSISTANT, "Old intermediate step 3"),
            msg("m6", MessageRole.USER, "Recent user command"),
            msg("m7", MessageRole.ASSISTANT, "Recent assistant reply"),
        )

        val record = CompactionRecord(
            id = "cmp-1",
            sessionId = "sess-1",
            startMessageId = "m3",
            endMessageId = "m5",
            summary = "Summary of steps 1 to 3",
            tokensBefore = 200,
            tokensAfter = 40,
            fallbackLevel = 0,
            createdAt = 2000L,
            supersededBy = null,
            active = true,
        )

        val projected = projector.project("sess-1", history, listOf(record))

        // Projected must contain: m1, m2, [Summary System Node], m6, m7
        assertEquals(5, projected.size)
        assertEquals("System Prompt", projected[0].content)
        assertEquals("First user goal", projected[1].content)
        assertEquals(MessageRole.SYSTEM, projected[2].role)
        assertTrue(projected[2].content.contains("模型智能摘要"))
        assertTrue(projected[2].content.contains("Summary of steps 1 to 3"))
        assertEquals("Recent user command", projected[3].content)
        assertEquals("Recent assistant reply", projected[4].content)
    }

    @Test
    fun `test in-memory context compaction store lifecycle`() {
        val store = InMemoryContextCompactionStore()
        val record1 = CompactionRecord(
            id = "c1",
            sessionId = "s1",
            startMessageId = "m2",
            endMessageId = "m4",
            summary = "Summary 1",
            tokensBefore = 100,
            tokensAfter = 20,
            fallbackLevel = 1,
            createdAt = 1000L,
            supersededBy = null,
            active = true,
        )
        store.insert(record1)

        val activeList1 = store.listActive("s1")
        assertEquals(1, activeList1.size)
        assertEquals("c1", activeList1[0].id)

        // Supersede c1 with c2
        store.supersede("c1", "c2")
        val activeList2 = store.listActive("s1")
        assertEquals(0, activeList2.size)

        val fetched = store.getCompaction("c1")
        assertNotNull(fetched)
        assertFalse(fetched!!.active)
        assertEquals("c2", fetched.supersededBy)
    }
}
