package com.cordiact.harness.deepseek

import java.net.InetAddress
import java.net.InetSocketAddress
import java.security.AlgorithmParameters
import java.security.GeneralSecurityException
import java.security.KeyFactory
import java.security.interfaces.ECPublicKey
import java.security.spec.ECFieldFp
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECParameterSpec
import java.security.spec.X509EncodedKeySpec

/**
 * Public metadata, not a trust assertion. Provision through an independently authenticated
 * channel; never enroll from a peer's handshake, offer, or unverified advertised descriptor.
 */
class FollowRouteDescriptor private constructor(
    val target: FollowTarget,
    val remoteWorkspaceId: String,
    val address: InetSocketAddress,
    private val pin: ByteArray,
    internal val enrollmentKey: ECPublicKey,
) {
    fun certificateSha256(): ByteArray = pin.copyOf()

    companion object {
        private val ID = Regex("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}")
        private val PORT = Regex("[1-9][0-9]{0,4}")
        private val HEX = Regex("[0-9a-f]+")
        internal const val MAX_LINE = 804 // 4 × 128 IDs, fixed fields, 8 spaces and one LF.
        private const val P256_PREFIX = "3059301306072a8648ce3d020106082a8648ce3d03010703420004"
        private val P256: ECParameterSpec by lazy {
            AlgorithmParameters.getInstance("EC").apply { init(ECGenParameterSpec("secp256r1")) }
                .getParameterSpec(ECParameterSpec::class.java)
        }

        /** Bounded canonical ASCII; only the explicit IPv4 loopback transport is supported. No DNS. */
        fun parse(line: String): FollowRouteDescriptor? {
            if (line.length !in 1..MAX_LINE || line.last() != '\n') return null
            val body = line.dropLast(1)
            if (body.any { it !in ' '..'~' }) return null
            val fields = body.split(' ')
            if (fields.size != 9 || fields[0] != "cordiact-follow-route/1" || fields[5] != "127.0.0.1" ||
                !fields.subList(1, 5).all(ID::matches) || !PORT.matches(fields[6])) return null
            val port = fields[6].toInt()
            if (port > 65535) return null
            val pin = decode(fields[7], 32) ?: return null
            if (!fields[8].startsWith(P256_PREFIX)) return null
            val spki = decode(fields[8], 91) ?: return null
            val key = try {
                KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(spki)) as? ECPublicKey ?: return null
            } catch (_: GeneralSecurityException) { return null }
            val params = key.params
            if (params.curve != P256.curve || params.generator != P256.generator || params.order != P256.order ||
                params.cofactor != P256.cofactor || !spki.contentEquals(key.encoded)) return null
            val point = key.w
            val prime = (P256.curve.field as ECFieldFp).p
            val x = point.affineX ?: return null
            val y = point.affineY ?: return null
            if (x.signum() < 0 || y.signum() < 0 || x >= prime || y >= prime ||
                y.multiply(y).mod(prime) != (x.pow(3) + P256.curve.a * x + P256.curve.b).mod(prime)) return null
            return FollowRouteDescriptor(
                FollowTarget(fields[1], fields[2], fields[4]), fields[3],
                InetSocketAddress(InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1)), port), pin, key,
            )
        }

        private fun decode(text: String, bytes: Int): ByteArray? {
            if (text.length != bytes * 2 || !HEX.matches(text)) return null
            return ByteArray(bytes) { index -> text.substring(index * 2, index * 2 + 2).toInt(16).toByte() }
        }
    }
}
