package com.cordiact.app.plugin

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.DatabaseErrorHandler
import android.database.sqlite.SQLiteAccessPermException
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteException
import android.database.sqlite.SQLiteOpenHelper
import android.database.sqlite.SQLiteReadOnlyDatabaseException
import android.os.Build
import com.cordiact.core.plugin.AuthoredPluginInstallationChangeResult
import com.cordiact.core.plugin.AuthoredPluginInstallationChangeResult.CHANGED
import com.cordiact.core.plugin.AuthoredPluginInstallationChangeResult.NOT_FOUND
import com.cordiact.core.plugin.AuthoredPluginInstallationChangeResult.STALE
import com.cordiact.core.plugin.AuthoredPluginInstallationStore
import com.cordiact.core.plugin.AuthoredPluginInstallationStoreException
import com.cordiact.core.plugin.AuthoredPluginInstallationStoreException.Code
import com.cordiact.core.plugin.AuthoredPluginInstallationWriteResult
import com.cordiact.core.plugin.AuthoredPluginInstallationWriteResult.Existing
import com.cordiact.core.plugin.AuthoredPluginInstallationWriteResult.Inserted
import com.cordiact.core.plugin.AuthoredPluginInstallationWriteResult.QuotaExceeded
import com.cordiact.core.plugin.AuthoredPluginInstallations.Companion.FORMAT_VERSION
import com.cordiact.core.plugin.AuthoredPluginInstallations.Companion.MAX_PER_WORKSPACE
import com.cordiact.core.plugin.AuthoredPluginInstalledRecord
import com.cordiact.core.plugin.AuthoredPluginNames
import com.cordiact.core.plugin.AuthoredPluginSource
import com.cordiact.core.plugin.AuthoredPluginSource.MAX_SOURCE_BYTES
import com.cordiact.core.plugin.AuthoredPluginStoredInstallation
import com.cordiact.core.plugin.AuthoredPluginStoredInstallation.Broken
import com.cordiact.core.plugin.AuthoredPluginStoredInstallation.Snapshot
import com.cordiact.core.plugin.AuthoredPluginStoredInstallation.Unsupported
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import org.json.JSONArray
import org.json.JSONException

/**
 * Host-private storage, separate from draft files, lifecycle state and capability grants.
 * The host authorizes the live workspace; the store treats it only as an exact, opaque SQL key.
 */
class AndroidAuthoredPluginInstallStore(context: Context) : AuthoredPluginInstallationStore, AutoCloseable {
    private val helper = storageOperation { Database(context.applicationContext) }

    override fun close() = helper.close()

    override fun list(workspaceId: String): List<AuthoredPluginStoredInstallation> {
        requireWorkspaceKey(workspaceId)
        return storageOperation {
            helper.readableDatabase.query(
                TABLE, READ_COLUMNS, WORKSPACE_KEY, arrayOf(workspaceId),
                null, null, "plugin_id ASC", (MAX_PER_WORKSPACE + 1).toString(),
            ).use { rows ->
                if (rows.count > MAX_PER_WORKSPACE) storeFailure(Code.STORAGE_FAILURE)
                buildList { while (rows.moveToNext()) add(rows.toInstallation(workspaceId)) }
            }
        }
    }

    override fun insertIfAbsent(
        workspaceId: String,
        installation: Snapshot,
        commit: (() -> Unit) -> Unit,
    ): AuthoredPluginInstallationWriteResult {
        requireWorkspaceKey(workspaceId)
        if (installation.record.workspaceId != workspaceId || !canonicalId(installation.id)) storeFailure(Code.STORAGE_FAILURE)
        return writeTransaction(commit) { db, publish ->
            storageOperation { find(db, workspaceId, installation.id) }?.let { return@writeTransaction Existing(it) }
            if (storageOperation { atQuota(db, workspaceId) }) return@writeTransaction QuotaExceeded
            val values = installation.toValues(workspaceId)
            storageOperation { db.insertOrThrow(TABLE, null, values) }
            publish()
            Inserted
        }
    }

