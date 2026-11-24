package com.cordiact.harness.deepseek

import java.nio.charset.StandardCharsets

/**
 * Receive version 1 follow-pipe frames from an already authenticated, exclusively owned carrier.
 * This is not a connection or authorization API. The caller must bind the exact carrier to its
 * approved target before opening, and keep that returned handle on every read and terminal callback.
 * Poll [status] with the currently selected handle at render time; never queue a detached snapshot.
 */
class FollowPipeReceiver {
    class Handle internal constructor(internal val generation: ReadOnlySessionFollow.Generation)

    enum class Receive { ACCEPTED, RETIRED, STALE }

    private class Stream(val handle: Handle) {
        val line = ByteArray(MAX_FRAME_BYTES - 1)
        var length = 0
        var expected = 0L
        var ended = false

        fun dropLine() {
            line.fill(0, 0, length)
            length = 0
        }
    }

    private val reducer = ReadOnlySessionFollow()
    private var selected: Stream? = null

    /** A reopen always needs a new pipe and a new baseline, even for the same target. */
    @Synchronized
    fun begin(target: FollowTarget): Handle {
        selected?.dropLine()
        return Handle(reducer.begin(target)).also { selected = Stream(it) }
    }

    @Synchronized
    fun status(handle: Handle): FollowStatus? = matching(handle)?.let { reducer.status(handle.generation) }

    /**
     * Consume an ordered slice as bytes, with no dependence on read boundaries. The caller owns
     * the array and must not mutate it during this call. A retired pipe must not be read again.
     */
    @Synchronized
    fun receive(handle: Handle, bytes: ByteArray, offset: Int = 0, length: Int = bytes.size - offset): Receive {
        val stream = matching(handle)?.takeUnless { it.ended } ?: return Receive.STALE
        require(offset in 0..bytes.size && length in 0..bytes.size - offset)
        // The carrier supplies bounded read slices; do not let one call monopolize the state lock.
        if (length > MAX_READ_BYTES) return invalid(stream)
        for (i in offset until offset + length) {
            val byte = bytes[i].toInt()
            when {
                byte == 10 -> if (!applyLine(stream)) return invalid(stream)
                byte !in 32..126 || stream.length == stream.line.size -> return invalid(stream)
                else -> stream.line[stream.length++] = bytes[i]
            }
        }
        return Receive.ACCEPTED
    }

    /** EOF has no authorization reason on this wire. An unfinished line is invalid, not a baseline. */
    @Synchronized
    fun eof(handle: Handle): Boolean {
        val stream = matching(handle)?.takeUnless { it.ended } ?: return false
        return end(stream, if (stream.length == 0) FollowEnd.DISCONNECTED else FollowEnd.INVALID_SOURCE)
    }

    /** Use only a reason known locally (e.g. trusted revocation notification, or carrier failure). */
    @Synchronized
    fun end(handle: Handle, reason: FollowEnd): Boolean = matching(handle)?.let { end(it, reason) } ?: false

    @Synchronized
    fun clear(handle: Handle): Boolean {
        val stream = matching(handle) ?: return false
        stream.dropLine()
        selected = null
        return reducer.clear(handle.generation)
    }

    private fun matching(handle: Handle): Stream? = selected?.takeIf { it.handle === handle }

    private fun end(stream: Stream, reason: FollowEnd): Boolean {
        if (stream.ended) return false
        stream.ended = true
        stream.dropLine()
        return reducer.end(stream.handle.generation, reason)
    }

    private fun invalid(stream: Stream): Receive {
        end(stream, FollowEnd.INVALID_SOURCE)
        return Receive.RETIRED
    }

    private fun applyLine(stream: Stream): Boolean {
        val text = String(stream.line, 0, stream.length, StandardCharsets.US_ASCII)
        stream.dropLine()
        val frame = FRAME.matchEntire(text) ?: return false
        val (wireDelivery, wireCursor, wireActive, wireRunning) = frame.destructured
        val delivery = wireDelivery.toLongOrNull() ?: return false
        val cursor = wireCursor.toLongOrNull() ?: return false
        if (delivery != stream.expected || delivery > MAX_SAFE_DSH_NUMBER || cursor > MAX_SAFE_DSH_NUMBER) return false
        val generation = stream.handle.generation
        val state = FollowSessionState(cursor, wireActive == "true", wireRunning == "true")
        if (reducer.accept(generation, generation.target, delivery, state) != FollowUpdate.APPLIED) return false
        stream.expected++
        return true
    }

    private companion object {
        const val MAX_FRAME_BYTES = 128 // including LF
        const val MAX_READ_BYTES = 4096 // a carrier can split arbitrary larger deliveries
        const val MAX_SAFE_DSH_NUMBER = 9_007_199_254_740_991L
        // An exact encoding, not a general JSON parser: no duplicate/unknown keys or ambiguous numbers.
        val FRAME = Regex("""\{"delivery":(0|[1-9][0-9]{0,15}),"cursor":(-1|0|[1-9][0-9]{0,15}),"active":(true|false),"running":(true|false)\}""")
    }
}
