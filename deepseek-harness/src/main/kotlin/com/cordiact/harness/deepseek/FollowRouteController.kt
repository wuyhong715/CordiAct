package com.cordiact.harness.deepseek

import java.net.InetSocketAddress
import java.security.interfaces.ECPublicKey
import java.util.concurrent.Executor
import java.util.concurrent.ScheduledExecutorService

/**
 * Host-owned lifecycle for one configured route. Changing scope or trust retires the old route
 * even when its visible IDs are unchanged. No persistence, trust enrollment, or credential UI.
 * The scheduler and off-thread reader are borrowed; close this controller before stopping them.
 */
class FollowRouteController internal constructor(
    private val scheduler: ScheduledExecutorService,
    private val client: FollowPipeClient,
    private val dial: (FollowPairing.Approval) -> ApprovedFollowPipe?,
) : AutoCloseable {
    constructor(scheduler: ScheduledExecutorService, reads: Executor) :
        this(scheduler, FollowPipeClient(scheduler, reads), FollowPairing.Approval::connect)

    class Route internal constructor()
    class Attempt internal constructor()

    private class Selection(val route: Route, val pairing: FollowPairing, val delivery: FollowPairingEnvelope) {
        var ready: Pending? = null
        var connecting: Pending? = null
        var current: Live? = null
    }

    private class Pending(val attempt: Attempt, val approval: FollowPairing.Approval)
    private class Live(val attempt: Attempt, val handle: FollowPipeLifecycle.Handle)

    private val lock = Any()
    private var selection: Selection? = null
    private var closed = false

    /** Atomically replace the approved configuration; never derive trust from a signed offer. */
    fun configure(
        target: FollowTarget,
        remoteWorkspaceId: String,
        address: InetSocketAddress,
        certificateSha256: ByteArray,
        enrollmentKey: ECPublicKey,
        deliveryKey: ByteArray,
    ): Route? {
        return synchronized(lock) {
            if (closed) return null
            val pairing = FollowPairing(target, remoteWorkspaceId, address, certificateSha256, enrollmentKey, scheduler)
            val delivery = FollowPairingEnvelope(deliveryKey)
            selection?.let(::discard)
            Route().also { selection = Selection(it, pairing, delivery) }
        }
    }

    /** Starting an explicit new attempt clears any previous data and cancels the prior dial. */
    fun challenge(route: Route): FollowPairing.Challenge? = synchronized(lock) {
        val selected = matching(route) ?: return null
        retire(selected)
        selected.pairing.challenge()
    }

    /** Accept only the sealed response for the current challenge; there is no plaintext fallback. */
    fun approve(route: Route, challenge: FollowPairing.Challenge, envelope: ByteArray): Attempt? = synchronized(lock) {
        val selected = matching(route) ?: return null
        val approval = selected.delivery.approve(selected.pairing, challenge, envelope) ?: return null
        Attempt().also { selected.ready = Pending(it, approval) }
    }

    /** Call off-thread. A late handshake cannot publish data or close a replacement selection. */
    fun connect(attempt: Attempt): Boolean {
        val pending = synchronized(lock) {
            val selected = selection ?: return false
            val exact = selected.ready?.takeIf { it.attempt === attempt } ?: return false
            selected.ready = null
            selected.connecting = exact
            exact
        }
        val pipe = try {
            dial(pending.approval)
        } catch (failure: Exception) {
            synchronized(lock) {
                selection?.takeIf { it.connecting === pending }?.connecting = null
            }
            throw failure
        } finally {
            pending.approval.close()
        }
        return try {
            synchronized(lock) {
                val selected = selection?.takeIf { it.connecting === pending } ?: return false
                selected.connecting = null
                val handle = pipe?.let(client::begin) ?: return false
                selected.current = Live(attempt, handle)
                true
            }
        } finally {
            // No-op if ownership was transferred to the selected reader.
            pipe?.close()
        }
    }

    /** Resolve fresh at render time. Stale attempts cannot read the replacement. */
    fun status(attempt: Attempt): FollowStatus? = synchronized(lock) {
        selection?.current?.takeIf { it.attempt === attempt }?.let { client.status(it.handle) }
    }

    /** A stale route cannot be removed by a delayed settings or lifecycle callback. */
    fun clear(route: Route): Boolean = synchronized(lock) {
        val selected = matching(route) ?: return false
        selection = null
        discard(selected)
        true
    }

    override fun close() = synchronized(lock) {
        closed = true
        selection?.let(::discard)
        selection = null
        client.close()
    }

    private fun matching(route: Route) = selection?.takeIf { it.route === route }

    private fun discard(selected: Selection) {
        retire(selected)
        selected.pairing.close()
        selected.delivery.close()
    }

    private fun retire(selected: Selection) {
        val live = selected.current
        selected.current = null
        selected.connecting = null
        selected.ready = null
        if (live != null) client.clear(live.handle)
        selected.pairing.cancel()
    }
}
