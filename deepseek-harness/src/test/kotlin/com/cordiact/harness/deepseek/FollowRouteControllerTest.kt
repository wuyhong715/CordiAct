package com.cordiact.harness.deepseek

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FollowRouteControllerTest {
    private val target = FollowTarget("host-a", "local-a", "session-a")
    private val ip = InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1))
    private val keys = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
    private val token = "caf1_" + "A".repeat(43)

    @Test fun delayedOldDialClosesItsOwnPipeWithoutStartingAReadOrReplacingNewSelection() {
        val scheduler = Executors.newSingleThreadScheduledExecutor()
        val worker = Executors.newSingleThreadExecutor()
        val oldDial = CountDownLatch(1)
        val finishOld = CountDownLatch(1)
        val dials = AtomicInteger()
        val oldCancel = AtomicInteger()
        val newCancel = AtomicInteger()
        val reads = AtomicInteger()
        val unsent = ByteArrayOutputStream()
        val address = InetSocketAddress(ip, 12345)
        try {
            val client = FollowPipeClient(scheduler, Executor { reads.incrementAndGet() })
            FollowRouteController(scheduler, client) {
                if (dials.getAndIncrement() == 0) {
                    oldDial.countDown()
                    check(finishOld.await(4, TimeUnit.SECONDS))
                    pipe(unsent) { oldCancel.incrementAndGet() }
                } else pipe(ByteArrayOutputStream()) { newCancel.incrementAndGet() }
            }.use { controller ->
                val route = configure(controller, address)
                val old = approve(controller, route, address)
                val late = worker.submit(Callable { controller.connect(old) })
                assertTrue(oldDial.await(4, TimeUnit.SECONDS))
                val replacement = configure(controller, address)
                val current = approve(controller, replacement, address)
                assertTrue(controller.connect(current))
                finishOld.countDown()
                assertFalse(late.get(4, TimeUnit.SECONDS))
                assertEquals(1, reads.get())
                assertEquals(0, unsent.size())
                assertEquals(1, oldCancel.get())
                assertEquals(0, newCancel.get())
                assertNull(controller.status(old))
                assertEquals(FollowStatus.AwaitingBaseline, controller.status(current))
                assertFalse(controller.clear(route))
                assertTrue(controller.clear(replacement))
                assertEquals(1, newCancel.get())
            }
        } finally {
            finishOld.countDown()
            worker.shutdownNow()
            scheduler.shutdownNow()
        }
    }

    @Test fun replacementActivelyClosesAStalledHandshake() {
        val scheduler = Executors.newSingleThreadScheduledExecutor()
        val worker = Executors.newSingleThreadExecutor()
        try {
            ServerSocket().use { server ->
                server.bind(InetSocketAddress(ip, 0))
                server.soTimeout = 2_000
                val address = server.localSocketAddress as InetSocketAddress
                FollowRouteController(scheduler, Executor { error("No reader before TLS handshake") }).use { controller ->
                    val route = configure(controller, address)
                    val attempt = approve(controller, route, address)
                    val dial = worker.submit(Callable { controller.connect(attempt) })
                    server.accept().use { socket ->
                        socket.soTimeout = 1_500
                        assertEquals(22, socket.getInputStream().read()) // ClientHello, no TLS server response.
                        configure(controller, address)
                        assertFalse(dial.get(1, TimeUnit.SECONDS))
                        assertNull(controller.status(attempt))
                        assertFalse(controller.connect(attempt))
                        var bytes = 1
                        while (socket.getInputStream().read() != -1) check(++bytes <= 65_536)
                    }
                }
            }
        } finally {
            worker.shutdownNow()
            scheduler.shutdownNow()
        }
    }

    @Test fun newChallengeOrClearInvalidatesUnusedApprovalsAndWipesLateOffers() {
        val scheduler = Executors.newSingleThreadScheduledExecutor()
        try {
            val address = InetSocketAddress(ip, 12345)
            FollowRouteController(scheduler, FollowPipeClient(scheduler, Executor { error("No reader") })) { error("No dial") }
                .use { controller ->
                    val route = configure(controller, address)
                    val stale = approve(controller, route, address)
                    val challenge = assertNotNull(controller.challenge(route))
                    assertFalse(controller.connect(stale))
                    val offer = frame(challenge, address)
                    val signature = sign(offer)
                    assertTrue(controller.clear(route))
                    assertNull(controller.approve(route, challenge, offer, signature))
                    assertTrue(offer.all { it == 0.toByte() })
                }
        } finally {
            scheduler.shutdownNow()
        }
    }

    @Test fun sameRouteWithRotatedSigningKeyRejectsOldSignature() {
        val scheduler = Executors.newSingleThreadScheduledExecutor()
        try {
            val address = InetSocketAddress(ip, 12345)
            FollowRouteController(scheduler, Executor { error("No reader") }).use { controller ->
                val original = configure(controller, address)
                val oldAttempt = approve(controller, original, address)
                val rotated = KeyPairGenerator.getInstance("EC").apply {
                    initialize(ECGenParameterSpec("secp256r1"))
                }.generateKeyPair()
                val replacement = assertNotNull(controller.configure(target, "remote-a", address, ByteArray(32), rotated.public as ECPublicKey))
                assertFalse(controller.connect(oldAttempt))
                val challenge = assertNotNull(controller.challenge(replacement))
                val staleSignature = frame(challenge, address)
                assertNull(controller.approve(replacement, challenge, staleSignature, sign(staleSignature)))
                val offer = frame(challenge, address)
                val signature = Signature.getInstance("SHA256withECDSA").run {
                    initSign(rotated.private)
                    update(offer)
                    sign()
                }
                assertNotNull(controller.approve(replacement, challenge, offer, signature))
            }
        } finally {
            scheduler.shutdownNow()
        }
    }

    private fun configure(controller: FollowRouteController, address: InetSocketAddress) =
        assertNotNull(controller.configure(target, "remote-a", address, ByteArray(32), keys.public as ECPublicKey))

    private fun approve(controller: FollowRouteController, route: FollowRouteController.Route, address: InetSocketAddress): FollowRouteController.Attempt {
        val challenge = assertNotNull(controller.challenge(route))
        val offer = frame(challenge, address)
        return assertNotNull(controller.approve(route, challenge, offer, sign(offer)))
    }

    private fun frame(challenge: FollowPairing.Challenge, address: InetSocketAddress) =
        "cordiact-follow-pair/1 host-a local-a remote-a session-a 127.0.0.1 ${address.port} ${"00".repeat(32)} ${challenge.nonce} $token\n"
            .encodeToByteArray()

    private fun sign(offer: ByteArray): ByteArray = Signature.getInstance("SHA256withECDSA").run {
        initSign(keys.private)
        update(offer)
        sign()
    }

    private fun pipe(output: ByteArrayOutputStream, cancel: () -> Unit) =
        ApprovedFollowPipe(target, "remote-a", token, 15_000, ByteArrayInputStream(byteArrayOf()), output, cancel)
}
