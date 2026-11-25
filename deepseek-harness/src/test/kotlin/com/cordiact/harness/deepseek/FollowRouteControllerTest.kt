package com.cordiact.harness.deepseek

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
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
    private val delivery = ByteArray(32) { 1 }

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

    @Test fun explicitCancellationClosesAnInFlightTlsHandshake() {
        val scheduler = Executors.newSingleThreadScheduledExecutor()
        val worker = Executors.newSingleThreadExecutor()
        try {
            ServerSocket().use { server ->
                server.bind(InetSocketAddress(ip, 0))
                server.soTimeout = 2_000
                val address = server.localSocketAddress as InetSocketAddress
                FollowRouteController(scheduler, Executor { error("No reader before TLS handshake") }).use { controller ->
                    val route = configure(controller, address)
                    val challenge = assertNotNull(controller.challenge(route))
                    val offer = frame(challenge, address)
                    val attempt = assertNotNull(controller.approve(route, challenge, seal(challenge, offer, sign(offer))))
                    val dial = worker.submit(Callable { controller.connect(attempt) })
                    server.accept().use { socket ->
                        socket.soTimeout = 1_500
                        assertEquals(22, socket.getInputStream().read())
                        assertTrue(controller.cancel(route, challenge))
                        assertFalse(dial.get(1, TimeUnit.SECONDS))
                        assertNull(controller.status(attempt))
                        var bytes = 1
                        while (socket.getInputStream().read() != -1) check(++bytes <= 65_536)
                    }
                    val fresh = assertNotNull(controller.challenge(route))
                    assertFalse(controller.cancel(route, challenge))
                    assertNotNull(controller.proof(route, fresh))
                }
            }
        } finally {
            worker.shutdownNow()
            scheduler.shutdownNow()
        }
    }

    @Test fun foregroundRecheckClosesAHandshakeAtItsOwnDeadlineBeforeGrantExpiry() {
        val scheduler = Executors.newSingleThreadScheduledExecutor()
        val worker = Executors.newSingleThreadExecutor()
        val elapsed = AtomicLong(1_000)
        try {
            // Hold timer delivery to model a scheduler that does not advance during suspend.
            val unblockTimer = CountDownLatch(1)
            scheduler.execute { unblockTimer.await() }
            try {
                ServerSocket().use { server ->
                    server.bind(InetSocketAddress(ip, 0))
                    server.soTimeout = 2_000
                    val address = server.localSocketAddress as InetSocketAddress
                    FollowRouteController(scheduler, Executor { error("No reader before TLS") }, elapsed::get).use { controller ->
                        val route = configure(controller, address)
                        val attempt = approve(controller, route, address)
                        val dial = worker.submit(Callable { controller.connect(attempt) })
                        server.accept().use { socket ->
                            socket.soTimeout = 1_500
                            assertEquals(22, socket.getInputStream().read())
                            elapsed.addAndGet(TimeUnit.SECONDS.toNanos(3))
                            assertTrue(controller.recheck(route))
                            assertFalse(dial.get(1, TimeUnit.SECONDS))
                            assertFalse(controller.connect(attempt))
                            assertNull(controller.status(attempt))
                        }
                    }
                }
            } finally { unblockTimer.countDown() }
        } finally {
            worker.shutdownNow()
            scheduler.shutdownNow()
        }
    }

    @Test fun foregroundRecheckDiscardsAnExpiredChallengeAndKeepsItsReplacementUsable() {
        val scheduler = Executors.newSingleThreadScheduledExecutor()
        var now = Long.MAX_VALUE - TimeUnit.SECONDS.toNanos(10)
        var samples = 0
        try {
            val address = InetSocketAddress(ip, 12345)
            FollowRouteController(scheduler, Executor { error("No reader") }, { samples++; now }).use { controller ->
                val route = configure(controller, address)
                val expired = assertNotNull(controller.challenge(route))
                val oldOffer = frame(expired, address)
                val oldEnvelope = seal(expired, oldOffer, sign(oldOffer))
                now += TimeUnit.SECONDS.toNanos(15) - 1
                assertTrue(controller.recheck(route))
                now++
                assertTrue(controller.recheck(route))
                val samplesAfterRecheck = samples
                assertNull(controller.approve(route, expired, oldEnvelope))
                // The old object is no longer pending; the late offer doesn't even consult the clock.
                assertEquals(samplesAfterRecheck, samples)
                val replacement = assertNotNull(controller.challenge(route))
                assertNull(controller.approve(route, expired, oldEnvelope))
                val offer = frame(replacement, address)
                assertNotNull(controller.approve(route, replacement, seal(replacement, offer, sign(offer))))
            }
        } finally { scheduler.shutdownNow() }
    }

    @Test fun lateApprovalDoesNotExtendItsChallengeDeadlineOrStartDialing() {
        val scheduler = Executors.newSingleThreadScheduledExecutor()
        var now = 0L
        try {
            val address = InetSocketAddress(ip, 12345)
            val client = FollowPipeClient(scheduler, Executor { error("No reader") })
            FollowRouteController(scheduler, client, { error("Expired approval must not dial") }, { now }).use { controller ->
                val route = configure(controller, address)
                val challenge = assertNotNull(controller.challenge(route))
                val offer = frame(challenge, address)
                now += TimeUnit.SECONDS.toNanos(15) - 1
                val attempt = assertNotNull(controller.approve(route, challenge, seal(challenge, offer, sign(offer))))
                assertTrue(controller.recheck(route))
                now++
                assertTrue(controller.recheck(route))
                assertFalse(controller.connect(attempt))
                assertNull(controller.status(attempt))

                val queued = approve(controller, route, address)
                now += TimeUnit.SECONDS.toNanos(15)
                // Dispatch independently checks expiry even if no foreground callback ran.
                assertFalse(controller.connect(queued))
                assertNull(controller.status(queued))
            }
        } finally { scheduler.shutdownNow() }
    }

    @Test fun newChallengeOrClearInvalidatesUnusedApprovalsAndLateEnvelopes() {
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
                    val envelope = seal(challenge, offer, sign(offer))
                    assertTrue(controller.clear(route))
                    assertNull(controller.approve(route, challenge, envelope))
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
                val replacement = assertNotNull(controller.configure(target, "remote-a", address, ByteArray(32), rotated.public as ECPublicKey, delivery))
                assertFalse(controller.connect(oldAttempt))
                val challenge = assertNotNull(controller.challenge(replacement))
                val staleSignature = frame(challenge, address)
                assertNull(controller.approve(replacement, challenge, seal(challenge, staleSignature, sign(staleSignature))))
                val offer = frame(challenge, address)
                val signature = Signature.getInstance("SHA256withECDSA").run {
                    initSign(rotated.private)
                    update(offer)
                    sign()
                }
                assertNotNull(controller.approve(replacement, challenge, seal(challenge, offer, signature)))
            }
        } finally {
            scheduler.shutdownNow()
        }
    }

    @Test fun sealedOnlyApprovalRejectsWrongKeyAadAndMutatedCiphertextWithoutConsumingChallenge() {
        val scheduler = Executors.newSingleThreadScheduledExecutor()
        try {
            val address = InetSocketAddress(ip, 12345)
            val configuredKey = delivery.copyOf()
            FollowRouteController(scheduler, Executor { error("No reader") }).use { controller ->
                val route = assertNotNull(controller.configure(target, "remote-a", address, ByteArray(32), keys.public as ECPublicKey, configuredKey))
                configuredKey.fill(0)
                val old = assertNotNull(controller.challenge(route))
                val current = assertNotNull(controller.challenge(route))
                val offer = frame(current, address)
                val signature = sign(offer)
                val exact = seal(current, offer, signature)
                val cases = listOf(offer, exact.copyOf(37), ByteArray(865),
                    seal(old, offer, signature), seal(current, offer, signature, ByteArray(32)),
                    exact.copyOf().also { it[15] = (it[15].toInt() xor 1).toByte() },
                    exact.copyOf().also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() })
                for (envelope in cases) assertNull(controller.approve(route, current, envelope))
                assertNotNull(controller.approve(route, current, exact))
                assertNull(controller.approve(route, current, exact))
            }
        } finally {
            scheduler.shutdownNow()
        }
    }

    @Test fun foregroundReplacementAndExplicitCancelRetireOnlyTheirOwnBlockedHandoff() {
        val scheduler = Executors.newSingleThreadScheduledExecutor()
        val worker = Executors.newSingleThreadExecutor()
        val elapsed = AtomicLong(0)
        try {
            val address = InetSocketAddress(ip, 12345)
            FollowRouteController(scheduler, Executor { error("No reader") }, elapsed::get).use { controller ->
                var current = configure(controller, address)
                for (action in listOf("cancel", "replace", "expire")) {
                    val route = current
                    val challenge = assertNotNull(controller.challenge(route))
                    val reading = CountDownLatch(1)
                    val stop = CountDownLatch(1)
                    val cancellations = AtomicInteger()
                    val wire = object : InputStream() {
                        override fun read(): Int {
                            reading.countDown()
                            check(stop.await(4, TimeUnit.SECONDS))
                            return -1
                        }
                    }
                    val handoff = FollowPairingHandoff(wire, ByteArrayOutputStream()) {
                        cancellations.incrementAndGet()
                        stop.countDown()
                    }
                    val attempt = worker.submit(Callable { controller.exchange(route, challenge, handoff) })
                    try {
                        assertTrue(reading.await(2, TimeUnit.SECONDS))
                        assertNull(controller.proof(route, challenge))
                        when (action) {
                            "cancel" -> assertTrue(controller.cancel(route, challenge))
                            "replace" -> current = configure(controller, address)
                            "expire" -> {
                                elapsed.addAndGet(TimeUnit.SECONDS.toNanos(7))
                                assertTrue(controller.recheck(route))
                            }
                        }
                        assertNull(attempt.get(1, TimeUnit.SECONDS))
                        assertEquals(1, cancellations.get())
                        assertNull(controller.proof(route, challenge))
                        assertFalse(controller.cancel(route, challenge))
                        assertTrue(controller.recheck(current))
                        assertNull(controller.exchange(route, challenge, handoff))
                    } finally { stop.countDown() }
                }
            }
        } finally {
            worker.shutdownNow()
            scheduler.shutdownNow()
        }
    }

    @Test fun cancellationRetiresTheExactPendingQueuedOrLiveAttemptWithoutRemovingTrust() {
        val scheduler = Executors.newSingleThreadScheduledExecutor()
        val cancellations = AtomicInteger()
        try {
            val address = InetSocketAddress(ip, 12345)
            val client = FollowPipeClient(scheduler, Executor { /* Hold reader dispatch. */ })
            FollowRouteController(scheduler, client) {
                pipe(ByteArrayOutputStream()) { cancellations.incrementAndGet() }
            }.use { controller ->
                val route = configure(controller, address)
                fun reply(challenge: FollowPairing.Challenge): ByteArray {
                    val offer = frame(challenge, address)
                    return seal(challenge, offer, sign(offer))
                }
                val pending = assertNotNull(controller.challenge(route))
                assertTrue(controller.cancel(route, pending))
                assertNull(controller.approve(route, pending, reply(pending)))
                assertFalse(controller.cancel(route, pending))

                val queued = assertNotNull(controller.challenge(route))
                val old = assertNotNull(controller.approve(route, queued, reply(queued)))
                assertTrue(controller.cancel(route, queued))
                assertFalse(controller.connect(old))

                val current = assertNotNull(controller.challenge(route))
                val live = assertNotNull(controller.approve(route, current, reply(current)))
                assertTrue(controller.connect(live))
                assertFalse(controller.cancel(route, queued))
                assertEquals(FollowStatus.AwaitingBaseline, controller.status(live))
                assertTrue(controller.cancel(route, current))
                assertNull(controller.status(live))
                assertEquals(1, cancellations.get())
                assertNotNull(controller.challenge(route))
            }
        } finally { scheduler.shutdownNow() }
    }

    private fun seal(challenge: FollowPairing.Challenge, offer: ByteArray, signature: ByteArray, key: ByteArray = delivery): ByteArray {
        val iv = ByteArray(12).also(SecureRandom()::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
        cipher.updateAAD("cordiact-follow-seal/1 ${challenge.nonce}\n".encodeToByteArray())
        return iv + cipher.doFinal(byteArrayOf(signature.size.toByte()) + signature + offer)
    }

    private fun configure(controller: FollowRouteController, address: InetSocketAddress) =
        assertNotNull(controller.configure(target, "remote-a", address, ByteArray(32), keys.public as ECPublicKey, delivery))

    private fun approve(controller: FollowRouteController, route: FollowRouteController.Route, address: InetSocketAddress): FollowRouteController.Attempt {
        val challenge = assertNotNull(controller.challenge(route))
        val offer = frame(challenge, address)
        return assertNotNull(controller.approve(route, challenge, seal(challenge, offer, sign(offer))))
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
