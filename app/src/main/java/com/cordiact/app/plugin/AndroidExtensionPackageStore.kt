package com.cordiact.app.plugin

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteException
import android.database.sqlite.SQLiteOpenHelper
import com.cordiact.core.plugin.ExtensionPackageArtifact
import com.cordiact.core.plugin.ExtensionPackageArtifact.Format
import com.cordiact.core.plugin.ExtensionPackageRecord
import com.cordiact.core.plugin.ExtensionPackageSource
import com.cordiact.core.plugin.ExtensionPackageStore
import com.cordiact.core.plugin.ExtensionPackageStore.Companion.MAX_PER_WORKSPACE
import com.cordiact.core.plugin.ExtensionPackageStoreException
import com.cordiact.core.plugin.ExtensionPackageStoreWrite
import java.io.File
import java.util.UUID

/** Persists only approved source bytes. The app still owns workspace authorization and runtime intent. */
class AndroidExtensionPackageStore(context: Context) : ExtensionPackageStore, AutoCloseable {
    private val database = lazy { PackageDatabase(context.applicationContext) }
    private val helper by database

    override fun close() {
        if (database.isInitialized()) helper.close()
    }

    override fun list(workspaceId: String): List<ExtensionPackageRecord> = storage {
        requireWorkspace(workspaceId)
        helper.readableDatabase.query(
            TABLE, columns, "workspace_id = ?", arrayOf(workspaceId), null, null, "package_id ASC",
            (MAX_PER_WORKSPACE + 1).toString(),
        ).use { rows ->
            if (rows.count > MAX_PER_WORKSPACE) throw ExtensionPackageStoreException()
            buildList { while (rows.moveToNext()) add(rows.record(workspaceId)) }
        }
    }

    override fun get(workspaceId: String, packageId: String): ExtensionPackageRecord? = storage {
        requireWorkspace(workspaceId)
        require(packageId.matches(packageKey))
        find(helper.readableDatabase, workspaceId, packageId)
    }

    override fun compareAndSet(
        workspaceId: String,
        packageId: String,
        expectedInstallationId: String?,
        replacement: ExtensionPackageRecord?,
        publish: (() -> Unit) -> Unit,
    ): ExtensionPackageStoreWrite = storage {
        requireWorkspace(workspaceId)
        require(packageId.matches(packageKey))
        require(expectedInstallationId != null || replacement != null)
        val values = replacement?.let {
            require(it.workspaceId == workspaceId && it.manifest.id == packageId && it.installationId != expectedInstallationId)
            it.values()
        }
        val db = helper.writableDatabase
        check(!db.inTransaction())
        db.beginTransactionNonExclusive()
        var transactionOpen = true
        var publishing = false
        var published = false
        var pendingFailure: Throwable? = null
        val owner = Thread.currentThread()
        try {
            val current = find(db, workspaceId, packageId)
            if (current?.installationId != expectedInstallationId) return@storage ExtensionPackageStoreWrite.STALE
            val key = arrayOf(workspaceId, packageId)
            if (current == null) {
                val atQuota = db.rawQuery(
                    "SELECT 1 FROM $TABLE WHERE workspace_id = ? LIMIT 1 OFFSET ${MAX_PER_WORKSPACE - 1}",
                    arrayOf(workspaceId),
                ).use { it.moveToFirst() }
                if (atQuota) return@storage ExtensionPackageStoreWrite.QUOTA_EXCEEDED
                db.insertOrThrow(TABLE, null, requireNotNull(values))
            } else {
                val changed = if (values == null) db.delete(TABLE, itemKey, key) else db.update(TABLE, values, itemKey, key)
                if (changed != 1) throw ExtensionPackageStoreException()
            }
            publish {
                check(Thread.currentThread() === owner && transactionOpen)
                db.setTransactionSuccessful()
                publishing = true
                transactionOpen = false
                db.endTransaction()
                published = true
            }
            check(published)
            ExtensionPackageStoreWrite.CHANGED
        } catch (failure: Throwable) {
            pendingFailure = failure
            if (publishing && failure is Exception) throw ExtensionPackageStoreException(commitUnknown = true, cause = failure)
            throw failure
        } finally {
            if (transactionOpen) {
                transactionOpen = false
                try {
                    db.endTransaction()
                } catch (failure: RuntimeException) {
                    pendingFailure?.addSuppressed(failure) ?: throw ExtensionPackageStoreException(cause = failure)
                }
            }
        }
    }

    private fun find(db: SQLiteDatabase, workspaceId: String, packageId: String): ExtensionPackageRecord? =
        db.query(TABLE, columns, itemKey, arrayOf(workspaceId, packageId), null, null, null, "1")
            .use { if (it.moveToFirst()) it.record(workspaceId) else null }

    private fun Cursor.record(workspaceId: String): ExtensionPackageRecord {
        val id = requireNotNull(getString(0))
        val generation = requireNotNull(getString(1))
        require(!isNull(2))
        val installedAt = getLong(2)
        require(id.matches(packageKey) && isCanonicalUuid(generation) && installedAt >= 0)
        val artifact = artifact(id, 3)
        val previous = if (getInt(9) == 0) null else artifact(id, 6)
        require(previous == null || ExtensionPackageSource.compareVersions(previous.manifest.version, artifact.manifest.version) < 0)
        require(!isNull(10) && getInt(10) in 0..1)
        return ExtensionPackageRecord(workspaceId, artifact, generation, installedAt, previous, enableIntent = getInt(10) == 1)
    }

