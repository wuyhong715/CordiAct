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
    fun enabledDependenciesGateEnableAndPreventProviderShutdown() {
        val workspace = FakePlugin(events, pluginId = "core.workspace-manager")
        val session = FakePlugin(
            events,
            pluginId = "core.session-manager",
            dependencies = setOf("core.workspace-manager"),
        )
        manager.registerBundledSystem(workspace)
        manager.registerBundledSystem(session)

        assertFailsWith<PluginLifecycleException> { manager.enable(session.spec.id) }
        manager.enable(workspace.spec.id)
        manager.enable(session.spec.id)
        assertFailsWith<PluginLifecycleException> { manager.disable(workspace.spec.id) }
        assertEquals(PluginState.ENABLED, manager.get(workspace.spec.id)?.state)
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
    fun startupRecoveryDisablesPreviouslyEnabledBundledPluginsBeforeRegistryUse() {
        // Bundled origin matters here: bundled plugins are repaired at startup, user plugins are
        // reconstructed by the rebuild path instead. The shared verifier trusts `core.*` ids.
        val bundled = FakePlugin(events, pluginId = "core.sample")
        manager.registerBundledSystem(bundled)
        manager.enable(bundled.spec.id)
        events.clear()
        val restarted = PluginLifecycleManager(records, runtime, grants, data)
        assertEquals(emptyList(), restarted.recoverAfterProcessRestart())
        assertEquals(PluginState.DISABLED, restarted.get(bundled.spec.id)?.state)
        assertEquals(listOf("quiesce", "drain", "suspend", "detach"), events)
    }

    @Test
    fun anInterruptedUserRegistrationIsCleanedUpByTheRebuildPath() {
        val spec = plugin.spec
        records.put(PluginRecord(spec, PluginOrigin.USER_INSTALLED, PluginState.REGISTERING))
        // Bundled recovery skips user plugins entirely; the rebuild path owns their cleanup.
        assertEquals(emptyList(), manager.recoverAfterProcessRestart())
        assertEquals(PluginState.REGISTERING, manager.get(spec.id)?.state)

        manager.rebuildUserPlugins { FakeSpecPlugin(it) }
        assertNull(manager.get(spec.id))
        assertTrue(events.containsAll(listOf("revoke", "erase", "remove-artifacts")))
    }

    @Test
    fun bundledOriginRequiresHostVerification() {
        val denied = PluginLifecycleManager(records, runtime, grants, data)
        assertFailsWith<PluginLifecycleException> { denied.registerBundledSystem(FakePlugin(events, pluginId = "core.unknown")) }
        assertNull(denied.get("core.unknown"))
    }

    @Test
    fun specConfigIsPartOfIdentitySoEditingItIsNotSilentlyAccepted() {
        val base = PluginSpec("mcp.demo", "1.0.0", PluginTarget.HARNESS_REMOTE, config = """{"url":"https://a"}""")
        val edited = base.copy(config = """{"url":"https://b"}""")
        assertTrue(base != edited)
        manager.register(FakeSpecPlugin(base))
        // A record already holding the old config must reject a registration that changed it.
        assertFailsWith<PluginLifecycleException> { manager.register(FakeSpecPlugin(edited)) }
    }

    @Test
    fun installUserPluginUsesTheFactoryAndRejectsMismatchedSpecs() {
        val spec = PluginSpec("mcp.demo", "1.0.0", PluginTarget.HARNESS_REMOTE, config = """{"serverName":"demo"}""")
        val record = manager.installUserPlugin(spec) { candidate ->
            if (candidate == spec) FakeSpecPlugin(candidate) else null
        }
        assertEquals(PluginState.REGISTERED, record.state)
        assertEquals(PluginOrigin.USER_INSTALLED, record.origin)
    }

    @Test
    fun installUserPluginFailsWhenThisBuildCannotHostTheSpec() {
        val spec = PluginSpec("mcp.future", "9.9.9", PluginTarget.HARNESS_REMOTE)
        assertFailsWith<PluginLifecycleException> { manager.installUserPlugin(spec) { null } }
        assertNull(manager.get(spec.id))
    }

    @Test
    fun startupRecoveryLeavesUserPluginsForTheRebuildPath() {
        val spec = PluginSpec("mcp.restart", "1.0.0", PluginTarget.HARNESS_REMOTE)
        manager.installUserPlugin(spec) { FakeSpecPlugin(it) }
        manager.enable(spec.id)
        assertEquals(PluginState.ENABLED, records.get(spec.id)?.state)

        val restarted = PluginLifecycleManager(records, runtime, grants, data)
        // Bundled recovery used to demote a user plugin to DISABLED here, so every restart forced
        // the user to re-enable it. It must now be left alone for rebuildUserPlugins.
        assertEquals(emptyList(), restarted.recoverAfterProcessRestart())
        assertEquals(PluginState.ENABLED, records.get(spec.id)?.state)

        val report = restarted.rebuildUserPlugins { FakeSpecPlugin(it) }
        assertEquals(listOf(spec.id), report.restored)
        assertEquals(PluginState.ENABLED, restarted.get(spec.id)?.state)
    }

    @Test
    fun rebuildStillCleansUpAnInterruptedUserInstall() {
        val spec = PluginSpec("mcp.interrupted", "1.0.0", PluginTarget.HARNESS_REMOTE)
        // A record left in REGISTERING is what a process death mid-install looks like.
        records.put(PluginRecord(spec, PluginOrigin.USER_INSTALLED, PluginState.REGISTERING))
        val restarted = PluginLifecycleManager(records, runtime, grants, data)

        val report = restarted.rebuildUserPlugins { FakeSpecPlugin(it) }

        // A clean cleanup removes the record, so it is neither restored nor left pending.
        assertNull(restarted.get(spec.id))
        assertEquals(emptyList(), report.pendingCleanup)
        assertEquals(emptyList(), report.restored)
        assertTrue(events.contains("erase"))
    }

    @Test
    fun rebuildRestoresEnabledUserPluginsAndLeavesDisabledOnesOff() {
        val enabled = PluginSpec("mcp.on", "1.0.0", PluginTarget.HARNESS_REMOTE)
        val disabled = PluginSpec("mcp.off", "1.0.0", PluginTarget.HARNESS_REMOTE)
        manager.installUserPlugin(enabled) { FakeSpecPlugin(it) }
        manager.installUserPlugin(disabled) { FakeSpecPlugin(it) }
        manager.enable(enabled.id)
        manager.disable(disabled.id)
        events.clear()

        val restarted = PluginLifecycleManager(records, runtime, grants, data)
        val report = restarted.rebuildUserPlugins { FakeSpecPlugin(it) }

        assertEquals(listOf(enabled.id), report.restored)
        assertEquals(listOf(disabled.id), report.rebuiltDisabled)
        assertEquals(PluginState.ENABLED, restarted.get(enabled.id)?.state)
        assertEquals(PluginState.DISABLED, restarted.get(disabled.id)?.state)
    }

    @Test
    fun rebuildKeepsAnUnsupportedUserPluginVisibleAsFailed() {
        val spec = PluginSpec("mcp.gone", "1.0.0", PluginTarget.HARNESS_REMOTE)
        manager.installUserPlugin(spec) { FakeSpecPlugin(it) }
        manager.enable(spec.id)

        val restarted = PluginLifecycleManager(records, runtime, grants, data)
        val report = restarted.rebuildUserPlugins { null }

        assertEquals(listOf(spec.id), report.unsupported)
        val record = restarted.get(spec.id)
        assertEquals(PluginState.FAILED, record?.state)
        assertTrue(record?.lastError?.isNotBlank() == true)
        // The durable record survives, so the user can inspect or remove it rather than losing it.
        assertEquals(spec, records.get(spec.id)?.spec)
    }

    @Test
    fun rebuildNeverResurrectsBundledPluginsFromTheDatabase() {
        records.put(PluginRecord(FakePlugin(events, pluginId = "core.shell").spec, PluginOrigin.BUNDLED_SYSTEM, PluginState.ENABLED))
        val restarted = PluginLifecycleManager(records, runtime, grants, data)
        val report = restarted.rebuildUserPlugins { FakeSpecPlugin(it) }
        assertEquals(emptyList(), report.restored)
        assertEquals(emptyList(), report.unsupported)
        assertEquals(PluginState.ENABLED, restarted.get("core.shell")?.state)
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
        dependencies: Set<String> = emptySet(),
    ) : ManagedPlugin {
        override val spec = PluginSpec(pluginId, "1.0.0", PluginTarget.ANDROID_NATIVE, setOf("workspace.read"), dependencies)
        override fun onRegister() { events += "register"; if (throwsOnRegister) error("register test failure") }
        override fun onEnable() { events += "enable"; if (throwsOnEnable) error("enable test failure") }
        override fun onDisable() { events += "disable" }
        override fun onUninstall() { events += "uninstall" }
    }

    private class FakeSpecPlugin(private val spec0: PluginSpec) : ManagedPlugin {
        override val spec: PluginSpec = spec0
    }

    @Test
    fun `enable invokes onEnable without holding the manager lock`() {
        val events = mutableListOf<String>()
        val records = FakeRecords()
        val manager = PluginLifecycleManager(
            records = records,
            runtime = FakeRuntime(events),
            capabilities = FakeCapabilities(events),
            ownedData = FakeData(events),
            bundledVerifier = BundledPluginVerifier { false },
        )
        val spec = PluginSpec("user.slow", "1.0.0", PluginTarget.ANDROID_NATIVE)
        val reachedOnEnable = java.util.concurrent.CountDownLatch(1)
        val allowComplete = java.util.concurrent.CountDownLatch(1)
        var readSucceededConcurrently = false

        val slowPlugin = object : ManagedPlugin {
            override val spec: PluginSpec = spec
            override fun onEnable() {
                reachedOnEnable.countDown()
                allowComplete.await(2, java.util.concurrent.TimeUnit.SECONDS)
            }
        }

        manager.register(slowPlugin)
        val enableThread = Thread {
            manager.enable(spec.id)
        }
        enableThread.start()

        assertTrue(reachedOnEnable.await(2, java.util.concurrent.TimeUnit.SECONDS))
        // While onEnable is still blocking in the enable thread, calling all() from another thread must not block
        val currentRecords = manager.all()
        readSucceededConcurrently = currentRecords.any { it.spec.id == spec.id && it.state == PluginState.ENABLING }
        allowComplete.countDown()
        enableThread.join(2000)

        assertTrue(readSucceededConcurrently, "all() should have read records concurrently during onEnable")
        assertEquals(PluginState.ENABLED, manager.get(spec.id)?.state)
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
