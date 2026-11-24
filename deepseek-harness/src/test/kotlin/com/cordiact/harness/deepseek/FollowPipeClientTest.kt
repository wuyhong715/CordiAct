package com.cordiact.harness.deepseek

import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.util.ArrayDeque
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FollowPipeClientTest {
    private val target = FollowTarget("host", "workspace", "session")
    private val frame = """{"delivery":0,"cursor":0,"active":true,"running":false}""".plus('\n').encodeToByteArray()
    private val disconnected = FollowStatus.Ended(FollowEnd.DISCONNECTED)
    private val invalid = FollowStatus.Ended(FollowEnd.INVALID_SOURCE)

    @Test fun queuedReplacedPipeIsNeverReadAndCannotBeTransferredAgain() = withScheduler { scheduler ->
        val tasks = ArrayDeque<Runnable>()
        FollowPipeClient(scheduler, Executor { tasks.add(it) }).use { client ->
            var oldReads = 0
            var oldCancels = 0
            val old = RequestedFollowPipe(target, input { oldReads++; -1 }) { oldCancels++ }
            val oldHandle = assertNotNull(client.begin(old, 30_000))
            var newCancels = 0
            val replacement = RequestedFollowPipe(target, ByteArrayInputStream(frame)) { newCancels++ }
            val handle = assertNotNull(client.begin(replacement, 30_000))
            assertNull(client.begin(old, 30_000))
            assertNull(client.status(oldHandle))
            tasks.remove().run()
            assertEquals(0, oldReads)
            assertEquals(1, oldCancels)
            assertEquals(FollowStatus.AwaitingBaseline, client.status(handle))
            tasks.remove().run()
            assertEquals(disconnected, client.status(handle))
            assertEquals(1, newCancels)
        }
    }

    @Test fun cleanEofTruncationAndReadFailureRetireTheirOwnPipes() = withScheduler { scheduler ->
        val tasks = ArrayDeque<Runnable>()
        FollowPipeClient(scheduler, Executor { tasks.add(it) }).use { client ->
            var cancels = 0
            for ((source, ended) in listOf(
                ByteArrayInputStream(frame) to disconnected,
                ByteArrayInputStream(frame.copyOf(frame.size - 1)) to invalid,
                input { throw IOException("private carrier detail") } to disconnected,
                input { 0 } to invalid,
            )) {
                val handle = assertNotNull(client.begin(RequestedFollowPipe(target, source) { cancels++ }, 30_000))
                tasks.remove().run()
                assertEquals(ended, client.status(handle))
                assertFalse(client.revoke(handle))
            }
            assertEquals(4, cancels)
        }
    }

    @Test fun rejectionOrClosureDoesNotLeakTransferredPipe() = withScheduler { scheduler ->
        val client = FollowPipeClient(scheduler, Executor { throw RejectedExecutionException() })
        var cancels = 0
        val handle = assertNotNull(client.begin(RequestedFollowPipe(target, input { error("must not read") }) { cancels++ }, 30_000))
        assertEquals(disconnected, client.status(handle))
        assertEquals(1, cancels)
        client.close()
        assertNull(client.begin(RequestedFollowPipe(target, input { error("must not read") }) { cancels++ }, 30_000))
        assertEquals(2, cancels)
    }

    @Test fun cancellationOfBlockedOldReadCannotCloseTheReplacement() = withScheduler { scheduler ->
        val reads = Executors.newFixedThreadPool(2)
        val oldEntered = CountDownLatch(1)
        val oldRelease = CountDownLatch(1)
        val oldReturned = CountDownLatch(1)
        val newEntered = CountDownLatch(1)
        val newRelease = CountDownLatch(1)
        try {
            FollowPipeClient(scheduler, reads).use { client ->
                val first = input { destination ->
                    oldEntered.countDown()
                    assertTrue(oldRelease.await(3, TimeUnit.SECONDS))
                    frame.copyInto(destination)
                    oldReturned.countDown()
                    frame.size
                }
                val old = assertNotNull(client.begin(RequestedFollowPipe(target, first) { oldRelease.countDown() }, 30_000))
                assertTrue(oldEntered.await(3, TimeUnit.SECONDS))
                val next = input {
                    newEntered.countDown()
                    assertTrue(newRelease.await(3, TimeUnit.SECONDS))
                    throw IOException()
                }
                val handle = assertNotNull(client.begin(RequestedFollowPipe(target, next) { newRelease.countDown() }, 30_000))
                assertTrue(newEntered.await(3, TimeUnit.SECONDS))
                assertTrue(oldReturned.await(3, TimeUnit.SECONDS))
                assertNull(client.status(old))
                assertEquals(FollowStatus.AwaitingBaseline, client.status(handle))
                assertEquals(1L, newRelease.count)
                assertTrue(client.revoke(handle))
                assertEquals(FollowStatus.Ended(FollowEnd.ACCESS_LOST), client.status(handle))
                assertTrue(newRelease.await(3, TimeUnit.SECONDS))
            }
        } finally {
            oldRelease.countDown()
            newRelease.countDown()
            reads.shutdownNow()
            assertTrue(reads.awaitTermination(3, TimeUnit.SECONDS))
        }
    }

    private fun input(read: (ByteArray) -> Int) = object : InputStream() {
        override fun read() = error("single-byte reads are not used")
        override fun read(b: ByteArray): Int = read.invoke(b)
    }

    private fun withScheduler(run: (java.util.concurrent.ScheduledExecutorService) -> Unit) {
        val scheduler = Executors.newSingleThreadScheduledExecutor()
        try {
            run(scheduler)
        } finally {
            scheduler.shutdownNow()
        }
    }
}