    override fun changeIfCurrent(
        workspaceId: String,
        expected: AuthoredPluginInstalledRecord,
        replacement: Snapshot?,
        commit: (() -> Unit) -> Unit,
    ): AuthoredPluginInstallationChangeResult {
        requireWorkspaceKey(workspaceId)
        if (expected.workspaceId != workspaceId || !canonicalId(expected.id)) storeFailure(Code.STORAGE_FAILURE)
        return writeTransaction(commit) { db, publish ->
            val current = storageOperation { find(db, workspaceId, expected.id) } ?: return@writeTransaction NOT_FOUND
            // Decoding requires a canonical UUID; equality then checks every detached manifest field.
            // Even an exact record match must not bypass source corruption or schema validation.
            if (current !is Snapshot || current.record != expected || !current.matchesSourceManifest()) {
                return@writeTransaction STALE
            }
            val replacementValues = replacement?.let {
                if (it.record.workspaceId != workspaceId || it.id != expected.id ||
                    it.record.installationId == expected.installationId
                ) storeFailure(Code.STORAGE_FAILURE)
                it.toValues(workspaceId, requireSupportedSource = true)
            }
            val key = arrayOf(workspaceId, expected.id, expected.installationId)
            val changed = storageOperation {
                if (replacementValues == null) db.delete(TABLE, GENERATION_KEY, key)
                else db.update(TABLE, replacementValues, GENERATION_KEY, key)
            }
            if (changed != 1) storeFailure(Code.STORAGE_FAILURE)
            publish()
            CHANGED
        }
    }

    private fun <T> writeTransaction(commit: (() -> Unit) -> Unit, block: (SQLiteDatabase, () -> Unit) -> T): T {
        val db = storageOperation { helper.writableDatabase }
        val ownerThread = Thread.currentThread()
        // The SQLite write lock serializes exact-key, generation, and quota checks across helpers.
        storageOperation {
            if (db.inTransaction()) storeFailure(Code.STORAGE_FAILURE)
            db.beginTransactionNonExclusive()
        }
        var transactionOpen = true
        var publicationStarted = false
        var publicationFinished = false
        var failureInFlight: Throwable? = null
        try {
            return block(db) {
                if (Thread.currentThread() !== ownerThread || !transactionOpen) storeFailure(Code.STORAGE_FAILURE)
                var gateOpen = true
                try {
                    commit {
                        if (Thread.currentThread() !== ownerThread || !gateOpen || !transactionOpen) {
                            storeFailure(Code.STORAGE_FAILURE)
                        }
                        storageOperation { db.setTransactionSuccessful() }
                        publicationStarted = true
                        // endTransaction, not setTransactionSuccessful, is the durable publication.
                        // An exception here gives an unknown outcome: never end/replay it a second time.
                        transactionOpen = false
                        db.endTransaction()
                        publicationFinished = true
                    }
                } finally {
                    gateOpen = false
                }
                if (!publicationFinished) storeFailure(Code.STORAGE_FAILURE)
            }
        } catch (failure: Throwable) {
            failureInFlight = failure
            if (publicationStarted && failure is Exception) {
                if (failure is InterruptedException) Thread.currentThread().interrupt()
                storeFailure(Code.COMMIT_UNKNOWN)
            }
            throw failure
        } finally {
            if (transactionOpen) {
                transactionOpen = false
                try {
                    storageOperation { db.endTransaction() }
                } catch (rollbackFailure: Exception) {
                    // In particular, do not replace the host's rejection/cancellation exception.
                    failureInFlight?.addSuppressed(rollbackFailure) ?: throw rollbackFailure
                }
            }
        }
    }

    private fun find(db: SQLiteDatabase, workspaceId: String, pluginId: String): AuthoredPluginStoredInstallation? =
        db.query(TABLE, READ_COLUMNS, ITEM_KEY, arrayOf(workspaceId, pluginId), null, null, null, "1")
            .use { if (it.moveToFirst()) it.toInstallation(workspaceId) else null }

    private fun atQuota(db: SQLiteDatabase, workspaceId: String): Boolean = db.rawQuery(
        "SELECT 1 FROM $TABLE WHERE $WORKSPACE_KEY LIMIT 1 OFFSET ${MAX_PER_WORKSPACE - 1}",
        arrayOf(workspaceId),
    ).use { it.moveToFirst() }

