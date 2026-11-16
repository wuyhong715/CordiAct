package com.cordiact.app.conversation

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import com.cordiact.core.conversation.MessageRole
import com.cordiact.core.conversation.firstUserSentenceTitle
import com.cordiact.core.conversation.normalizeSessionTitle
import com.cordiact.core.attachment.MessagePart
import org.junit.Assert.*
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE)
class SessionLifecycleTest {
    private lateinit var context: Context
    private lateinit var store: AndroidConversationSessionStore
    private val database = "session-lifecycle-test.db"
    @Before fun setup() { context = RuntimeEnvironment.getApplication(); context.deleteDatabase(database); store = AndroidConversationSessionStore(context, database); store.ensureDefault(0) }
    @After fun teardown() { store.close(); context.deleteDatabase(database) }
    @Test fun firstSentenceAndManualTitlePersistAcrossReopen() {
        val session = store.create("session", com.cordiact.core.workspace.DEFAULT_WORKSPACE_ID, "临时标题", 1)
        store.append(session.id, MessageRole.USER, "  你好   世界。下一句", 2)
        assertEquals("你好 世界。", store.get(session.id)!!.title)
        store.updateFirstUserTitleFromOriginal(session.id, "用户原话！展开内容不得入标题")
        assertEquals("用户原话！", store.get(session.id)!!.title)
        store.renameSession(session.id, "  手动命名  ")
        store.updateFirstUserTitleFromOriginal(session.id, "不能覆盖")
        store.append(session.id, MessageRole.USER, "第二次发言", 3)
        store.close(); store = AndroidConversationSessionStore(context, database)
        assertEquals("手动命名", store.get(session.id)!!.title)
        assertTrue(store.get(session.id)!!.titleManuallySet)
    }
    @Test fun metadataIsIdempotentAndDoesNotTouchActivityOrMessages() {
        val session = store.create("session", com.cordiact.core.workspace.DEFAULT_WORKSPACE_ID, "initial", 1)
        store.append(session.id, MessageRole.USER, "内容", 2)
        val messages = store.messages(session.id)
        store.archiveSession(session.id, 10); store.archiveSession(session.id, 20)
        store.setSessionPinned(session.id, true, 11); store.setSessionPinned(session.id, true, 21)
        store.renameSession(session.id, "新标题")
        val row = store.get(session.id)!!
        assertEquals(10L, row.archivedAtMillis); assertEquals(11L, row.pinnedAtMillis)
        assertEquals(2L, row.updatedAtMillis); assertEquals(messages, store.messages(session.id))
        store.append(session.id, MessageRole.ASSISTANT, "后台继续", 30)
        assertEquals(10L, store.get(session.id)!!.archivedAtMillis)
        store.restoreSession(session.id); store.restoreSession(session.id)
        store.setSessionPinned(session.id, false, 40)
        assertNull(store.get(session.id)!!.archivedAtMillis); assertNull(store.get(session.id)!!.pinnedAtMillis)
    }
    @Test fun partsAndLegacyHistoryDoNotOverwriteTitles() {
        val session = store.create("session", com.cordiact.core.workspace.DEFAULT_WORKSPACE_ID, "initial", 1)
        store.appendWithParts(session.id, MessageRole.USER, "图像说明？后句", listOf(MessagePart.Text("图像说明？后句")), 2)
        assertEquals("图像说明？", store.get(session.id)!!.title)
        store.appendWithParts(session.id, MessageRole.USER, "下一轮", listOf(MessagePart.Text("下一轮")), 3)
        store.updateFirstUserTitleFromOriginal(session.id, "禁止覆盖历史")
        assertEquals("图像说明？", store.get(session.id)!!.title)
    }
    @Test fun upgradeV10PreservesRowsAndAddsMetadata() {
        store.create("legacy", com.cordiact.core.workspace.DEFAULT_WORKSPACE_ID, "历史标题", 1)
        store.append("legacy", MessageRole.USER, "已有首句", 2)
        store.close()
        val db = SQLiteDatabase.openDatabase(context.getDatabasePath(database).path, null, SQLiteDatabase.OPEN_READWRITE)
        db.execSQL("ALTER TABLE conversation_sessions DROP COLUMN session_archived_at")
        db.execSQL("ALTER TABLE conversation_sessions DROP COLUMN session_pinned_at")
        db.execSQL("ALTER TABLE conversation_sessions DROP COLUMN title_manually_set")
        db.version = 10; db.close()
        store = AndroidConversationSessionStore(context, database)
        assertEquals("已有首句", store.get("legacy")!!.title)
        assertEquals(1, store.messages("legacy").size)
        store.append("legacy", MessageRole.USER, "新消息不得回填历史", 3)
        assertEquals("已有首句", store.get("legacy")!!.title)
        val session = store.create("session", com.cordiact.core.workspace.DEFAULT_WORKSPACE_ID, "after upgrade", 1)
        assertNull(store.get(session.id)!!.archivedAtMillis)
        store.archiveSession(session.id, 2)
        assertEquals(2L, store.get(session.id)!!.archivedAtMillis)
    }
    @Test fun titleValidationAndTruncation() {
        assertEquals("附件对话", firstUserSentenceTitle(" \n"))
        assertEquals("第一行", firstUserSentenceTitle("第一行\n第二行"))
        assertEquals(120, firstUserSentenceTitle("长".repeat(200)).length)
        assertEquals("a b!", firstUserSentenceTitle("a\tb!next"))
        for (bad in listOf("", "x\ny", "x\u2028y", "长".repeat(121))) {
            assertThrows(IllegalArgumentException::class.java) { normalizeSessionTitle(bad) }
        }
    }
}
