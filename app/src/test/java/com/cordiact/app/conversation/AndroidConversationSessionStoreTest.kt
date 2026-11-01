package com.cordiact.app.conversation

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.cordiact.core.conversation.MessageRole
import com.cordiact.core.workspace.DEFAULT_WORKSPACE_ID
import com.cordiact.core.workspace.DEFAULT_WORKSPACE_NAME
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
        store.ensureDefault(1L)
    }

    @After
    fun tearDown() {
        store.close()
        context.deleteDatabase(TEST_DATABASE)
    }

    @Test
    fun sessionsAndMessagesPersistAndStayWorkspaceScoped() {
        store.createWorkspace("workspace-a", "Alpha", workspaceRoot("workspace-a"), 2L)
        store.createWorkspace("workspace-b", "Beta", workspaceRoot("workspace-b"), 3L)
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
    fun workspaceArchiveBlocksNewSessionsAndRestoresActiveWorkspace() {
        val secondary = store.createWorkspace("workspace-archived", "Archive me", workspaceRoot("workspace-archived"), 2L)
        store.selectWorkspace(secondary.id, 3L)
        store.archiveWorkspace(secondary.id, 4L)

        assertEquals(DEFAULT_WORKSPACE_ID, store.activeWorkspace()?.id)
        assertNull(store.findSessionWorkspace(secondary.id))
        try {
            store.create("session-archived", secondary.id, "Rejected", 5L)
            throw AssertionError("Expected archived workspace rejection")
        } catch (expected: IllegalStateException) {
            assertTrue(expected.message.orEmpty().contains("does not exist or is archived"))
        }
        assertNull(store.get("session-archived"))

        store.restoreWorkspace(secondary.id, 6L)
        assertEquals(secondary.id, store.findSessionWorkspace(secondary.id)?.id)
    }

    @Test
    fun foreignKeyRejectsUnknownWorkspace() {
        try {
            store.create("orphan", "unknown", "Orphan", 1L)
            throw AssertionError("Expected unknown workspace rejection")
        } catch (expected: IllegalStateException) {
            assertTrue(expected.message.orEmpty().contains("Workspace"))
        }
        assertNull(store.get("orphan"))
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

    @Test
    fun versionOneUpgradePreservesSessionsMessagesAndWorkspaceIdentity() {
        val legacyDb = "cordiact-v1-migration-test.db"
        context.deleteDatabase(legacyDb)
        val old = object : SQLiteOpenHelper(context, legacyDb, null, 1) {
            override fun onCreate(db: SQLiteDatabase) {
                db.execSQL("CREATE TABLE conversation_sessions (session_id TEXT PRIMARY KEY NOT NULL, workspace_id TEXT NOT NULL, title TEXT NOT NULL, created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL)")
                db.execSQL("CREATE TABLE conversation_messages (message_id TEXT PRIMARY KEY NOT NULL, session_id TEXT NOT NULL, role TEXT NOT NULL, content TEXT NOT NULL, created_at INTEGER NOT NULL, FOREIGN KEY(session_id) REFERENCES conversation_sessions(session_id) ON DELETE CASCADE)")
            }
            override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
        }
        old.writableDatabase.execSQL("INSERT INTO conversation_sessions VALUES('legacy-session','legacy-workspace','Old title',10,20)")
        old.writableDatabase.execSQL("INSERT INTO conversation_messages VALUES('legacy-message','legacy-session','USER','Keep me',15)")
        old.close()

        val migrated = AndroidConversationSessionStore(context, legacyDb)
        try {
            val session = migrated.get("legacy-session")
            assertEquals("legacy-workspace", session?.workspaceId)
            assertEquals("Old title", session?.title)
            assertEquals(listOf("Keep me"), migrated.messages("legacy-session").map { it.content })
            assertEquals("legacy-workspace", migrated.findSessionWorkspace("legacy-workspace")?.id)
            assertEquals(0, migrated.readableDatabase.rawQuery("PRAGMA foreign_key_check", null).use { cursor ->
                var count = 0
                while (cursor.moveToNext()) count++
                count
            })
        } finally {
            migrated.close()
            context.deleteDatabase(legacyDb)
        }
    }

    private fun workspaceRoot(id: String) = "workspaces/${java.util.UUID.nameUUIDFromBytes(id.toByteArray())}"

    private companion object { const val TEST_DATABASE = "cordiact-conversation-test.db" }
}
