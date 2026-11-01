package com.cordiact.app.conversation

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.cordiact.core.conversation.ConversationMessage
import com.cordiact.core.conversation.ConversationSession
import com.cordiact.core.conversation.ConversationSessionStore
import com.cordiact.core.conversation.MessageRole
import com.cordiact.core.workspace.DEFAULT_WORKSPACE_ID
import com.cordiact.core.workspace.DEFAULT_WORKSPACE_NAME
import com.cordiact.core.workspace.Workspace
import com.cordiact.core.workspace.WorkspaceStore
import java.util.UUID

/** Shared durable source of truth for Workspace and conversation records. */
class AndroidConversationSessionStore(
    context: Context,
    databaseName: String = DATABASE_NAME,
) : SQLiteOpenHelper(context.applicationContext, databaseName, null, DATABASE_VERSION), ConversationSessionStore, WorkspaceStore {

    override fun onConfigure(db: SQLiteDatabase) {
        super.onConfigure(db)
        db.setForeignKeyConstraintsEnabled(true)
    }

    override fun onCreate(db: SQLiteDatabase) {
        createWorkspaceSchema(db)
        createSessionSchema(db, TABLE_SESSIONS)
        createMessageSchema(db, TABLE_MESSAGES, TABLE_SESSIONS)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2 && newVersion >= 2) migrateV1ToV2(db)
        if (newVersion > DATABASE_VERSION) throw IllegalStateException("Unsupported conversation database version $newVersion")
    }

    private fun migrateV1ToV2(db: SQLiteDatabase) {
        db.beginTransaction()
        try {
            db.execSQL("DROP INDEX IF EXISTS conversation_sessions_workspace")
            db.execSQL("DROP INDEX IF EXISTS conversation_messages_session")
            createWorkspaceSchema(db)

            val legacyIds = db.rawQuery("SELECT DISTINCT $COL_WORKSPACE_ID FROM $TABLE_SESSIONS", null).use { cursor ->
                buildList { while (cursor.moveToNext()) add(cursor.getString(0).orEmpty().ifBlank { DEFAULT_WORKSPACE_ID }) }
            }
            val now = System.currentTimeMillis()
            if (legacyIds.isEmpty() || DEFAULT_WORKSPACE_ID in legacyIds) {
                insertWorkspaceIfMissing(db, DEFAULT_WORKSPACE_ID, DEFAULT_WORKSPACE_NAME, relativeRoot(DEFAULT_WORKSPACE_ID), now)
            }
            legacyIds.filter { it != DEFAULT_WORKSPACE_ID }.forEach { legacyId ->
                insertWorkspaceIfMissing(db, legacyId, "Migrated workspace", relativeRoot(legacyId), now)
            }

            createSessionSchema(db, TABLE_SESSIONS_NEW)
            db.execSQL("""INSERT INTO $TABLE_SESSIONS_NEW ($COL_SESSION_ID,$COL_WORKSPACE_ID,$COL_TITLE,$COL_CREATED_AT,$COL_UPDATED_AT)
                SELECT $COL_SESSION_ID, CASE WHEN trim($COL_WORKSPACE_ID) = '' THEN ? ELSE $COL_WORKSPACE_ID END,
                $COL_TITLE,$COL_CREATED_AT,$COL_UPDATED_AT FROM $TABLE_SESSIONS""".trimIndent(), arrayOf(DEFAULT_WORKSPACE_ID))
            createMessageSchema(db, TABLE_MESSAGES_NEW, TABLE_SESSIONS_NEW)
            db.execSQL("""INSERT INTO $TABLE_MESSAGES_NEW ($COL_MESSAGE_ID,$COL_MESSAGE_SESSION_ID,$COL_ROLE,$COL_CONTENT,$COL_CREATED_AT)
                SELECT $COL_MESSAGE_ID,$COL_MESSAGE_SESSION_ID,$COL_ROLE,$COL_CONTENT,$COL_CREATED_AT FROM $TABLE_MESSAGES""".trimIndent())
            db.execSQL("DROP TABLE $TABLE_MESSAGES")
            db.execSQL("DROP TABLE $TABLE_SESSIONS")
            db.execSQL("ALTER TABLE $TABLE_SESSIONS_NEW RENAME TO $TABLE_SESSIONS")
            db.execSQL("ALTER TABLE $TABLE_MESSAGES_NEW RENAME TO $TABLE_MESSAGES")
            db.execSQL("CREATE INDEX conversation_sessions_workspace ON $TABLE_SESSIONS($COL_WORKSPACE_ID,$COL_UPDATED_AT DESC)")
            db.execSQL("CREATE INDEX conversation_messages_session ON $TABLE_MESSAGES($COL_MESSAGE_SESSION_ID,$COL_CREATED_AT,$COL_MESSAGE_ID)")
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    private fun createWorkspaceSchema(db: SQLiteDatabase) {
        db.execSQL("""CREATE TABLE IF NOT EXISTS $TABLE_WORKSPACES (
            $COL_WORKSPACE_ID TEXT PRIMARY KEY NOT NULL,
            $COL_WORKSPACE_NAME TEXT NOT NULL,
            $COL_WORKSPACE_ROOT TEXT NOT NULL UNIQUE,
            $COL_WORKSPACE_CREATED INTEGER NOT NULL,
            $COL_WORKSPACE_UPDATED INTEGER NOT NULL,
            $COL_WORKSPACE_ARCHIVED INTEGER
        )""".trimIndent())
        db.execSQL("""CREATE TABLE IF NOT EXISTS $TABLE_WORKSPACE_STATE (
            singleton INTEGER PRIMARY KEY CHECK(singleton = 1),
            active_workspace_id TEXT NOT NULL REFERENCES $TABLE_WORKSPACES($COL_WORKSPACE_ID) ON DELETE RESTRICT
        )""".trimIndent())
    }

    private fun createSessionSchema(db: SQLiteDatabase, table: String) {
        db.execSQL("""CREATE TABLE $table (
            $COL_SESSION_ID TEXT PRIMARY KEY NOT NULL,
            $COL_WORKSPACE_ID TEXT NOT NULL REFERENCES $TABLE_WORKSPACES($COL_WORKSPACE_ID) ON DELETE RESTRICT,
            $COL_TITLE TEXT NOT NULL,
            $COL_CREATED_AT INTEGER NOT NULL,
            $COL_UPDATED_AT INTEGER NOT NULL
        )""".trimIndent())
    }

    private fun createMessageSchema(db: SQLiteDatabase, table: String, sessionsTable: String) {
        db.execSQL("""CREATE TABLE $table (
            $COL_MESSAGE_ID TEXT PRIMARY KEY NOT NULL,
            $COL_MESSAGE_SESSION_ID TEXT NOT NULL REFERENCES $sessionsTable($COL_SESSION_ID) ON DELETE CASCADE,
            $COL_ROLE TEXT NOT NULL,
            $COL_CONTENT TEXT NOT NULL,
            $COL_CREATED_AT INTEGER NOT NULL
        )""".trimIndent())
    }

    private fun insertWorkspaceIfMissing(db: SQLiteDatabase, id: String, name: String, root: String, now: Long) {
        val values = ContentValues().apply {
            put(COL_WORKSPACE_ID, id); put(COL_WORKSPACE_NAME, name); put(COL_WORKSPACE_ROOT, root)
            put(COL_WORKSPACE_CREATED, now); put(COL_WORKSPACE_UPDATED, now)
        }
        db.insertWithOnConflict(TABLE_WORKSPACES, null, values, SQLiteDatabase.CONFLICT_IGNORE)
    }

    override fun ensureDefault(nowMillis: Long): Workspace {
        val db = writableDatabase
        db.beginTransaction()
        try {
            insertWorkspaceIfMissing(db, DEFAULT_WORKSPACE_ID, DEFAULT_WORKSPACE_NAME, relativeRoot(DEFAULT_WORKSPACE_ID), nowMillis)
            db.execSQL("INSERT OR IGNORE INTO $TABLE_WORKSPACE_STATE(singleton,active_workspace_id) VALUES(1,?)", arrayOf(DEFAULT_WORKSPACE_ID))
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
        return requireNotNull(getWorkspace(DEFAULT_WORKSPACE_ID))
    }

    override fun createWorkspace(id: String, name: String, rootRelativePath: String, nowMillis: Long): Workspace {
        require(id.isNotBlank() && name.isNotBlank())
        require(rootRelativePath == relativeRoot(id)) { "Workspace storage roots are host-assigned" }
        val values = ContentValues().apply {
            put(COL_WORKSPACE_ID, id); put(COL_WORKSPACE_NAME, name.trim()); put(COL_WORKSPACE_ROOT, rootRelativePath)
            put(COL_WORKSPACE_CREATED, nowMillis); put(COL_WORKSPACE_UPDATED, nowMillis)
        }
        writableDatabase.insertOrThrow(TABLE_WORKSPACES, null, values)
        return requireNotNull(getWorkspace(id))
    }

    override fun getWorkspace(id: String): Workspace? = readableDatabase.query(
        TABLE_WORKSPACES, WORKSPACE_COLUMNS, "$COL_WORKSPACE_ID = ?", arrayOf(id), null, null, null,
    ).use { if (it.moveToFirst()) it.toWorkspace() else null }

    override fun listWorkspaces(includeArchived: Boolean): List<Workspace> {
        val where = if (includeArchived) null else "$COL_WORKSPACE_ARCHIVED IS NULL"
        return readableDatabase.query(TABLE_WORKSPACES, WORKSPACE_COLUMNS, where, null, null, null,
            "$COL_WORKSPACE_UPDATED DESC,$COL_WORKSPACE_ID ASC").use { c -> buildList { while (c.moveToNext()) add(c.toWorkspace()) } }
    }

    override fun renameWorkspace(id: String, name: String, nowMillis: Long): Workspace {
        require(name.isNotBlank())
        val values = ContentValues().apply { put(COL_WORKSPACE_NAME, name.trim()); put(COL_WORKSPACE_UPDATED, nowMillis) }
        check(writableDatabase.update(TABLE_WORKSPACES, values, "$COL_WORKSPACE_ID = ? AND $COL_WORKSPACE_ARCHIVED IS NULL", arrayOf(id)) == 1) {
            "Workspace does not exist or is archived"
        }
        return requireNotNull(getWorkspace(id))
    }

    override fun archiveWorkspace(id: String, nowMillis: Long): Workspace {
        require(id != DEFAULT_WORKSPACE_ID) { "Default workspace cannot be archived" }
        val db = writableDatabase
        db.beginTransaction()
        try {
            val active = activeWorkspace()
            if (active?.id == id) {
                val replacement = listWorkspaces(false).firstOrNull { it.id != id }
                if (replacement != null) {
                    db.execSQL("INSERT OR REPLACE INTO $TABLE_WORKSPACE_STATE(singleton,active_workspace_id) VALUES(1,?)", arrayOf(replacement.id))
                } else {
                    insertWorkspaceIfMissing(db, DEFAULT_WORKSPACE_ID, DEFAULT_WORKSPACE_NAME, relativeRoot(DEFAULT_WORKSPACE_ID), nowMillis)
                    val defaultValues = ContentValues().apply {
                        put(COL_WORKSPACE_ID, DEFAULT_WORKSPACE_ID)
                        put(COL_WORKSPACE_NAME, DEFAULT_WORKSPACE_NAME)
                        put(COL_WORKSPACE_ROOT, relativeRoot(DEFAULT_WORKSPACE_ID))
                        put(COL_WORKSPACE_CREATED, nowMillis)
                        put(COL_WORKSPACE_UPDATED, nowMillis)
                    }
                    db.insertWithOnConflict(TABLE_WORKSPACES, null, defaultValues, SQLiteDatabase.CONFLICT_IGNORE)
                    db.execSQL("INSERT OR REPLACE INTO $TABLE_WORKSPACE_STATE(singleton,active_workspace_id) VALUES(1,?)", arrayOf(DEFAULT_WORKSPACE_ID))
                }
            }
            val values = ContentValues().apply { put(COL_WORKSPACE_ARCHIVED, nowMillis); put(COL_WORKSPACE_UPDATED, nowMillis) }
            check(db.update(TABLE_WORKSPACES, values, "$COL_WORKSPACE_ID = ? AND $COL_WORKSPACE_ARCHIVED IS NULL", arrayOf(id)) == 1) {
                "Workspace does not exist or is already archived"
            }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
        return requireNotNull(getWorkspace(id))
    }

    override fun restoreWorkspace(id: String, nowMillis: Long): Workspace {
        val values = ContentValues().apply { putNull(COL_WORKSPACE_ARCHIVED); put(COL_WORKSPACE_UPDATED, nowMillis) }
        check(writableDatabase.update(TABLE_WORKSPACES, values, "$COL_WORKSPACE_ID = ? AND $COL_WORKSPACE_ARCHIVED IS NOT NULL", arrayOf(id)) == 1) {
            "Workspace does not exist or is not archived"
        }
        return requireNotNull(getWorkspace(id))
    }

    override fun selectWorkspace(id: String, nowMillis: Long): Workspace {
        val db = writableDatabase
        db.beginTransaction()
        try {
            check(db.query(TABLE_WORKSPACES, arrayOf(COL_WORKSPACE_ID), "$COL_WORKSPACE_ID = ? AND $COL_WORKSPACE_ARCHIVED IS NULL", arrayOf(id), null, null, null).use { it.moveToFirst() }) {
                "Workspace does not exist or is archived"
            }
            db.execSQL("INSERT OR REPLACE INTO $TABLE_WORKSPACE_STATE(singleton,active_workspace_id) VALUES(1,?)", arrayOf(id))
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
        return requireNotNull(getWorkspace(id))
    }

    override fun activeWorkspace(): Workspace? = readableDatabase.rawQuery(
        "SELECT w.$COL_WORKSPACE_ID,w.$COL_WORKSPACE_NAME,w.$COL_WORKSPACE_CREATED,w.$COL_WORKSPACE_UPDATED,w.$COL_WORKSPACE_ARCHIVED " +
            "FROM $TABLE_WORKSPACES w JOIN $TABLE_WORKSPACE_STATE s ON w.$COL_WORKSPACE_ID=s.active_workspace_id WHERE s.singleton=1", null,
    ).use { if (it.moveToFirst()) it.toWorkspace() else null }

    override fun rootRelativePath(id: String): String? = readableDatabase.query(
        TABLE_WORKSPACES, arrayOf(COL_WORKSPACE_ROOT), "$COL_WORKSPACE_ID = ?", arrayOf(id), null, null, null,
    ).use { if (it.moveToFirst()) it.getString(0) else null }

    override fun findSessionWorkspace(id: String): Workspace? = readableDatabase.query(
        TABLE_WORKSPACES, WORKSPACE_COLUMNS, "$COL_WORKSPACE_ID = ? AND $COL_WORKSPACE_ARCHIVED IS NULL", arrayOf(id), null, null, null,
    ).use { if (it.moveToFirst()) it.toWorkspace() else null }

    override fun create(id: String, workspaceId: String, title: String, nowMillis: Long): ConversationSession {
        require(id.isNotBlank() && workspaceId.isNotBlank() && title.isNotBlank())
        val db = writableDatabase
        db.beginTransaction()
        try {
            val exists = db.query(
                TABLE_WORKSPACES,
                arrayOf(COL_WORKSPACE_ID),
                "$COL_WORKSPACE_ID = ? AND $COL_WORKSPACE_ARCHIVED IS NULL",
                arrayOf(workspaceId),
                null,
                null,
                null,
            ).use { it.moveToFirst() }
            check(exists) { "Workspace '$workspaceId' does not exist or is archived" }
            val values = ContentValues().apply {
                put(COL_SESSION_ID, id)
                put(COL_WORKSPACE_ID, workspaceId)
                put(COL_TITLE, title)
                put(COL_CREATED_AT, nowMillis)
                put(COL_UPDATED_AT, nowMillis)
            }
            db.insertOrThrow(TABLE_SESSIONS, null, values)
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        return ConversationSession(id, workspaceId, title, nowMillis, nowMillis)
    }

    override fun get(sessionId: String): ConversationSession? = readableDatabase.query(
        TABLE_SESSIONS, SESSION_COLUMNS, "$COL_SESSION_ID = ?", arrayOf(sessionId), null, null, null,
    ).use { c -> if (c.moveToFirst()) c.toSession() else null }

    override fun list(workspaceId: String): List<ConversationSession> = readableDatabase.query(
        TABLE_SESSIONS, SESSION_COLUMNS, "$COL_WORKSPACE_ID = ?", arrayOf(workspaceId), null, null,
        "$COL_UPDATED_AT DESC,$COL_SESSION_ID ASC",
    ).use { c -> buildList { while (c.moveToNext()) add(c.toSession()) } }

    override fun append(sessionId: String, role: MessageRole, content: String, nowMillis: Long): ConversationMessage {
        require(content.isNotBlank())
        val id = UUID.randomUUID().toString()
        val db = writableDatabase
        db.beginTransaction()
        try {
            val updated = ContentValues().apply { put(COL_UPDATED_AT, nowMillis) }
            check(db.update(TABLE_SESSIONS, updated, "$COL_SESSION_ID = ?", arrayOf(sessionId)) == 1) { "Conversation session does not exist" }
            val values = ContentValues().apply {
                put(COL_MESSAGE_ID, id); put(COL_MESSAGE_SESSION_ID, sessionId); put(COL_ROLE, role.name)
                put(COL_CONTENT, content); put(COL_CREATED_AT, nowMillis)
            }
            db.insertOrThrow(TABLE_MESSAGES, null, values)
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
        return ConversationMessage(id, sessionId, role, content, nowMillis)
    }

    override fun messages(sessionId: String): List<ConversationMessage> = readableDatabase.query(
        TABLE_MESSAGES, MESSAGE_COLUMNS, "$COL_MESSAGE_SESSION_ID = ?", arrayOf(sessionId), null, null,
        "$COL_CREATED_AT ASC,$COL_MESSAGE_ID ASC",
    ).use { c -> buildList { while (c.moveToNext()) add(c.toMessage()) } }

    private fun Cursor.toWorkspace() = Workspace(
        id = getString(getColumnIndexOrThrow(COL_WORKSPACE_ID)),
        name = getString(getColumnIndexOrThrow(COL_WORKSPACE_NAME)),
        createdAtMillis = getLong(getColumnIndexOrThrow(COL_WORKSPACE_CREATED)),
        updatedAtMillis = getLong(getColumnIndexOrThrow(COL_WORKSPACE_UPDATED)),
        archivedAtMillis = if (isNull(getColumnIndexOrThrow(COL_WORKSPACE_ARCHIVED))) null else getLong(getColumnIndexOrThrow(COL_WORKSPACE_ARCHIVED)),
    )

    private fun Cursor.toSession() = ConversationSession(
        id = getString(getColumnIndexOrThrow(COL_SESSION_ID)), workspaceId = getString(getColumnIndexOrThrow(COL_WORKSPACE_ID)),
        title = getString(getColumnIndexOrThrow(COL_TITLE)), createdAtMillis = getLong(getColumnIndexOrThrow(COL_CREATED_AT)),
        updatedAtMillis = getLong(getColumnIndexOrThrow(COL_UPDATED_AT)),
    )

    private fun Cursor.toMessage() = ConversationMessage(
        id = getString(getColumnIndexOrThrow(COL_MESSAGE_ID)), sessionId = getString(getColumnIndexOrThrow(COL_MESSAGE_SESSION_ID)),
        role = MessageRole.valueOf(getString(getColumnIndexOrThrow(COL_ROLE))), content = getString(getColumnIndexOrThrow(COL_CONTENT)),
        createdAtMillis = getLong(getColumnIndexOrThrow(COL_CREATED_AT)),
    )

    private fun relativeRoot(id: String): String = "workspaces/${UUID.nameUUIDFromBytes(id.toByteArray())}"

    private companion object {
        const val DATABASE_NAME = "cordiact-conversations.db"
        const val DATABASE_VERSION = 2
        const val TABLE_WORKSPACES = "workspaces"
        const val TABLE_WORKSPACE_STATE = "workspace_state"
        const val TABLE_SESSIONS = "conversation_sessions"
        const val TABLE_SESSIONS_NEW = "conversation_sessions_v2"
        const val TABLE_MESSAGES = "conversation_messages"
        const val TABLE_MESSAGES_NEW = "conversation_messages_v2"
        const val COL_SESSION_ID = "session_id"
        const val COL_WORKSPACE_ID = "workspace_id"
        const val COL_WORKSPACE_NAME = "name"
        const val COL_WORKSPACE_ROOT = "root_relative_path"
        const val COL_WORKSPACE_CREATED = "created_at"
        const val COL_WORKSPACE_UPDATED = "updated_at"
        const val COL_WORKSPACE_ARCHIVED = "archived_at"
        const val COL_TITLE = "title"
        const val COL_CREATED_AT = "created_at"
        const val COL_UPDATED_AT = "updated_at"
        const val COL_MESSAGE_ID = "message_id"
        const val COL_MESSAGE_SESSION_ID = "session_id"
        const val COL_ROLE = "role"
        const val COL_CONTENT = "content"
        val WORKSPACE_COLUMNS = arrayOf(COL_WORKSPACE_ID,COL_WORKSPACE_NAME,COL_WORKSPACE_CREATED,COL_WORKSPACE_UPDATED,COL_WORKSPACE_ARCHIVED)
        val SESSION_COLUMNS = arrayOf(COL_SESSION_ID,COL_WORKSPACE_ID,COL_TITLE,COL_CREATED_AT,COL_UPDATED_AT)
        val MESSAGE_COLUMNS = arrayOf(COL_MESSAGE_ID,COL_MESSAGE_SESSION_ID,COL_ROLE,COL_CONTENT,COL_CREATED_AT)
    }
}
