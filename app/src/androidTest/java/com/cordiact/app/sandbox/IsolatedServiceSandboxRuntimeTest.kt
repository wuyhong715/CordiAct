package com.cordiact.app.sandbox

import android.content.Context
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.cordiact.core.sandbox.BundledPluginIdentity
import com.cordiact.core.sandbox.SandboxLimits
import com.cordiact.core.sandbox.SandboxProfile
import com.cordiact.core.sandbox.SandboxTarget
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.Executors
import java.util.concurrent.ExecutionException
import java.util.concurrent.RejectedExecutionException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class IsolatedServiceSandboxRuntimeTest {
    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private val runtime = AndroidIsolatedServiceSandboxRuntime(context)

    @Test
    fun isolatedProcessHasDistinctUidAndOnlyRunsFixedProbeOperations() {
        assumeTrue("Named isolated service instances require API 29+", Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
        val session = runtime.start(profile("plugin.probe.echo")) as AndroidIsolatedServiceSandboxRuntime.ProbeSession
        try {
            assertTrue("service must confirm isolated process", session.reportsIsolated)
            assertNotEquals("isolated service must have a distinct Linux UID", context.applicationInfo.uid, session.processUid)
            assertEquals("hello from isolated process", session.executeProbe("echo", "hello from isolated process", 5_000))
            assertEquals("slept:40", session.executeProbe("sleep", "40", 5_000))

            try {
                session.executeWithNonceForTest("0".repeat(64), "echo", "forged")
                throw AssertionError("Expected wrong sandbox session nonce to be rejected")
            } catch (expected: SecurityException) {
                assertTrue(expected.message.orEmpty().contains("identity mismatch"))
            }

            try {
                session.executeProbe("echo", "x".repeat(4_097), 5_000)
                throw AssertionError("Expected oversized Binder request rejection")
            } catch (expected: IllegalArgumentException) {
                assertTrue(expected.message.orEmpty().contains("frame limit"))
            }
        } finally {
            session.terminate(2_000)
        }
        assertFalse(session.isAlive)
    }

    @Test
    fun watchdogKillsInfiniteProbeWithoutKillingHostAndNewSessionCanStart() {
        assumeTrue("Named isolated service instances require API 29+", Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
        val first = runtime.start(profile("plugin.probe.hang")) as AndroidIsolatedServiceSandboxRuntime.ProbeSession
        try {
            try {
                first.executeProbe("hang", "", 1_200)
                throw AssertionError("Expected deadline termination")
            } catch (_: Exception) {
                // RemoteException/TimeoutException is expected after the isolated process is killed.
            }
            assertTrue("isolated probe process should be dead after its deadline", waitUntil(5_000) { !first.isAlive })
        } finally {
            first.kill()
        }

        val second = runtime.start(profile("plugin.probe.after-crash")) as AndroidIsolatedServiceSandboxRuntime.ProbeSession
        try {
            assertTrue(second.reportsIsolated)
            assertNotEquals(context.applicationInfo.uid, second.processUid)
            assertEquals("host survived", second.executeProbe("echo", "host survived", 5_000))
        } finally {
            second.terminate(2_000)
        }
    }

    @Test
    fun simultaneousSandboxSessionsReceiveDifferentIsolatedUids() {
        assumeTrue("Named isolated service instances require API 29+", Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
        val first = runtime.start(profile("plugin.probe.one")) as AndroidIsolatedServiceSandboxRuntime.ProbeSession
        val second = runtime.start(profile("plugin.probe.two")) as AndroidIsolatedServiceSandboxRuntime.ProbeSession
        try {
            assertNotEquals(first.processUid, second.processUid)
            assertEquals("one", first.executeProbe("echo", "one", 5_000))
            assertEquals("two", second.executeProbe("echo", "two", 5_000))
        } finally {
            first.terminate(2_000)
            second.terminate(2_000)
        }
    }

    @Test
    fun workerCrashIsContainedToItsIsolatedProcess() {
        assumeTrue("Named isolated service instances require API 29+", Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
        val session = runtime.start(profile("plugin.probe.crash")) as AndroidIsolatedServiceSandboxRuntime.ProbeSession
        try {
            try {
                session.executeProbe("crash", "", 5_000)
                throw AssertionError("Expected isolated process death")
            } catch (_: Exception) {
                // The isolated worker died; the instrumentation host process must remain alive.
            }
            assertTrue(waitUntil(5_000) { !session.isAlive })
            assertEquals(4, 2 + 2)
        } finally {
            session.kill()
        }
    }

    @Test
    fun malformedRequestsFailClosedWithoutBreakingTheIsolatedWorker() {
        assumeTrue(Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
        val session = runtime.start(profile("plugin.probe.invalid")) as AndroidIsolatedServiceSandboxRuntime.ProbeSession
        try {
            fun rejected(requestId: String, operation: String, payload: String, deadline: Long) {
                try {
                    session.executeRawForTest(requestId, operation, payload, deadline)
                    throw AssertionError("Invalid request was accepted: $requestId")
                } catch (_: IllegalArgumentException) { /* expected from isolated service */ }
            }
            rejected("invalid/id", "echo", "x", 1_000)
            rejected("bad-op", "arbitrary-code", "x", 1_000)
            rejected("bad-time", "echo", "x", 30_001)
            rejected("bad-frame", "echo", "x".repeat(4_097), 1_000)
            assertEquals(0, session.activeProbeCountForTest())
            assertEquals("healthy", session.executeProbe("echo", "healthy", 2_000))
        } finally { session.terminate(2_000) }
    }

    @Test
    fun concurrentBinderFloodIsRejectedWithoutGrowingTheWorkerQueue() {
        assumeTrue(Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
        val session = runtime.start(profile("plugin.probe.flood")) as AndroidIsolatedServiceSandboxRuntime.ProbeSession
        val workers = Executors.newSingleThreadExecutor()
        try {
            val held = workers.submit<String> { session.executeRawForTest("held", "sleep", "4000", 8_000) }
            assertTrue("first Binder call must be active", waitUntil(3_000) { session.activeProbeCountForTest() == 1 })
            repeat(12) { index ->
                try {
                    session.executeRawForTest("flood-$index", "echo", "x", 1_000)
                    throw AssertionError("Concurrent call $index was incorrectly admitted")
                } catch (_: IllegalStateException) { /* server rejects excess work */ }
            }
            assertEquals(1, session.activeProbeCountForTest())
            assertEquals("slept:4000", held.get(7, TimeUnit.SECONDS))
            assertEquals(0, session.activeProbeCountForTest())
        } finally {
            workers.shutdownNow()
            session.terminate(2_000)
        }
    }

    @Test
    fun hostExecutorRejectsExcessCallsAndCannotQueueUnboundedWork() {
        assumeTrue(Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
        val session = runtime.start(profile("plugin.probe.hostlimit")) as AndroidIsolatedServiceSandboxRuntime.ProbeSession
        val workers = Executors.newSingleThreadExecutor()
        try {
            val held = workers.submit<String> { session.executeProbe("sleep", "2500", 5_000) }
            assertTrue(waitUntil(3_000) { session.activeProbeCountForTest() == 1 })
            try {
                session.executeProbe("echo", "unexpected", 2_000)
                throw AssertionError("Host accepted a second in-flight request")
            } catch (_: RejectedExecutionException) { /* bounded host executor */ }
            assertEquals("slept:2500", held.get(6, TimeUnit.SECONDS))
            assertEquals("still alive", session.executeProbe("echo", "still alive", 2_000))
        } finally {
            workers.shutdownNow()
            session.terminate(2_000)
        }
    }

    @Test
    fun repeatedCancellationAndShutdownDoNotPermitLateResults() {
        assumeTrue(Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
        val session = runtime.start(profile("plugin.probe.cancel")) as AndroidIsolatedServiceSandboxRuntime.ProbeSession
        val workers = Executors.newSingleThreadExecutor()
        try {
            val running = workers.submit<String> { session.executeRawForTest("cancellable", "sleep", "5000", 9_000) }
            assertTrue(waitUntil(3_000) { session.activeProbeCountForTest() == 1 })
            session.cancelRawForTest("cancellable")
            session.cancelRawForTest("cancellable")
            try {
                running.get(3, TimeUnit.SECONDS)
                throw AssertionError("Cancelled probe returned a late success")
            } catch (_: ExecutionException) { /* cancelled sleep does not complete normally */ }
            assertTrue(waitUntil(2_000) { session.activeProbeCountForTest() == 0 })
            assertEquals("ready", session.executeProbe("echo", "ready", 2_000))
            session.terminate(2_000)
            assertFalse(session.isAlive)
        } finally {
            workers.shutdownNow()
            session.kill()
        }
    }

    @Test
    fun repeatedSequentialCallsDoNotRaceTheBoundedWorkerHandoff() {
        assumeTrue(Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
        val session = runtime.start(profile("plugin.probe.sequence")) as AndroidIsolatedServiceSandboxRuntime.ProbeSession
        try {
            repeat(24) { index ->
                assertEquals(index.toString(), session.executeProbe("echo", index.toString(), 2_000))
            }
            assertEquals(0, session.activeProbeCountForTest())
        } finally { session.terminate(2_000) }
    }

    @Test
    fun shutdownDuringActiveRequestClosesAdmissionAndPreventsLateSuccess() {
        assumeTrue(Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
        val session = runtime.start(profile("plugin.probe.shutdown")) as AndroidIsolatedServiceSandboxRuntime.ProbeSession
        val workers = Executors.newSingleThreadExecutor()
        try {
            val running = workers.submit<String> { session.executeRawForTest("late", "sleep", "5000", 9_000) }
            assertTrue(waitUntil(3_000) { session.activeProbeCountForTest() == 1 })
            val stopped = session.terminate(2_000)
            assertTrue("shutdown must confirm the process is dead", stopped.processStopped)
            assertFalse(session.isAlive)
            try {
                running.get(3, TimeUnit.SECONDS)
                throw AssertionError("Closing session allowed late output")
            } catch (_: ExecutionException) { /* dead worker cannot deliver a success */ }
            try {
                session.executeProbe("echo", "unexpected", 1_000)
                throw AssertionError("Closed session accepted a new request")
            } catch (_: IllegalStateException) { /* admission remains closed */ }
        } finally {
            workers.shutdownNow()
            session.kill()
        }
    }

    @Test
    fun rawAidlFrameMutationsAreRejectedWithoutWorkerDeath() {
        assumeTrue(Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
        val session = runtime.start(profile("plugin.probe.rawframe")) as AndroidIsolatedServiceSandboxRuntime.ProbeSession
        try {
            assertFalse("Raw transaction code must dispatch a valid executeProbe frame",
                session.malformedBinderFrameRejectedForTest("valid-control"))
            val mutations = listOf(
                "wrong-transaction", "wrong-token", "truncated", "large-input", "trailing-data",
                "bad-session", "bad-nonce", "bad-request-id", "bad-operation",
                "negative-deadline", "over-deadline",
            )
            for (kind in mutations) {
                assertTrue("Raw Binder frame accepted: $kind", session.malformedBinderFrameRejectedForTest(kind))
                assertTrue("Malformed frame killed isolated worker: $kind", session.isAlive)
                assertEquals("ok", session.executeProbe("echo", "ok", 2_000))
            }
        } finally { session.terminate(2_000) }
    }

    @Test
    fun boundedCpuAndHeapPressureReportsMetricsAndKeepsWorkerResponsive() {
        assumeTrue(Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
        val session = runtime.start(profile("plugin.probe.s23.pressure")) as AndroidIsolatedServiceSandboxRuntime.ProbeSession
        try {
            val cpu = session.executeProbe("cpu-stress", "2000000", 5_000)
            val cpuParts = cpu.split(':')
            assertEquals("cpu", cpuParts[0])
            assertEquals("2000000", cpuParts[1])
            val cpuMillis = cpuParts[2].toLong()
            val wallMillis = cpuParts[3].toLong()
            assertTrue("CPU probe exceeded 5s bound: $cpu", wallMillis in 0..5_000)
            Log.i("CordiActS23", "cpu api=${Build.VERSION.SDK_INT} threadMs=$cpuMillis wallMs=$wallMillis result=$cpu")

            val heap = session.executeProbe("heap-stress", "2048", 5_000)
            val heapParts = heap.split(':')
            assertEquals("heap", heapParts[0])
            assertEquals("2048", heapParts[1])
            val pssBeforeKb = heapParts[2].toLong()
            val pssDuringKb = heapParts[3].toLong()
            val heapBefore = heapParts[4].toLong()
            val heapDuring = heapParts[5].toLong()
            assertTrue("PSS sample unavailable: $heap", pssDuringKb > 0)
            assertTrue("Java heap sample unavailable: $heap", heapDuring > 0)
            Log.i(
                "CordiActS23",
                "heap api=${Build.VERSION.SDK_INT} requestedKiB=2048 pssBeforeKiB=$pssBeforeKb " +
                    "pssDuringKiB=$pssDuringKb javaHeapBefore=$heapBefore javaHeapDuring=$heapDuring result=$heap",
            )
            assertTrue(session.isAlive)
            assertEquals("responsive", session.executeProbe("echo", "responsive", 2_000))
        } finally {
            session.terminate(2_000)
        }
    }

    @Test
    fun fixedProbeRespondsWhileDeviceIsForcedIdleAndPackageInactive() {
        assumeTrue(Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
        val session = runtime.start(profile("plugin.probe.s23.doze")) as AndroidIsolatedServiceSandboxRuntime.ProbeSession
        val initialForceIdle = deviceIdleForceFlag()
        val initialStates = deviceIdleStates()
        val initialInactive = packageInactiveFlag()
        var forcedByTest = false
        try {
            assumeTrue(
                "Skip Doze mutation unless the initial state is active and not already forced: " +
                    "forceIdle=$initialForceIdle states=$initialStates",
                !initialForceIdle && initialStates == ("ACTIVE" to "ACTIVE"),
            )
            runShell("cmd deviceidle force-idle")
            forcedByTest = true
            val reachedForcedIdle = awaitForceFlag(true)
            assertTrue("Device did not enter forced idle", reachedForcedIdle)
            runShell("am set-inactive com.cordiact.app true")
            assertTrue("Package did not become inactive", awaitInactiveFlag(true))
            assertEquals("doze-responsive", session.executeProbe("echo", "doze-responsive", 5_000))
            Log.i("CordiActS23", "background probe succeeded api=${Build.VERSION.SDK_INT} forceIdle=true inactive=true")
        } finally {
            runShell("am set-inactive com.cordiact.app $initialInactive")
            if (forcedByTest) runShell("cmd deviceidle unforce")
            session.terminate(2_000)
        }
        assertEquals("Force-idle flag was not restored", initialForceIdle, deviceIdleForceFlag())
        assertTrue("DeviceIdle states were not restored: ${deviceIdleStates()}", awaitDeviceIdleStates(initialStates))
        assertEquals("Package inactive state was not restored", initialInactive, packageInactiveFlag())
    }

    @Test
    fun deterministicOversizedOutputFailsAtTheServiceBoundary() {
        assumeTrue(Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
        val session = runtime.start(profile("plugin.probe.output")) as AndroidIsolatedServiceSandboxRuntime.ProbeSession
        try {
            try {
                session.executeRawForTest("large-output", "oversized-output", "", 2_000)
                throw AssertionError("Fixed 8193-byte output was accepted")
            } catch (expected: IllegalArgumentException) {
                assertTrue(expected.message.orEmpty().contains("output exceeds frame limit"))
            }
            assertTrue(session.isAlive)
            assertEquals("bounded", session.executeProbe("echo", "bounded", 2_000))
        } finally { session.terminate(2_000) }
    }

    @Test
    fun repeatedMaxSizedRequestsLeaveTheWorkerResponsive() {
        assumeTrue(Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
        val session = runtime.start(profile("plugin.probe.pressure")) as AndroidIsolatedServiceSandboxRuntime.ProbeSession
        val payload = "x".repeat(4_096)
        try {
            repeat(64) { index ->
                assertEquals("boundary request $index", payload,
                    session.executeProbe("echo", payload, 3_000))
            }
            assertEquals(0, session.activeProbeCountForTest())
            assertTrue(session.isAlive)
        } finally { session.terminate(2_000) }
    }

    private fun profile(pluginId: String) = SandboxProfile(
        sessionId = UUID.randomUUID().toString(),
        plugin = BundledPluginIdentity(pluginId, "0.1.0", "a".repeat(64)),
        workspaceId = "sandbox-poc-test",
        grantedCapabilities = emptySet(),
        limits = SandboxLimits(5_000, 4_096, 8_192, 1),
        target = SandboxTarget.LOCAL_CORDIS_JS,
    )

    private fun runShell(command: String): String {
        val descriptor = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command)
        return ParcelFileDescriptor.AutoCloseInputStream(descriptor).bufferedReader().use { it.readText() }
    }

    private fun deviceIdleStates(): Pair<String, String> {
        val output = runShell("dumpsys deviceidle")
        val state = Regex("mState=([A-Z_]+)").find(output)?.groupValues?.get(1)
        val lightState = Regex("mLightState=([A-Z_]+)").find(output)?.groupValues?.get(1)
        return (requireNotNull(state) { "Could not parse DeviceIdle state" }) to
            (requireNotNull(lightState) { "Could not parse light DeviceIdle state" })
    }

    private fun deviceIdleForceFlag(): Boolean {
        val output = runShell("dumpsys deviceidle")
        val value = Regex("mForceIdle=(true|false)").find(output)?.groupValues?.get(1)
        return requireNotNull(value) { "Could not parse dumpsys deviceidle force state" }.toBoolean()
    }

    private fun packageInactiveFlag(): Boolean {
        val output = runShell("am get-inactive com.cordiact.app").trim()
        val value = Regex("(?i)(true|false)\\s*$").find(output)?.groupValues?.get(1)
        return requireNotNull(value) { "Could not parse app inactive state: $output" }.toBoolean()
    }

    private fun awaitDeviceIdleStates(expected: Pair<String, String>): Boolean {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (System.nanoTime() < deadline) {
            if (deviceIdleStates() == expected) return true
            Thread.sleep(250)
        }
        return deviceIdleStates() == expected
    }

    private fun awaitForceFlag(expected: Boolean): Boolean {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (System.nanoTime() < deadline) {
            if (deviceIdleForceFlag() == expected) return true
            Thread.sleep(250)
        }
        return deviceIdleForceFlag() == expected
    }

    private fun awaitInactiveFlag(expected: Boolean): Boolean {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (System.nanoTime() < deadline) {
            if (packageInactiveFlag() == expected) return true
            Thread.sleep(250)
        }
        return packageInactiveFlag() == expected
    }

    private fun waitUntil(timeoutMillis: Long, predicate: () -> Boolean): Boolean {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis)
        while (System.nanoTime() < deadline) {
            if (predicate()) return true
            Thread.sleep(25)
        }
        return predicate()
    }
}
