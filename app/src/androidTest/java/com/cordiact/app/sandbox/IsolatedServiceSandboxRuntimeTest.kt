package com.cordiact.app.sandbox

import android.content.Context
import android.os.Build
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
            for (kind in listOf("wrong-token", "truncated", "large-input", "trailing-data")) {
                assertTrue("Raw Binder frame accepted: $kind", session.malformedBinderFrameRejectedForTest(kind))
                assertTrue("Malformed frame killed isolated worker: $kind", session.isAlive)
                assertEquals("ok", session.executeProbe("echo", "ok", 2_000))
            }
        } finally { session.terminate(2_000) }
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

    private fun waitUntil(timeoutMillis: Long, predicate: () -> Boolean): Boolean {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis)
        while (System.nanoTime() < deadline) {
            if (predicate()) return true
            Thread.sleep(25)
        }
        return predicate()
    }
}
