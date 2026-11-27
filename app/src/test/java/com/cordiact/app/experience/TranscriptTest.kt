package com.cordiact.app.experience

import com.cordiact.app.experience.model.*
import org.junit.Assert.*
import org.junit.Test

class TranscriptTest {
    private fun running() = TranscriptTurn("t", "prompt", phase = RunPhase.RUNNING, startedAt = 100)

    @Test fun `subagent preset source survives transcript replay without a live plugin`() {
        val preset = requireNotNull(TranscriptSubagentPreset.parse("review-pack.review", "review-pack@1.0.0"))
        val turns = restoreTranscript(listOf(
            StoredMessage("u1", "USER", "Check this", 1, subagentPreset = preset),
            StoredMessage("a1", "ASSISTANT", "Result", 2),
            StoredMessage("u2", "USER", "Next", 3),
        ))
        assertEquals(preset, turns[0].subagentPreset)
        assertNull(turns[1].subagentPreset)
        assertNull(TranscriptSubagentPreset.parse("review-pack.review", "different@1.0.0"))
    }

    @Test fun `package context attribution uses the current message and survives restoration`() {
        val sources = listOf("review-kit@1.0.0")
        val live = running().reduce(TranscriptEvent.PackageContext(sources)).reduce(TranscriptEvent.Answer("reply"))
        assertEquals(sources, live.packageContextSources)
        val restored = restoreTranscript(listOf(
            StoredMessage("u1", "USER", "review", 10, packageContextSources = sources),
            StoredMessage("a1", "ASSISTANT", "reply", 11),
            StoredMessage("u2", "USER", "other", 12),
        ))
        assertEquals(sources, restored[0].packageContextSources)
        assertTrue(restored[1].packageContextSources.isEmpty())
    }

    @Test fun `streams append answer and reasoning independently`() {
        val result = running().reduce(TranscriptEvent.Answer("你")).reduce(TranscriptEvent.Reasoning("check"))
            .reduce(TranscriptEvent.Answer("好"))
        assertEquals("你好", result.answer)
        assertEquals("check", result.reasoning)
    }
    @Test fun `tool completion uses call identity not name`() {
        val turn = running().reduce(TranscriptEvent.ToolStarted("a", "read", "{}", 110))
            .reduce(TranscriptEvent.ToolStarted("b", "read", "{}", 120))
            .reduce(TranscriptEvent.ToolFinished("b", "read", true, "ok", 20, 140))
        assertEquals(ToolPhase.RUNNING, turn.tools[0].phase)
        assertEquals(ToolPhase.SUCCEEDED, turn.tools[1].phase)
        assertEquals(20L, turn.tools[1].durationMillis)
    }

