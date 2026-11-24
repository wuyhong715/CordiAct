package com.cordiact.harness.deepseek

import java.io.InputStream
import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.atomic.AtomicReference

/**
 * A dedicated, already requested read stream and its cancellation. The trusted connector must
 * bind it to the exact target and keep the request side open. Cancellation must be non-blocking,
 * stop both directions, and unblock a pending read. This is not an authentication mechanism.
 */
class RequestedFollowPipe(
    val target: FollowTarget,
    input: InputStream,
    cancel: () -> Unit,
) {
    internal class Port(val input: InputStream, val cancel: () -> Unit)
    private val port = AtomicReference<Port?>(Port(input, cancel))
    internal fun take(): Port? = port.getAndSet(null)
}

/** Runs the read side of an authenticated pipe once, without reconnecting or opening a process. */
class FollowPipeClient internal constructor(
    private val lifecycle: FollowPipeLifecycle,
    private val reads: Executor,
) : AutoCloseable {
    /** Supply a bounded off-thread executor that throws on rejection, separate from the deadline scheduler. */
    constructor(scheduler: ScheduledExecutorService, reads: Executor) : this(FollowPipeLifecycle(scheduler), reads)

    /** Start a separate read loop for this pipe only. A transferred pipe cannot be reopened. */
    fun begin(pipe: RequestedFollowPipe, remainingMillis: Long): FollowPipeLifecycle.Handle? {
        require(remainingMillis in 1..MAX_FOLLOW_LEASE_MS)
        val port = pipe.take() ?: return null
        // The slot releases both resources when the lifecycle ends, even if the task is still queued.
        val pending = AtomicReference(port)
        val handle = lifecycle.begin(FollowPipeOwnership(pipe.target) {
            pending.set(null)
            port.cancel()
        }, remainingMillis) ?: return null
        if (pending.get() == null) return handle
        try {
            reads.execute {
                val current = pending.getAndSet(null) ?: return@execute
                read(handle, current.input)
            }
        } catch (_: RejectedExecutionException) {
            lifecycle.end(handle, FollowEnd.DISCONNECTED)
        }
        return handle
    }

    /** Read on demand for the exact handle rather than retaining a snapshot across a selection change. */
    fun status(handle: FollowPipeLifecycle.Handle): FollowStatus? = lifecycle.status(handle)

    /** For trusted notifications only. EOF on the byte pipe is not a revocation notification. */
    fun revoke(handle: FollowPipeLifecycle.Handle): Boolean = lifecycle.end(handle, FollowEnd.ACCESS_LOST)

    fun clear(handle: FollowPipeLifecycle.Handle): Boolean = lifecycle.clear(handle)

    override fun close() = lifecycle.close()

    private fun read(handle: FollowPipeLifecycle.Handle, input: InputStream) {
        val buffer = ByteArray(4096)
        try {
            while (true) {
                when (lifecycle.status(handle)) {
                    FollowStatus.AwaitingBaseline, is FollowStatus.Current -> Unit
                    else -> return
                }
                val count = input.read(buffer)
                if (count == -1) {
                    lifecycle.eof(handle)
                    return
                }
                if (count !in 1..buffer.size) {
                    lifecycle.end(handle, FollowEnd.INVALID_SOURCE)
                    return
                }
                if (lifecycle.receive(handle, buffer, length = count) != FollowPipeReceiver.Receive.ACCEPTED) return
            }
        } catch (_: Exception) {
            lifecycle.end(handle, FollowEnd.DISCONNECTED)
        } finally {
            buffer.fill(0)
        }
    }
}
