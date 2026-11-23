package com.cordiact.app.plugin

import android.app.Application
import android.database.sqlite.SQLiteDatabase
import com.cordiact.core.plugin.ExtensionPackageChange
import com.cordiact.core.plugin.ExtensionPackageInstaller
import com.cordiact.core.plugin.ExtensionPackageStoreException
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit.SECONDS
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
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
