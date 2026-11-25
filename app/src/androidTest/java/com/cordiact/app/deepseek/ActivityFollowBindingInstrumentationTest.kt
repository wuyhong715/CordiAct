package com.cordiact.app.deepseek

import android.os.Build
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cordiact.harness.deepseek.FollowPairingHandoff
import com.cordiact.harness.deepseek.FollowRouteDescriptor
import com.cordiact.harness.deepseek.FollowTarget
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.security.KeyPairGenerator
import java.security.spec.ECGenParameterSpec
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Exercises actual framework transitions, not direct calls to the binding's callbacks. */
@RunWith(AndroidJUnit4::class)
class ActivityFollowBindingInstrumentationTest {
    @Test fun pauseCancelsBlockedHandoffAndRecreationDoesNotInheritTrust() {
        assumeTrue("requires Android 10 lifecycle callbacks", Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
        val signer = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
        val descriptor = checkNotNull(FollowRouteDescriptor.parse(
            "cordiact-follow-route/1 host local remote session 127.0.0.1 12345 ${"ab".repeat(32)} ${signer.public.encoded.toHexString()}\n",
        ))
        val target = FollowTarget("host", "local", "session")
        val worker = Executors.newSingleThreadExecutor()
        val reading = CountDownLatch(1)
        val released = CountDownLatch(1)
        val cancellations = AtomicInteger()
        try {
            ActivityScenario.launch(FollowLifecycleProbeActivity::class.java).use { scenario ->
                lateinit var original: ForegroundFollowScope
                scenario.onActivity { activity -> original = checkNotNull(activity.binding).scope }
                assertTrue(original.configure(target, "remote", descriptor, ByteArray(32) { 1 }))
                val challenge = checkNotNull(original.begin())
                val input = object : InputStream() {
                    override fun read(): Int {
                        reading.countDown()
                        check(released.await(5, TimeUnit.SECONDS)) { "Pause failed to release the handoff" }
                        return -1
                    }
                }
                val handoff = FollowPairingHandoff(input, ByteArrayOutputStream()) {
                    cancellations.incrementAndGet()
                    released.countDown()
                }
                val exchange = worker.submit(Callable { original.exchange(challenge, handoff) })
                try {
                    assertTrue(reading.await(3, TimeUnit.SECONDS))
                    scenario.moveToState(Lifecycle.State.STARTED)
                    assertNull(exchange.get(2, TimeUnit.SECONDS))
                    assertEquals(1, cancellations.get())
                    assertNull(original.begin())
                    assertFalse(original.cancel(challenge))
                    scenario.moveToState(Lifecycle.State.RESUMED)
                    val fresh = checkNotNull(original.begin())
                    assertNotSame(challenge, fresh)
                    assertFalse(original.cancel(challenge))
                    scenario.recreate()
                    lateinit var replacement: ForegroundFollowScope
                    scenario.onActivity { activity -> replacement = checkNotNull(activity.binding).scope }
                    assertNotSame(original, replacement)
                    // Even a delayed resume cannot reopen the destroyed scope.
                    original.resume()
                    assertFalse(original.configure(target, "remote", descriptor, ByteArray(32) { 1 }))
                    assertNull(original.begin())
                    assertNull(replacement.begin())
                    assertTrue(replacement.configure(target, "remote", descriptor, ByteArray(32) { 2 }))
                    checkNotNull(replacement.begin())
                } finally { released.countDown() }
            }
        } finally { worker.shutdownNow() }
    }
}
