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
import com.cordiact.core.conversation.SessionKind
import com.cordiact.core.subagent.AgentRunRecord
import com.cordiact.core.subagent.AgentRunStore
import com.cordiact.core.subagent.ChildRunState
import com.cordiact.core.workspace.DEFAULT_WORKSPACE_ID
import com.cordiact.core.workspace.DEFAULT_WORKSPACE_NAME
import com.cordiact.core.workspace.Workspace
import com.cordiact.core.workspace.WorkspaceStore
import com.cordiact.core.tool.MutableToolPreferenceStore
import java.util.UUID

/** Shared durable source of truth for Workspace and conversation records. */
class AndroidConversationSessionStore(
    context: Context,
    databaseName: String = DATABASE_NAME,
) : SQLiteOpenHelper(context.applicationContext, databaseName, null, DATABASE_VERSION),
    ConversationSessionStore, WorkspaceStore, MutableToolPreferenceStore, AgentRunStore {

    override fun onConfigure(db: SQLiteDatabase) {
        super.onConfigure(db)
        db.setForeignKeyConstraintsEnabled(true)
    }

    override fun onCreate(db: SQLiteDatabase) {
        createWorkspaceSchema(db)
        createSessionSchema(db, TABLE_SESSIONS, withDelegation = true)
        createMessageSchema(db, TABLE_MESSAGES, TABLE_SESSIONS)
        createToolPreferenceSchema(db)
        createPluginSettingsSchema(db)
        createAgentRunSchema(db)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2 && newVersion >= 2) migrateV1ToV2(db)
        if (oldVersion < 3 && newVersion >= 3) migrateV2ToV3(db)
        if (oldVersion < 4 && newVersion >= 4) migrateV3ToV4(db)
        if (oldVersion < 5 && newVersion >= 5) migrateV4ToV5(db)
        if (oldVersion < 6 && newVersion >= 6) migrateV5ToV6(db)
        if (newVersion > DATABASE_VERSION) throw IllegalStateException("Unsupported conversation database version $newVersion")
    }

    /**
     * Adds the Session tree.
     *
     * Columns only, no table rebuild: unlike the v1→v2 migration (which had to change a foreign
     * key), every new column has a default that describes an existing row correctly — every
     * Session that exists before this migration is a root conversation.
     */
    private fun migrateV4ToV5(db: SQLiteDatabase) {
        db.beginTransaction()
        try {
            if (!db.hasColumn(TABLE_SESSIONS, COL_PARENT_SESSION_ID)) {
                db.execSQL(
                    "ALTER TABLE $TABLE_SESSIONS ADD COLUMN $COL_PARENT_SESSION_ID TEXT " +
                        "REFERENCES $TABLE_SESSIONS($COL_SESSION_ID) ON DELETE CASCADE",
                )
            }
            if (!db.hasColumn(TABLE_SESSIONS, COL_SESSION_KIND)) {
                db.execSQL("ALTER TABLE $TABLE_SESSIONS ADD COLUMN $COL_SESSION_KIND TEXT NOT NULL DEFAULT 'USER'")
            }
            if (!db.hasColumn(TABLE_SESSIONS, COL_AGENT_LABEL)) {
                db.execSQL("ALTER TABLE $TABLE_SESSIONS ADD COLUMN $COL_AGENT_LABEL TEXT")
            }
            if (!db.hasColumn(TABLE_SESSIONS, COL_DEPTH)) {
                db.execSQL("ALTER TABLE $TABLE_SESSIONS ADD COLUMN $COL_DEPTH INTEGER NOT NULL DEFAULT 0")
            }
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS conversation_sessions_parent " +
                    "ON $TABLE_SESSIONS($COL_PARENT_SESSION_ID,$COL_CREATED_AT)",
            )
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    private fun migrateV3ToV4(db: SQLiteDatabase) {
        db.beginTransaction()
        try {
            createPluginSettingsSchema(db)
            if (!db.hasColumn(TABLE_MESSAGES, COL_MESSAGE_METADATA)) {
                db.execSQL("ALTER TABLE $TABLE_MESSAGES ADD COLUMN $COL_MESSAGE_METADATA TEXT")
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    private fun SQLiteDatabase.hasColumn(table: String, column: String): Boolean =
        rawQuery("PRAGMA table_info($table)", null).use { cursor ->
            val nameIndex = cursor.getColumnIndexOrThrow("name")
            while (cursor.moveToNext()) {
                if (cursor.getString(nameIndex) == column) return true
            }
            false
        }

    private fun migrateV2ToV3(db: SQLiteDatabase) {
        db.beginTransaction()
        try {
            createToolPreferenceSchema(db)
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    private fun createToolPreferenceSchema(db: SQLiteDatabase) {
        db.execSQL("""CREATE TABLE IF NOT EXISTS $TABLE_TOOL_PREFERENCES (
            $COL_WORKSPACE_ID TEXT NOT NULL REFERENCES $TABLE_WORKSPACES($COL_WORKSPACE_ID) ON DELETE CASCADE,
            $COL_TOOL_ID TEXT NOT NULL,
            $COL_TOOL_ENABLED INTEGER NOT NULL CHECK($COL_TOOL_ENABLED IN (0,1)),
            $COL_TOOL_UPDATED INTEGER NOT NULL,
            PRIMARY KEY($COL_WORKSPACE_ID,$COL_TOOL_ID)
        )""".trimIndent())
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

    private fun createSessionSchema(db: SQLiteDatabase, table: String, withDelegation: Boolean = false) {
        val delegationColumns = if (withDelegation) """
            ,
            $COL_PARENT_SESSION_ID TEXT REFERENCES $TABLE_SESSIONS($COL_SESSION_ID) ON DELETE CASCADE,
            $COL_SESSION_KIND TEXT NOT NULL DEFAULT 'USER',
            $COL_AGENT_LABEL TEXT,
            $COL_DEPTH INTEGER NOT NULL DEFAULT 0
        """.trimIndent() else ""
        db.execSQL(
            """CREATE TABLE $table (
            $COL_SESSION_ID TEXT PRIMARY KEY NOT NULL,
            $COL_WORKSPACE_ID TEXT NOT NULL REFERENCES $TABLE_WORKSPACES($COL_WORKSPACE_ID) ON DELETE RESTRICT,
            $COL_TITLE TEXT NOT NULL,
            $COL_CREATED_AT INTEGER NOT NULL,
            $COL_UPDATED_AT INTEGER NOT NULL$delegationColumns
        )""".trimIndent(),
        )
        if (withDelegation) {
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS conversation_sessions_parent " +
                    "ON $table($COL_PARENT_SESSION_ID,$COL_CREATED_AT)",
            )
        }
    }

    private fun createMessageSchema(db: SQLiteDatabase, table: String, sessionsTable: String) {
        db.execSQL("""CREATE TABLE $table (
            $COL_MESSAGE_ID TEXT PRIMARY KEY NOT NULL,
            $COL_MESSAGE_SESSION_ID TEXT NOT NULL REFERENCES $sessionsTable($COL_SESSION_ID) ON DELETE CASCADE,
            $COL_ROLE TEXT NOT NULL,
            $COL_CONTENT TEXT NOT NULL,
            $COL_CREATED_AT INTEGER NOT NULL,
            $COL_MESSAGE_METADATA TEXT
        )""".trimIndent())
    }

    private fun createPluginSettingsSchema(db: SQLiteDatabase) {
        db.execSQL("""CREATE TABLE IF NOT EXISTS $TABLE_PLUGIN_SETTINGS (
            $COL_WORKSPACE_ID TEXT NOT NULL REFERENCES $TABLE_WORKSPACES($COL_WORKSPACE_ID) ON DELETE CASCADE,
            $COL_PLUGIN_ID TEXT NOT NULL,
            $COL_SETTING_KEY TEXT NOT NULL,
            $COL_SETTING_VALUE TEXT NOT NULL,
            $COL_UPDATED_AT INTEGER NOT NULL,
            PRIMARY KEY($COL_WORKSPACE_ID,$COL_PLUGIN_ID,$COL_SETTING_KEY)
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

    override fun isEnabled(workspaceId: String, toolId: String): Boolean? = readableDatabase.query(
        TABLE_TOOL_PREFERENCES,
        arrayOf(COL_TOOL_ENABLED),
        "$COL_WORKSPACE_ID = ? AND $COL_TOOL_ID = ?",
        arrayOf(workspaceId, toolId), null, null, null,
    ).use { cursor ->
        if (cursor.moveToFirst()) cursor.getInt(0) != 0 else null
    }

    override fun setEnabled(workspaceId: String, toolId: String, enabled: Boolean?) {
        val db = writableDatabase
        val workspaceIsActive = db.query(
            TABLE_WORKSPACES,
            arrayOf(COL_WORKSPACE_ID),
            "$COL_WORKSPACE_ID = ? AND $COL_WORKSPACE_ARCHIVED IS NULL",
            arrayOf(workspaceId),
            null,
            null,
            null,
        ).use { it.moveToFirst() }
        check(workspaceIsActive) { "Workspace does not exist or is archived" }
        if (enabled == null) {
            writableDatabase.delete(
                TABLE_TOOL_PREFERENCES,
                "$COL_WORKSPACE_ID = ? AND $COL_TOOL_ID = ?",
                arrayOf(workspaceId, toolId),
            )
            return
        }
        val values = ContentValues().apply {
            put(COL_WORKSPACE_ID, workspaceId)
            put(COL_TOOL_ID, toolId)
            put(COL_TOOL_ENABLED, if (enabled) 1 else 0)
            put(COL_TOOL_UPDATED, System.currentTimeMillis())
        }
        writableDatabase.insertWithOnConflict(TABLE_TOOL_PREFERENCES, null, values, SQLiteDatabase.CONFLICT_REPLACE)
    }

    /** Plugin-scoped Workspace setting. Secrets must never be stored here. */
    fun pluginSetting(workspaceId: String, pluginId: String, key: String): String? = readableDatabase.query(
        TABLE_PLUGIN_SETTINGS,
        arrayOf(COL_SETTING_VALUE),
        "$COL_WORKSPACE_ID = ? AND $COL_PLUGIN_ID = ? AND $COL_SETTING_KEY = ?",
        arrayOf(workspaceId, pluginId, key),
        null,
        null,
        null,
    ).use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }

    fun putPluginSetting(workspaceId: String, pluginId: String, key: String, value: String?) {
        val db = writableDatabase
        val workspaceIsActive = db.query(
            TABLE_WORKSPACES,
            arrayOf(COL_WORKSPACE_ID),
            "$COL_WORKSPACE_ID = ? AND $COL_WORKSPACE_ARCHIVED IS NULL",
            arrayOf(workspaceId),
            null,
            null,
            null,
        ).use { it.moveToFirst() }
        check(workspaceIsActive) { "Workspace does not exist or is archived" }
        if (value == null) {
            db.delete(
                TABLE_PLUGIN_SETTINGS,
                "$COL_WORKSPACE_ID = ? AND $COL_PLUGIN_ID = ? AND $COL_SETTING_KEY = ?",
                arrayOf(workspaceId, pluginId, key),
            )
            return
        }
        val values = ContentValues().apply {
            put(COL_WORKSPACE_ID, workspaceId)
            put(COL_PLUGIN_ID, pluginId)
            put(COL_SETTING_KEY, key)
            put(COL_SETTING_VALUE, value)
            put(COL_UPDATED_AT, System.currentTimeMillis())
        }
        db.insertWithOnConflict(TABLE_PLUGIN_SETTINGS, null, values, SQLiteDatabase.CONFLICT_REPLACE)
    }

    override fun findSessionWorkspace(id: String): Workspace? = readableDatabase.query(
        TABLE_WORKSPACES, WORKSPACE_COLUMNS, "$COL_WORKSPACE_ID = ? AND $COL_WORKSPACE_ARCHIVED IS NULL", arrayOf(id), null, null, null,
    ).use { if (it.moveToFirst()) it.toWorkspace() else null }

    override fun create(
        id: String,
        workspaceId: String,
        title: String,
        nowMillis: Long,
        parentSessionId: String?,
        kind: SessionKind,
        agentLabel: String?,
        depth: Int,
    ): ConversationSession {
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
                // Null parent for a root conversation; SQLite keeps it NULL rather than "" so
                // the child lookup never matches a root by accident.
                put(COL_PARENT_SESSION_ID, parentSessionId)
                put(COL_SESSION_KIND, kind.name)
                put(COL_AGENT_LABEL, agentLabel)
                put(COL_DEPTH, depth)
            }
            db.insertOrThrow(TABLE_SESSIONS, null, values)
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        return ConversationSession(
            id, workspaceId, title, nowMillis, nowMillis, parentSessionId, kind, agentLabel, depth,
        )
    }

    override fun get(sessionId: String): ConversationSession? = readableDatabase.query(
        TABLE_SESSIONS, SESSION_COLUMNS, "$COL_SESSION_ID = ?", arrayOf(sessionId), null, null, null,
    ).use { c -> if (c.moveToFirst()) c.toSession() else null }

    override fun list(workspaceId: String): List<ConversationSession> = readableDatabase.query(
        TABLE_SESSIONS,
        SESSION_COLUMNS,
        // Root conversations only: a Workspace's session list is what the user thinks of as
        // their conversations, and a fan-out would otherwise bury them under delegated tasks.
        "$COL_WORKSPACE_ID = ? AND $COL_SESSION_KIND = ?",
        arrayOf(workspaceId, SessionKind.USER.name),
        null,
        null,
        "$COL_UPDATED_AT DESC,$COL_SESSION_ID ASC",
    ).use { c -> buildList { while (c.moveToNext()) add(c.toSession()) } }

    override fun listChildren(parentSessionId: String): List<ConversationSession> = readableDatabase.query(
        TABLE_SESSIONS,
        SESSION_COLUMNS,
        "$COL_PARENT_SESSION_ID = ?",
        arrayOf(parentSessionId),
        null,
        null,
        "$COL_CREATED_AT ASC,$COL_SESSION_ID ASC",
    ).use { c -> buildList { while (c.moveToNext()) add(c.toSession()) } }

    override fun append(
        sessionId: String,
        role: MessageRole,
        content: String,
        nowMillis: Long,
        metadataJson: String?,
    ): ConversationMessage {
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
                if (metadataJson != null) put(COL_MESSAGE_METADATA, metadataJson)
            }
            db.insertOrThrow(TABLE_MESSAGES, null, values)
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
        return ConversationMessage(id, sessionId, role, content, nowMillis, metadataJson)
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
        parentSessionId = getColumnIndex(COL_PARENT_SESSION_ID).takeIf { it >= 0 && !isNull(it) }
            ?.let { getString(it) },
        // An unknown or missing value reads as USER: the migration default is USER, and a
        // Session whose kind cannot be parsed must stay visible rather than silently vanish
        // from the list it has always appeared in.
        kind = getColumnIndex(COL_SESSION_KIND).takeIf { it >= 0 && !isNull(it) }
            ?.let { runCatching { SessionKind.valueOf(getString(it)) }.getOrNull() }
            ?: SessionKind.USER,
        agentLabel = getColumnIndex(COL_AGENT_LABEL).takeIf { it >= 0 && !isNull(it) }?.let { getString(it) },
        depth = getColumnIndex(COL_DEPTH).takeIf { it >= 0 }?.let { getInt(it) } ?: 0,
    )

    /**
     * Background agent runs.
     *
     * A separate table rather than columns on the Session: a Session can be driven more than
     * once (send_child_message), and what has to survive a process death is the *run*, not the
     * Session it happened in.
     */
    private fun createAgentRunSchema(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS $TABLE_AGENT_RUNS (
            $COL_RUN_SESSION_ID TEXT PRIMARY KEY NOT NULL,
            $COL_RUN_PARENT_SESSION_ID TEXT NOT NULL
                REFERENCES $TABLE_SESSIONS($COL_SESSION_ID) ON DELETE CASCADE,
            $COL_RUN_LABEL TEXT NOT NULL,
            $COL_RUN_STATE TEXT NOT NULL,
            $COL_RUN_STARTED_AT INTEGER NOT NULL,
            $COL_RUN_DIAGNOSTIC TEXT
        )""".trimIndent(),
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS conversation_agent_runs_parent " +
                "ON $TABLE_AGENT_RUNS($COL_RUN_PARENT_SESSION_ID,$COL_RUN_STARTED_AT)",
        )
    }

    private fun migrateV5ToV6(db: SQLiteDatabase) {
        db.beginTransaction()
        try {
            createAgentRunSchema(db)
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    override fun put(run: AgentRunRecord) {
        val values = ContentValues().apply {
            put(COL_RUN_SESSION_ID, run.sessionId)
            put(COL_RUN_PARENT_SESSION_ID, run.parentSessionId)
            put(COL_RUN_LABEL, run.label)
            put(COL_RUN_STATE, run.state.name)
            put(COL_RUN_STARTED_AT, run.startedAtMillis)
            put(COL_RUN_DIAGNOSTIC, run.diagnostic)
        }
        writableDatabase.insertWithOnConflict(
            TABLE_AGENT_RUNS, null, values, SQLiteDatabase.CONFLICT_REPLACE,
        )
    }

    override fun updateState(sessionId: String, state: ChildRunState, diagnostic: String?) {
        val values = ContentValues().apply {
            put(COL_RUN_STATE, state.name)
            put(COL_RUN_DIAGNOSTIC, diagnostic)
        }
        writableDatabase.update(
            TABLE_AGENT_RUNS, values, "$COL_RUN_SESSION_ID = ?", arrayOf(sessionId),
        )
    }

    override fun runOf(sessionId: String): AgentRunRecord? = readableDatabase.query(
        TABLE_AGENT_RUNS, RUN_COLUMNS, "$COL_RUN_SESSION_ID = ?", arrayOf(sessionId),
        null, null, null,
    ).use { c -> if (c.moveToNext()) c.toAgentRun() else null }

    override fun runsOf(parentSessionId: String): List<AgentRunRecord> = readableDatabase.query(
        TABLE_AGENT_RUNS, RUN_COLUMNS, "$COL_RUN_PARENT_SESSION_ID = ?", arrayOf(parentSessionId),
        null, null, "$COL_RUN_STARTED_AT ASC",
    ).use { c -> buildList { while (c.moveToNext()) add(c.toAgentRun()) } }

    /**
     * Anything still RUNNING belongs to a process that is gone.
     *
     * There is exactly one app process, so at startup no run can legitimately be running yet.
     * Without this a child killed with the app would read as RUNNING forever and the parent would
     * wait on it indefinitely.
     */
    override fun abortRunning(): List<AgentRunRecord> {
        val db = writableDatabase
        db.beginTransaction()
        try {
            val running = db.query(
                TABLE_AGENT_RUNS, RUN_COLUMNS, "$COL_RUN_STATE = ?", arrayOf(ChildRunState.RUNNING.name),
                null, null, "$COL_RUN_STARTED_AT ASC",
            ).use { c -> buildList { while (c.moveToNext()) add(c.toAgentRun()) } }
            running.forEach { record ->
                val values = ContentValues().apply {
                    put(COL_RUN_STATE, ChildRunState.ABORTED.name)
                    put(
                        COL_RUN_DIAGNOSTIC,
                        record.diagnostic ?: "the app was closed while this task was running",
                    )
                }
                db.update(
                    TABLE_AGENT_RUNS, values, "$COL_RUN_SESSION_ID = ?", arrayOf(record.sessionId),
                )
            }
            db.setTransactionSuccessful()
            return running.map { it.copy(state = ChildRunState.ABORTED) }
        } finally {
            db.endTransaction()
        }
    }

    private fun Cursor.toAgentRun() = AgentRunRecord(
        sessionId = getString(getColumnIndexOrThrow(COL_RUN_SESSION_ID)),
        parentSessionId = getString(getColumnIndexOrThrow(COL_RUN_PARENT_SESSION_ID)),
        label = getString(getColumnIndexOrThrow(COL_RUN_LABEL)),
        state = runCatching {
            ChildRunState.valueOf(getString(getColumnIndexOrThrow(COL_RUN_STATE)))
        }.getOrDefault(ChildRunState.UNTRACKED),
        startedAtMillis = getLong(getColumnIndexOrThrow(COL_RUN_STARTED_AT)),
        diagnostic = getColumnIndex(COL_RUN_DIAGNOSTIC)
            .takeIf { it >= 0 && !isNull(it) }
            ?.let { getString(it) },
    )

    private fun Cursor.toMessage(): ConversationMessage {
        val metadataIndex = getColumnIndex(COL_MESSAGE_METADATA)
        return ConversationMessage(
            id = getString(getColumnIndexOrThrow(COL_MESSAGE_ID)),
            sessionId = getString(getColumnIndexOrThrow(COL_MESSAGE_SESSION_ID)),
            role = MessageRole.valueOf(getString(getColumnIndexOrThrow(COL_ROLE))),
            content = getString(getColumnIndexOrThrow(COL_CONTENT)),
            createdAtMillis = getLong(getColumnIndexOrThrow(COL_CREATED_AT)),
            metadataJson = if (metadataIndex >= 0 && !isNull(metadataIndex)) getString(metadataIndex) else null,
        )
    }

    private fun relativeRoot(id: String): String = "workspaces/${UUID.nameUUIDFromBytes(id.toByteArray())}"

    private companion object {
        const val DATABASE_NAME = "cordiact-conversations.db"
        const val DATABASE_VERSION = 6
        const val TABLE_WORKSPACES = "workspaces"
        const val TABLE_TOOL_PREFERENCES = "workspace_tool_preferences"
        const val TABLE_PLUGIN_SETTINGS = "workspace_plugin_settings"
        const val COL_PLUGIN_ID = "plugin_id"
        const val COL_SETTING_KEY = "setting_key"
        const val COL_SETTING_VALUE = "setting_value"
        const val COL_MESSAGE_METADATA = "metadata_json"
        const val COL_TOOL_ID = "tool_id"
        const val COL_TOOL_ENABLED = "enabled"
        const val COL_TOOL_UPDATED = "updated_at"
        const val TABLE_WORKSPACE_STATE = "workspace_state"
        const val TABLE_SESSIONS = "conversation_sessions"
        const val TABLE_SESSIONS_NEW = "conversation_sessions_v2"
        const val TABLE_AGENT_RUNS = "conversation_agent_runs"
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
        const val COL_PARENT_SESSION_ID = "parent_session_id"
        const val COL_SESSION_KIND = "session_kind"
        const val COL_AGENT_LABEL = "agent_label"
        const val COL_DEPTH = "delegation_depth"
        val WORKSPACE_COLUMNS = arrayOf(COL_WORKSPACE_ID,COL_WORKSPACE_NAME,COL_WORKSPACE_CREATED,COL_WORKSPACE_UPDATED,COL_WORKSPACE_ARCHIVED)
        val SESSION_COLUMNS = arrayOf(
            COL_SESSION_ID,COL_WORKSPACE_ID,COL_TITLE,COL_CREATED_AT,COL_UPDATED_AT,
            COL_PARENT_SESSION_ID,COL_SESSION_KIND,COL_AGENT_LABEL,COL_DEPTH,
        )
        const val COL_RUN_SESSION_ID = "session_id"
        const val COL_RUN_PARENT_SESSION_ID = "parent_session_id"
        const val COL_RUN_LABEL = "label"
        const val COL_RUN_STATE = "state"
        const val COL_RUN_STARTED_AT = "started_at"
        const val COL_RUN_DIAGNOSTIC = "diagnostic"
        const val COL_RUN_SESSION_ID = "session_id"
        const val COL_RUN_PARENT_SESSION_ID = "parent_session_id"
        const val COL_RUN_LABEL = "label"
        const val COL_RUN_STATE = "state"
        const val COL_RUN_STARTED_AT = "started_at"
        const val COL_RUN_DIAGNOSTIC = "diagnostic"
        val MESSAGE_COLUMNS = arrayOf(COL_MESSAGE_ID,COL_MESSAGE_SESSION_ID,COL_ROLE,COL_CONTENT,COL_CREATED_AT,COL_MESSAGE_METADATA)
        val RUN_COLUMNS = arrayOf(
            COL_RUN_SESSION_ID,COL_RUN_PARENT_SESSION_ID,COL_RUN_LABEL,
            COL_RUN_STATE,COL_RUN_STARTED_AT,COL_RUN_DIAGNOSTIC,
        )
    }
}
