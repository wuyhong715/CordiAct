package com.cordiact.harness.deepseek

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream
import java.util.ArrayDeque
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ApprovedFollowPipeTest {
    private val target = FollowTarget("host-a", "local-workspace", "session-a")
    private val token = "caf1_" + "A".repeat(43)

    @Test fun requestIsBoundToRemoteWorkspaceAndSessionAndSentOnce() {
        val f = Fixture()
        val output = ByteArrayOutputStream()
        val pipe = f.approve(output)
        assertNotNull(f.client.begin(pipe))
        assertNull(f.client.begin(pipe))
        pipe.close() // Caller no longer owns it.
        assertEquals(0, output.size())
        f.run()
        assertEquals("cordiact-follow/1 remote-workspace session-a $token\n", output.toString(Charsets.US_ASCII))
        assertEquals(1, f.cancels)
    }

    @Test fun timeSpentWaitingForHandoffOrQueuedDispatchIsNotGrantedAgain() {
        val f = Fixture()
        val output = ByteArrayOutputStream()
        val pipe = f.approve(output, 100)
        f.advance(75)
        val handle = assertNotNull(f.client.begin(pipe))
        assertEquals(TimeUnit.MILLISECONDS.toNanos(25), f.delays.single())
        f.advance(25)
        f.run() // No timer ran, so the dispatch must itself check the original deadline.
        assertEquals(0, output.size())
        assertEquals(1, f.cancels)
        assertEquals(FollowStatus.Ended(FollowEnd.DISCONNECTED), f.client.status(handle))
    }

    @Test fun lateExpiredHandoffCannotReplaceTheCurrentSelection() {
        val f = Fixture()
        val output = ByteArrayOutputStream()
        val expired = f.approve(output, 1)
        val current = assertNotNull(f.client.begin(f.approve(ByteArrayOutputStream())))
        f.advance(1)
        assertNull(f.client.begin(expired))
        assertEquals(FollowStatus.AwaitingBaseline, f.client.status(current))
        assertEquals(1, f.cancels)
        assertEquals(0, output.size())
        f.run()
        assertEquals(2, f.cancels)
    }

    @Test fun replacedQueuedHandoffCannotSendItsCredential() {
        val f = Fixture()
        val stale = ByteArrayOutputStream()
        val handle = assertNotNull(f.client.begin(f.approve(stale)))
        val next = ByteArrayOutputStream()
        assertNotNull(f.client.begin(f.approve(next)))
        f.run()
        assertEquals(0, stale.size())
        assertNull(f.client.status(handle))
        f.run()
        assertTrue(next.size() > 0)
        assertEquals(2, f.cancels)
    }

    @Test fun unusedHandoffAndFailedWriteNeverRetry() {
        val f = Fixture()
        val unused = f.approve(ByteArrayOutputStream())
        unused.close()
        unused.close()
        assertNull(f.client.begin(unused))
        var writes = 0
        var frame: ByteArray? = null
        val failed = f.approve(object : OutputStream() {
            override fun write(b: Int) = error("not a byte-wise request")
            override fun write(b: ByteArray) {
                writes++
                frame = b
                throw IOException("request outcome unknown")
            }
        })
        val handle = assertNotNull(f.client.begin(failed))
        f.run()
        assertEquals(FollowStatus.Ended(FollowEnd.DISCONNECTED), f.client.status(handle))
        assertNull(f.client.begin(failed))
        assertEquals(1, writes)
        assertTrue(assertNotNull(frame).all { it == 0.toByte() })
        assertEquals(2, f.cancels)
    }

    @Test fun deadlineCancelsBlockedDispatchWithoutStartingARead() {
        val reads = Executors.newSingleThreadExecutor()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val now = AtomicLong()
        val timers = mutableListOf<() -> Unit>()
        val lifecycle = FollowPipeLifecycle(now::get) { _, action ->
            timers.add(action)
            ({})
        }
        var readCalls = 0
        var writes = 0
        try {
            FollowPipeClient(lifecycle, reads).use { client ->
                val input = object : ByteArrayInputStream(byteArrayOf()) {
                    override fun read(b: ByteArray): Int { readCalls++; return -1 }
                }
                val output = object : OutputStream() {
                    override fun write(b: Int) = error("not a byte-wise request")
                    override fun write(b: ByteArray) {
                        writes++
                        entered.countDown()
                        check(release.await(4, TimeUnit.SECONDS))
                        throw IOException("carrier closed during dispatch")
                    }
                }
                val pipe = ApprovedFollowPipe(target, "remote-workspace", token, 1_500, input, output, release::countDown, now::get)
                val handle = assertNotNull(client.begin(pipe))
                assertTrue(entered.await(2, TimeUnit.SECONDS))
                now.set(TimeUnit.MILLISECONDS.toNanos(1_500))
                timers.single().invoke()
                assertEquals(0L, release.count)
                reads.shutdown()
                assertTrue(reads.awaitTermination(2, TimeUnit.SECONDS))
                assertEquals(1, writes)
                assertEquals(0, readCalls)
                assertEquals(FollowStatus.Ended(FollowEnd.DISCONNECTED), client.status(handle))
            }
        } finally {
            release.countDown()
            reads.shutdownNow()
        }
    }

    @Test fun invalidGrantOrRemoteIdentityDoesNotTakeOwnership() {
        val f = Fixture()
        val output = ByteArrayOutputStream()
        for (bad in listOf("owner-token", "$token\n", "", "caf1_" + "A".repeat(44))) {
            assertFailsWith<IllegalArgumentException> { f.approve(output, grant = bad) }
        }
        for (bad in listOf("", "a/b", "a\nb", "a b", "a".repeat(129))) {
            assertFailsWith<IllegalArgumentException> { f.approve(output, workspace = bad) }
        }
        assertEquals(0, output.size())
        assertEquals(0, f.cancels)
    }

    private inner class Fixture {
        private var now = 0L
        private val tasks = ArrayDeque<Runnable>()
        val delays = mutableListOf<Long>()
        var cancels = 0
        val client = FollowPipeClient(FollowPipeLifecycle({ now }) { delay, _ ->
            delays.add(delay)
            ({})
        }, Executor { tasks.add(it) })

        fun approve(output: OutputStream, millis: Long = 10_000, grant: String = token, workspace: String = "remote-workspace") =
            ApprovedFollowPipe(target, workspace, grant, millis, ByteArrayInputStream(byteArrayOf()), output, { cancels++ }, { now })

        fun advance(millis: Long) { now += TimeUnit.MILLISECONDS.toNanos(millis) }
        fun run() = tasks.removeFirst().run()
    }
}
