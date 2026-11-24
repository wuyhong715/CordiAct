package com.cordiact.harness.deepseek

import java.security.GeneralSecurityException
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Confidential response layer; the inner signature still proves the independently trusted issuer. */
internal class FollowPairingEnvelope(key: ByteArray) : AutoCloseable {
    private val bytes = key.also { require(it.size == 32) { "A 256-bit pairing delivery key is required" } }.copyOf()

    // Serialized with route changes by the owner. Provider-internal key copies are not controllable.
    override fun close() { bytes.fill(0) }

    fun approve(pairing: FollowPairing, challenge: FollowPairing.Challenge, envelope: ByteArray): FollowPairing.Approval? {
        if (envelope.size !in 38..MAX_ENVELOPE) return null
        val plain = try {
            Cipher.getInstance("AES/GCM/NoPadding").run {
                init(Cipher.DECRYPT_MODE, SecretKeySpec(bytes, "AES"), GCMParameterSpec(128, envelope, 0, IV_SIZE))
                updateAAD("cordiact-follow-seal/1 ${challenge.nonce}\n".encodeToByteArray())
                doFinal(envelope, IV_SIZE, envelope.size - IV_SIZE)
            }
        } catch (_: GeneralSecurityException) {
            return null
        }
        try {
            val length = plain[0].toInt() and 0xff
            if (length !in 8..80 || plain.size <= 1 + length) return null
            val signature = plain.copyOfRange(1, length + 1)
            return pairing.approve(challenge, plain.copyOfRange(length + 1, plain.size), signature)
        } finally {
            plain.fill(0)
        }
    }

    private companion object {
        const val IV_SIZE = 12
        const val MAX_ENVELOPE = 864
    }
}
