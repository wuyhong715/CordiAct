package com.cordiact.harness.deepseek

import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Path
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Run only via the explicit Gradle followPipeInteropTest task with a local Node executable. */
class FollowPipeInteropTest {
    private val node = checkNotNull(System.getProperty("cordiact.follow.node"))
    private val fixture = Path.of("host-follow", "test", "jvm-interop.fixture.mjs").toAbsolutePath()
    private val target = FollowTarget("host-a", "local-a", "session-a")

    @Test fun realNodeHostFramesPassKotlinDecoderThenEofClearsWithoutGuessingReason() = withHost { host, token ->
        val old = host.receiver.begin(target)
        host.open(token)
        host.readLineInto(old)
        assertEquals(FollowStatus.Current(FollowSessionState(-1, false, false)), host.receiver.status(old))
        host.readLineInto(old)
        assertEquals(FollowStatus.Current(FollowSessionState(7, true, true)), host.receiver.status(old))
        assertEquals(-1, host.input.read())
        assertTrue(host.receiver.eof(old))
        assertEquals(FollowStatus.Ended(FollowEnd.DISCONNECTED), host.receiver.status(old))
    }

    @Test fun lateRealPipeBytesAndEofCannotRestoreOrCloseReplacementSelection() = withHost { host, token ->
        val previous = host.receiver.begin(target)
        host.open(token)
        host.readLineInto(previous)
        val replacement = host.receiver.begin(target)
        host.readLineInto(previous, FollowPipeReceiver.Receive.STALE)
        assertEquals(-1, host.input.read())
        assertEquals(false, host.receiver.eof(previous))
        assertEquals(FollowStatus.AwaitingBaseline, host.receiver.status(replacement))
    }

    @Test fun silentHostLosesItsProjectionAndClosesTheRealPipeAtLocalExpiry() = withHost("silent") { host, token ->
        val scheduler = Executors.newSingleThreadScheduledExecutor()
        val cancelled = CountDownLatch(1)
        try {
            FollowPipeLifecycle(scheduler).use { follow ->
                val pipe = FollowPipeOwnership(target) {
                    try {
                        host.disconnect()
                    } finally {
                        cancelled.countDown()
                    }
                }
                val handle = checkNotNull(follow.begin(pipe, 1_500))
                host.open(token)
                host.readFrame { bytes -> assertEquals(FollowPipeReceiver.Receive.ACCEPTED, follow.receive(handle, bytes)) }
                assertEquals(FollowStatus.Current(FollowSessionState(-1, false, false)), follow.status(handle))
                // No read or status call triggers expiry; cancelled is signalled by the local timer.
                assertTrue(cancelled.await(4, TimeUnit.SECONDS))
                assertEquals(FollowStatus.Ended(FollowEnd.DISCONNECTED), follow.status(handle))
                assertEquals(-1, host.input.read())
                assertEquals(false, follow.eof(handle))
            }
        } finally {
            scheduler.shutdownNow()
        }
    }

    private class Host(val input: InputStream, private val output: OutputStream) {
        val receiver = FollowPipeReceiver()

        fun open(token: String) {
            output.write("cordiact-follow/1 remote-a session-a $token\n".encodeToByteArray())
            output.flush()
        }

        fun disconnect() = output.close()

        fun readFrame(accept: (ByteArray) -> Unit) {
            val bytes = ByteArray(128)
            for (i in bytes.indices) {
                val next = input.read()
                assertTrue(next >= 0, "EOF inside a follow frame")
                bytes[i] = next.toByte()
                if (next == 10) {
                    accept(bytes.copyOf(i + 1))
                    return
                }
            }
            error("Follow frame exceeded its wire bound")
        }

        fun readLineInto(handle: FollowPipeReceiver.Handle, result: FollowPipeReceiver.Receive = FollowPipeReceiver.Receive.ACCEPTED) {
            val byte = ByteArray(1)
            repeat(128) {
                val next = input.read()
                assertTrue(next >= 0, "EOF inside a follow frame")
                byte[0] = next.toByte()
                assertEquals(result, receiver.receive(handle, byte))
                if (next == 10) return
            }
            error("Follow frame exceeded its wire bound")
        }
    }

    private fun withHost(mode: String = "normal", body: (Host, String) -> Unit) {
        val child = ProcessBuilder(node, fixture.toString(), mode).apply { environment().clear() }.start()
        val executor = Executors.newSingleThreadExecutor { thread -> Thread(thread, "follow-pipe-interop").apply { isDaemon = true } }
        try {
            val run = executor.submit(Callable {
                val token = readToken(child.errorStream)
                body(Host(child.inputStream, child.outputStream), token)
                check(child.waitFor(2, TimeUnit.SECONDS) && child.exitValue() == 0) { "Synthetic Host did not exit cleanly" }
            })
            run.get(10, TimeUnit.SECONDS)
        } finally {
            if (child.isAlive) child.destroyForcibly()
            child.waitFor(2, TimeUnit.SECONDS)
            child.inputStream.close()
            child.outputStream.close()
            child.errorStream.close()
            executor.shutdownNow()
        }
    }

    private fun readToken(input: InputStream): String {
        val token = StringBuilder(48)
        repeat(49) {
            val byte = input.read()
            if (byte == 10) {
                val value = token.toString()
                check(Regex("caf1_[A-Za-z0-9_-]{43}").matches(value)) { "Invalid synthetic grant" }
                return value
            }
            check(byte in 32..126) { "Incomplete synthetic grant" }
            token.append(byte.toChar())
        }
        error("Oversized synthetic grant")
    }
}
