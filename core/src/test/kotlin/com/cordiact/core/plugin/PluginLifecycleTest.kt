package com.cordiact.core.plugin

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PluginLifecycleTest {
    private val events = mutableListOf<String>()
    private val records = FakeRecords()
    private val runtime = FakeRuntime(events)
    private val grants = FakeCapabilities(events)
    private val data = FakeData(events)
    private val manager = PluginLifecycleManager(records, runtime, grants, data, BundledPluginVerifier { it.id.startsWith("core.") })
    private val plugin = FakePlugin(events)

    @Test
    fun registersEnablesAndDisablesIdempotently() {
        assertEquals(PluginState.REGISTERED, manager.register(plugin).state)
        assertEquals(PluginState.REGISTERED, manager.register(plugin).state)
        assertEquals(PluginState.ENABLED, manager.enable(plugin.spec.id).state)
        assertEquals(PluginState.ENABLED, manager.enable(plugin.spec.id).state)
        assertEquals(PluginState.DISABLED, manager.disable(plugin.spec.id).state)
        assertEquals(PluginState.DISABLED, manager.disable(plugin.spec.id).state)
        assertEquals(listOf("register", "activate", "enable", "resume", "quiesce", "disable", "drain", "suspend"), events)
    }

    @Test
    fun cannotEnablePersistedPluginUntilImplementationIsLoaded() {
        records.put(PluginRecord(plugin.spec, PluginOrigin.USER_INSTALLED, PluginState.DISABLED))
        assertFailsWith<PluginLifecycleException> { manager.enable(plugin.spec.id) }
        assertEquals(PluginState.DISABLED, manager.get(plugin.spec.id)?.state)
        assertTrue(events.isEmpty())
    }

    @Test
    fun protectedSystemPluginCannotBeUninstalled() {
        val systemPlugin = FakePlugin(events, pluginId = "core.shell")
        manager.registerBundledSystem(systemPlugin)
        assertFailsWith<PluginLifecycleException> { manager.uninstall(systemPlugin.spec.id) }
        assertEquals(PluginState.REGISTERED, manager.get(systemPlugin.spec.id)?.state)
        assertTrue(events.none { it == "erase" || it == "remove-artifacts" })
    }

    @Test
    fun uninstallRunsOrderedCleanupAndRemovesRecord() {
        manager.register(plugin)
        manager.enable(plugin.spec.id)
        manager.uninstall(plugin.spec.id)
        assertNull(manager.get(plugin.spec.id))
        assertEquals(
            listOf("register", "activate", "enable", "resume", "quiesce", "disable", "drain", "revoke", "uninstall", "detach", "erase", "remove-artifacts"),
            events,
        )
        manager.uninstall(plugin.spec.id) // idempotent when already gone
    }

    @Test
    fun failedCleanupLeavesTombstoneAndRetryCompletes() {
        manager.register(plugin)
        data.failOnce = true
        assertFailsWith<PluginLifecycleException> { manager.uninstall(plugin.spec.id) }
        assertEquals(PluginState.UNINSTALLING, manager.get(plugin.spec.id)?.state)
        assertTrue(events.contains("remove-artifacts")) // later stages still run after a failure
        manager.uninstall(plugin.spec.id)
        assertNull(manager.get(plugin.spec.id))
        assertEquals(2, events.count { it == "erase" })
    }

    @Test
    fun failedRegistrationRollsBackUserPluginResources() {
        val broken = FakePlugin(events, throwsOnRegister = true)
        assertFailsWith<PluginLifecycleException> { manager.register(broken) }
        assertNull(manager.get(broken.spec.id))
        assertEquals(
            listOf("register", "quiesce", "disable", "drain", "revoke", "uninstall", "detach", "erase", "remove-artifacts"),
            events,
        )
    }

    @Test
    fun pendingUninstallResumesWithoutInMemoryPluginBinding() {
        manager.register(plugin)
        data.failOnce = true
        assertFailsWith<PluginLifecycleException> { manager.uninstall(plugin.spec.id) }
        val restarted = PluginLifecycleManager(records, runtime, grants, data)
        assertEquals(emptyList(), restarted.recoverPendingUninstalls())
        assertNull(restarted.get(plugin.spec.id))
    }

    @Test
    fun startupRecoveryDisablesPreviouslyEnabledPluginsBeforeRegistryUse() {
        manager.register(plugin)
        manager.enable(plugin.spec.id)
        events.clear()
        val restarted = PluginLifecycleManager(records, runtime, grants, data)
        assertEquals(emptyList(), restarted.recoverAfterProcessRestart())
        assertEquals(PluginState.DISABLED, restarted.get(plugin.spec.id)?.state)
        assertEquals(listOf("quiesce", "drain", "suspend", "detach"), events)
    }

    @Test
    fun interruptedUserRegistrationIsUninstalledOnStartup() {
        records.put(PluginRecord(plugin.spec, PluginOrigin.USER_INSTALLED, PluginState.REGISTERING))
        assertEquals(emptyList(), manager.recoverAfterProcessRestart())
        assertNull(manager.get(plugin.spec.id))
        assertTrue(events.containsAll(listOf("revoke", "erase", "remove-artifacts")))
    }

    @Test
    fun bundledOriginRequiresHostVerification() {
        val denied = PluginLifecycleManager(records, runtime, grants, data)
        assertFailsWith<PluginLifecycleException> { denied.registerBundledSystem(FakePlugin(events, pluginId = "core.unknown")) }
        assertNull(denied.get("core.unknown"))
    }

    @Test
    fun failedEnableFailsClosedAndRevokesCapabilities() {
        val broken = FakePlugin(events, throwsOnEnable = true)
        manager.register(broken)
        assertFailsWith<PluginLifecycleException> { manager.enable(broken.spec.id) }
        assertEquals(PluginState.FAILED, manager.get(broken.spec.id)?.state)
        assertTrue(events.containsAll(listOf("activate", "quiesce", "drain", "detach", "suspend")))
    }

    private class FakePlugin(
        private val events: MutableList<String>,
        private val throwsOnEnable: Boolean = false,
        pluginId: String = "sample.notes",
        private val throwsOnRegister: Boolean = false,
    ) : ManagedPlugin {
        override val spec = PluginSpec(pluginId, "1.0.0", PluginTarget.ANDROID_NATIVE, setOf("workspace.read"))
        override fun onRegister() { events += "register"; if (throwsOnRegister) error("register test failure") }
        override fun onEnable() { events += "enable"; if (throwsOnEnable) error("enable test failure") }
        override fun onDisable() { events += "disable" }
        override fun onUninstall() { events += "uninstall" }
    }

    private class FakeRecords : PluginRecordStore {
        private val values = mutableMapOf<String, PluginRecord>()
        override fun get(id: String) = values[id]
        override fun all() = values.values.toList()
        override fun put(record: PluginRecord) { values[record.spec.id] = record }
        override fun remove(id: String) { values.remove(id) }
    }

    private class FakeRuntime(private val events: MutableList<String>) : PluginRuntimeResources {
        override fun quiesce(id: String) { events += "quiesce" }
        override fun resume(id: String) { events += "resume" }
        override fun stopAndDrain(id: String) { events += "drain" }
        override fun detach(id: String) { events += "detach" }
        override fun removeInstalledArtifacts(id: String) { events += "remove-artifacts" }
    }

    private class FakeCapabilities(private val events: MutableList<String>) : PluginCapabilities {
        override fun activateConsentedAccess(id: String) { events += "activate" }
        override fun suspendRuntimeAccess(id: String) { events += "suspend" }
        override fun revokeAll(id: String) { events += "revoke" }
    }

    private class FakeData(private val events: MutableList<String>) : PluginOwnedData {
        var failOnce = false
        override fun eraseAll(id: String) {
            events += "erase"
            if (failOnce) { failOnce = false; error("data backend unavailable") }
        }
    }
}
