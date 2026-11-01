package com.cordiact.app.plugin

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.cordiact.core.plugin.PluginCapabilities
import com.cordiact.core.plugin.PluginOrigin
import com.cordiact.core.plugin.PluginRecord
import com.cordiact.core.plugin.PluginRecordStore
import com.cordiact.core.plugin.PluginSpec
import com.cordiact.core.plugin.PluginState
import com.cordiact.core.plugin.PluginTarget
import org.json.JSONArray

/**
 * Durable transactional store for plugin lifecycle records and app-level user-consent grants.
 * Android OS permissions are still checked separately by the native capability/policy gate.
 */
class AndroidPluginLifecycleStore(
    context: Context,
    databaseName: String = DEFAULT_DATABASE_NAME,
) : SQLiteOpenHelper(context.applicationContext, databaseName, null, DATABASE_VERSION),
    PluginRecordStore,
    PluginCapabilities {

    override fun onConfigure(db: SQLiteDatabase) {
        super.onConfigure(db)
        db.setForeignKeyConstraintsEnabled(true)
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE $TABLE_RECORDS (
                $COL_ID TEXT PRIMARY KEY NOT NULL,
                $COL_VERSION TEXT NOT NULL,
                $COL_TARGET TEXT NOT NULL,
                $COL_CAPABILITIES TEXT NOT NULL,
                $COL_DEPENDENCIES TEXT NOT NULL DEFAULT '[]',
                $COL_ORIGIN TEXT NOT NULL,
                $COL_STATE TEXT NOT NULL,
                $COL_ERROR TEXT
            )""".trimIndent(),
        )
        db.execSQL(
            """CREATE TABLE $TABLE_RESOURCES (
                $COL_RESOURCE_PLUGIN_ID TEXT NOT NULL,
                $COL_RESOURCE_KIND TEXT NOT NULL,
                $COL_RESOURCE_KEY TEXT NOT NULL,
                $COL_RESOURCE_PAYLOAD TEXT NOT NULL,
                PRIMARY KEY ($COL_RESOURCE_PLUGIN_ID, $COL_RESOURCE_KIND, $COL_RESOURCE_KEY),
                FOREIGN KEY ($COL_RESOURCE_PLUGIN_ID) REFERENCES $TABLE_RECORDS($COL_ID) ON DELETE CASCADE
            )""".trimIndent(),
        )
        db.execSQL("CREATE INDEX plugin_resources_owner ON $TABLE_RESOURCES($COL_RESOURCE_PLUGIN_ID)")
        db.execSQL(
            """CREATE TABLE $TABLE_GRANTS (
                $COL_GRANT_PLUGIN_ID TEXT NOT NULL,
                $COL_GRANT_CAPABILITY TEXT NOT NULL,
                $COL_CONSENTED INTEGER NOT NULL,
                $COL_ACTIVE INTEGER NOT NULL,
                PRIMARY KEY ($COL_GRANT_PLUGIN_ID, $COL_GRANT_CAPABILITY),
                FOREIGN KEY ($COL_GRANT_PLUGIN_ID) REFERENCES $TABLE_RECORDS($COL_ID) ON DELETE CASCADE
            )""".trimIndent(),
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2 && newVersion >= 2) {
            db.execSQL("ALTER TABLE $TABLE_RECORDS ADD COLUMN $COL_DEPENDENCIES TEXT NOT NULL DEFAULT '[]'")
            db.execSQL(
                "UPDATE $TABLE_RECORDS SET $COL_DEPENDENCIES = ? WHERE $COL_ID = ?",
                arrayOf("""["core.workspace-manager"]""", "core.session-manager"),
            )
        }
        if (newVersion > DATABASE_VERSION) throw IllegalStateException("Unsupported plugin lifecycle database version $newVersion")
    }

    override fun get(id: String): PluginRecord? = readableDatabase.query(
        TABLE_RECORDS,
        RECORD_COLUMNS,
        "$COL_ID = ?",
        arrayOf(id),
        null,
        null,
        null,
    ).use { cursor -> if (cursor.moveToFirst()) cursor.toRecord() else null }

    override fun all(): List<PluginRecord> = readableDatabase.query(
        TABLE_RECORDS,
        RECORD_COLUMNS,
        null,
        null,
        null,
        null,
        "$COL_ID ASC",
    ).use { cursor -> buildList { while (cursor.moveToNext()) add(cursor.toRecord()) } }

    override fun put(record: PluginRecord) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            val previousVersion = db.query(
                TABLE_RECORDS,
                arrayOf(COL_VERSION),
                "$COL_ID = ?",
                arrayOf(record.spec.id),
                null,
                null,
                null,
            ).use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }

            val values = ContentValues().apply {
                put(COL_ID, record.spec.id)
                put(COL_VERSION, record.spec.version)
                put(COL_TARGET, record.spec.target.name)
                put(COL_CAPABILITIES, JSONArray(record.spec.requestedCapabilities.sorted()).toString())
                put(COL_DEPENDENCIES, JSONArray(record.spec.dependencies.sorted()).toString())
                put(COL_ORIGIN, record.origin.name)
                put(COL_STATE, record.state.name)
                if (record.lastError == null) putNull(COL_ERROR) else put(COL_ERROR, record.lastError)
            }
            // UPDATE in place: SQLite CONFLICT_REPLACE deletes the parent row first, which would
            // cascade-delete consent rows on every lifecycle state transition.
            val updated = db.update(TABLE_RECORDS, values, "$COL_ID = ?", arrayOf(record.spec.id))
            if (updated == 0) db.insertOrThrow(TABLE_RECORDS, null, values)

            // Consent must not silently carry over to a different plugin version or removed capability.
            if (previousVersion != null && previousVersion != record.spec.version) {
                db.delete(TABLE_GRANTS, "$COL_GRANT_PLUGIN_ID = ?", arrayOf(record.spec.id))
            } else {
                val allowed = record.spec.requestedCapabilities
                val existingCapabilities = db.query(
                    TABLE_GRANTS,
                    arrayOf(COL_GRANT_CAPABILITY),
                    "$COL_GRANT_PLUGIN_ID = ?",
                    arrayOf(record.spec.id),
                    null,
                    null,
                    null,
                ).use { cursor -> buildList { while (cursor.moveToNext()) add(cursor.getString(0)) } }
                existingCapabilities.filterNot { it in allowed }.forEach { capability ->
                    db.delete(
                        TABLE_GRANTS,
                        "$COL_GRANT_PLUGIN_ID = ? AND $COL_GRANT_CAPABILITY = ?",
                        arrayOf(record.spec.id, capability),
                    )
                }
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    override fun remove(id: String) {
        writableDatabase.delete(TABLE_RECORDS, "$COL_ID = ?", arrayOf(id))
    }

    /**
     * Persist cleanup intent before creating an Android/host resource. The host must bind pluginId
     * from the executing plugin context; do not expose this raw method to plugin code.
     */
    fun recordOwnedResource(pluginId: String, kind: String, key: String, payload: String) {
        require(pluginId.matches(PLUGIN_ID_PATTERN)) { "Invalid plugin id '$pluginId'" }
        require(kind.matches(RESOURCE_KIND_PATTERN)) { "Invalid plugin resource kind '$kind'" }
        require(key.isNotBlank() && key.length <= 512) { "Invalid plugin resource key" }
        require(payload.length <= MAX_RESOURCE_PAYLOAD) { "Plugin resource payload is too large" }
        val db = writableDatabase
        val record = queryRecord(db, pluginId) ?: throw IllegalArgumentException("Plugin '$pluginId' is not registered")
        require(record.state in setOf(PluginState.ENABLING, PluginState.ENABLED)) {
            "Cannot create owned resources while plugin '$pluginId' is ${record.state}"
        }
        val values = ContentValues().apply {
            put(COL_RESOURCE_PLUGIN_ID, pluginId)
            put(COL_RESOURCE_KIND, kind)
            put(COL_RESOURCE_KEY, key)
            put(COL_RESOURCE_PAYLOAD, payload)
        }
        db.insertWithOnConflict(TABLE_RESOURCES, null, values, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun ownedResources(pluginId: String): List<AndroidPluginResource> = readableDatabase.query(
        TABLE_RESOURCES,
        RESOURCE_COLUMNS,
        "$COL_RESOURCE_PLUGIN_ID = ?",
        arrayOf(pluginId),
        null,
        null,
        "$COL_RESOURCE_KIND ASC, $COL_RESOURCE_KEY ASC",
    ).use { cursor ->
        buildList {
            while (cursor.moveToNext()) {
                add(
                    AndroidPluginResource(
                        pluginId = cursor.getString(0),
                        kind = cursor.getString(1),
                        key = cursor.getString(2),
                        payload = cursor.getString(3),
                    ),
                )
            }
        }
    }

    fun forgetOwnedResource(pluginId: String, kind: String, key: String) {
        writableDatabase.delete(
            TABLE_RESOURCES,
            "$COL_RESOURCE_PLUGIN_ID = ? AND $COL_RESOURCE_KIND = ? AND $COL_RESOURCE_KEY = ?",
            arrayOf(pluginId, kind, key),
        )
    }

    /** Record user consent for one requested capability. This is not a substitute for OS permission. */
    fun grantConsent(pluginId: String, capability: String) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            val record = queryRecord(db, pluginId)
                ?: throw IllegalArgumentException("Plugin '$pluginId' is not registered")
            require(capability in record.spec.requestedCapabilities) {
                "Plugin '$pluginId' did not request capability '$capability'"
            }
            require(record.state !in setOf(PluginState.UNINSTALLING, PluginState.FAILED, PluginState.REGISTERING)) {
                "Cannot grant capability to plugin '$pluginId' while it is ${record.state}"
            }
            val values = ContentValues().apply {
                put(COL_GRANT_PLUGIN_ID, pluginId)
                put(COL_GRANT_CAPABILITY, capability)
                put(COL_CONSENTED, 1)
                put(COL_ACTIVE, if (record.state == PluginState.ENABLED) 1 else 0)
            }
            db.insertWithOnConflict(TABLE_GRANTS, null, values, SQLiteDatabase.CONFLICT_REPLACE)
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    /** Returns only app-level grants active for the plugin. The action policy must also check state and OS permission. */
    fun activeConsents(pluginId: String): Set<String> = readableDatabase.query(
        TABLE_GRANTS,
        arrayOf(COL_GRANT_CAPABILITY),
        "$COL_GRANT_PLUGIN_ID = ? AND $COL_CONSENTED = 1 AND $COL_ACTIVE = 1",
        arrayOf(pluginId),
        null,
        null,
        "$COL_GRANT_CAPABILITY ASC",
    ).use { cursor -> buildSet { while (cursor.moveToNext()) add(cursor.getString(0)) } }

    override fun activateConsentedAccess(id: String) {
        writableDatabase.execSQL(
            "UPDATE $TABLE_GRANTS SET $COL_ACTIVE = $COL_CONSENTED WHERE $COL_GRANT_PLUGIN_ID = ?",
            arrayOf(id),
        )
    }

    override fun suspendRuntimeAccess(id: String) {
        writableDatabase.execSQL(
            "UPDATE $TABLE_GRANTS SET $COL_ACTIVE = 0 WHERE $COL_GRANT_PLUGIN_ID = ?",
            arrayOf(id),
        )
    }

    override fun revokeAll(id: String) {
        writableDatabase.delete(TABLE_GRANTS, "$COL_GRANT_PLUGIN_ID = ?", arrayOf(id))
    }

    private fun queryRecord(db: SQLiteDatabase, id: String): PluginRecord? = db.query(
        TABLE_RECORDS,
        RECORD_COLUMNS,
        "$COL_ID = ?",
        arrayOf(id),
        null,
        null,
        null,
    ).use { cursor -> if (cursor.moveToFirst()) cursor.toRecord() else null }

    private fun android.database.Cursor.toRecord(): PluginRecord {
        val capabilityArray = JSONArray(getString(getColumnIndexOrThrow(COL_CAPABILITIES)))
        val capabilities = buildSet {
            for (index in 0 until capabilityArray.length()) add(capabilityArray.getString(index))
        }
        val dependencyArray = JSONArray(getString(getColumnIndexOrThrow(COL_DEPENDENCIES)))
        val dependencies = buildSet {
            for (index in 0 until dependencyArray.length()) add(dependencyArray.getString(index))
        }
        return PluginRecord(
            spec = PluginSpec(
                id = getString(getColumnIndexOrThrow(COL_ID)),
                version = getString(getColumnIndexOrThrow(COL_VERSION)),
                target = PluginTarget.valueOf(getString(getColumnIndexOrThrow(COL_TARGET))),
                requestedCapabilities = capabilities,
                dependencies = dependencies,
            ),
            origin = PluginOrigin.valueOf(getString(getColumnIndexOrThrow(COL_ORIGIN))),
            state = PluginState.valueOf(getString(getColumnIndexOrThrow(COL_STATE))),
            lastError = getColumnIndex(COL_ERROR).let { if (isNull(it)) null else getString(it) },
        )
    }

    private companion object {
        const val DEFAULT_DATABASE_NAME = "cordiact-plugin-lifecycle.db"
        const val DATABASE_VERSION = 2
        const val TABLE_RECORDS = "plugin_records"
        const val TABLE_GRANTS = "plugin_capability_grants"
        const val TABLE_RESOURCES = "plugin_owned_resources"
        const val COL_ID = "plugin_id"
        const val COL_VERSION = "version"
        const val COL_TARGET = "target"
        const val COL_CAPABILITIES = "requested_capabilities"
        const val COL_DEPENDENCIES = "dependencies"
        const val COL_ORIGIN = "origin"
        const val COL_STATE = "state"
        const val COL_ERROR = "last_error"
        const val COL_GRANT_PLUGIN_ID = "plugin_id"
        const val COL_GRANT_CAPABILITY = "capability"
        const val COL_CONSENTED = "consented"
        const val COL_ACTIVE = "active"
        const val COL_RESOURCE_PLUGIN_ID = "plugin_id"
        const val COL_RESOURCE_KIND = "resource_kind"
        const val COL_RESOURCE_KEY = "resource_key"
        const val COL_RESOURCE_PAYLOAD = "payload"
        const val MAX_RESOURCE_PAYLOAD = 16 * 1024
        val PLUGIN_ID_PATTERN = Regex("[a-z0-9][a-z0-9._-]{1,127}")
        val RESOURCE_KIND_PATTERN = Regex("[a-z][a-z0-9._-]{0,63}")
        val RESOURCE_COLUMNS = arrayOf(COL_RESOURCE_PLUGIN_ID, COL_RESOURCE_KIND, COL_RESOURCE_KEY, COL_RESOURCE_PAYLOAD)
        val RECORD_COLUMNS = arrayOf(COL_ID, COL_VERSION, COL_TARGET, COL_CAPABILITIES, COL_DEPENDENCIES, COL_ORIGIN, COL_STATE, COL_ERROR)
    }
}
