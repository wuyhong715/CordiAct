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
            assertEquals("instrumentation process remains responsive", 2 + 2)
        } finally {
            session.kill()
        }
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
