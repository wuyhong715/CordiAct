package com.cordiact.model.providers

import com.cordiact.core.conversation.ConversationMessage
import com.cordiact.core.conversation.ConversationSession
import com.cordiact.core.conversation.MessageRole
import com.cordiact.core.conversation.ModelAdapterService
import com.cordiact.core.conversation.ModelCall
import com.cordiact.core.conversation.ModelDescriptor
import com.cordiact.core.conversation.ModelEvent
import com.cordiact.core.conversation.ModelGenerationRequest
import com.cordiact.core.conversation.ModelId
import com.cordiact.core.conversation.ModelToolCall
import com.cordiact.core.conversation.SessionKind
import com.cordiact.core.conversation.SessionManagerService
import com.cordiact.core.tool.ToolInvocationContext
import com.cordiact.core.toolloop.ToolLoopLimits
import com.cordiact.core.toolloop.ToolLoopOutcome
import com.cordiact.core.toolloop.ToolLoopRound
import com.cordiact.core.toolloop.ToolLoopService
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class TurnControlAndEventTest {

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

    @Test
    fun turnCancelStopsActiveTurnAndFreesSession() {
        val sessions = Sessions()
        val cancelled = AtomicBoolean(false)
        val mockAdapter = object : ModelAdapterService {
            override fun models(): List<ModelDescriptor> = listOf(ModelDescriptor(ModelId("test", "m1"), "Test Model"))
            override fun generate(
                request: ModelGenerationRequest,
                onEvent: (ModelEvent) -> Unit,
            ): ModelCall {
                return ModelCall {
                    cancelled.set(true)
                }
            }
        }

        val turnService = ConversationTurnService(
            sessions = sessions,
            models = mockAdapter,
        )

        turnService.submit(
            sessionId = "s1",
            model = ModelId("test", "m1"),
            text = "Hello",
            onEvent = {},
        )

        assertTrue(turnService.isTurnActive("s1"))
        val didCancel = turnService.cancelTurn("s1")
        assertTrue(didCancel)
        assertFalse(turnService.isTurnActive("s1"))
        assertTrue(cancelled.get())

        // Cancelling again returns false
        assertFalse(turnService.cancelTurn("s1"))
    }

    @Test
    fun toolLoopEmitsToolCallStartedAndCompletedEvents() {
        val events = CopyOnWriteArrayList<ModelEvent>()

        val roundCalls = listOf(ModelToolCall("c1", "test_tool", "{\"arg\":1}"))
        var roundCount = 0

        val service = com.cordiact.core.toolloop.ToolLoopPlugin(limits = ToolLoopLimits(maxRounds = 2, maxToolCalls = 2)).apply {
            onEnable()
        }
        val context = ToolInvocationContext(
            workspaceId = "ws1",
            sessionId = "s1",
            onEvent = { event: ModelEvent -> events.add(event) },
        )

        val outcome = service.run(
            context = context,
            initialMessages = emptyList(),
            dispatcher = { _, _ ->
                com.cordiact.core.conversation.ModelMessage(
                    role = MessageRole.TOOL,
                    content = "Tool executed successfully",
                    toolCallId = "c1",
                    toolName = "test_tool",
                )
            },
            send = {
                roundCount++
                if (roundCount == 1) {
                    ToolLoopRound("Let me run a tool", roundCalls, emptyList())
                } else {
                    ToolLoopRound("All done!", emptyList(), emptyList())
                }
            },
        )

        assertTrue(outcome is ToolLoopOutcome.Completed)
        assertEquals("All done!", (outcome as ToolLoopOutcome.Completed).text)

        val started = events.filterIsInstance<ModelEvent.ToolCallStarted>()
        assertEquals(1, started.size)
        assertEquals("test_tool", started[0].toolName)
        assertEquals("c1", started[0].callId)

        val completed = events.filterIsInstance<ModelEvent.ToolCallCompleted>()
        assertEquals(1, completed.size)
        assertEquals("test_tool", completed[0].toolName)
        assertTrue(completed[0].success)
        assertTrue(completed[0].durationMillis >= 0)
    }
}
