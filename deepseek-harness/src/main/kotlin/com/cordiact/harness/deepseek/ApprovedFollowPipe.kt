package com.cordiact.harness.deepseek

import java.io.InputStream
import java.io.OutputStream
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicReference

/**
 * Trusted handoff of an exclusive pipe and its single-target grant. The caller must have verified
 * the Host, bound both directions to it, and computed a conservative remaining grant duration.
 * Neither construction nor request submission authenticates the Host. The cancellation must
 * close this exact pair and unblock its reads and writes without blocking the caller.
 * Close an unused handoff. A successfully constructed handoff owns both directions; on invalid
 * constructor arguments, the caller retains ownership. It never exposes the grant again.
 */
class ApprovedFollowPipe private constructor(
    internal val deadlineNanos: Long,
    target: FollowTarget,
    remoteWorkspaceId: String,
    token: String,
    input: InputStream,
    output: OutputStream,
    cancel: () -> Unit,
) : AutoCloseable {
    internal constructor(
        target: FollowTarget,
        remoteWorkspaceId: String,
        token: String,
        remainingMillis: Long,
        input: InputStream,
        output: OutputStream,
        cancel: () -> Unit,
        nowNanos: () -> Long,
    ) : this(followDeadline(nowNanos(), remainingMillis), target, remoteWorkspaceId, token, input, output, cancel)
    constructor(
        target: FollowTarget,
        remoteWorkspaceId: String,
        token: String,
        remainingMillis: Long,
        input: InputStream,
        output: OutputStream,
        cancel: () -> Unit,
    ) : this(target, remoteWorkspaceId, token, remainingMillis, input, output, cancel, System::nanoTime)

    private val request: AtomicReference<RequestedFollowPipe?>

    init {
        require(ID.matches(remoteWorkspaceId)) { "Invalid remote workspace identity" }
        require(TOKEN.matches(token)) { "Invalid follow grant" }
        val prefix = "cordiact-follow/1 $remoteWorkspaceId ${target.remoteSessionId} "
            .toByteArray(StandardCharsets.US_ASCII)
        val bytes = prefix.copyOf(prefix.size + token.length + 1)
        for (i in token.indices) bytes[prefix.size + i] = token[i].code.toByte()
        bytes[bytes.lastIndex] = 10
        val frame = AtomicReference<ByteArray?>(bytes)
        request = AtomicReference(RequestedFollowPipe(target, input, send = {
            frame.getAndSet(null)?.let { payload ->
                try {
                    output.write(payload)
                    output.flush()
                } finally {
                    payload.fill(0)
                }
            }
        }, cancel = {
            frame.getAndSet(null)?.fill(0)
            cancel()
        }))
    }

    internal fun take(): RequestedFollowPipe? = request.getAndSet(null)

    override fun close() {
        take()?.take()?.cancel?.invoke()
    }

    internal companion object {
        fun until(
            target: FollowTarget,
            remoteWorkspaceId: String,
            token: String,
            deadlineNanos: Long,
            input: InputStream,
            output: OutputStream,
            cancel: () -> Unit,
        ): ApprovedFollowPipe = ApprovedFollowPipe(deadlineNanos, target, remoteWorkspaceId, token, input, output, cancel)

        private val ID = Regex("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}")
        private val TOKEN = Regex("caf1_[A-Za-z0-9_-]{43}")
    }
}
