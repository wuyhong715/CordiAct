package com.cordiact.harness.deepseek

import java.net.Inet4Address
import java.net.InetSocketAddress
import java.security.AlgorithmParameters
import java.security.GeneralSecurityException
import java.security.SecureRandom
import java.security.Signature
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECParameterSpec
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * In-memory approval for one preconfigured route. The enrollment key, leaf pin, resolved IPv4
 * endpoint and exact target must all come from a trusted configuration, not from the offer.
 * No discovery, key enrollment, storage, or network access here. Construct a new instance
 * when changing trust or scope; close the old one and retire any carrier already handed off.
 * The issuer must create a fresh one-use Host grant in response to the challenge (at least the
 * 15-second challenge window); the Host independently enforces its own earlier revocation/expiry.
 */
class FollowPairing internal constructor(
    target: FollowTarget,
    remoteWorkspaceId: String,
    address: InetSocketAddress,
    certificateSha256: ByteArray,
    private val enrollmentKey: ECPublicKey,
    scheduler: ScheduledExecutorService,
    private val nowNanos: () -> Long,
) : AutoCloseable {
    constructor(
        target: FollowTarget,
        remoteWorkspaceId: String,
        address: InetSocketAddress,
        certificateSha256: ByteArray,
        enrollmentKey: ECPublicKey,
        scheduler: ScheduledExecutorService,
    ) : this(target, remoteWorkspaceId, address, certificateSha256, enrollmentKey, scheduler, System::nanoTime)

    init {
        require(address.address is Inet4Address) { "Pairing requires a configured IPv4 address" }
        val params = enrollmentKey.params
        require(params.curve == P256.curve && params.generator == P256.generator &&
            params.order == P256.order && params.cofactor == P256.cofactor) { "Pairing requires an approved P-256 signing key" }
    }

    private val pin = certificateSha256.copyOf()
    private val connector = PinnedFollowTlsConnector(target, remoteWorkspaceId, address, pin, scheduler, nowNanos)
    private val scope = listOf(target.connectionId, target.localWorkspaceId, remoteWorkspaceId, target.remoteSessionId,
        address.address.hostAddress, address.port.toString(), hex(pin)).joinToString(" ")
    private var pending: Challenge? = null
    private var active: Approval? = null
    private var closed = false

    class Challenge internal constructor(val nonce: String, internal val start: Long)

    /** A fresh challenge supersedes the previous pending approval, even for the same route. */
    @Synchronized
    fun challenge(): Challenge? {
        if (closed) return null
        active?.close()
        active = null
        val nonce = ByteArray(32).also(RANDOM::nextBytes)
        return Challenge(hex(nonce), nowNanos()).also { pending = it }
    }

    /**
     * Accept a detached signature from the trusted issuer over the exact route, nonce, and bearer.
     * The caller transfers ownership of the offer bytes to this call; do not reuse them.
     * A successful result can dial once. Approval itself does not dial or read any session.
     */
    @Synchronized
    fun approve(challenge: Challenge, offer: ByteArray, signature: ByteArray): Approval? {
        try {
            if (!isPending(challenge) || signature.size !in 8..80) return null
            val prefix = "cordiact-follow-pair/1 $scope ${challenge.nonce} ".encodeToByteArray()
            if (offer.size != prefix.size + 49 || offer.last() != 10.toByte()) return null
            if (!prefix.indices.all { offer[it] == prefix[it] }) return null
            val token = offer.decodeToString(prefix.size, offer.lastIndex)
            if (!GRANT.matches(token)) return null
            val valid = try {
                Signature.getInstance("SHA256withECDSA").run {
                    initVerify(enrollmentKey)
                    update(offer)
                    verify(signature)
                }
            } catch (_: GeneralSecurityException) {
                false
            }
            if (!isPending(challenge) || !valid) return null
            pending = null
            return Approval(connector, connector.grantUntil(token, challenge.start + WINDOW_NANOS)).also { active = it }
        } finally {
            offer.fill(0)
        }
    }

    /** Check exact identity and drop an expired challenge before attempting to decrypt a reply. */
    @Synchronized
    internal fun isPending(challenge: Challenge): Boolean {
        if (closed || pending !== challenge) return false
        if (nowNanos() - challenge.start < WINDOW_NANOS) return true
        pending = null
        return false
    }

    @Synchronized
    internal fun proofMessage(challenge: Challenge): ByteArray? =
        if (isPending(challenge)) "cordiact-follow-proof/1 $scope ${challenge.nonce}\n".encodeToByteArray() else null

    @Synchronized
    internal fun discardExpiredChallenge() {
        pending?.let(::isPending)
    }

    @Synchronized
    internal fun cancel() {
        pending = null
        active?.close()
        active = null
    }

    @Synchronized
    override fun close() {
        closed = true
        cancel()
    }

    class Approval internal constructor(
        private val connector: PinnedFollowTlsConnector,
        private val grant: PinnedFollowTlsConnector.Grant,
    ) : AutoCloseable {
        private val retired = AtomicBoolean()
        internal val deadlineNanos: Long get() = grant.deadline
        internal fun expired(nowNanos: Long): Boolean = grant.expired(nowNanos)

        /** Off-thread; always uses the original approved endpoint and scope. */
        fun connect(): ApprovedFollowPipe? {
            if (retired.get()) return null
            val pipe = connector.connect(grant) ?: return null
            if (!retired.get()) return pipe
            pipe.close()
            return null
        }

        override fun close() {
            retired.set(true)
            grant.close()
        }
    }

    private companion object {
        val P256 = AlgorithmParameters.getInstance("EC").apply { init(ECGenParameterSpec("secp256r1")) }
            .getParameterSpec(ECParameterSpec::class.java)
        val RANDOM = SecureRandom()
        val WINDOW_NANOS = TimeUnit.SECONDS.toNanos(15)
        val GRANT = Regex("caf1_[A-Za-z0-9_-]{43}")
        const val HEX = "0123456789abcdef"
        fun hex(bytes: ByteArray): String = buildString(bytes.size * 2) {
            for (byte in bytes) {
                append(HEX[(byte.toInt() ushr 4) and 15])
                append(HEX[byte.toInt() and 15])
            }
        }
    }
}
