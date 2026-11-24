package com.cordiact.app.plugin

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.database.sqlite.SQLiteDatabase
import com.cordiact.core.plugin.ExtensionPackageChange
import com.cordiact.core.plugin.ExtensionPackageInstaller
import com.cordiact.core.plugin.ExtensionPackageRecord
import com.cordiact.core.plugin.ExtensionPackageSourceReceipt
import com.cordiact.core.plugin.ExtensionPackageStoreException
import com.cordiact.core.plugin.ExtensionPackageStoreWrite
import java.io.File
import java.nio.file.Files
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit.SECONDS
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class AndroidExtensionPackageStoreTest {
    @get:Rule val temporaryFolder = TemporaryFolder()

    @Test
    fun reopensExactSourceAndRollbackSnapshotAcrossIndependentWorkspaces() {
        val context = RuntimeEnvironment.getApplication()
        val first = source("1.0.0")
        val upgrade = source("1.1.0")
        val workspace = "workspace '%_"
        AndroidExtensionPackageStore(context).use { store ->
            val installer = ExtensionPackageInstaller(store)
            val installed = install(installer, workspace, first)
            install(installer, "other", first)
            val updated = install(installer, workspace, upgrade, installed.record.installationId).record
            val enabled = installer.setEnableIntent(workspace, updated.manifest.id, updated.installationId, true)
            assertTrue(enabled is ExtensionPackageChange.Applied)
        }
        AndroidExtensionPackageStore(context).use { store ->
            val installer = ExtensionPackageInstaller(store)
            val installed = installer.list(workspace).single()
            assertTrue(installed.enableIntent)
            assertEquals(false, installer.list("other").single().enableIntent)
            assertArrayEquals(upgrade, installed.artifact.copySource())
            assertArrayEquals(first, requireNotNull(installed.previous).copySource())
            assertArrayEquals(first, installer.list("other").single().artifact.copySource())
            val rolledBack = installer.rollback(workspace, "notes", installed.installationId) as ExtensionPackageChange.Applied
            assertArrayEquals(first, rolledBack.record.artifact.copySource())
            val stale = installer.remove(workspace, "notes", installed.installationId) as ExtensionPackageChange.Rejected
            assertEquals(ExtensionPackageChange.Code.STALE, stale.code)
        }
    }

    @Test
    fun receiptsShareTheArtifactCasAndSurviveReopen() {
        val dataDir = temporaryFolder.newFolder()
        val context = writableTestContext(dataDir)
        val earlier = source("1.0.0")
        val later = source("1.1.0")
        val first = ExtensionPackageSourceReceipt("s".repeat(32), "p".repeat(32), "k".repeat(32), 1, 1)
        val rotated = first.copy(keyId = "r".repeat(32), trustRevision = 9_999_999_999_999_999L, indexRevision = 9_999_999_999_999_999L)
        val updated = AndroidExtensionPackageStore(context).use { store ->
            val installer = ExtensionPackageInstaller(store)
            val original = ExtensionPackageRecord("ws", installer.inspect(earlier), generation(), 42, sourceReceipt = first)
            val next = original.copy(
                artifact = installer.inspect(later), installationId = generation(), previous = original.artifact,
                sourceReceipt = rotated, previousSourceReceipt = first,
            )
            assertEquals(ExtensionPackageStoreWrite.CHANGED, store.compareAndSet("ws", "notes", null, original))
            assertThrows(IllegalStateException::class.java) {
                store.compareAndSet("ws", "notes", original.installationId, next) { error("Do not publish") }
            }
            val uncommitted = requireNotNull(store.get("ws", "notes"))
            assertEquals(original.installationId, uncommitted.installationId)
            assertEquals(first, uncommitted.sourceReceipt)
            assertNull(uncommitted.previousSourceReceipt)
            assertArrayEquals(earlier, uncommitted.artifact.copySource())
            assertEquals(ExtensionPackageStoreWrite.CHANGED, store.compareAndSet("ws", "notes", original.installationId, next))
            assertEquals(ExtensionPackageStoreWrite.STALE, store.compareAndSet("ws", "notes", original.installationId, null))
            next
        }
        val file = AndroidExtensionPackageStore.databaseFile(context)
        assertFilesUnchanged(file) {
            AndroidExtensionPackageStore(diagnosticsContext(dataDir)).use { store ->
                assertReceiptSnapshot(requireNotNull(store.readExistingForDiagnostics("ws")).single(), updated)
            }
        }
        AndroidExtensionPackageStore(context).use { store ->
            assertReceiptSnapshot(requireNotNull(store.get("ws", "notes")), updated)
            val rollback = updated.copy(
                artifact = requireNotNull(updated.previous), installationId = generation(), previous = null,
                sourceReceipt = first, previousSourceReceipt = null,
            )
            assertEquals(ExtensionPackageStoreWrite.CHANGED, store.compareAndSet("ws", "notes", updated.installationId, rollback))
        }
        AndroidExtensionPackageStore(context).use { store ->
            val record = store.list("ws").single()
            assertEquals(first, record.sourceReceipt)
            assertNull(record.previousSourceReceipt)
            assertNull(record.previous)
            assertArrayEquals(earlier, record.artifact.copySource())
        }
    }

    @Test
    fun invalidReceiptsAreNotAbsenceForReadersOrCas() {
        val dataDir = temporaryFolder.newFolder()
        val context = writableTestContext(dataDir)
        val record = AndroidExtensionPackageStore(context).use { store ->
            install(ExtensionPackageInstaller(store), "ws", source("1.0.0")).record
        }
        val file = AndroidExtensionPackageStore.databaseFile(context)
        val invalid = listOf(
            "'source publisher key 1 2'" to "NULL", // TEXT is not the receipt encoding.
            "42" to "NULL",
            "zeroblob(1048576)" to "NULL",
            "x''" to "NULL",
            "x'ff'" to "NULL",
            "CAST('source publisher key 0 2' AS BLOB)" to "NULL",
            "NULL" to "'source publisher key 1 2'",
            "NULL" to "zeroblob(1048576)",
            "CAST('source publisher key 2 3' AS BLOB)" to "CAST('source publisher key 1 2' AS BLOB)", // Orphan.
        )
        for ((current, previous) in invalid) {
            openFixtureForWrite(file).use { db ->
                db.execSQL("UPDATE extension_packages SET current_source_receipt = $current, previous_source_receipt = $previous")
            }
            assertFilesUnchanged(file) {
                AndroidExtensionPackageStore(diagnosticsContext(dataDir)).use { assertDiagnosticsUnreadable(it, "ws") }
            }
            AndroidExtensionPackageStore(context).use { store ->
                assertThrows(ExtensionPackageStoreException::class.java) { store.list("ws") }
                assertThrows(ExtensionPackageStoreException::class.java) { store.get("ws", "notes") }
                assertThrows(ExtensionPackageStoreException::class.java) {
                    store.compareAndSet("ws", "notes", record.installationId, record.copy(installationId = generation())) {
                        error("Corruption must be rejected before publication")
                    }
                }
            }
        }
    }

    @Test
    fun twoHelpersCannotBothInstallAgainstAbsence() {
        val context = RuntimeEnvironment.getApplication()
        val bytes = source("1.0.0")
        AndroidExtensionPackageStore(context).use { left ->
            AndroidExtensionPackageStore(context).use { right ->
                left.list("race")
                right.list("race")
                val pool = Executors.newFixedThreadPool(2)
                val start = CountDownLatch(1)
                try {
                    val runs = listOf(left, right).map { store ->
                        pool.submit(Callable {
                            assertTrue(start.await(10, SECONDS))
                            val installer = ExtensionPackageInstaller(store)
                            val artifact = installer.inspect(bytes)
                            installer.install("race", artifact, null, artifact.manifest.capabilities)
                        })
                    }
                    start.countDown()
                    val results = runs.map { it.get(10, SECONDS) }
                    assertEquals(1, results.count { it is ExtensionPackageChange.Applied })
                    assertEquals(ExtensionPackageChange.Code.STALE, results.filterIsInstance<ExtensionPackageChange.Rejected>().single().code)
                    assertEquals(1, left.list("race").size)
                } finally {
                    pool.shutdownNow()
                    assertTrue(pool.awaitTermination(10, SECONDS))
                }
            }
        }
    }

    @Test
    fun digestMismatchIsNotAbsenceAndDoesNotPermitReplacement() {
        val context = RuntimeEnvironment.getApplication()
        val bytes = source("1.0.0")
        AndroidExtensionPackageStore(context).use { install(ExtensionPackageInstaller(it), "ws", bytes) }
        SQLiteDatabase.openDatabase(AndroidExtensionPackageStore.databaseFile(context).path, null, SQLiteDatabase.OPEN_READWRITE).use { db ->
            db.execSQL("UPDATE extension_packages SET current_digest = ?", arrayOf("0".repeat(64)))
        }
        AndroidExtensionPackageStore(context).use { store ->
            val installer = ExtensionPackageInstaller(store)
            assertThrows(ExtensionPackageStoreException::class.java) { installer.list("ws") }
            val replacement = installer.inspect(bytes)
            val result = installer.install("ws", replacement, null, replacement.manifest.capabilities) as ExtensionPackageChange.Rejected
            assertEquals(ExtensionPackageChange.Code.STORE_FAILURE, result.code)
        }
    }

    @Test
    fun diagnosticsDoesNotInitializeMissingDirectoriesOrDatabase() {
        val dataDir = temporaryFolder.newFolder()
        val noBackupDir = File(dataDir, "no_backup")
        val databaseDir = File(noBackupDir, "cordiact")
        AndroidExtensionPackageStore(diagnosticsContext(dataDir)).use { store ->
            assertNull(store.readExistingForDiagnostics("ws"))
            assertFalse(noBackupDir.exists())
            assertTrue(noBackupDir.mkdir())
            assertNull(store.readExistingForDiagnostics("ws"))
            assertFalse(databaseDir.exists())
            assertTrue(databaseDir.mkdir())
            assertNull(store.readExistingForDiagnostics("ws"))
            assertEquals(emptyList<String>(), databaseDir.list()!!.toList())
        }
    }

    @Test
    fun diagnosticsReadsExactTargetRecordsAndDoesNotDecodeAnotherWorkspace() {
        val dataDir = temporaryFolder.newFolder()
        val file = createDiagnosticsDatabase(dataDir)
        val workspace = "workspace '%_"
        val first = source("1.0.0")
        val upgrade = source("1.1.0")
        val enabled = AndroidExtensionPackageStore(writableTestContext(dataDir)).use { store ->
            val installer = ExtensionPackageInstaller(store)
            val initial = install(installer, workspace, first).record
            val updated = install(installer, workspace, upgrade, initial.installationId).record
            val enabled = installer.setEnableIntent(workspace, updated.manifest.id, updated.installationId, true)
                as ExtensionPackageChange.Applied
            install(installer, "other", first)
            enabled.record
        }
        openFixtureForWrite(file).use { db ->
            db.execSQL("UPDATE extension_packages SET current_digest = ? WHERE workspace_id = ?", arrayOf("0".repeat(64), "other"))
        }
        assertFilesUnchanged(file) {
            AndroidExtensionPackageStore(diagnosticsContext(dataDir)).use { store ->
                val record = requireNotNull(store.readExistingForDiagnostics(workspace)).single()
                assertEquals(workspace, record.workspaceId)
                assertEquals(enabled.installationId, record.installationId)
                assertEquals(enabled.installedAtEpochMillis, record.installedAtEpochMillis)
                assertTrue(record.enableIntent)
                assertArrayEquals(upgrade, record.artifact.copySource())
                assertArrayEquals(first, requireNotNull(record.previous).copySource())
                assertEquals(emptyList<ExtensionPackageRecord>(), store.readExistingForDiagnostics(workspace.uppercase()))
                assertDiagnosticsUnreadable(store, "other")
            }
        }
    }

    @Test
    fun writableOpenMigratesV1AndV2WithoutRewritingArtifactsButDiagnosticsDisallowsBoth() {
        for (version in 1..2) {
            val dataDir = temporaryFolder.newFolder()
            val context = writableTestContext(dataDir)
            val file = AndroidExtensionPackageStore.databaseFile(context)
            val old = source("1.0.0")
            val current = source("1.1.0")
            val installation = generation()
            openFixtureForWrite(file, create = true).use { db ->
                val enableColumn = if (version == 2) ", enable_intent INTEGER NOT NULL DEFAULT 0" else ""
                db.execSQL("""
                    CREATE TABLE extension_packages (
                        workspace_id TEXT NOT NULL, package_id TEXT NOT NULL, installation_id TEXT NOT NULL,
                        installed_at INTEGER NOT NULL, current_format TEXT NOT NULL, current_digest TEXT NOT NULL,
                        current_source BLOB NOT NULL, previous_format TEXT, previous_digest TEXT, previous_source BLOB
                        $enableColumn, PRIMARY KEY (workspace_id, package_id)
                    )
                """.trimIndent())
                AndroidExtensionPackageStore(context).use { store ->
                    val inspector = ExtensionPackageInstaller(store)
                    db.execSQL("""
                        INSERT INTO extension_packages (workspace_id, package_id, installation_id, installed_at,
                            current_format, current_digest, current_source, previous_format, previous_digest, previous_source)
                        VALUES ('ws', 'notes', ?, 42, 'declarative', ?, ?, 'declarative', ?, ?)
                    """.trimIndent(), arrayOf(
                        installation, inspector.inspect(current).manifest.sha256, current, inspector.inspect(old).manifest.sha256, old,
                    ))
                }
                if (version == 2) db.execSQL("UPDATE extension_packages SET enable_intent = 1")
                db.version = version
            }
            assertFilesUnchanged(file) {
                AndroidExtensionPackageStore(diagnosticsContext(dataDir)).use { assertDiagnosticsUnreadable(it, "ws") }
            }
            val migrated = AndroidExtensionPackageStore(context).use { store ->
                store.list("ws").single().also { record ->
                    assertEquals(installation, record.installationId)
                    assertEquals(42L, record.installedAtEpochMillis)
                    assertEquals(version == 2, record.enableIntent)
                    assertNull(record.sourceReceipt)
                    assertNull(record.previousSourceReceipt)
                    assertArrayEquals(current, record.artifact.copySource())
                    assertArrayEquals(old, requireNotNull(record.previous).copySource())
                }
            }
            assertFilesUnchanged(file) {
                SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
                    assertEquals(3, db.version)
                }
                AndroidExtensionPackageStore(diagnosticsContext(dataDir)).use { store ->
                    assertReceiptSnapshot(requireNotNull(store.readExistingForDiagnostics("ws")).single(), migrated)
                }
            }
        }
    }

    @Test
    fun diagnosticsDoesNotTreatInvalidV3SchemaAsEmpty() {
        val dataDir = temporaryFolder.newFolder()
        val file = AndroidExtensionPackageStore.databaseFile(writableTestContext(dataDir))
        openFixtureForWrite(file, create = true).use { db ->
            db.execSQL("CREATE TABLE extension_packages (workspace_id TEXT, package_id TEXT)")
            db.version = 3
        }
        assertFilesUnchanged(file) {
            AndroidExtensionPackageStore(diagnosticsContext(dataDir)).use { assertDiagnosticsUnreadable(it, "ws") }
        }
    }

    @Test
    fun diagnosticsDoesNotDeleteCorruptDatabase() {
        val dataDir = temporaryFolder.newFolder()
        val file = File(dataDir, "no_backup/cordiact/extension-packages.db")
        assertTrue(file.parentFile!!.mkdirs())
        // Match the journal format bytes to exercise SQLite's corruption handler itself.
        file.writeBytes(ByteArray(4096).also {
            it[18] = 1
            it[19] = 1
        })
        assertFilesUnchanged(file) {
            AndroidExtensionPackageStore(diagnosticsContext(dataDir)).use { assertDiagnosticsUnreadable(it, "ws") }
        }
    }

    @Test
    fun diagnosticsDoesNotRecoverHotRollbackJournal() {
        val dataDir = temporaryFolder.newFolder()
        val file = createDiagnosticsDatabase(dataDir)
        openFixtureForWrite(file).close()
        val journal = File("${file.path}-journal")
        // SQLite rollback-journal magic and a sector-sized header simulate an interrupted write.
        val header = byteArrayOf(0xd9.toByte(), 0xd5.toByte(), 0x05, 0xf9.toByte(), 0x20, 0xa1.toByte(), 0x63, 0xd7.toByte())
        journal.writeBytes(header.copyOf(512))
        assertFilesUnchanged(file) {
            AndroidExtensionPackageStore(diagnosticsContext(dataDir)).use { assertDiagnosticsUnreadable(it, "ws") }
        }
    }

    @Test
    fun diagnosticsRejectsWalDatabaseWithoutCreatingSidecars() {
        val dataDir = temporaryFolder.newFolder()
        val file = createDiagnosticsDatabase(dataDir)
        openFixtureForWrite(file).use { db ->
            assertTrue(db.enableWriteAheadLogging())
            db.execSQL("CREATE TABLE wal_marker (id INTEGER)")
        }
        assertFalse(File("${file.path}-wal").exists())
        assertFalse(File("${file.path}-shm").exists())
        assertFilesUnchanged(file) {
            AndroidExtensionPackageStore(diagnosticsContext(dataDir)).use { assertDiagnosticsUnreadable(it, "ws") }
        }
    }

    @Test
    fun diagnosticsRejectsDanglingDatabaseSymlinkRatherThanReportingAbsence() {
        val dataDir = temporaryFolder.newFolder()
        val file = File(dataDir, "no_backup/cordiact/extension-packages.db")
        val target = File(dataDir, "missing.db")
        assertTrue(file.parentFile!!.mkdirs())
        Files.createSymbolicLink(file.toPath(), target.toPath())
        AndroidExtensionPackageStore(diagnosticsContext(dataDir)).use { assertDiagnosticsUnreadable(it, "ws") }
        assertTrue(Files.isSymbolicLink(file.toPath()))
        assertFalse(target.exists())
    }

    private fun diagnosticsContext(dataDir: File): Context =
        object : ContextWrapper(RuntimeEnvironment.getApplication()) {
            override fun getApplicationContext(): Context = this
            override fun getDataDir(): File = dataDir
            override fun getNoBackupFilesDir(): File = error("Diagnostics must not initialize the no-backup path")
        }

    private fun writableTestContext(dataDir: File): Context =
        object : ContextWrapper(RuntimeEnvironment.getApplication()) {
            override fun getApplicationContext(): Context = this
            override fun getDataDir(): File = dataDir
            override fun getNoBackupFilesDir(): File = File(dataDir, "no_backup").apply { mkdirs() }
        }

    private fun createDiagnosticsDatabase(dataDir: File): File {
        val context = writableTestContext(dataDir)
        AndroidExtensionPackageStore(context).use { it.list("ws") }
        return AndroidExtensionPackageStore.databaseFile(context)
    }

    private fun openFixtureForWrite(file: File, create: Boolean = false): SQLiteDatabase {
        val flags = SQLiteDatabase.OPEN_READWRITE or (if (create) SQLiteDatabase.CREATE_IF_NECESSARY else 0)
        return SQLiteDatabase.openDatabase(file.path, null, flags).apply { disableWriteAheadLogging() }
    }

    private fun assertFilesUnchanged(file: File, read: () -> Unit) {
        val before = file.parentFile!!.listFiles()!!.associate { it.name to it.readBytes() }
        read()
        val after = file.parentFile!!.listFiles()!!.associate { it.name to it.readBytes() }
        assertEquals(before.keys, after.keys)
        before.forEach { (name, bytes) -> assertArrayEquals(bytes, after.getValue(name)) }
    }

    private fun assertDiagnosticsUnreadable(store: AndroidExtensionPackageStore, workspace: String) {
        val error = assertThrows(ExtensionPackageStoreException::class.java) { store.readExistingForDiagnostics(workspace) }
        assertEquals("PACKAGE_STORAGE_UNAVAILABLE", error.message)
        assertFalse(error.commitUnknown)
    }

    private fun assertReceiptSnapshot(actual: ExtensionPackageRecord, expected: ExtensionPackageRecord) {
        assertEquals(expected.installationId, actual.installationId)
        assertEquals(expected.enableIntent, actual.enableIntent)
        assertEquals(expected.sourceReceipt, actual.sourceReceipt)
        assertEquals(expected.previousSourceReceipt, actual.previousSourceReceipt)
        assertArrayEquals(expected.artifact.copySource(), actual.artifact.copySource())
        assertArrayEquals(requireNotNull(expected.previous).copySource(), requireNotNull(actual.previous).copySource())
    }

    private fun generation(): String = UUID.randomUUID().toString()

    private fun install(installer: ExtensionPackageInstaller, workspace: String, bytes: ByteArray, generation: String? = null): ExtensionPackageChange.Applied {
        val artifact = installer.inspect(bytes)
        return installer.install(workspace, artifact, generation, artifact.manifest.capabilities) as ExtensionPackageChange.Applied
    }

    private fun source(version: String) = """
        {"schemaVersion":1,"id":"notes","version":"$version","title":"Notes","description":"Notes",
         "host":{"api":1,"minVersion":"0.1.0"},"capabilities":["tool.publish"],"commands":[],
         "tools":[{"name":"show","description":"Show note","kind":"static_text","text":"literal $version"}]}
    """.trimIndent().toByteArray()
}