    private fun Snapshot.toValues(workspaceId: String, requireSupportedSource: Boolean = false): ContentValues {
        if (record.formatVersion != FORMAT_VERSION || record.schemaVersion <= 0 || record.installedAtEpochMillis < 0 ||
            sourceBytes.size !in 1..MAX_SOURCE_BYTES || record.sizeBytes != sourceBytes.size ||
            !canonicalInstallationId(record.installationId) || !SHA256.matches(record.sha256) ||
            !boundedText(record.title, MAX_TITLE_BYTES) || record.toolIds.size !in 1..MAX_TOOLS
        ) storeFailure(Code.STORAGE_FAILURE)
        val toolList = record.toolIds.toList()
        if (toolList.size !in 1..MAX_TOOLS || toolList.any { !boundedText(it, MAX_TOOL_ID_BYTES) }) {
            storeFailure(Code.STORAGE_FAILURE)
        }
        val source = sourceBytes.copyOf()
        val toolIds = JSONArray(toolList).toString()
        if (requireSupportedSource) {
            if (!Snapshot(record.copy(toolIds = toolList), source).matchesSourceManifest()) storeFailure(Code.STORAGE_FAILURE)
        } else if (sha256(source) != record.sha256) storeFailure(Code.STORAGE_FAILURE)
        if (!boundedText(toolIds, MAX_TOOL_IDS_BYTES)) storeFailure(Code.STORAGE_FAILURE)
        return ContentValues().apply {
            put("workspace_id", workspaceId)
            put("plugin_id", id)
            put("installation_id", record.installationId)
            put("sha256", record.sha256)
            put("schema_version", record.schemaVersion)
            put("format_version", record.formatVersion)
            put("source_size", record.sizeBytes)
            put("installed_at", record.installedAtEpochMillis)
            put("title", record.title)
            put("tool_ids", toolIds)
            put("source", source)
        }
    }

    private fun Cursor.toInstallation(workspaceId: String): AuthoredPluginStoredInstallation {
        val id = getString(0)?.takeIf(::canonicalId) ?: storeFailure(Code.STORAGE_FAILURE)
        val format = integer(1)?.takeIf { it > 0 } ?: return Broken(id)
        if (format != FORMAT_VERSION.toLong()) return Unsupported(id)
        val schema = integer(2)?.takeIf { it in 1..Int.MAX_VALUE.toLong() } ?: return Broken(id)
        val size = integer(3)?.takeIf { it in 1..MAX_SOURCE_BYTES.toLong() } ?: return Broken(id)
        val installedAt = integer(4)?.takeIf { it >= 0 } ?: return Broken(id)
        val sha = getString(5)?.takeIf(SHA256::matches) ?: return Broken(id)
        val title = getString(6) ?: return Broken(id)
        val toolIds = getString(7)?.let(::decodeToolIds) ?: return Broken(id)
        val installationId = getString(8)?.takeIf(::canonicalInstallationId) ?: return Broken(id)
        if (isNull(9)) return Broken(id)
        val source = getBlob(9) // SQL projects only a bounded BLOB whose byte length matches the manifest.
        if (source.size.toLong() != size || sha256(source) != sha) return Broken(id)
        return Snapshot(
            AuthoredPluginInstalledRecord(workspaceId, id, sha, schema.toInt(), format.toInt(), size.toInt(),
                installedAt, title, toolIds, installationId),
            source,
        )
    }

    private fun Snapshot.matchesSourceManifest(): Boolean = try {
        val definition = AuthoredPluginSource.compile(id, sourceBytes)
        definition.sha256 == record.sha256 && definition.schemaVersion == record.schemaVersion &&
            definition.title == record.title &&
            definition.tools.map { AuthoredPluginNames.tool(id, it.name) } == record.toolIds
    } catch (_: IllegalArgumentException) {
        false
    }

    private fun Cursor.integer(index: Int): Long? = if (getType(index) == Cursor.FIELD_TYPE_INTEGER) getLong(index) else null

