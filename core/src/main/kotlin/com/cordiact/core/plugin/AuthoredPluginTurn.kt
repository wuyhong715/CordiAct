package com.cordiact.core.plugin

/** Host-only lifetime for one foreground authoring turn or one local confirmation. */
class AuthoredPluginTurn internal constructor(
    val workspaceId: String,
    val sessionId: String,
    internal val isUserConfirmation: Boolean = false,
) : AutoCloseable {
    private val lock = Any()
    private val closing = mutableSetOf<() -> Unit>()
    private var claimed = false
    @Volatile var isOpen: Boolean = true
        private set

    fun matches(workspaceId: String, sessionId: String?): Boolean =
        this.workspaceId == workspaceId && this.sessionId == sessionId

    internal fun claimConfirmation(): Boolean = synchronized(lock) {
        if (!isUserConfirmation || !isOpen || claimed) return@synchronized false
        claimed = true
        true
    }

    /** Serialize a short publication against cancellation, without holding the gate for user or I/O waits. */
    internal fun <T> commit(block: () -> T): T = synchronized(lock) {
        if (!isOpen || Thread.currentThread().isInterrupted) {
            throw AuthoredPluginException(AuthoredPluginException.Code.CALLER_UNAVAILABLE, "The foreground plugin turn ended")
        }
        block()
    }

    /** For a non-blocking, exact-request cancellation callback. Never invoked while this gate is held. */
    internal fun onClose(callback: () -> Unit): AutoCloseable {
        val registered = synchronized(lock) { isOpen && closing.add(callback) }
        if (!registered) callback()
        return AutoCloseable { synchronized(lock) { closing.remove(callback) } }
    }

    override fun close() {
        val callbacks = synchronized(lock) {
            if (!isOpen) return
            isOpen = false
            closing.toList().also { closing.clear() }
        }
        callbacks.forEach { it() }
    }
}

/** Tracks the selected session and resumed workbench. Returning to the same owner cannot revive its old turn. */
class AuthoredPluginForeground {
    private data class Owner(val workspaceId: String, val sessionId: String)
    private var owner: Owner? = null
    private var workbench: Any? = null
    private var turn: AuthoredPluginTurn? = null
    private var confirmation: AuthoredPluginTurn? = null
    private val policyUpdates = mutableSetOf<String>()

    @Synchronized fun select(workspaceId: String, sessionId: String) {
        val selected = Owner(workspaceId, sessionId)
        if (owner == selected) return
        retireForegroundActions()
        owner = selected
    }

    @Synchronized fun resume(workbench: Any) {
        if (this.workbench === workbench) return
        retireForegroundActions()
        this.workbench = workbench
    }

    @Synchronized fun pause(workbench: Any) {
        if (this.workbench !== workbench) return
        this.workbench = null
        retireForegroundActions()
    }

    @Synchronized fun invalidateSession(workspaceId: String, sessionId: String) {
        if (owner == Owner(workspaceId, sessionId)) retireForegroundActions()
    }

    /** Retire the old foreground rights and block new offers until the host's policy write finishes. */
    fun <T> whileUpdatingPolicy(workspaceId: String, update: () -> T): T {
        synchronized(this) {
            check(policyUpdates.add(workspaceId)) { "Plugin policy update already in progress" }
            if (owner?.workspaceId == workspaceId) retireForegroundActions()
        }
        return try { update() } finally { synchronized(this) { policyUpdates.remove(workspaceId) } }
    }

    @Synchronized fun openTurn(workspaceId: String, sessionId: String): AuthoredPluginTurn? {
        if (workspaceId in policyUpdates || workbench == null || owner != Owner(workspaceId, sessionId)) return null
        retireTurn()
        return AuthoredPluginTurn(workspaceId, sessionId).also { turn = it }
    }

    /** Issued to trusted UI only; does not cancel or borrow the running Agent's turn. */
    @Synchronized fun openConfirmation(workspaceId: String, sessionId: String): AuthoredPluginTurn? {
        if (workspaceId in policyUpdates || workbench == null || owner != Owner(workspaceId, sessionId)) return null
        confirmation?.close()
        return AuthoredPluginTurn(workspaceId, sessionId, isUserConfirmation = true).also { confirmation = it }
    }

    private fun retireForegroundActions() {
        retireTurn()
        confirmation?.close()
        confirmation = null
    }

    private fun retireTurn() {
        turn?.close()
        turn = null
    }
}
