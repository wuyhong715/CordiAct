package com.cordiact.app.plugin

import android.app.Application
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import com.cordiact.core.plugin.AuthoredPluginInstallationChangeResult.CHANGED
import com.cordiact.core.plugin.AuthoredPluginInstallationChangeResult.NOT_FOUND
import com.cordiact.core.plugin.AuthoredPluginInstallationChangeResult.STALE
import com.cordiact.core.plugin.AuthoredPluginInstallationStoreException
import com.cordiact.core.plugin.AuthoredPluginInstallationStoreException.Code
import com.cordiact.core.plugin.AuthoredPluginInstallationWriteResult.Existing
import com.cordiact.core.plugin.AuthoredPluginInstallationWriteResult.Inserted
import com.cordiact.core.plugin.AuthoredPluginInstallationWriteResult.QuotaExceeded
import com.cordiact.core.plugin.AuthoredPluginInstallations.Companion.FORMAT_VERSION
import com.cordiact.core.plugin.AuthoredPluginInstallations.Companion.MAX_PER_WORKSPACE
import com.cordiact.core.plugin.AuthoredPluginInstalledRecord
import com.cordiact.core.plugin.AuthoredPluginNames
import com.cordiact.core.plugin.AuthoredPluginSource
import com.cordiact.core.plugin.AuthoredPluginSource.MAX_SOURCE_BYTES
import com.cordiact.core.plugin.AuthoredPluginStoredInstallation.Broken
import com.cordiact.core.plugin.AuthoredPluginStoredInstallation.Snapshot
import com.cordiact.core.plugin.AuthoredPluginStoredInstallation.Unsupported
import java.io.File
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit.SECONDS
import org.json.JSONArray
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class AndroidAuthoredPluginInstallStoreTest {
    private lateinit var context: Context
    private lateinit var file: File
    private lateinit var store: AndroidAuthoredPluginInstallStore

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        file = File(context.noBackupFilesDir, "cordiact/authored-plugin-installations.db")
        SQLiteDatabase.deleteDatabase(file)
        store = AndroidAuthoredPluginInstallStore(context)
    }

    @After
    fun tearDown() {
        store.close()
        SQLiteDatabase.deleteDatabase(file)
    }

    @Test
    fun reopensExactBoundedBytesAndKeepsIdenticalIdsIsolated() {
        val exact = candidate(atSizeLimit = true)
        val other = candidate(workspace = "case", title = "另一个工作区")
        val punctuation = candidate(workspace = "' OR 1=1 -- %_", title = "opaque key")
        assertEquals(MAX_SOURCE_BYTES, exact.sourceBytes.size)
        for (input in listOf(exact, other, punctuation)) assertEquals(Inserted, insert(input))
        assertTrue(file.isFile)
        reopen()

        for (expected in listOf(exact, other, punctuation)) {
            val workspace = expected.record.workspaceId
            val snapshot = store.list(workspace).single() as Snapshot
            assertSnapshot(expected, snapshot)
            snapshot.sourceBytes.fill(0)
            assertSnapshot(expected, store.list(workspace).single() as Snapshot)
        }
        assertTrue(store.list("CASE").isEmpty())
        assertTrue(store.list("absent").isEmpty())
    }

    @Test
    fun matchingHashAndDifferentHashReturnOriginalWithoutCallingCommitAfterReopen() {
        val original = candidate()
        assertEquals(Inserted, insert(original))
        reopen()

        val duplicates = listOf(
            original.copy(record = original.record.copy(installedAtEpochMillis = 2L)),
            candidate(title = "Conflicting version"),
        )
        duplicates.forEach { incoming ->
            val existing = store.insertIfAbsent(WORKSPACE, incoming) { error("Existing must not call commit") } as Existing
            assertSnapshot(original, existing.installation as Snapshot)
        }
        assertSnapshot(original, store.list(WORKSPACE).single() as Snapshot)
    }

    @Test
    fun quotaIsPerWorkspaceAndExistingIsCheckedFirst() {
        val original = candidate(id = "item-00")
        assertEquals(Inserted, insert(original))
        repeat(MAX_PER_WORKSPACE - 1) { index ->
            assertEquals(Inserted, insert(candidate(id = "item-${(index + 1).toString().padStart(2, '0')}")))
        }
        reopen()

        assertEquals(QuotaExceeded, store.insertIfAbsent(WORKSPACE, candidate(id = "overflow")) { error("Quota must not call commit") })
        val existing = store.insertIfAbsent(WORKSPACE, original) { error("Existing must not call commit") } as Existing
        assertSnapshot(original, existing.installation as Snapshot)
        assertEquals(Inserted, insert(candidate(workspace = "other", id = "overflow")))
        assertEquals(MAX_PER_WORKSPACE, store.list(WORKSPACE).size)
        assertEquals(1, store.list("other").size)
    }

    @Test
    fun refusesHistoricalTamperingEvenWhenStoredHashMatchesAndSkipsOversizedValues() {
        val altered = candidate(id = "altered")
        val oversizedSource = candidate(id = "huge-source")
        val oversizedTitle = candidate(id = "huge-title")
        val future = candidate(id = "future")
        val intact = candidate(id = "intact")
        val oversizedId = candidate(id = "huge-install-id")
        val invalidManifest = candidate(id = "invalid-manifest")
        listOf(altered, oversizedSource, oversizedTitle, future, intact, oversizedId, invalidManifest).forEach {
            assertEquals(Inserted, insert(it))
        }
        mutate {
            execSQL("UPDATE $TABLE SET source = zeroblob(source_size) WHERE plugin_id = ?", arrayOf(altered.id))
            execSQL("PRAGMA ignore_check_constraints = ON")
            // Bigger than a normal Android CursorWindow, so filtering in Java would already be too late.
            execSQL("UPDATE $TABLE SET source = zeroblob(4194304), source_size = 4194304 WHERE plugin_id = ?", arrayOf(oversizedSource.id))
            execSQL("UPDATE $TABLE SET title = CAST(zeroblob(4194304) AS TEXT) WHERE plugin_id = ?", arrayOf(oversizedTitle.id))
            execSQL("UPDATE $TABLE SET installation_id = hex(zeroblob(2097152)) WHERE plugin_id = ?", arrayOf(oversizedId.id))
            execSQL("UPDATE $TABLE SET title = ? WHERE plugin_id = ?", arrayOf("Forged manifest", invalidManifest.id))
            execSQL("UPDATE $TABLE SET format_version = ? WHERE plugin_id = ?", arrayOf<Any>(FORMAT_VERSION + 1, future.id))
        }
        reopen()

        val rows = store.list(WORKSPACE).associateBy { it.id }
        listOf(altered, oversizedSource, oversizedTitle, oversizedId).forEach { expected ->
            assertEquals(Broken(expected.id), rows[expected.id])
            assertEquals(Existing(Broken(expected.id)), store.insertIfAbsent(WORKSPACE, expected) { error("Must not repair an existing row") })
            assertEquals(STALE, store.changeIfCurrent(WORKSPACE, expected.record, null) { error("Must not delete a broken row") })
        }
        assertEquals(Unsupported(future.id), rows[future.id])
        assertEquals(Existing(Unsupported(future.id)), store.insertIfAbsent(WORKSPACE, future) { error("Must not replace a future row") })
        assertEquals(STALE, store.changeIfCurrent(WORKSPACE, future.record, changedGeneration(future)) {
            error("Must not replace a future row")
        })
        val forged = rows.getValue(invalidManifest.id) as Snapshot
        assertEquals(STALE, store.changeIfCurrent(WORKSPACE, forged.record, null) { error("Manifest disagrees with source") })
        assertSnapshot(intact, rows.getValue(intact.id) as Snapshot)
        assertEquals(rows.keys, store.list(WORKSPACE).map { it.id }.toSet())
    }

    @Test
    fun commitsInsideGateAndRollsBackWhenGateRejects() {
        val approved = candidate()
        val rejected = candidate(id = "rejected")
        val denial = IllegalStateException("host canceled the confirmation")
        assertSame(denial, assertThrows(IllegalStateException::class.java) {
            store.insertIfAbsent(WORKSPACE, rejected) { throw denial }
        })
        assertEquals(Code.STORAGE_FAILURE, assertThrows(AuthoredPluginInstallationStoreException::class.java) {
            store.insertIfAbsent(WORKSPACE, rejected) { /* Host returned without calling publish. */ }
        }.code)
        reopen()
        AndroidAuthoredPluginInstallStore(context).use { reader ->
            assertTrue(reader.list(WORKSPACE).isEmpty())
            val pool = Executors.newSingleThreadExecutor()
            try {
                fun read() = pool.submit(Callable { reader.list(WORKSPACE) }).get(10, SECONDS)
                assertEquals(Inserted, store.insertIfAbsent(WORKSPACE, approved) { publish ->
                    assertTrue(read().isEmpty())
                    publish()
                    assertSnapshot(approved, read().single() as Snapshot)
                })
            } finally {
                pool.shutdownNow()
            }
        }
        reopen()
        assertSnapshot(approved, store.list(WORKSPACE).single() as Snapshot)
    }

    @Test
    fun upgradesRealV1WithoutLosingOrCoalescingRowsAndPersistsTheirDistinctUuids() {
        val original = listOf(candidate(atSizeLimit = true), candidate(id = "second"), candidate(workspace = "case"))
        createV1(original)
        val migrated = original.map { old ->
            val actual = store.list(old.record.workspaceId).single { it.id == old.id } as Snapshot
            val installationId = actual.record.installationId
            assertEquals(installationId, UUID.fromString(installationId).toString())
            assertSnapshot(old.copy(record = old.record.copy(installationId = installationId)), actual)
            actual
        }
        assertEquals(original.size, migrated.map { it.record.installationId }.toSet().size)
        reopen()
        for (expected in migrated) {
            assertSnapshot(expected, store.list(expected.record.workspaceId).single { it.id == expected.id } as Snapshot)
        }
        assertEquals(CHANGED, store.changeIfCurrent(WORKSPACE, migrated.first().record, null) { it() })
        reopen()
        assertSnapshot(migrated[1], store.list(WORKSPACE).single() as Snapshot)
        assertSnapshot(migrated[2], store.list("case").single() as Snapshot)
    }

    @Test
    fun comparesTheEntireManifestAndRejectsSameHashAbaAfterReplaceAndReinsert() {
        val initial = candidate()
        val peer = candidate(workspace = "case")
        val replacement = changedGeneration(initial)
        val reinstalled = changedGeneration(initial)
        assertEquals(Inserted, insert(initial))
        assertEquals(Inserted, insert(peer))
        val confirmed = store.list(WORKSPACE).single() as Snapshot
        val record = confirmed.record
        val changedFields = listOf(
            record.copy(sha256 = "0".repeat(64)),
            record.copy(schemaVersion = record.schemaVersion + 1),
            record.copy(formatVersion = record.formatVersion + 1),
            record.copy(sizeBytes = record.sizeBytes + 1),
            record.copy(installedAtEpochMillis = record.installedAtEpochMillis + 1),
            record.copy(title = "Other title"),
            record.copy(toolIds = listOf("extension.authored.notes.write")),
            record.copy(installationId = ""),
        )
        changedFields.forEach { forged ->
            assertEquals(STALE, store.changeIfCurrent(WORKSPACE, forged, null) { error("Manifest mismatch reached gate") })
        }
        assertEquals(NOT_FOUND, store.changeIfCurrent(WORKSPACE, record.copy(id = "missing"), null) {
            error("Absent row reached gate")
        })
        assertEquals(CHANGED, store.changeIfCurrent(WORKSPACE, record, replacement) { it() })
        reopen()
        assertSnapshot(replacement, store.list(WORKSPACE).single() as Snapshot)
        assertNotEquals(record.installationId, replacement.record.installationId)
        assertEquals(record, replacement.record.copy(installationId = record.installationId))
        assertEquals(STALE, store.changeIfCurrent(WORKSPACE, record, null) { error("Old generation reached gate") })
        assertEquals(CHANGED, store.changeIfCurrent(WORKSPACE, replacement.record, null) { it() })
        reopen()
        assertTrue(store.list(WORKSPACE).isEmpty())
        assertEquals(Inserted, insert(reinstalled))
        assertEquals(STALE, store.changeIfCurrent(WORKSPACE, replacement.record, null) { error("Deleted generation reached gate") })
        assertSnapshot(reinstalled, store.list(WORKSPACE).single() as Snapshot)
        assertSnapshot(peer, store.list("case").single() as Snapshot)
    }

    @Test
    fun rejectsInvalidReplacementWithoutPublishingOrTouchingTheOriginal() {
        val initial = candidate()
        val valid = changedGeneration(initial)
        val invalid = listOf(
            initial, // Same generation is invalid even when every other field matches.
            candidate(workspace = "case"),
            candidate(id = "other"),
            valid.copy(record = valid.record.copy(installationId = "")),
            valid.copy(record = valid.record.copy(installationId = "Aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa")),
            valid.copy(record = valid.record.copy(title = "Does not match source")),
            valid.copy(sourceBytes = ByteArray(valid.sourceBytes.size)),
        )
        assertEquals(Code.STORAGE_FAILURE, assertThrows(AuthoredPluginInstallationStoreException::class.java) {
            store.insertIfAbsent(WORKSPACE, initial.copy(record = initial.record.copy(installationId = ""))) {
                error("An insertion without a generation reached the gate")
            }
        }.code)
        assertEquals(Inserted, insert(initial))
        invalid.forEach { replacement ->
            assertEquals(Code.STORAGE_FAILURE, assertThrows(AuthoredPluginInstallationStoreException::class.java) {
                store.changeIfCurrent(WORKSPACE, initial.record, replacement) { error("Invalid replacement reached the gate") }
            }.code)
        }
        reopen()
        assertSnapshot(initial, store.list(WORKSPACE).single() as Snapshot)
    }

    @Test
    fun conditionalChangesPublishInsideGateRollBackOnRejectionAndSurviveReopen() {
        val initial = candidate()
        val replacement = candidate(title = "Replacement")
        val denial = IllegalStateException("host refused to publish")
        assertEquals(Inserted, insert(initial))
        for (candidate in listOf(null, replacement)) {
            assertSame(denial, assertThrows(IllegalStateException::class.java) {
                store.changeIfCurrent(WORKSPACE, initial.record, candidate) { throw denial }
            })
            assertEquals(Code.STORAGE_FAILURE, assertThrows(AuthoredPluginInstallationStoreException::class.java) {
                store.changeIfCurrent(WORKSPACE, initial.record, candidate) { /* Return without publication. */ }
            }.code)
            reopen()
            assertSnapshot(initial, store.list(WORKSPACE).single() as Snapshot)
        }
        AndroidAuthoredPluginInstallStore(context).use { reader ->
            reader.list(WORKSPACE) // Open the separate helper before the writer's transaction.
            val pool = Executors.newSingleThreadExecutor()
            try {
                fun read() = pool.submit(Callable { reader.list(WORKSPACE) }).get(10, SECONDS)
                assertEquals(CHANGED, store.changeIfCurrent(WORKSPACE, initial.record, replacement) { publish ->
                    assertSnapshot(initial, read().single() as Snapshot)
                    publish()
                    assertSnapshot(replacement, read().single() as Snapshot)
                })
            } finally {
                pool.shutdownNow()
            }
        }
        reopen()
        assertSnapshot(replacement, store.list(WORKSPACE).single() as Snapshot)
        assertEquals(Code.COMMIT_UNKNOWN, assertThrows(AuthoredPluginInstallationStoreException::class.java) {
            store.changeIfCurrent(WORKSPACE, replacement.record, null) { publish ->
                publish()
                throw IllegalStateException("gate failed after durable publication")
            }
        }.code)
        reopen()
        assertTrue(store.list(WORKSPACE).isEmpty())
        assertEquals(NOT_FOUND, store.changeIfCurrent(WORKSPACE, replacement.record, null) { error("Do not replay") })
    }

    @Test
    fun competingHelpersCanOnlyPublishOneSnapshot() {
        val a = candidate()
        val b = candidate(title = "Competing version")
        store.list(WORKSPACE) // Initialize the schema before starting the competing writers.
        AndroidAuthoredPluginInstallStore(context).use { competing ->
            competing.list(WORKSPACE)
            val pool = Executors.newFixedThreadPool(2)
            val ready = CountDownLatch(2)
            val start = CountDownLatch(1)
            try {
                val writes = listOf(store to a, competing to b).map { (writer, input) ->
                    pool.submit(Callable {
                        ready.countDown()
                        check(start.await(10, SECONDS))
                        writer.insertIfAbsent(WORKSPACE, input) { it() }
                    })
                }
                assertTrue(ready.await(10, SECONDS))
                start.countDown()
                val results = writes.map { it.get(10, SECONDS) }
                assertEquals(1, results.count { it == Inserted })
                val winner = if (results.first() == Inserted) a else b
                assertSnapshot(winner, results.filterIsInstance<Existing>().single().installation as Snapshot)
                assertSnapshot(winner, store.list(WORKSPACE).single() as Snapshot)
            } finally {
                start.countDown()
                pool.shutdownNow()
            }
        }
    }

    @Test
    fun rejectsWorkspaceMismatchAndDoesNotSilentlyTruncateOverflow() {
        assertEquals(Code.STORAGE_FAILURE, assertThrows(AuthoredPluginInstallationStoreException::class.java) {
            store.insertIfAbsent("Other", candidate()) { error("Mismatched workspace reached commit") }
        }.code)
        assertTrue(store.list("Other").isEmpty())
        val original = candidate()
        assertEquals(Inserted, insert(original))
        mutate {
            repeat(MAX_PER_WORKSPACE) { index ->
                execSQL(
                    """INSERT INTO $TABLE(workspace_id, plugin_id, sha256, schema_version, format_version,
                        source_size, installed_at, title, tool_ids, source, installation_id)
                        SELECT workspace_id, ?, sha256, schema_version, format_version,
                        source_size, installed_at, title, tool_ids, source, ? FROM $TABLE
                        WHERE workspace_id = ? AND plugin_id = ?""",
                    arrayOf("extra-$index", UUID.randomUUID().toString(), WORKSPACE, original.id),
                )
            }
        }
        reopen()
        assertEquals(Code.STORAGE_FAILURE, assertThrows(AuthoredPluginInstallationStoreException::class.java) { store.list(WORKSPACE) }.code)
        assertTrue(store.list("Other").isEmpty())
    }

    private fun createV1(rows: List<Snapshot>) {
        store.close()
        SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
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
                    PRIMARY KEY (workspace_id, plugin_id)
                )""".trimIndent(),
            )
            for ((record, sourceBytes) in rows) {
                db.execSQL(
                    """INSERT INTO $TABLE(workspace_id, plugin_id, sha256, schema_version, format_version,
                        source_size, installed_at, title, tool_ids, source) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""",
                    arrayOf(
                        record.workspaceId, record.id, record.sha256, record.schemaVersion, record.formatVersion,
                        record.sizeBytes, record.installedAtEpochMillis, record.title, JSONArray(record.toolIds).toString(),
                        sourceBytes,
                    ),
                )
            }
            db.version = 1
        }
        reopen()
    }

    private fun mutate(block: SQLiteDatabase.() -> Unit) {
        store.close()
        SQLiteDatabase.openDatabase(file.absolutePath, null, SQLiteDatabase.OPEN_READWRITE).use { it.block() }
    }

    private fun reopen() {
        store.close()
        store = AndroidAuthoredPluginInstallStore(context)
    }

    private fun insert(value: Snapshot) = store.insertIfAbsent(value.record.workspaceId, value) { publish -> publish() }

    private fun changedGeneration(value: Snapshot) = value.copy(record = value.record.copy(installationId = UUID.randomUUID().toString()))

    private fun assertSnapshot(expected: Snapshot, actual: Snapshot) {
        assertEquals(expected.record, actual.record)
        assertArrayEquals(expected.sourceBytes, actual.sourceBytes)
    }

    private fun candidate(
        id: String = "notes",
        workspace: String = WORKSPACE,
        title: String = "例子 📝",
        atSizeLimit: Boolean = false,
    ): Snapshot {
        val json = """{"schemaVersion":1,"id":"$id","title":"$title","tools":[{"name":"read","description":"Read notes","text":"原样"}]}"""
        val bytes = if (atSizeLimit) {
            (json + " ".repeat(MAX_SOURCE_BYTES - json.encodeToByteArray().size)).encodeToByteArray()
        } else {
            json.encodeToByteArray()
        }
        val definition = AuthoredPluginSource.compile(id, bytes)
        return Snapshot(AuthoredPluginInstalledRecord(
            workspace, id, definition.sha256, definition.schemaVersion, FORMAT_VERSION, bytes.size, 1L,
            definition.title, definition.tools.map { AuthoredPluginNames.tool(id, it.name) }, UUID.randomUUID().toString(),
        ), bytes)
    }

    private companion object {
        const val TABLE = "authored_plugin_installations"
        const val WORKSPACE = "Case"
    }
}