    private fun Cursor.artifact(packageId: String, column: Int): ExtensionPackageArtifact {
        val format = when (getString(column)) {
            "declarative" -> Format.DECLARATIVE
            "legacy-authored" -> Format.LEGACY_AUTHORED
            else -> throw ExtensionPackageStoreException()
        }
        val digest = getString(column + 1)
        val source = requireNotNull(getBlob(column + 2))
        return ExtensionPackageArtifact.decode(source, format, packageId).also {
            require(it.manifest.id == packageId && it.manifest.sha256 == digest)
        }
    }

    private fun ExtensionPackageRecord.values(): ContentValues = ContentValues().apply {
        require(isCanonicalUuid(installationId) && installedAtEpochMillis >= 0)
        previous?.let {
            require(it.manifest.id == manifest.id && ExtensionPackageSource.compareVersions(it.manifest.version, manifest.version) < 0)
        }
        put("workspace_id", workspaceId)
        put("package_id", manifest.id)
        put("installation_id", installationId)
        put("installed_at", installedAtEpochMillis)
        put("enable_intent", if (enableIntent) 1 else 0)
        putArtifact("current", artifact)
        putArtifact("previous", previous)
    }

    private fun ContentValues.putArtifact(prefix: String, artifact: ExtensionPackageArtifact?) {
        if (artifact == null) {
            putNull("${prefix}_format")
            putNull("${prefix}_digest")
            putNull("${prefix}_source")
        } else {
            put("${prefix}_format", when (artifact.format) {
                Format.DECLARATIVE -> "declarative"
                Format.LEGACY_AUTHORED -> "legacy-authored"
            })
            put("${prefix}_digest", artifact.manifest.sha256)
            put("${prefix}_source", artifact.copySource())
        }
    }

    private inline fun <T> storage(block: () -> T): T = try {
        block()
    } catch (failure: SQLiteException) {
        throw ExtensionPackageStoreException(cause = failure)
    } catch (failure: IllegalArgumentException) {
        throw ExtensionPackageStoreException(cause = failure)
    } catch (failure: java.io.IOException) {
        throw ExtensionPackageStoreException(cause = failure)
    }

    private class PackageDatabase(context: Context) : SQLiteOpenHelper(
        context, databaseFile(context).absolutePath, null, 2, { throw ExtensionPackageStoreException() },
    ) {
        override fun onCreate(db: SQLiteDatabase) {
            val limit = ExtensionPackageSource.MAX_SOURCE_BYTES
            db.execSQL("""
                CREATE TABLE $TABLE (
                    workspace_id TEXT NOT NULL,
                    package_id TEXT NOT NULL,
                    installation_id TEXT NOT NULL,
                    installed_at INTEGER NOT NULL CHECK(installed_at >= 0),
                    $ENABLE_COLUMN,
                    current_format TEXT NOT NULL,
                    current_digest TEXT NOT NULL CHECK(length(current_digest) = 64),
                    current_source BLOB NOT NULL CHECK(length(current_source) BETWEEN 1 AND $limit),
                    previous_format TEXT,
                    previous_digest TEXT,
                    previous_source BLOB,
                    PRIMARY KEY (workspace_id, package_id),
                    CHECK ((previous_source IS NULL AND previous_format IS NULL AND previous_digest IS NULL)
                        OR (previous_source IS NOT NULL AND previous_format IS NOT NULL AND previous_digest IS NOT NULL
                            AND length(previous_digest) = 64 AND length(previous_source) BETWEEN 1 AND $limit))
                )
            """.trimIndent())
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            if (oldVersion != 1 || newVersion != 2) throw ExtensionPackageStoreException()
            db.execSQL("ALTER TABLE $TABLE ADD COLUMN $ENABLE_COLUMN")
        }
    }

    companion object {
        private const val TABLE = "extension_packages"
        private const val ENABLE_COLUMN = "enable_intent INTEGER NOT NULL DEFAULT 0 CHECK(enable_intent IN (0, 1))"
        private const val itemKey = "workspace_id = ? AND package_id = ?"
        private fun boundedColumn(name: String, type: String, max: Int): String =
            "CASE WHEN typeof($name) = '$type' AND length(CAST($name AS BLOB)) BETWEEN 1 AND $max THEN $name ELSE NULL END"

        // Include a presence flag so a malformed rollback artifact is not silently dropped.
        private val columns = arrayOf(
            boundedColumn("package_id", "text", 24), boundedColumn("installation_id", "text", 36),
            "CASE WHEN typeof(installed_at) = 'integer' THEN installed_at ELSE NULL END",
            boundedColumn("current_format", "text", 16), boundedColumn("current_digest", "text", 64),
            boundedColumn("current_source", "blob", ExtensionPackageSource.MAX_SOURCE_BYTES),
            boundedColumn("previous_format", "text", 16), boundedColumn("previous_digest", "text", 64),
            boundedColumn("previous_source", "blob", ExtensionPackageSource.MAX_SOURCE_BYTES),
            "(previous_format IS NOT NULL OR previous_digest IS NOT NULL OR previous_source IS NOT NULL)",
            "CASE WHEN typeof(enable_intent) = 'integer' AND enable_intent IN (0, 1) THEN enable_intent ELSE NULL END",
        )

        internal fun databaseFile(context: Context): File {
            val file = File(context.noBackupFilesDir.canonicalFile, "cordiact/extension-packages.db")
            val dir = requireNotNull(file.parentFile)
            if ((!dir.isDirectory && !dir.mkdirs() && !dir.isDirectory) || file.canonicalFile != file) {
                throw ExtensionPackageStoreException()
            }
            return file
        }
        private val packageKey = Regex("[a-z][a-z0-9-]{1,23}")
        private fun requireWorkspace(id: String) = require(id.isNotBlank() && id.length <= 256 && '\u0000' !in id)
        private fun isCanonicalUuid(id: String) = UUID.fromString(id).toString() == id
    }
}
