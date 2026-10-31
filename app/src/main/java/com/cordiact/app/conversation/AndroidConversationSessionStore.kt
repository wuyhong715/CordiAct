package com.cordiact.app.conversation

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.cordiact.core.conversation.ConversationMessage
import com.cordiact.core.conversation.ConversationSession
import com.cordiact.core.conversation.ConversationSessionStore
import com.cordiact.core.conversation.MessageRole
import java.util.UUID

/** Private durable conversation store. Session and message writes are transactional. */
class AndroidConversationSessionStore(
    context: Context,
    databaseName: String = DATABASE_NAME,
) : SQLiteOpenHelper(context.applicationContext, databaseName, null, DATABASE_VERSION), ConversationSessionStore {

    override fun onConfigure(db: SQLiteDatabase) {
        super.onConfigure(db)
        db.setForeignKeyConstraintsEnabled(true)
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE $TABLE_SESSIONS (
                $COL_SESSION_ID TEXT PRIMARY KEY NOT NULL,
                $COL_WORKSPACE_ID TEXT NOT NULL,
                $COL_TITLE TEXT NOT NULL,
                $COL_CREATED_AT INTEGER NOT NULL,
                $COL_UPDATED_AT INTEGER NOT NULL
            )""".trimIndent(),
        )
        db.execSQL("CREATE INDEX conversation_sessions_workspace ON $TABLE_SESSIONS($COL_WORKSPACE_ID, $COL_UPDATED_AT DESC)")
        db.execSQL(
            """CREATE TABLE $TABLE_MESSAGES (
                $COL_MESSAGE_ID TEXT PRIMARY KEY NOT NULL,
                $COL_MESSAGE_SESSION_ID TEXT NOT NULL,
                $COL_ROLE TEXT NOT NULL,
                $COL_CONTENT TEXT NOT NULL,
                $COL_CREATED_AT INTEGER NOT NULL,
                FOREIGN KEY ($COL_MESSAGE_SESSION_ID) REFERENCES $TABLE_SESSIONS($COL_SESSION_ID) ON DELETE CASCADE
            )""".trimIndent(),
        )
        db.execSQL("CREATE INDEX conversation_messages_session ON $TABLE_MESSAGES($COL_MESSAGE_SESSION_ID, $COL_CREATED_AT, $COL_MESSAGE_ID)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        throw IllegalStateException("No conversation database migration from $oldVersion to $newVersion")
    }

    override fun create(id: String, workspaceId: String, title: String, nowMillis: Long): ConversationSession {
        require(id.isNotBlank() && workspaceId.isNotBlank() && title.isNotBlank())
        val values = ContentValues().apply {
            put(COL_SESSION_ID, id)
            put(COL_WORKSPACE_ID, workspaceId)
            put(COL_TITLE, title)
            put(COL_CREATED_AT, nowMillis)
            put(COL_UPDATED_AT, nowMillis)
        }
        writableDatabase.insertOrThrow(TABLE_SESSIONS, null, values)
        return ConversationSession(id, workspaceId, title, nowMillis, nowMillis)
    }

    override fun get(sessionId: String): ConversationSession? = readableDatabase.query(
        TABLE_SESSIONS,
        SESSION_COLUMNS,
        "$COL_SESSION_ID = ?",
        arrayOf(sessionId),
        null,
        null,
        null,
    ).use { cursor -> if (cursor.moveToFirst()) cursor.toSession() else null }

    override fun list(workspaceId: String): List<ConversationSession> = readableDatabase.query(
        TABLE_SESSIONS,
        SESSION_COLUMNS,
        "$COL_WORKSPACE_ID = ?",
        arrayOf(workspaceId),
        null,
        null,
        "$COL_UPDATED_AT DESC, $COL_SESSION_ID ASC",
    ).use { cursor -> buildList { while (cursor.moveToNext()) add(cursor.toSession()) } }

    override fun append(sessionId: String, role: MessageRole, content: String, nowMillis: Long): ConversationMessage {
        require(content.isNotBlank())
        val id = UUID.randomUUID().toString()
        val db = writableDatabase
        db.beginTransaction()
        try {
            val updated = ContentValues().apply { put(COL_UPDATED_AT, nowMillis) }
            check(db.update(TABLE_SESSIONS, updated, "$COL_SESSION_ID = ?", arrayOf(sessionId)) == 1) {
                "Conversation session '$sessionId' does not exist"
            }
            val values = ContentValues().apply {
                put(COL_MESSAGE_ID, id)
                put(COL_MESSAGE_SESSION_ID, sessionId)
                put(COL_ROLE, role.name)
                put(COL_CONTENT, content)
                put(COL_CREATED_AT, nowMillis)
            }
            db.insertOrThrow(TABLE_MESSAGES, null, values)
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        return ConversationMessage(id, sessionId, role, content, nowMillis)
    }

    override fun messages(sessionId: String): List<ConversationMessage> = readableDatabase.query(
        TABLE_MESSAGES,
        MESSAGE_COLUMNS,
        "$COL_MESSAGE_SESSION_ID = ?",
        arrayOf(sessionId),
        null,
        null,
        "$COL_CREATED_AT ASC, $COL_MESSAGE_ID ASC",
    ).use { cursor -> buildList { while (cursor.moveToNext()) add(cursor.toMessage()) } }

    private fun android.database.Cursor.toSession() = ConversationSession(
        id = getString(getColumnIndexOrThrow(COL_SESSION_ID)),
        workspaceId = getString(getColumnIndexOrThrow(COL_WORKSPACE_ID)),
        title = getString(getColumnIndexOrThrow(COL_TITLE)),
        createdAtMillis = getLong(getColumnIndexOrThrow(COL_CREATED_AT)),
        updatedAtMillis = getLong(getColumnIndexOrThrow(COL_UPDATED_AT)),
    )

    private fun android.database.Cursor.toMessage() = ConversationMessage(
        id = getString(getColumnIndexOrThrow(COL_MESSAGE_ID)),
        sessionId = getString(getColumnIndexOrThrow(COL_MESSAGE_SESSION_ID)),
        role = MessageRole.valueOf(getString(getColumnIndexOrThrow(COL_ROLE))),
        content = getString(getColumnIndexOrThrow(COL_CONTENT)),
        createdAtMillis = getLong(getColumnIndexOrThrow(COL_CREATED_AT)),
    )

    private companion object {
        const val DATABASE_NAME = "cordiact-conversations.db"
        const val DATABASE_VERSION = 1
        const val TABLE_SESSIONS = "conversation_sessions"
        const val TABLE_MESSAGES = "conversation_messages"
        const val COL_SESSION_ID = "session_id"
        const val COL_WORKSPACE_ID = "workspace_id"
        const val COL_TITLE = "title"
        const val COL_CREATED_AT = "created_at"
        const val COL_UPDATED_AT = "updated_at"
        const val COL_MESSAGE_ID = "message_id"
        const val COL_MESSAGE_SESSION_ID = "session_id"
        const val COL_ROLE = "role"
        const val COL_CONTENT = "content"
        val SESSION_COLUMNS = arrayOf(COL_SESSION_ID, COL_WORKSPACE_ID, COL_TITLE, COL_CREATED_AT, COL_UPDATED_AT)
        val MESSAGE_COLUMNS = arrayOf(COL_MESSAGE_ID, COL_MESSAGE_SESSION_ID, COL_ROLE, COL_CONTENT, COL_CREATED_AT)
    }
}