    @Test fun `process retains interleaved thinking and tool order`() {
        val turn = running()
            .reduce(TranscriptEvent.Reasoning("first"))
            .reduce(TranscriptEvent.ToolStarted("a", "read", "{}", 110))
            .reduce(TranscriptEvent.Reasoning("second"))
            .reduce(TranscriptEvent.ToolFinished("a", "read", true, "ok", 20, 140))

        assertEquals(
            listOf(ProcessEntry.Thinking(0, 5), ProcessEntry.Tool("a"), ProcessEntry.Thinking(5, 11)),
            turn.process,
        )
        assertEquals(ToolPhase.SUCCEEDED, turn.tools.single().phase)
    }
    @Test fun `duplicate start cannot duplicate cards`() {
        val start = TranscriptEvent.ToolStarted("a", "read", "{}", 110)
        assertEquals(1, running().reduce(start).reduce(start).tools.size)
    }
    @Test fun `out of order completion produces truthful completed tool`() {
        val turn = running().reduce(TranscriptEvent.ToolFinished("a", "read", false, "denied", 20, 140))
        assertEquals(120L, turn.tools.single().startedAt)
        assertEquals(ToolPhase.FAILED, turn.tools.single().phase)
    }
    @Test fun `late start after completion does not revert state`() {
        val turn = running().reduce(TranscriptEvent.ToolFinished("a", "read", true, "ok", 20, 140))
            .reduce(TranscriptEvent.ToolStarted("a", "read", "{}", 150))
        assertEquals(ToolPhase.SUCCEEDED, turn.tools.single().phase)
    }
    @Test fun `terminal rejects late deltas and late complete`() {
        val stopped = running().reduce(TranscriptEvent.Finished(RunPhase.CANCELLED, 200))
        assertEquals(stopped, stopped.reduce(TranscriptEvent.Answer("late")))
        assertEquals(stopped, stopped.reduce(TranscriptEvent.Finished(RunPhase.COMPLETE, 300)))
    }
    @Test fun `failure closes unfinished tool spans`() {
        val turn = running().reduce(TranscriptEvent.ToolStarted("a", "read", "{}", 110))
            .reduce(TranscriptEvent.Finished(RunPhase.FAILED, 200, "error"))
        assertEquals(ToolPhase.INTERRUPTED, turn.tools.single().phase)
        assertEquals(200L, turn.tools.single().finishedAt)
        assertEquals("error", turn.error)
    }
    @Test fun `usage belongs only to completed turn`() {
        val turn = running().reduce(TranscriptEvent.Finished(RunPhase.COMPLETE, 200, input = 10, output = 50))
        assertEquals(10L, turn.inputTokens)
        assertEquals(50L, turn.outputTokens)
    }
    @Test fun `restore groups assistants and never displays system or tool prose`() {
        val turns = restoreTranscript(listOf(
            StoredMessage("s", "SYSTEM", "private", 1), StoredMessage("u", "USER", "prompt", 2),
            StoredMessage("a", "ASSISTANT", "one", 3), StoredMessage("t", "TOOL", "output", 4),
            StoredMessage("b", "ASSISTANT", "two", 5), StoredMessage("v", "USER", "next", 6),
        ))
        assertEquals(2, turns.size)
        assertEquals("one\n\ntwo", turns.first().answer)
        assertTrue(turns.first().tools.isEmpty())
        assertEquals(RunPhase.COMPLETE, turns.last().phase)
    }
    @Test fun `orphan assistant is retained without invented prompt`() {
        val turn = restoreTranscript(listOf(StoredMessage("a", "ASSISTANT", "text", 3))).single()
        assertEquals("", turn.prompt)
        assertEquals("text", turn.answer)
    }
    @Test fun `duration formatting is stable at boundaries`() {
        assertEquals("0 ms", durationLabel(-10)); assertEquals("999 ms", durationLabel(999))
        assertEquals("1.0 s", durationLabel(1000)); assertEquals("1m 0s", durationLabel(60_000))
    }
    @Test fun `scroll unlock requires ten raw pixels toward history`() {
        val policy = PinnedScrollPolicy()
        assertTrue(policy.userDrag(4f)); assertTrue(policy.userDrag(5f))
        assertFalse(policy.userDrag(1f)); assertFalse(policy.userDrag(-100f))
        policy.pin(); assertTrue(policy.pinned)
    }
    @Test fun `direction change resets accumulated history drag`() {
        val policy = PinnedScrollPolicy()
        assertTrue(policy.userDrag(9f)); assertTrue(policy.userDrag(-1f))
        assertTrue(policy.userDrag(9f)); assertFalse(policy.userDrag(1f))
    }

    @Test fun `restoreTranscript hydrates persisted trace without fabricating missing timings`() {
        val tool = ToolActivity("c1", "priv_status", "{}", "shell", ToolPhase.SUCCEEDED, 1000L, 1140L, 140L)
        val restored = restoreTranscript(listOf(
            StoredMessage("u1", "USER", "旧消息", 100L),
            StoredMessage("a1", "ASSISTANT", "旧回复", 200L),
            StoredMessage("u2", "USER", "新消息", 900L),
            StoredMessage(
                "a2", "ASSISTANT", "新回复", 1500L,
                trace = StoredTurnTrace(
                    startedAt = 950L,
                    finishedAt = 1500L,
                    inputTokens = 120L,
                    outputTokens = 45L,
                    reasoning = "先检查状态",
                    tools = listOf(tool),
                ),
            ),
        ))
        assertEquals(2, restored.size)
        assertNull(restored[0].finishedAt)
        assertTrue(restored[0].tools.isEmpty())
        assertEquals(950L, restored[1].startedAt)
        assertEquals(1500L, restored[1].finishedAt)
        assertEquals(45L, restored[1].outputTokens)
        assertEquals("先检查状态", restored[1].reasoning)
        assertEquals(listOf(tool), restored[1].tools)
    }

    @Test fun `markdown splitter repairs tight and collapsed pipe tables and fenced code`() {
        val collapsed = "1. 设备特权控制 | 工具 | 作用 | |---|---| | `priv_status` | 查看通道 | | `priv_exec` | 运行命令 |"
        val collapsedBlocks = splitMarkdownBlocks(collapsed)
        assertTrue(collapsedBlocks.any { it is MarkdownBlock.Table && it.headers == listOf("工具", "作用") && it.rows.size == 2 })

        val tight = """
            1. 清单
            | 工具 | 说明 |
            | --- | --- |
            | `read_file` | 读取 |
            ```kotlin
            val x = 1
            ```
        """.trimIndent()
        val blocks = splitMarkdownBlocks(tight)
        assertEquals(3, blocks.size)
        assertTrue(blocks[0] is MarkdownBlock.Prose)
        assertTrue(blocks[1] is MarkdownBlock.Table)
        assertTrue(blocks[2] is MarkdownBlock.Code)
        val code = blocks[2] as MarkdownBlock.Code
        assertEquals("kotlin", code.language)
        assertTrue(code.closed)
    }

}
