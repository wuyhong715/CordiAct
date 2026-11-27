package com.cordiact.model.providers

import com.cordiact.core.conversation.ModelBackend
import com.cordiact.core.conversation.ModelCall
import com.cordiact.core.conversation.ModelEvent
import com.cordiact.core.conversation.ModelGenerationRequest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/** Tracks calls to a scoped route so withdrawal sends a terminal event and cancels the transport. */
class RevocableModelRoute(
    private val delegate: ModelBackend,
    private val published: () -> Boolean,
    private val admit: (ModelGenerationRequest) -> Boolean,
) : ModelBackend by delegate, AutoCloseable {
    private val closed = AtomicBoolean()
    private val active = ConcurrentHashMap.newKeySet<Pending>()

    override fun generate(request: ModelGenerationRequest, onEvent: (ModelEvent) -> Unit): ModelCall {
        val pending = Pending(onEvent)
        active.add(pending)
        try {
            if (!current() || !admit(request)) {
                pending.retire()
                return ModelCall {}
            }
            pending.attach(delegate.generate(request, pending::deliver))
        } catch (failure: Exception) {
            pending.cancel()
            throw failure
        }
        return ModelCall(pending::cancel)
    }

    private fun current() = !closed.get() && published()

    override fun close() {
        closed.set(true)
        active.toList().forEach { it.retire() }
    }

    private inner class Pending(private val callback: (ModelEvent) -> Unit) {
        private val gate = Any()
        private var stopped = false
        private var cancelOnAttach = false
        private var upstream: ModelCall? = null

        fun attach(call: ModelCall) {
            val cancel = synchronized(gate) {
                if (stopped) cancelOnAttach else { upstream = call; false }
            }
            if (cancel) runCatching { call.cancel() }
            if (!current()) retire()
        }

        fun deliver(event: ModelEvent) {
            val cancel = synchronized(gate) {
                if (stopped) return
                val allowed = current()
                if (!allowed || event is ModelEvent.Completed || event is ModelEvent.Failed) {
                    stopped = true
                    cancelOnAttach = !allowed
                    active.remove(this)
                }
                runCatching { callback(if (allowed) event else retiredEvent()) }
                if (allowed) null else upstream
            }
            cancel?.let { runCatching { it.cancel() } }
        }

        fun retire() = stop(retiredEvent())
        fun cancel() = stop(null)

        private fun stop(event: ModelEvent?) {
            val call = synchronized(gate) {
                if (stopped) return
                stopped = true
                cancelOnAttach = true
                active.remove(this)
                event?.let { runCatching { callback(it) } }
                upstream
            }
            call?.let { runCatching { it.cancel() } }
        }
    }

    private fun retiredEvent() = ModelEvent.Failed("route_revoked", "Workspace model route is no longer approved")
}
