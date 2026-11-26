package com.cordiact.app.deepseek

import com.cordiact.harness.deepseek.FollowPairing
import com.cordiact.harness.deepseek.FollowPairingHandoff
import com.cordiact.harness.deepseek.FollowRouteController
import com.cordiact.harness.deepseek.FollowRouteDescriptor
import com.cordiact.harness.deepseek.FollowStatus
import com.cordiact.harness.deepseek.FollowTarget

/** Keeps preconfigured trust in memory, but retires all attempt state on leaving the foreground. */
internal class ForegroundFollowScope(private val controller: FollowRouteController) : AutoCloseable {
    private val lock = Any()
    private var foreground = false
    private var closed = false
    private var route: FollowRouteController.Route? = null
    private var challenge: FollowPairing.Challenge? = null
    private var attempt: FollowRouteController.Attempt? = null
    private var configuration: Configuration? = null

    /** Opaque one-use intent; scope is captured before asynchronous provisioning starts. */
    class Configuration internal constructor(internal val target: FollowTarget, internal val remoteWorkspace: String)

    fun resume() = synchronized(lock) { if (!closed) foreground = true }

    fun pause() = synchronized(lock) {
        foreground = false
        configuration = null
        retire()
    }

    /** Capture intent before provisioning. Null expects an empty selection, never any current route. */
    fun requestConfiguration(
        expectedRoute: FollowRouteController.Route?,
        target: FollowTarget,
        remoteWorkspace: String,
    ): Configuration? = synchronized(lock) {
        if (!foreground || closed || route !== expectedRoute) return null
        Configuration(target, remoteWorkspace).also { configuration = it }
    }

    fun discardConfiguration(exact: Configuration): Boolean = synchronized(lock) {
        if (configuration !== exact) return false
        configuration = null
        true
    }

    /** The trusted owner authenticates the descriptor and separately provisions the key. */
    fun configure(
        exact: Configuration,
        descriptor: FollowRouteDescriptor,
        key: ByteArray,
    ): FollowRouteController.Route? = synchronized(lock) {
        if (!foreground || closed || configuration !== exact) return null
        configuration = null
        if (key.size != 32) return null
        val next = controller.configure(exact.target, exact.remoteWorkspace, descriptor, key) ?: return null
        route = next
        challenge = null
        attempt = null
        next
    }

    /** A delayed action cannot begin a challenge using a replacement page's trust. */
    fun begin(exact: FollowRouteController.Route): FollowPairing.Challenge? = synchronized(lock) {
        if (!foreground || closed || route !== exact) return null
        attempt = null
        controller.challenge(exact).also { challenge = it }
    }

    /** Release the page's exact selection, even when it exits while the Activity is paused. */
    fun clear(exact: FollowRouteController.Route): Boolean = synchronized(lock) {
        if (route !== exact) return false
        configuration = null
        route = null
        challenge = null
        attempt = null
        controller.clear(exact)
    }

    /** Off-thread; always consumes the exclusive carrier, even if the scope has gone away. */
    fun exchange(exact: FollowPairing.Challenge, handoff: FollowPairingHandoff): FollowRouteController.Attempt? {
        val selected = synchronized(lock) { route?.takeIf { valid(exact, it) } }
        if (selected == null) {
            handoff.close()
            return null
        }
        val result = controller.exchange(selected, exact, handoff) ?: return null
        return synchronized(lock) {
            if (!valid(exact, selected)) return null
            result.also { attempt = it }
        }
    }

    /** Off-thread; this is a separate explicit step after approval. */
    fun connect(exact: FollowRouteController.Attempt): Boolean {
        synchronized(lock) { if (!foreground || closed || attempt !== exact) return false }
        if (!controller.connect(exact)) return false
        return synchronized(lock) { foreground && !closed && attempt === exact }
    }

    fun status(exact: FollowRouteController.Attempt): FollowStatus? = synchronized(lock) {
        if (!foreground || closed || attempt !== exact) null else controller.status(exact)
    }

    fun cancel(exact: FollowPairing.Challenge): Boolean = synchronized(lock) {
        if (challenge !== exact) return false
        retire()
        true
    }

    override fun close(): Unit = synchronized(lock) {
        if (closed) return@synchronized
        closed = true
        foreground = false
        configuration = null
        route = null
        challenge = null
        attempt = null
        controller.close()
    }

    private fun valid(exact: FollowPairing.Challenge, selected: FollowRouteController.Route): Boolean =
        foreground && !closed && route === selected && challenge === exact

    private fun retire() {
        val original = challenge
        challenge = null
        attempt = null
        if (original != null) route?.let { controller.cancel(it, original) }
    }
}