    private class Database(context: Context) : SQLiteOpenHelper(
        context, databasePath(context), null, DATABASE_VERSION,
        PRESERVE_CORRUPT_DATABASE,
    ) {
        init {
            if (Build.VERSION.SDK_INT >= 28) {
                setOpenParams(SQLiteDatabase.OpenParams.Builder()
                    .setErrorHandler(PRESERVE_CORRUPT_DATABASE)
                    .addOpenFlags(SQLiteDatabase.ENABLE_WRITE_AHEAD_LOGGING)
                    .setSynchronousMode("FULL")
                    .build())
            }
        }

        override fun onConfigure(db: SQLiteDatabase) {
            if (Build.VERSION.SDK_INT < 28) db.execSQL("PRAGMA synchronous = FULL")
        }

        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL(
                """CREATE TABLE $TABLE (
                    workspace_id TEXT NOT NULL,
                    plugin_id TEXT NOT NULL,
                    sha256 TEXT NOT NULL,
                    schema_version INTEGER NOT NULL,
                    format_version INTEGER NOT NULL,
                    source_size INTEGER NOT NULL CHECK (source_size BETWEEN 1 AND $MAX_SOURCE_BYTES),
                    installed_at INTEGER NOT NULL,
                    title TEXT NOT NULL,
                    tool_ids TEXT NOT NULL,
                    source BLOB NOT NULL CHECK (typeof(source) = 'blob' AND length(source) = source_size),
                    installation_id TEXT NOT NULL DEFAULT '',
                    PRIMARY KEY (workspace_id, plugin_id)
                )""".trimIndent(),
            )
            createInstallationIdIndex(db)
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            if (oldVersion != 1 || newVersion != DATABASE_VERSION) storeFailure(Code.STORAGE_FAILURE)
            // SQLiteOpenHelper wraps the migration in a transaction. Generate each UUID in SQLite,
            // without selecting, decoding, or modifying any historical manifest or source payload.
            db.execSQL("ALTER TABLE $TABLE ADD COLUMN installation_id TEXT NOT NULL DEFAULT ''")
            db.execSQL(
                """UPDATE $TABLE SET installation_id = lower(
                    hex(randomblob(4)) || '-' || hex(randomblob(2)) || '-' || hex(randomblob(2)) || '-' ||
                    hex(randomblob(2)) || '-' || hex(randomblob(6)))""".trimIndent(),
            )
            createInstallationIdIndex(db)
        }

        private fun createInstallationIdIndex(db: SQLiteDatabase) {
            db.execSQL("CREATE UNIQUE INDEX ${TABLE}_installation_id ON $TABLE(installation_id)")
        }

