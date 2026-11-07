package com.cordiact.core.toolloop

import com.cordiact.core.conversation.MessageRole
import com.cordiact.core.conversation.ModelMessage
import com.cordiact.core.conversation.ModelToolCall
import com.cordiact.core.inbox.DefaultAgentInbox
import com.cordiact.core.inbox.InboxItemKind
import com.cordiact.core.tool.ToolInvocationContext
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ToolLoopInboxTest {

    private val context = ToolInvocationContext(workspaceId = "ws-1", sessionId = "parent")

    private fun call(id: String) = ModelToolCall(id = id, name = "noop", argumentsJson = "{}")

    @Test
    fun `a report queued during the turn reaches the model at the next round boundary`() {
        val inbox = DefaultAgentInbox()
        val plugin = ToolLoopPlugin(inbox = inbox).apply { onEnable() }

        val seenRounds = mutableListOf<List<ModelMessage>>()
        var round = 0

        val outcome = plugin.run(
            context = context,
            initialMessages = listOf(ModelMessage(MessageRole.USER, "start")),
            dispatcher = ToolDispatcher { _, c ->
                // The child settles while the first tool is still executing.
                if (round == 1) {
                    inbox.enqueue(InboxItemKind.CHILD_SETTLED, "parent", "child-1", "child finished", "all good")
                }
                ModelMessage(MessageRole.TOOL, "tool ok", toolCallId = c.id, toolName = c.name)
            },
            send = { messages ->
                seenRounds += messages.toList()
                round++
                if (round < 3) {
                    ToolLoopRound(text = "working", calls = listOf(call("c$round")), events = emptyList())
                } else {
                    ToolLoopRound(text = "done", calls = emptyList(), events = emptyList())
                }
            },
        )

        assertTrue(outcome is ToolLoopOutcome.Completed)
        // Round 1 could not have seen it; round 2 must.
        assertTrue(seenRounds[0].none { it.content.contains("child_report") })
        assertTrue(
            seenRounds[1].any { it.content.contains("child_report") && it.content.contains("all good") },
            "the report must be visible from the round after it was produced",
        )
        assertEquals(0, inbox.size("parent"), "delivered reports are consumed exactly once")
    }

    @Test
    fun `reports are put back when the request never leaves`() {
        val inbox = DefaultAgentInbox()
        val plugin = ToolLoopPlugin(inbox = inbox).apply { onEnable() }
        inbox.enqueue(InboxItemKind.CHILD_SETTLED, "parent", "child-1", "child finished", "important")

        val outcome = plugin.run(
            context = context,
            initialMessages = listOf(ModelMessage(MessageRole.USER, "start")),
            dispatcher = ToolDispatcher { _, c -> ModelMessage(MessageRole.TOOL, "ok", toolCallId = c.id) },
            send = { throw IllegalStateException("provider unavailable") },
        )

        assertTrue(outcome is ToolLoopOutcome.Stopped)
        assertEquals(1, inbox.size("parent"), "an undelivered report must stay queued, not vanish")
        assertEquals("important", inbox.peek("parent").single().body)
    }

    @Test
    fun `at most the per-round cap is delivered in one round`() {
        val inbox = DefaultAgentInbox()
        val plugin = ToolLoopPlugin(inbox = inbox, maxReportsPerRound = 2).apply { onEnable() }
        repeat(5) { index ->
            inbox.enqueue(InboxItemKind.JOB_SETTLED, "parent", "job-$index", "job $index done")
        }

        var deliveredBlocks = 0
        plugin.run(
            context = context,
            initialMessages = listOf(ModelMessage(MessageRole.USER, "start")),
            dispatcher = ToolDispatcher { _, c -> ModelMessage(MessageRole.TOOL, "ok", toolCallId = c.id) },
            send = { messages ->
                deliveredBlocks = messages.count { it.content.contains("<child_report") }
                ToolLoopRound(text = "done", calls = emptyList(), events = emptyList())
            },
        )

        assertEquals(1, deliveredBlocks, "five reports are rendered as one system block")
        assertEquals(3, inbox.size("parent"), "only two of the five were consumed")
    }
}
