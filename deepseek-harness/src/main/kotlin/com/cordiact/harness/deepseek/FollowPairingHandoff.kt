package com.cordiact.harness.deepseek

import java.io.InputStream
import java.io.OutputStream
import java.util.Base64
import java.util.concurrent.atomic.AtomicReference

/** Exclusive one-shot control-plane carrier supplied by the trusted launcher, not peer authentication. */
class FollowPairingHandoff(
    input: InputStream,
    output: OutputStream,
    cancel: () -> Unit,
) : AutoCloseable {
    // close() can run concurrently with blocking I/O. The launcher supplies cancellation that
    // closes both directions and unblocks I/O without waiting for the worker or flushing bytes.
    internal class Channel(val input: InputStream, val output: OutputStream, val cancel: () -> Unit)
    private val channel = AtomicReference<Channel?>(Channel(input, output, cancel))

    internal fun take(): Channel? = channel.getAndSet(null)
    override fun close() { take()?.let(::cancelHandoff) }
}

internal fun cancelHandoff(channel: FollowPairingHandoff.Channel) {
    try { channel.cancel() } catch (_: Exception) { /* Ownership is already retired. */ }
}

/** Send one exact frame, signal EOF, then require an entire canonical response followed by EOF. */
internal fun exchangeFollowPairing(
    channel: FollowPairingHandoff.Channel,
    nonce: String,
    proof: String,
    live: () -> Boolean,
): ByteArray? {
    if (!live()) return null
    val request = "cordiact-follow-review/1 $nonce $proof\n".encodeToByteArray()
    val response = ByteArray(PREFIX.size + 1152 + 1)
    try {
        channel.output.write(request)
        if (!live()) return null
        channel.output.flush()
        if (!live()) return null
        channel.output.close() // Trusted dedicated request half; must signal EOF to the Host.
        var size = 0
        while (live()) {
            val byte = channel.input.read()
            if (byte == -1) break
            if (size == response.size || (size > 0 && response[size - 1] == 10.toByte())) return null
            if (byte != 10 && byte !in 32..126) return null
            response[size++] = byte.toByte()
        }
        if (!live() || size < PREFIX.size + 53 || response[size - 1] != 10.toByte() ||
            !PREFIX.indices.all { response[it] == PREFIX[it] }) return null
        val encoded = response.decodeToString(PREFIX.size, size - 1)
        val sealed = try { Base64.getDecoder().decode(encoded) } catch (_: IllegalArgumentException) { return null }
        if (sealed.size in 38..864 && Base64.getEncoder().encodeToString(sealed) == encoded) return sealed
        sealed.fill(0)
        return null
    } finally {
        request.fill(0)
        response.fill(0)
    }
}

private val PREFIX = "cordiact-follow-envelope/1 ".encodeToByteArray()
