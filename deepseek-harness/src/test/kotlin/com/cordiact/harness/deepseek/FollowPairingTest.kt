package com.cordiact.harness.deepseek

import java.net.InetAddress
import java.net.InetSocketAddress
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FollowPairingTest {
    private val target = FollowTarget("host-a", "local-a", "session-a")
    private val address = InetSocketAddress(InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1)), 12345)
    private val keys = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }
    private val signer = keys.generateKeyPair()
    private val pinHex = "00".repeat(32)
    private val token = "caf1_" + "A".repeat(43)
    private val scope = "host-a local-a remote-a session-a 127.0.0.1 12345 $pinHex"

    @Test fun validOfferConsumesChallengeAndWipesTransferredBytes() = withPairing { pairing, _ ->
        val challenge = assertNotNull(pairing.challenge())
        val signed = offer(challenge)
        val signature = sign(signed)
        val replay = signed.copyOf()
        assertNotNull(pairing.approve(challenge, signed, signature)).close()
        assertTrue(signed.all { it == 0.toByte() })
        assertNull(pairing.approve(challenge, replay, signature))
        assertTrue(replay.all { it == 0.toByte() })
    }

    @Test fun eachRouteFieldAndNonceMustMatchEvenWithAValidSignature() = withPairing { pairing, _ ->
        val challenge = assertNotNull(pairing.challenge())
        val fields = scope.split(' ')
        val replacements = listOf("host-b", "local-b", "remote-b", "session-b", "127.0.0.2", "12346", "01".repeat(32))
        replacements.forEachIndexed { index, replacement ->
            val mutated = fields.toMutableList().apply { this[index] = replacement }.joinToString(" ")
            val signed = offer(challenge, route = mutated)
            assertNull(pairing.approve(challenge, signed, sign(signed)), "route field $index")
        }
        val mismatched = offer(challenge, nonce = "0".repeat(64))
        assertNull(pairing.approve(challenge, mismatched, sign(mismatched)))
        val valid = offer(challenge)
        assertNotNull(pairing.approve(challenge, valid, sign(valid))).close()
    }

    @Test fun changedTokenWrongKeyAndInvalidGrammarNeverApprove() = withPairing { pairing, _ ->
        val challenge = assertNotNull(pairing.challenge())
        val original = offer(challenge)
        val signature = sign(original)
        original[original.lastIndex - 1] = 'B'.code.toByte()
        assertNull(pairing.approve(challenge, original, signature))
        val wrong = offer(challenge)
        val wrongKeySignature = Signature.getInstance("SHA256withECDSA").run {
            initSign(keys.generateKeyPair().private)
            update(wrong)
            sign()
        }
        assertNull(pairing.approve(challenge, wrong, wrongKeySignature))
        for (bad in listOf("owner-token", "$token\n", "$token ", token.dropLast(1))) {
            val signed = offer(challenge, grant = bad)
            assertNull(pairing.approve(challenge, signed, sign(signed)))
        }
        val oversizedSignature = offer(challenge)
        assertNull(pairing.approve(challenge, oversizedSignature, ByteArray(81)))
    }

    @Test fun expiryAndNewChallengeInvalidateOldOffers() = withPairing { pairing, advance ->
        val first = assertNotNull(pairing.challenge())
        val second = assertNotNull(pairing.challenge())
        assertTrue(first.nonce != second.nonce)
        val firstBytes = offer(first)
        assertNull(pairing.approve(first, firstBytes, sign(firstBytes)))
        advance(15_000)
        val secondBytes = offer(second)
        assertNull(pairing.approve(second, secondBytes, sign(secondBytes)))
        assertTrue(secondBytes.all { it == 0.toByte() })
    }

    @Test fun newChallengeAndCloseRetireUnusedApprovals() = withPairing { pairing, _ ->
        val first = assertNotNull(pairing.challenge())
        val firstBytes = offer(first)
        val firstApproval = assertNotNull(pairing.approve(first, firstBytes, sign(firstBytes)))
        val second = assertNotNull(pairing.challenge())
        assertNull(firstApproval.connect())
        val secondBytes = offer(second)
        val secondApproval = assertNotNull(pairing.approve(second, secondBytes, sign(secondBytes)))
        pairing.close()
        assertNull(secondApproval.connect())
        assertNull(pairing.challenge())
    }

    @Test fun leaseKeepsOriginalChallengeDeadlineAcrossLongApprovalDelay() = withPairing { pairing, advance ->
        val challenge = assertNotNull(pairing.challenge())
        advance(14_999)
        val bytes = offer(challenge)
        assertNotNull(pairing.approve(challenge, bytes, sign(bytes))).use { approval ->
            assertEquals(challenge.start + TimeUnit.SECONDS.toNanos(15), approval.deadlineNanos)
        }
    }

    private fun offer(challenge: FollowPairing.Challenge, route: String = scope, nonce: String = challenge.nonce, grant: String = token) =
        "cordiact-follow-pair/1 $route $nonce $grant\n".encodeToByteArray()

    private fun sign(bytes: ByteArray): ByteArray = Signature.getInstance("SHA256withECDSA").run {
        initSign(signer.private)
        update(bytes)
        sign()
    }

    private fun withPairing(body: (FollowPairing, (Long) -> Unit) -> Unit) {
        val timer = Executors.newSingleThreadScheduledExecutor()
        var now = System.nanoTime()
        try {
            FollowPairing(target, "remote-a", address, ByteArray(32), signer.public as ECPublicKey, timer) { now }.use {
                body(it) { millis -> now += TimeUnit.MILLISECONDS.toNanos(millis) }
            }
        } finally {
            timer.shutdownNow()
        }
    }
}
