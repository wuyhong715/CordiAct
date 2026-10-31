package com.cordiact.app.conversation

import android.content.Context
import com.cordiact.core.conversation.MessageRole
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AndroidConversationSessionStoreTest {
    private lateinit var context: Context
    private lateinit var store: AndroidConversationSessionStore

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        context.deleteDatabase(TEST_DATABASE)
        store = AndroidConversationSessionStore(context, TEST_DATABASE)
    }

    @After
    fun tearDown() {
        store.close()
        context.deleteDatabase(TEST_DATABASE)
    }

    @Test
    fun sessionsAndMessagesPersistAndStayWorkspaceScoped() {
        val session = store.create("session-1", "workspace-a", "First chat", 10)
        val first = store.append(session.id, MessageRole.USER, "Hi", 20)
        val second = store.append(session.id, MessageRole.ASSISTANT, "Hello", 30)
        assertNotEquals(first.id, second.id)
        assertEquals(30L, store.get(session.id)?.updatedAtMillis)
        assertEquals(listOf(session.copy(updatedAtMillis = 30)), store.list("workspace-a"))
        assertTrue(store.list("workspace-b").isEmpty())

        store.close()
        store = AndroidConversationSessionStore(context, TEST_DATABASE)

        assertEquals(session.id, store.get(session.id)?.id)
        assertEquals(listOf(first, second), store.messages(session.id))
    }

    @Test
    fun appendingToMissingSessionFailsWithoutCreatingOrphanMessage() {
        try {
            store.append("missing-session", MessageRole.USER, "Hi", 1)
            throw AssertionError("Expected missing session failure")
        } catch (expected: IllegalStateException) {
            assertTrue(expected.message!!.contains("does not exist"))
        }
        assertNull(store.get("missing-session"))
    }

    private companion object { const val TEST_DATABASE = "cordiact-conversation-test.db" }
}
