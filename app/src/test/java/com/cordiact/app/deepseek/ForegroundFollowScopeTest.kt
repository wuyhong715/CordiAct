package com.cordiact.app.deepseek

import com.cordiact.harness.deepseek.FollowPairingHandoff
import com.cordiact.harness.deepseek.FollowRouteController
import com.cordiact.harness.deepseek.FollowRouteDescriptor
import com.cordiact.harness.deepseek.FollowTarget
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.security.KeyPairGenerator
import java.security.spec.ECGenParameterSpec
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ForegroundFollowScopeTest {
    private val target = FollowTarget("host", "local", "session")
    private val signer = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
    private val descriptor = checkNotNull(FollowRouteDescriptor.parse(
        "cordiact-follow-route/1 host local remote session 127.0.0.1 12345 ${"ab".repeat(32)} ${signer.public.encoded.toHexString()}\n",
    ))

    @Test fun backgroundAndDestructionDoNotAdmitNewWork() = withScope { scope ->
        assertNull(configure(scope))
        scope.resume()
        val route = checkNotNull(configure(scope))
        val old = checkNotNull(scope.begin(route))
        scope.pause()
        val output = ByteArrayOutputStream()
        val releases = AtomicInteger()
        assertNull(scope.exchange(old, FollowPairingHandoff(ByteArrayInputStream(byteArrayOf()), output) { releases.incrementAndGet() }))
        assertEquals(0, output.size())
        assertEquals(1, releases.get())
        scope.resume()
        val current = checkNotNull(scope.begin(route)) // Same trust, explicitly started fresh attempt.
        assertNotSame(old, current)
        assertFalse(scope.cancel(old))
        assertTrue(scope.cancel(current))
        scope.close()
        scope.resume()
        assertNull(configure(scope))
        assertNull(scope.begin(route))
        assertFalse(scope.clear(route))
    }

    @Test fun pauseUnblocksInFlightHandoffAndLateCompletionCannotTouchNewAttempt() =
        blockedHandoff { scope, route ->
            scope.pause()
            assertNull(scope.begin(route))
            scope.resume()
            route
        }

    @Test fun closingPageUnblocksItsHandoffAndOldCallbacksCannotUseReplacementTrust() =
        blockedHandoff { scope, route ->
            assertTrue(scope.clear(route))
            assertFalse(scope.clear(route))
            assertNull(scope.begin(route))
            val replacement = checkNotNull(configure(scope)) // Same visible IDs, independent lifetime.
            assertNotSame(route, replacement)
            assertFalse(scope.clear(route))
            assertNull(scope.begin(route))
            replacement
        }

    @Test fun pageMayReleaseTrustWhilePausedWithoutAffectingAnotherActivity() = withScope { scope ->
        scope.resume()
        val route = checkNotNull(configure(scope))
        withScope { other ->
            other.resume()
            val foreign = checkNotNull(configure(other))
            assertFalse(scope.clear(foreign))
            assertNull(scope.begin(foreign))
            scope.pause()
            assertTrue(scope.clear(route))
            scope.resume()
            assertNull(scope.begin(route))
            checkNotNull(other.begin(foreign))
        }
    }

    private fun blockedHandoff(retire: (ForegroundFollowScope, FollowRouteController.Route) -> FollowRouteController.Route) = withScope { scope ->
        val worker = Executors.newSingleThreadExecutor()
        val reading = CountDownLatch(1)
        val released = CountDownLatch(1)
        val cancellations = AtomicInteger()
        try {
            scope.resume()
            val route = checkNotNull(configure(scope))
            val old = checkNotNull(scope.begin(route))
            val input = object : InputStream() {
                override fun read(): Int {
                    reading.countDown()
                    check(released.await(3, TimeUnit.SECONDS))
                    return -1
                }
            }
            val handoff = FollowPairingHandoff(input, ByteArrayOutputStream()) {
                cancellations.incrementAndGet()
                released.countDown()
            }
            val result = worker.submit(Callable { scope.exchange(old, handoff) })
            assertTrue(reading.await(2, TimeUnit.SECONDS))
            val next = retire(scope, route)
            val current = checkNotNull(scope.begin(next))
            assertNull(result.get(2, TimeUnit.SECONDS))
            assertEquals(1, cancellations.get())
            assertFalse(scope.cancel(old))
            assertTrue(scope.cancel(current))
        } finally {
            released.countDown()
            worker.shutdownNow()
        }
    }

    @Test fun staleConfigurationCannotReplaceSameIdRouteOrConsumeNewIntent() = withScope { scope ->
        assertNull(scope.requestConfiguration(null, target, "remote"))
        scope.resume()
        val original = checkNotNull(configure(scope))
        val old = checkNotNull(scope.requestConfiguration(original, target, "remote"))
        val fresh = checkNotNull(scope.requestConfiguration(original, target, "remote"))
        assertNull(scope.requestConfiguration(null, target, "remote"))
        val key = ByteArray(32) { 1 }
        assertNull(scope.configure(old, descriptor, key))
        assertFalse(scope.discardConfiguration(old))
        checkNotNull(scope.begin(original))
        val next = checkNotNull(scope.configure(fresh, descriptor, key))
        assertNotSame(original, next)
        assertNull(scope.requestConfiguration(original, target, "remote"))
        assertNull(scope.configure(fresh, descriptor, key))
        assertNull(scope.begin(original))
        checkNotNull(scope.begin(next))
    }

    @Test fun discardedPausedAndRejectedConfigurationResultsCannotInstallTrust() = withScope { scope ->
        scope.resume()
        val original = checkNotNull(configure(scope))
        val key = ByteArray(32) { 2 }
        val pending = checkNotNull(scope.requestConfiguration(original, target, "remote"))
        scope.pause()
        scope.resume()
        assertNull(scope.configure(pending, descriptor, key))
        val discarded = checkNotNull(scope.requestConfiguration(original, target, "remote"))
        assertTrue(scope.discardConfiguration(discarded))
        assertNull(scope.configure(discarded, descriptor, key))
        val wrongTarget = checkNotNull(scope.requestConfiguration(original, target.copy(localWorkspaceId = "other"), "remote"))
        assertNull(scope.configure(wrongTarget, descriptor, key))
        val wrongKeySize = checkNotNull(scope.requestConfiguration(original, target, "remote"))
        assertNull(scope.configure(wrongKeySize, descriptor, ByteArray(31)))
        assertNull(scope.configure(wrongKeySize, descriptor, key))
        checkNotNull(scope.begin(original))
    }

    @Test fun pageExitInvalidatesPendingReplacementButStalePageExitDoesNot() = withScope { scope ->
        scope.resume()
        val first = checkNotNull(configure(scope))
        val secondIntent = checkNotNull(scope.requestConfiguration(first, target, "remote"))
        val second = checkNotNull(scope.configure(secondIntent, descriptor, ByteArray(32) { 2 }))
        val pending = checkNotNull(scope.requestConfiguration(second, target, "remote"))
        assertFalse(scope.clear(first))
        assertFalse(scope.discardConfiguration(secondIntent))
        val third = checkNotNull(scope.configure(pending, descriptor, ByteArray(32) { 3 }))
        val abandoned = checkNotNull(scope.requestConfiguration(third, target, "remote"))
        assertTrue(scope.clear(third))
        assertNull(scope.configure(abandoned, descriptor, ByteArray(32) { 4 }))
        checkNotNull(configure(scope))
    }

    private fun configure(scope: ForegroundFollowScope): FollowRouteController.Route? =
        scope.requestConfiguration(null, target, "remote")?.let { scope.configure(it, descriptor, ByteArray(32) { 1 }) }

    private fun withScope(test: (ForegroundFollowScope) -> Unit) {
        val scheduler = Executors.newSingleThreadScheduledExecutor()
        try {
            val controller = checkNotNull(AndroidFollowRuntime.createForPlatform(29, System::nanoTime, scheduler,
                Executor { error("These tests never start projection I/O") }))
            ForegroundFollowScope(controller).use(test)
        } finally { scheduler.shutdownNow() }
    }
}