        override fun onDowngrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = storeFailure(Code.STORAGE_FAILURE)
    }

    private companion object {
        const val DATABASE_VERSION = 2
        const val DATABASE_PATH = "cordiact/authored-plugin-installations.db"
        const val TABLE = "authored_plugin_installations"
        const val WORKSPACE_KEY = "workspace_id = ?"
        const val ITEM_KEY = "$WORKSPACE_KEY AND plugin_id = ?"
        const val GENERATION_KEY = "$ITEM_KEY AND installation_id = ?"
        const val INSTALLATION_ID_BYTES = 36
        const val MAX_TITLE_BYTES = 120
        const val MAX_TOOLS = 4
        const val MAX_TOOL_ID_BYTES = 68
        const val MAX_TOOL_IDS_BYTES = 512
        // Keep corrupt evidence in place instead of Android's default delete-and-recreate behavior.
        val PRESERVE_CORRUPT_DATABASE = DatabaseErrorHandler { }
        val SHA256 = Regex("[0-9a-f]{64}")
        val INSTALLATION_ID = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")

        fun canonicalInstallationId(id: String): Boolean = id.length == INSTALLATION_ID_BYTES && INSTALLATION_ID.matches(id)

        // CASE protects CursorWindow and app memory even if a row bypassed the writer's checks.
        // CAST measures the entire UTF-8 value in bytes, including any embedded NULs.
        fun limitedText(column: String, max: Int) = "CASE WHEN typeof($column) = 'text' " +
            "AND length(CAST($column AS BLOB)) BETWEEN 1 AND $max " +
            "AND instr($column, char(0)) = 0 THEN $column END"

        fun limitedInt(column: String) = "CASE WHEN typeof($column) = 'integer' THEN $column END"

        val READ_COLUMNS = arrayOf(
            limitedText("plugin_id", 24), limitedInt("format_version"), limitedInt("schema_version"),
            limitedInt("source_size"), limitedInt("installed_at"), limitedText("sha256", 64),
            limitedText("title", MAX_TITLE_BYTES), limitedText("tool_ids", MAX_TOOL_IDS_BYTES),
            limitedText("installation_id", INSTALLATION_ID_BYTES),
            "CASE WHEN typeof(format_version) = 'integer' AND format_version = $FORMAT_VERSION " +
                "AND typeof(source) = 'blob' AND typeof(source_size) = 'integer' " +
                "AND length(source) BETWEEN 1 AND $MAX_SOURCE_BYTES AND length(source) = source_size THEN source END",
        )

        fun canonicalId(id: String): Boolean = try {
            AuthoredPluginSource.path(id)
            true
        } catch (_: IllegalArgumentException) {
            false
        }

        fun requireWorkspaceKey(workspaceId: String) {
            if (workspaceId.isBlank() || '\u0000' in workspaceId || !Charsets.UTF_8.newEncoder().canEncode(workspaceId)) {
                storeFailure(Code.WORKSPACE_UNAVAILABLE)
            }
        }

        fun databasePath(context: Context): String {
            val file = File(context.noBackupFilesDir.canonicalFile, DATABASE_PATH)
            val dir = requireNotNull(file.parentFile)
            if ((!dir.isDirectory && !dir.mkdirs() && !dir.isDirectory) || file.canonicalFile != file) {
                storeFailure(Code.STORAGE_FAILURE)
            }
            return file.absolutePath
        }

        fun boundedText(value: String, maxBytes: Int): Boolean = value.length in 1..maxBytes && '\u0000' !in value &&
            Charsets.UTF_8.newEncoder().canEncode(value) && value.toByteArray(Charsets.UTF_8).size <= maxBytes

        fun decodeToolIds(json: String): List<String>? = try {
            val array = JSONArray(json)
            if (array.length() !in 1..MAX_TOOLS) null else {
                val ids = (0 until array.length()).map { array.opt(it) }
                // Android's JSON reader is lenient; only accept our exact serialization.
                if (ids.all { it is String && boundedText(it, MAX_TOOL_ID_BYTES) } && JSONArray(ids).toString() == json) {
                    ids.filterIsInstance<String>()
                } else null
            }
        } catch (_: JSONException) {
            null
        }

        fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).toHexString()

        inline fun <T> storageOperation(block: () -> T): T = try {
            block()
        } catch (failure: AuthoredPluginInstallationStoreException) {
            throw failure
        } catch (_: SQLiteAccessPermException) {
            storeFailure(Code.ACCESS_DENIED)
        } catch (_: SQLiteReadOnlyDatabaseException) {
            storeFailure(Code.ACCESS_DENIED)
        } catch (_: SecurityException) {
            storeFailure(Code.ACCESS_DENIED)
        } catch (_: SQLiteException) {
            storeFailure(Code.STORAGE_FAILURE)
        } catch (_: IOException) {
            storeFailure(Code.STORAGE_FAILURE)
        } catch (_: IllegalStateException) {
            storeFailure(Code.STORAGE_FAILURE)
        }

        fun storeFailure(code: Code): Nothing = throw AuthoredPluginInstallationStoreException(code, when (code) {
            Code.WORKSPACE_UNAVAILABLE -> "The installation workspace is unavailable"
            Code.ACCESS_DENIED -> "Plugin installation storage access was denied"
            Code.STORAGE_FAILURE -> "Could not access plugin installation storage"
            Code.COMMIT_UNKNOWN -> "Installation outcome is unknown; reopen the installed versions list"
        })
    }
}
