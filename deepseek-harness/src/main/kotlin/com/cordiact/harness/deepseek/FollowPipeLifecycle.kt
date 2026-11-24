package com.cordiact.harness.deepseek

import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * One transfer of an exclusively owned, already authorized pipe. The trusted builder binds the
 * exact local target and supplies a non-blocking cancellation that closes both directions. This
 * object cannot verify Host identity or authorize a pipe, and must never wrap a shared endpoint.
 */
class FollowPipeOwnership(val target: FollowTarget, cancel: () -> Unit) {
    private val owner = AtomicReference<(() -> Unit)?>(cancel)
    internal fun take(): (() -> Unit)? = owner.getAndSet(null)
}

/**
 * Owns one pipe generation. State is cleared before cancellation is requested. The borrowed
 * scheduler must remain live while following; it is not shut down by this class. A stalled
 * scheduler cannot keep expired status readable. No automatic reconnect or lease extension,
 * including on valid data. Retrieve status at render time.
 */
class FollowPipeLifecycle internal constructor(
    private val nowNanos: () -> Long,
    private val schedule: (delayNanos: Long, action: () -> Unit) -> (() -> Unit),
) : AutoCloseable {
    constructor(scheduler: ScheduledExecutorService) : this(System::nanoTime, { delay, action ->
        val future = scheduler.schedule({ action() }, delay, TimeUnit.NANOSECONDS)
        val cancel: () -> Unit = { future.cancel(false) }
        cancel
    })

    class Handle internal constructor(internal val receiver: FollowPipeReceiver.Handle)

    private class Selection(
        val handle: Handle,
        val deadline: Long,
        val baselineDeadline: Long,
        var cancelPipe: (() -> Unit)?,
    ) {
        var cancelBaselineTimer: (() -> Unit)? = null
        var cancelLeaseTimer: (() -> Unit)? = null
        var awaiting = true
    }

    private val lock = Any()
    private val receiver = FollowPipeReceiver()
    private var selected: Selection? = null
    private var closed = false

    /**
     * Take ownership once, even if scheduling fails or this lifecycle has already closed.
     * Supply a conservative remaining grant budget; the local cap may end earlier than the Host.
     */
    fun begin(pipe: FollowPipeOwnership, remainingMillis: Long): Handle? {
        return beginUntil(pipe, followDeadline(nowNanos(), remainingMillis))
    }

    /** Preserve the existing local deadline across request dispatch and carrier handoff. */
    internal fun beginUntil(pipe: FollowPipeOwnership, deadlineNanos: Long): Handle? {
        val cancel = pipe.take() ?: return null
        var retire: (() -> Unit)? = null
        var reject: (() -> Unit)? = null
        val handle = synchronized(lock) {
            val start = nowNanos()
            val lease = deadlineNanos - start
            if (closed || lease <= 0 || lease > TimeUnit.MILLISECONDS.toNanos(MAX_FOLLOW_LEASE_MS)) {
                reject = cancel
                return@synchronized null
            }
            retire = selected?.let { detach(it) }
            val baseline = minOf(lease, TimeUnit.MILLISECONDS.toNanos(BASELINE_MS))
            val next = Selection(Handle(receiver.begin(pipe.target)), deadlineNanos, start + baseline, cancel)
            selected = next
            try {
                next.cancelLeaseTimer = schedule(lease) { expire(next) }
                if (baseline < lease) next.cancelBaselineTimer = schedule(baseline) { expire(next) }
            } catch (_: RejectedExecutionException) {
                receiver.end(next.handle.receiver, FollowEnd.DISCONNECTED)
                reject = detach(next)
            }
            next.handle
        }
        requestCancel(retire)
        requestCancel(reject)
        return handle
    }

    fun status(handle: Handle): FollowStatus? = withCurrent(handle, null) { stream ->
        receiver.status(stream.handle.receiver)
    }

    fun receive(handle: Handle, bytes: ByteArray, offset: Int = 0, length: Int = bytes.size - offset): FollowPipeReceiver.Receive =
        withCurrent(handle, FollowPipeReceiver.Receive.STALE) { stream ->
            val result = receiver.receive(stream.handle.receiver, bytes, offset, length)
            if (expireIfDue(stream)) return@withCurrent FollowPipeReceiver.Receive.RETIRED
            if (stream.awaiting && receiver.status(stream.handle.receiver) is FollowStatus.Current) {
                stream.awaiting = false
                stream.cancelBaselineTimer?.invoke()
                stream.cancelBaselineTimer = null
            }
            result
        }

    fun eof(handle: Handle): Boolean = withCurrent(handle, false) { receiver.eof(it.handle.receiver) }

    /** Only trusted out-of-band revocation should supply ACCESS_LOST; ordinary EOF has no reason. */
    fun end(handle: Handle, reason: FollowEnd): Boolean = withCurrent(handle, false) { receiver.end(it.handle.receiver, reason) }

    fun clear(handle: Handle): Boolean {
        val cancel = synchronized(lock) {
            val stream = selected?.takeIf { it.handle === handle } ?: return false
            receiver.clear(stream.handle.receiver)
            selected = null
            detach(stream)
        }
        requestCancel(cancel)
        return true
    }

    override fun close() {
        val cancel = synchronized(lock) {
            closed = true
            val stream = selected ?: return
            receiver.clear(stream.handle.receiver)
            selected = null
            detach(stream)
        }
        requestCancel(cancel)
    }

    private fun expire(stream: Selection) {
        val cancel = synchronized(lock) {
            if (selected !== stream || !expireIfDue(stream)) return
            detach(stream)
        }
        requestCancel(cancel)
    }

    private fun expireIfDue(stream: Selection): Boolean {
        val now = nowNanos()
        if (now - stream.deadline < 0 && (!stream.awaiting || now - stream.baselineDeadline < 0)) return false
        return receiver.end(stream.handle.receiver, FollowEnd.DISCONNECTED)
    }

    private inline fun <T> withCurrent(handle: Handle, stale: T, action: (Selection) -> T): T {
        var cancel: (() -> Unit)? = null
        try {
            return synchronized(lock) {
                val stream = selected?.takeIf { it.handle === handle } ?: return stale
                expireIfDue(stream)
                try {
                    action(stream)
                } finally {
                    if (receiver.status(stream.handle.receiver) is FollowStatus.Ended) cancel = detach(stream)
                }
            }
        } finally {
            requestCancel(cancel)
        }
    }

    private fun detach(stream: Selection): (() -> Unit)? {
        stream.cancelBaselineTimer?.invoke()
        stream.cancelLeaseTimer?.invoke()
        stream.cancelBaselineTimer = null
        stream.cancelLeaseTimer = null
        return stream.cancelPipe.also { stream.cancelPipe = null }
    }

    private fun requestCancel(cancel: (() -> Unit)?) {
        try {
            cancel?.invoke()
        } catch (_: Exception) {
            // State is already retired; a broken carrier cannot restore it or stop replacement.
        }
    }

    private companion object {
        const val BASELINE_MS = 3_000L
    }
}

internal const val MAX_FOLLOW_LEASE_MS = 30_000L

internal fun followDeadline(nowNanos: Long, remainingMillis: Long): Long {
    require(remainingMillis in 1..MAX_FOLLOW_LEASE_MS)
    return nowNanos + TimeUnit.MILLISECONDS.toNanos(remainingMillis)
}
