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
import com.cordiact.core.conversation.SessionKind
import com.cordiact.core.conversation.SessionManagerService
import com.cordiact.core.conversation.TurnNotice
import com.cordiact.core.conversation.TurnNoticeSource
import com.cordiact.core.inbox.DefaultAgentInbox
import com.cordiact.core.inbox.InboxItemKind
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class WokenTurnTest {

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

    private class Adapter(private val onRequest: (ModelGenerationRequest) -> Unit) : ModelAdapterService {
        override fun models() = listOf(ModelDescriptor(ModelId("p", "m"), "M"))
        override fun generate(request: ModelGenerationRequest, onEvent: (ModelEvent) -> Unit): ModelCall {
            onRequest(request)
            onEvent(ModelEvent.Delta("acknowledged"))
            onEvent(ModelEvent.Completed(inputTokens = 10L, outputTokens = 2L))
            return ModelCall {}
        }
    }

    private class Notices : TurnNoticeSource {
        val items = mutableListOf<TurnNotice>()
        override fun pending(sessionId: String) = items.toList()
        override fun acknowledge(sessionId: String, ids: List<String>) {
            items.removeAll { it.id in ids }
        }
    }

    @Test
    fun `a woken turn reports the child outcome without inventing a user message`() {
        val sessions = Sessions()
        sessions.appendMessage("s1", MessageRole.USER, "original goal")
        sessions.appendMessage("s1", MessageRole.ASSISTANT, "working on it")

        val notices = Notices()
        notices.items += TurnNotice(id = "child-1", heading = "child finished", body = "all tests pass")

        var dispatched: List<com.cordiact.core.conversation.ModelMessage> = emptyList()
        val service = ConversationTurnService(sessions, Adapter { dispatched = it.messages }, notices)

        service.submitWoken("s1", ModelId("p", "m")) {}

        // The child's outcome is present, as a runtime report.
        assertTrue(dispatched.any { it.content.contains("<child_report") })
        assertTrue(dispatched.any { it.content.contains("all tests pass") })

        // No user message was fabricated: the only USER entry is the original one.
        val userMessages = sessions.stored.filter { it.role == MessageRole.USER }
        assertEquals(1, userMessages.size)
        assertEquals("original goal", userMessages.single().content)

        // The reply is still persisted, and the notice is consumed.
        assertTrue(sessions.stored.any { it.role == MessageRole.ASSISTANT && it.content == "acknowledged" })
        assertTrue(notices.items.isEmpty())
    }

    @Test
    fun `a woken turn refuses to start when nothing is pending`() {
        val service = ConversationTurnService(Sessions(), Adapter { }, Notices())
        assertThrows<IllegalArgumentException> {
            service.submitWoken("s1", ModelId("p", "m")) {}
        }
    }

    @Test
    fun `a turn start drops the mid-turn shadow copy so a report is not delivered twice`() {
        val sessions = Sessions()
        val notices = Notices()
        notices.items += TurnNotice(id = "child-1", heading = "child finished", body = "done")

        val inbox = DefaultAgentInbox()
        inbox.enqueue(InboxItemKind.CHILD_SETTLED, "s1", "child-1", "child finished", "done")

        var dispatched: List<com.cordiact.core.conversation.ModelMessage> = emptyList()
        val service = ConversationTurnService(sessions, Adapter { dispatched = it.messages }, notices, inbox = inbox)

        service.submit("s1", ModelId("p", "m"), "next question") {}

        assertEquals(0, inbox.size("s1"), "the shadow copy must not survive the turn head")
        assertEquals(1, dispatched.count { it.content.contains("<child_report") })
    }

    @Test
    fun `the session reports whether a turn is active`() {
        val service = ConversationTurnService(Sessions(), Adapter { }, Notices())
        assertFalse(service.isTurnActive("s1"))
    }
}
