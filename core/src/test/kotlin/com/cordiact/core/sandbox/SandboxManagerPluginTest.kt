package com.cordiact.core.sandbox

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class SandboxManagerPluginTest {
    private val revoked = mutableListOf<String>()
    private val identity = BundledPluginIdentity("plugin.notes", "1.0.0", "a".repeat(64))
    private val request = SandboxLaunchRequest(
        plugin = identity,
        workspaceId = "workspace-1",
        requestedCapabilities = setOf("workspace.read", "model.generate"),
        limits = SandboxLimits(5_000, 4_096, 8_192, 1),
    )

    @Test
    fun defaultRuntimeFailsClosedWithoutInProcessFallback() {
        val manager = manager(runtime = UnavailableIsolatedSandboxRuntime)
        manager.onEnable()
        assertFailsWith<IllegalStateException> { manager.launch(request) }
        assertTrue(manager.instances().isEmpty())
    }

    @Test
    fun admitsOnlyVerifiedBundledPluginAndUsesHostResolvedCapabilities() {
        val runtime = FakeRuntime()
        val manager = manager(runtime = runtime, granted = setOf("workspace.read"))
        manager.onEnable()
        val instance = manager.launch(request)
        assertEquals(SandboxInstanceState.RUNNING, instance.state)
        assertEquals("workspace-1", instance.workspaceId)
        assertEquals(44_001, instance.processUid)
        assertEquals(setOf("workspace.read"), runtime.startedProfiles.single().grantedCapabilities)

        val stopped = manager.terminate(instance.sessionId)
        assertEquals(SandboxInstanceState.STOPPED, stopped.state)
        assertTrue(manager.instances().isEmpty())
        assertEquals(listOf(instance.sessionId), revoked)
    }

    @Test
    fun rejectsUnbundledOrInvalidDigestBeforeStartingRuntime() {
        val runtime = FakeRuntime()
        val manager = manager(runtime = runtime, verified = false)
        manager.onEnable()
        assertFailsWith<IllegalStateException> { manager.launch(request) }
        assertTrue(runtime.startedProfiles.isEmpty())
        assertFailsWith<IllegalArgumentException> { manager.launch(request.copy(plugin = identity.copy(sha256 = "bad"))) }
    }

    @Test
    fun rejectsRuntimeThatClaimsInProcessOrUsesHostUidAndKillsIt() {
        val badModeRuntime = FakeRuntime(mode = SandboxIsolationKind.IN_PROCESS)
        val first = manager(runtime = badModeRuntime)
        first.onEnable()
        assertFailsWith<IllegalStateException> { first.launch(request) }
        assertTrue(badModeRuntime.sessions.isEmpty())

        val sameUidRuntime = FakeRuntime(processUid = APP_UID)
        val second = manager(runtime = sameUidRuntime)
        second.onEnable()
        assertFailsWith<IllegalStateException> { second.launch(request) }
        assertTrue(sameUidRuntime.sessions.single().killed)
    }

    @Test
    fun policyCannotGrantCapabilitiesThePluginDidNotRequest() {
        val runtime = FakeRuntime()
        val manager = manager(runtime = runtime, granted = setOf("android.shell"))
        manager.onEnable()
        assertFailsWith<IllegalStateException> { manager.launch(request) }
        assertTrue(runtime.startedProfiles.isEmpty())
    }

    @Test
    fun hostLimitCeilingsCannotBeRelaxedByPluginRequest() {
        val manager = manager(runtime = FakeRuntime())
        manager.onEnable()
        assertFailsWith<IllegalArgumentException> {
            manager.launch(request.copy(limits = request.limits.copy(maxWallClockMillis = 60_001)))
        }
    }

    @Test
    fun disableTerminatesInstancesAndRevokesGrantsBeforeCompleting() {
        val runtime = FakeRuntime()
        val manager = manager(runtime = runtime)
        manager.onEnable()
        val instance = manager.launch(request)
        manager.onDisable()
        assertTrue(runtime.sessions.single().killed)
        assertTrue(manager.instances().isEmpty())
        assertEquals(listOf(instance.sessionId), revoked)
    }

    @Test
    fun failedKillKeepsSessionVisibleAndDisableFailsClosed() {
        val runtime = FakeRuntime(neverDies = true)
        val manager = manager(runtime = runtime)
        manager.onEnable()
        val instance = manager.launch(request)
        assertFailsWith<IllegalStateException> { manager.terminate(instance.sessionId) }
        assertEquals(SandboxInstanceState.FAILED, manager.instances().single().state)
        assertFailsWith<IllegalStateException> { manager.onDisable() }
        assertEquals(SandboxInstanceState.FAILED, manager.instances().single().state)
    }

    private fun manager(
        runtime: IsolatedSandboxRuntime,
        granted: Set<String> = request.requestedCapabilities,
        verified: Boolean = true,
    ) = SandboxManagerPlugin(
        appUid = APP_UID,
        bundledVerifier = BundledPluginVerifier { verified && it == identity },
        capabilityPolicy = SandboxCapabilityPolicy { granted },
        sessionRevoker = SandboxSessionRevoker { revoked += it },
        runtime = runtime,
        idFactory = { "sandbox-session" },
    )

    private class FakeRuntime(
        private val mode: SandboxIsolationKind = SandboxIsolationKind.ANDROID_ISOLATED_SERVICE,
        private val processUid: Int = 44_001,
        private val neverDies: Boolean = false,
    ) : IsolatedSandboxRuntime {
        override val isolationKind = mode
        val startedProfiles = mutableListOf<SandboxProfile>()
        val sessions = mutableListOf<FakeSession>()
        override fun start(profile: SandboxProfile): SandboxRuntimeSession {
            startedProfiles += profile
            return FakeSession(mode, processUid, neverDies).also { sessions += it }
        }
    }

    private class FakeSession(
        override val isolationKind: SandboxIsolationKind,
        override val processUid: Int,
        private val neverDies: Boolean,
    ) : SandboxRuntimeSession {
        var killed = false
        override var isAlive = true
            private set
        override fun terminate(gracePeriodMillis: Long): SandboxTermination {
            if (!neverDies) isAlive = false
            return SandboxTermination(processStopped = !isAlive)
        }
        override fun kill() {
            killed = true
            if (!neverDies) isAlive = false
        }
    }

    private companion object { const val APP_UID = 10_123 }
}
