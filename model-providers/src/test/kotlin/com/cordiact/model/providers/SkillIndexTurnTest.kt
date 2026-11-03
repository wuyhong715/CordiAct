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
import com.cordiact.core.conversation.SessionManagerService
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SkillIndexTurnTest {
    @Test
    fun skillIndexIsSystemContextForOneTurnAndIsNeverPersisted() {
        val session = ConversationSession("s", "ws", "test", 0, 0)
        val saved = mutableListOf<ConversationMessage>()
        val sessions = object : SessionManagerService {
            override fun createSession(workspaceId: String, title: String): ConversationSession = session
            override fun getSession(sessionId: String): ConversationSession? = session
            override fun listSessions(workspaceId: String): List<ConversationSession> = listOf(session)
            override fun appendMessage(sessionId: String, role: MessageRole, content: String): ConversationMessage =
                ConversationMessage("m${saved.size}", sessionId, role, content, 0).also(saved::add)
            override fun messages(sessionId: String): List<ConversationMessage> = saved.toList()
        }
        val requests = mutableListOf<ModelGenerationRequest>()
        val backend = object : ModelAdapterService {
            override fun models(): List<ModelDescriptor> = emptyList()
            override fun generate(request: ModelGenerationRequest, onEvent: (ModelEvent) -> Unit): ModelCall {
                requests += request
                onEvent(ModelEvent.Completed())
                return ModelCall { }
            }
        }
        val turn = ConversationTurnService(sessions, backend)
        val model = ModelId("provider", "model")
        turn.submit("s", model, "please review", emptyList(), systemContext = "<cordiact-skills>\n- review: Review changes", onEvent = { })
        val first = requests.single().messages
        assertEquals(MessageRole.SYSTEM, first.first().role)
        assertTrue(first.first().content.contains("review: Review changes"))
        assertEquals(listOf(MessageRole.USER), saved.map { it.role })
        turn.submit("s", model, "another request", emptyList(), systemContext = null, onEvent = { })
        assertTrue(requests.last().messages.none { it.role == MessageRole.SYSTEM })
        assertEquals(2, saved.size)
    }
}
