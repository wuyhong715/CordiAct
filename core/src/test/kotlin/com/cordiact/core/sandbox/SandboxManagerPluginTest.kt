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
        assertTrue(!runtime.sessions.single().isAlive)
        assertTrue(!runtime.sessions.single().killed) // graceful stop succeeded; force kill was unnecessary
        assertTrue(manager.instances().isEmpty())
        assertEquals(listOf(instance.sessionId), revoked)
    }

    @Test
    fun disableContinuesAcrossRevocationFailuresAndKeepsFailedSessionsForRetry() {
        val runtime = FakeRuntime()
        val attempts = mutableListOf<String>()
        val failingId = "session-1"
        var failFirstRevoke = true
        val manager = manager(
            runtime = runtime,
            ceilings = SandboxLimitCeilings(maxInstancesPerPlugin = 2),
            sessionIdFactory = { "session-${runtime.startedProfiles.size + 1}" },
            revoker = { id ->
                attempts += id
                if (id == failingId && failFirstRevoke) {
                    failFirstRevoke = false
                    throw IllegalStateException("policy store unavailable")
                }
                revoked += id
            },
        )
        manager.onEnable()
        val first = manager.launch(request)
        val second = manager.launch(request)

        val error = assertFailsWith<IllegalStateException> { manager.onDisable() }
        assertTrue(error.message.orEmpty().contains("could not be fully terminated"))
        assertTrue(manager.instances().any { it.sessionId == first.sessionId && it.state == SandboxInstanceState.FAILED })
        assertTrue(manager.instances().none { it.sessionId == second.sessionId })
        assertEquals(2, attempts.size)
        assertEquals(listOf(first.sessionId, second.sessionId), attempts)
        assertEquals(listOf(second.sessionId), revoked)

        val recovered = manager.terminate(first.sessionId)
        assertEquals(SandboxInstanceState.STOPPED, recovered.state)
        assertTrue(manager.instances().isEmpty())
        assertEquals(3, attempts.size)
        assertEquals(first.sessionId, attempts.last())
        assertEquals(listOf(second.sessionId, first.sessionId), revoked)
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
        revoker: (String) -> Unit = { revoked += it },
        sessionIdFactory: () -> String = { "sandbox-session" },
        ceilings: SandboxLimitCeilings = SandboxLimitCeilings(),
    ) = SandboxManagerPlugin(
        appUid = APP_UID,
        bundledVerifier = BundledPluginVerifier { verified && it == identity },
        capabilityPolicy = SandboxCapabilityPolicy { granted },
        sessionRevoker = SandboxSessionRevoker(revoker),
        runtime = runtime,
        ceilings = ceilings,
        idFactory = sessionIdFactory,
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
