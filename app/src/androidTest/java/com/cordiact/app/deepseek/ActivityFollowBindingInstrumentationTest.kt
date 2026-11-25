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
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.security.KeyPairGenerator
import java.security.SecureRandom
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.util.Base64
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
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
    @Test fun pauseClosesRealLoopbackSocketDuringTlsHandshake() {
        assumeTrue("requires Android 10 TLS and ALPN", Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
        val signer = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
        val key = ByteArray(32).also(SecureRandom()::nextBytes)
        val pin = "ab".repeat(32)
        val target = FollowTarget("host", "local", "session")
        val loopback = InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1))
        val worker = Executors.newSingleThreadExecutor()
        try {
            ServerSocket().use { server ->
                server.bind(InetSocketAddress(loopback, 0))
                server.soTimeout = 2_000
                val port = server.localPort
                val descriptor = checkNotNull(FollowRouteDescriptor.parse(
                    "cordiact-follow-route/1 host local remote session 127.0.0.1 $port $pin ${signer.public.encoded.toHexString()}\n",
                ))
                ActivityScenario.launch(FollowLifecycleProbeActivity::class.java).use { scenario ->
                    lateinit var scope: ForegroundFollowScope
                    scenario.onActivity { scope = checkNotNull(it.binding).scope }
                    val route = checkNotNull(scope.configure(target, "remote", descriptor, key))
                    val challenge = checkNotNull(scope.begin(route))
                    val offer = ("cordiact-follow-pair/1 host local remote session 127.0.0.1 $port $pin " +
                        "${challenge.nonce} caf1_${"A".repeat(43)}\n").encodeToByteArray()
                    val sig = Signature.getInstance("SHA256withECDSA").run {
                        initSign(signer.private)
                        update(offer)
                        sign()
                    }
                    val iv = ByteArray(12).also(SecureRandom()::nextBytes)
                    val sealed = Cipher.getInstance("AES/GCM/NoPadding").run {
                        init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
                        updateAAD("cordiact-follow-seal/1 ${challenge.nonce}\n".encodeToByteArray())
                        iv + doFinal(byteArrayOf(sig.size.toByte()) + sig + offer)
                    }
                    val reply = "cordiact-follow-envelope/1 ${Base64.getEncoder().encodeToString(sealed)}\n".encodeToByteArray()
                    val attempt = worker.submit(Callable {
                        val input = reply.inputStream()
                        val output = ByteArrayOutputStream()
                        checkNotNull(scope.exchange(challenge, FollowPairingHandoff(input, output) { input.close(); output.close() }))
                    }).get(3, TimeUnit.SECONDS)
                    val connect = worker.submit(Callable { scope.connect(attempt) })
                    server.accept().use { peer ->
                        peer.soTimeout = 2_000
                        val stream = peer.getInputStream()
                        assertEquals(22, stream.read()) // Real Android TLS ClientHello; no server reply.
                        scenario.moveToState(Lifecycle.State.STARTED)
                        assertFalse(connect.get(2, TimeUnit.SECONDS))
                        assertNull(scope.status(attempt))
                        var bytes = 1
                        while (stream.read() != -1) check(++bytes <= 65_536)
                    }
                }
            }
        } finally {
            key.fill(0)
            worker.shutdownNow()
        }
    }

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
                val route = checkNotNull(original.configure(target, "remote", descriptor, ByteArray(32) { 1 }))
                val challenge = checkNotNull(original.begin(route))
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
                    assertNull(original.begin(route))
                    assertFalse(original.cancel(challenge))
                    scenario.moveToState(Lifecycle.State.RESUMED)
                    val fresh = checkNotNull(original.begin(route))
                    assertNotSame(challenge, fresh)
                    assertFalse(original.cancel(challenge))
                    scenario.recreate()
                    lateinit var replacement: ForegroundFollowScope
                    scenario.onActivity { activity -> replacement = checkNotNull(activity.binding).scope }
                    assertNotSame(original, replacement)
                    // Even a delayed resume cannot reopen the destroyed scope.
                    original.resume()
                    assertNull(original.configure(target, "remote", descriptor, ByteArray(32) { 1 }))
                    assertNull(original.begin(route))
                    assertNull(replacement.begin(route))
                    assertFalse(replacement.clear(route))
                    val next = checkNotNull(replacement.configure(target, "remote", descriptor, ByteArray(32) { 2 }))
                    checkNotNull(replacement.begin(next))
                } finally { released.countDown() }
            }
        } finally { worker.shutdownNow() }
    }
}
