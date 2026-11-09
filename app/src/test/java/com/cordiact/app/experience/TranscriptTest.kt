package com.cordiact.app.experience

import com.cordiact.app.experience.model.*
import org.junit.Assert.*
import org.junit.Test

class TranscriptTest {
    private fun running() = TranscriptTurn("t", "prompt", phase = RunPhase.RUNNING, startedAt = 100)

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

}
