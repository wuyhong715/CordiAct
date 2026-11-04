package com.cordiact.app.plugin

import android.content.Context
import com.cordiact.core.plugin.BundledPluginVerifier
import com.cordiact.core.plugin.ManagedPlugin
import com.cordiact.core.plugin.PluginLifecycleException
import com.cordiact.core.plugin.PluginLifecycleManager
import com.cordiact.core.plugin.PluginOrigin
import com.cordiact.core.plugin.PluginRecord
import com.cordiact.core.plugin.PluginSpec
import com.cordiact.core.plugin.PluginState
import com.cordiact.core.plugin.PluginTarget
import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AndroidPluginLifecycleIntegrationTest {
    private lateinit var context: Context
    private lateinit var store: AndroidPluginLifecycleStore
    private lateinit var storage: AndroidPluginStorage
    private lateinit var runtime: AndroidPluginRuntimeResources

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        context.deleteDatabase(TEST_DATABASE)
        store = AndroidPluginLifecycleStore(context, TEST_DATABASE)
        storage = AndroidPluginStorage(context)
        runtime = AndroidPluginRuntimeResources(storage, AndroidPluginResourceLedger(store, emptyMap()), store)
    }

    @After
    fun tearDown() {
        store.close()
        context.deleteDatabase(TEST_DATABASE)
    }

    @Test
    fun workspaceDependencySurvivesStoreReopen() {
        val plugin = PluginSpec(
            id = "core.session-manager",
            version = "1.0.0",
            target = PluginTarget.ANDROID_NATIVE,
            dependencies = setOf("core.workspace-manager"),
        )
        store.put(PluginRecord(plugin, PluginOrigin.BUNDLED_SYSTEM, PluginState.DISABLED))
        store.close()
        store = AndroidPluginLifecycleStore(context, TEST_DATABASE)

        assertEquals(setOf("core.workspace-manager"), store.get(plugin.id)?.spec?.dependencies)
    }

    @Test
    fun pluginConfigSurvivesDatabaseReopen() {
        // Without this column an MCP server would come back with an empty config after a restart,
        // and rebuildUserPlugins would fail to reconstruct it.
        val spec = PluginSpec(
            id = "core.mcp.demo",
            version = "0.1.0",
            target = PluginTarget.ANDROID_NATIVE,
            config = """{"serverName":"demo","url":"https://example.test/mcp"}""",
        )
        store.put(PluginRecord(spec, PluginOrigin.USER_INSTALLED, PluginState.ENABLED))
        store.close()
        store = AndroidPluginLifecycleStore(context, TEST_DATABASE)

        assertEquals(spec, store.get(spec.id)?.spec)
        assertEquals("""{"serverName":"demo","url":"https://example.test/mcp"}""", store.get(spec.id)?.spec?.config)
    }

    @Test
    fun lifecycleStateAndConsentsSurviveDatabaseReopenWithoutReplaceCascade() {
        val record = record(state = PluginState.REGISTERED)
        store.put(record)
        store.grantConsent(record.spec.id, "workspace.read")
        assertTrue(store.activeConsents(record.spec.id).isEmpty())

        store.put(record.copy(state = PluginState.ENABLING))
        store.activateConsentedAccess(record.spec.id)
        store.put(record.copy(state = PluginState.ENABLED))
        assertEquals(setOf("workspace.read"), store.activeConsents(record.spec.id))

        store.close()
        store = AndroidPluginLifecycleStore(context, TEST_DATABASE)
        assertEquals(record.spec.copy(), store.get(record.spec.id)?.spec)
        assertEquals(PluginState.ENABLED, store.get(record.spec.id)?.state)
        assertEquals(setOf("workspace.read"), store.activeConsents(record.spec.id))

        store.suspendRuntimeAccess(record.spec.id)
        assertTrue(store.activeConsents(record.spec.id).isEmpty())
        store.activateConsentedAccess(record.spec.id)
        assertEquals(setOf("workspace.read"), store.activeConsents(record.spec.id))
    }

    @Test
    fun changingPluginVersionRevokesOldConsent() {
        val original = record()
        store.put(original)
        store.grantConsent(original.spec.id, "workspace.read")
        store.put(original.copy(spec = original.spec.copy(version = "2.0.0")))
        assertTrue(store.activeConsents(original.spec.id).isEmpty())
        store.activateConsentedAccess(original.spec.id)
        assertTrue(store.activeConsents(original.spec.id).isEmpty())
    }

    @Test
    fun uninstallRemovesOnlyOwnedDataGrantsArtifactsAndRuntimeBinding() {
        val controller = TestController()
        val plugin = TestPlugin(onEnableAction = { id -> runtime.attach(id, controller) })
        val sibling = TestPlugin("sample.sibling")
        val manager = PluginLifecycleManager(store, runtime, store, storage, BundledPluginVerifier { false })
        manager.register(plugin)
        manager.register(sibling)
        store.grantConsent(plugin.spec.id, "workspace.read")
        store.grantConsent(sibling.spec.id, "workspace.read")
        manager.enable(plugin.spec.id)
        manager.enable(sibling.spec.id)

        File(storage.dataDirectoryFor(plugin.spec.id), "notes.db").writeText("plugin data")
        File(storage.cacheDirectoryFor(plugin.spec.id), "index.bin").writeText("cache")
        File(storage.artifactsDirectoryFor(plugin.spec.id), "plugin.js").writeText("bundle")
        File(storage.dataDirectoryFor(sibling.spec.id), "keep.txt").writeText("sibling")

        manager.uninstall(plugin.spec.id)

        assertNull(store.get(plugin.spec.id))
        assertTrue(store.activeConsents(plugin.spec.id).isEmpty())
        assertEquals(setOf("workspace.read"), store.activeConsents(sibling.spec.id))
        assertFalse(File(context.filesDir, "cordiact/plugin-data/${plugin.spec.id}").exists())
        assertFalse(File(context.cacheDir, "cordiact/plugin-cache/${plugin.spec.id}").exists())
        assertFalse(File(context.noBackupFilesDir, "cordiact/plugin-artifacts/${plugin.spec.id}").exists())
        assertEquals("sibling", File(storage.dataDirectoryFor(sibling.spec.id), "keep.txt").readText())
        assertEquals(listOf("resume", "quiesce", "drain", "detach"), controller.events)
        assertEquals(listOf("register", "enable", "disable", "uninstall"), plugin.events)
    }

    @Test
    fun disablingPluginCancelsDurableExternalResourcesAndSuspendsConsent() {
        val cleaned = mutableListOf<String>()
        val ledger = AndroidPluginResourceLedger(
            store,
            mapOf("test.job" to AndroidPluginResourceCleaner { cleaned += it.key }),
        )
        val runtimeWithLedger = AndroidPluginRuntimeResources(storage, ledger, store)
        val plugin = TestPlugin(onEnableAction = { id -> runtimeWithLedger.attach(id, TestController()) })
        val lifecycle = PluginLifecycleManager(store, runtimeWithLedger, store, storage, BundledPluginVerifier { false })
        lifecycle.register(plugin)
        store.grantConsent(plugin.spec.id, "workspace.read")
        lifecycle.enable(plugin.spec.id)
        ledger.track(plugin.spec.id, "test.job", "periodic-job", "work-name")

        assertEquals(PluginState.DISABLED, lifecycle.disable(plugin.spec.id).state)

        assertEquals(listOf("periodic-job"), cleaned)
        assertTrue(store.ownedResources(plugin.spec.id).isEmpty())
        assertTrue(store.activeConsents(plugin.spec.id).isEmpty())
    }

    @Test
    fun startupRecoveryBlocksPreviouslyEnabledPluginAndSuspendsConsent() {
        val plugin = TestPlugin()
        store.put(record(state = PluginState.ENABLED))
        store.grantConsent(plugin.spec.id, "workspace.read")
        store.activateConsentedAccess(plugin.spec.id)
        assertEquals(setOf("workspace.read"), store.activeConsents(plugin.spec.id))

        val restartedRuntime = AndroidPluginRuntimeResources(storage, AndroidPluginResourceLedger(store, emptyMap()), store)
        val manager = PluginLifecycleManager(store, restartedRuntime, store, storage, BundledPluginVerifier { false })
        assertTrue(manager.recoverAfterProcessRestart().isEmpty())

        assertEquals(PluginState.DISABLED, store.get(plugin.spec.id)?.state)
        assertTrue(store.activeConsents(plugin.spec.id).isEmpty())
        manager.register(plugin)
        manager.enable(plugin.spec.id)
        assertEquals(setOf("workspace.read"), store.activeConsents(plugin.spec.id))
    }

    @Test
    fun symlinkCleanupDoesNotFollowTargetOutsidePluginDirectory() {
        val pluginDir = storage.dataDirectoryFor("sample.linked")
        val external = File(context.filesDir, "outside-owned-by-user.txt").apply { writeText("keep") }
        Files.createSymbolicLink(File(pluginDir, "escape").toPath(), external.toPath())

        storage.eraseAll("sample.linked")

        assertTrue(external.exists())
        assertEquals("keep", external.readText())
        assertFalse(pluginDir.exists())
    }

    @Test
    fun invalidPluginIdCannotEscapeStorageRoot() {
        try {
            storage.eraseAll("../outside")
            throw AssertionError("Expected invalid plugin id to be rejected")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message!!.contains("Invalid plugin id"))
        }
    }

    @Test
    fun persistedExternalResourcesAreCleanedBeforePluginRecordIsRemoved() {
        val plugin = TestPlugin()
        val manager = PluginLifecycleManager(store, runtime, store, storage, BundledPluginVerifier { false })
        manager.register(plugin)
        manager.enable(plugin.spec.id)
        val seen = mutableListOf<AndroidPluginResource>()
        val ledger = AndroidPluginResourceLedger(
            store,
            mapOf("test.subscription" to AndroidPluginResourceCleaner { seen += it }),
        )
        ledger.track(plugin.spec.id, "test.subscription", "subscriber-1", "event-bus-topic")
        store.close()
        store = AndroidPluginLifecycleStore(context, TEST_DATABASE)
        val reopenedLedger = AndroidPluginResourceLedger(
            store,
            mapOf("test.subscription" to AndroidPluginResourceCleaner { seen += it }),
        )
        val reopenedRuntime = AndroidPluginRuntimeResources(storage, reopenedLedger, store)
        val resumedManager = PluginLifecycleManager(store, reopenedRuntime, store, storage, BundledPluginVerifier { false })
        assertEquals(1, store.ownedResources(plugin.spec.id).size)

        resumedManager.uninstall(plugin.spec.id)

        assertEquals(listOf("subscriber-1"), seen.map { it.key })
        assertTrue(store.ownedResources(plugin.spec.id).isEmpty())
        assertNull(store.get(plugin.spec.id))
    }

    @Test
    fun failedExternalCleanupKeepsTombstoneAndLedgerForRetry() {
        val plugin = TestPlugin()
        val manager = PluginLifecycleManager(store, runtime, store, storage, BundledPluginVerifier { false })
        manager.register(plugin)
        manager.enable(plugin.spec.id)
        val attempts = mutableListOf<Int>()
        var failOnce = true
        val ledger = AndroidPluginResourceLedger(
            store,
            mapOf("test.job" to AndroidPluginResourceCleaner {
                attempts += 1
                if (failOnce) { failOnce = false; error("cleanup temporarily unavailable") }
            }),
        )
        ledger.track(plugin.spec.id, "test.job", "work-1", "unique-work-name")
        val firstRuntime = AndroidPluginRuntimeResources(storage, ledger, store)
        val firstManager = PluginLifecycleManager(store, firstRuntime, store, storage, BundledPluginVerifier { false })
        try {
            firstManager.uninstall(plugin.spec.id)
            throw AssertionError("Expected resource cleanup failure")
        } catch (_: PluginLifecycleException) {
            assertEquals(PluginState.UNINSTALLING, store.get(plugin.spec.id)?.state)
            assertEquals(1, store.ownedResources(plugin.spec.id).size)
        }

        firstManager.uninstall(plugin.spec.id)
        assertTrue("Cleanup should be retried; attempts=$attempts", attempts.size >= 2)
        assertNull(store.get(plugin.spec.id))
    }

    @Test
    fun protectedSystemPluginCannotBeRemovedThroughStoredOrigin() {
        val protected = record(
            id = "core.workspace",
            origin = PluginOrigin.BUNDLED_SYSTEM,
            state = PluginState.REGISTERED,
        )
        store.put(protected)
        val manager = PluginLifecycleManager(store, runtime, store, storage, BundledPluginVerifier { it.id.startsWith("core.") })
        try {
            manager.uninstall(protected.spec.id)
            throw AssertionError("Expected protected plugin removal to be rejected")
        } catch (expected: PluginLifecycleException) {
            assertEquals(PluginState.REGISTERED, store.get(protected.spec.id)?.state)
        }
    }

    private fun record(
        id: String = "sample.notes",
        origin: PluginOrigin = PluginOrigin.USER_INSTALLED,
        state: PluginState = PluginState.REGISTERED,
    ) = PluginRecord(
        spec = PluginSpec(id, "1.0.0", PluginTarget.ANDROID_NATIVE, setOf("workspace.read")),
        origin = origin,
        state = state,
    )

    private class TestPlugin(
        pluginId: String = "sample.notes",
        private val onEnableAction: (String) -> Unit = {},
    ) : ManagedPlugin {
        override val spec = PluginSpec(pluginId, "1.0.0", PluginTarget.ANDROID_NATIVE, setOf("workspace.read"))
        val events = mutableListOf<String>()
        override fun onRegister() { events += "register" }
        override fun onEnable() { events += "enable"; onEnableAction(spec.id) }
        override fun onDisable() { events += "disable" }
        override fun onUninstall() { events += "uninstall" }
    }

    private class TestController : AndroidPluginRuntimeController {
        val events = mutableListOf<String>()
        override fun quiesce() { events += "quiesce" }
        override fun resume() { events += "resume" }
        override fun stopAndDrain() { events += "drain" }
        override fun detach() { events += "detach" }
    }

    private companion object { const val TEST_DATABASE = "cordiact-plugin-test.db" }
}
