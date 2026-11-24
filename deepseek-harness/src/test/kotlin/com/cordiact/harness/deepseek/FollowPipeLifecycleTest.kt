package com.cordiact.harness.deepseek

import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FollowPipeLifecycleTest {
    private val target = FollowTarget("host", "workspace", "session")
    private val baseline = frame(0)
    private val disconnected = FollowStatus.Ended(FollowEnd.DISCONNECTED)

    @Test fun silentBeforeBaselineClosesAndClearsEvenWithAnUnfinishedFrame() {
        val clock = Clock()
        val follow = clock.lifecycle()
        var cancels = 0
        val handle = assertNotNull(follow.begin(FollowPipeOwnership(target) { cancels++ }, 30_000))
        assertEquals(FollowStatus.AwaitingBaseline, follow.status(handle))
        follow.receive(handle, baseline, length = baseline.size - 1)
        clock.advance(2_999)
        assertEquals(0, cancels)
        clock.advance(1)
        assertEquals(1, cancels) // No status/read callback was needed to retire this pipe.
        assertEquals(disconnected, follow.status(handle))
        assertEquals(FollowPipeReceiver.Receive.STALE, follow.receive(handle, baseline))
    }

    @Test fun healthyIdleAndRecentUpdatesNeverExtendTheAbsoluteLocalLease() {
        val clock = Clock()
        val follow = clock.lifecycle()
        var cancels = 0
        val handle = assertNotNull(follow.begin(FollowPipeOwnership(target) { cancels++ }, 10_000))
        follow.receive(handle, baseline)
        clock.advance(3_000)
        assertEquals(0, cancels)
        assertEquals(FollowStatus.Current(FollowSessionState(0, true, false)), follow.status(handle))
        clock.advance(6_999)
        follow.receive(handle, frame(1))
        clock.advance(1)
        assertEquals(1, cancels)
        assertEquals(disconnected, follow.status(handle))
    }

    @Test fun renderAndReadCheckDeadlineEvenWhenSchedulerIsDelayed() {
        for (render in listOf(true, false)) {
            val clock = Clock(Long.MAX_VALUE - 1_000)
            val follow = clock.lifecycle()
            var cancel = 0
            val handle = assertNotNull(follow.begin(FollowPipeOwnership(target) { cancel++ }, 1))
            follow.receive(handle, baseline)
            clock.advance(1, run = false)
            if (render) assertEquals(disconnected, follow.status(handle))
            else assertEquals(FollowPipeReceiver.Receive.STALE, follow.receive(handle, frame(1)))
            assertEquals(1, cancel)
            assertEquals(disconnected, follow.status(handle))
        }
    }

    @Test fun staleTimerAndCancellationCannotTouchSameTargetReplacement() {
        val clock = Clock()
        val follow = clock.lifecycle()
        var firstCancels = 0
        var secondCancels = 0
        val old = assertNotNull(follow.begin(FollowPipeOwnership(target) { firstCancels++ }, 10_000))
        val stale = clock.tasks.toList()
        val next = assertNotNull(follow.begin(FollowPipeOwnership(target) { secondCancels++ }, 30_000))
        follow.receive(next, baseline)
        assertEquals(1, firstCancels)
        clock.advance(10_000)
        stale.forEach { it.call() } // Simulate a callback already dequeued before cancellation.
        assertFalse(follow.eof(old))
        assertFalse(follow.end(old, FollowEnd.ACCESS_LOST))
        assertFalse(follow.clear(old))
        assertEquals(0, secondCancels)
        assertEquals(FollowStatus.Current(FollowSessionState(0, true, false)), follow.status(next))
        assertTrue(follow.clear(next))
        assertNull(follow.status(next))
        assertEquals(1, secondCancels)
    }

    @Test fun cancellationRunsOnceAndAfterTerminalStateHasBeenPublished() {
        val clock = Clock()
        val follow = clock.lifecycle()
        lateinit var handle: FollowPipeLifecycle.Handle
        var cancels = 0
        val pipe = FollowPipeOwnership(target) {
            cancels++
            assertEquals(FollowStatus.Ended(FollowEnd.ACCESS_LOST), follow.status(handle))
            assertFalse(follow.eof(handle))
        }
        handle = assertNotNull(follow.begin(pipe, 10_000))
        assertNull(clock.lifecycle().begin(pipe, 10_000))
        follow.receive(handle, baseline)
        assertTrue(follow.end(handle, FollowEnd.ACCESS_LOST))
        assertFalse(follow.end(handle, FollowEnd.ACCESS_LOST))
        follow.close()
        assertEquals(1, cancels)
        assertNull(follow.status(handle))
    }

    @Test fun schedulerRejectionAndClosedOwnerCleanUpTransferredPipes() {
        val clock = Clock()
        var timers = 0
        val follow = FollowPipeLifecycle({ clock.now }) { _, _ ->
            if (++timers == 2) throw RejectedExecutionException()
            ({ timers-- })
        }
        var cancels = 0
        val handle = assertNotNull(follow.begin(FollowPipeOwnership(target) { cancels++ }, 10_000))
        assertEquals(disconnected, follow.status(handle))
        assertEquals(1, timers)
        assertEquals(1, cancels)
        follow.close()
        val afterClose = FollowPipeOwnership(target) { cancels++ }
        assertNull(follow.begin(afterClose, 10_000))
        assertNull(clock.lifecycle().begin(afterClose, 10_000))
        assertEquals(2, cancels)
    }

    @Test fun malformedInputOrEofRetiresPipeAndLeavesNoTimers() {
        val clock = Clock()
        val follow = clock.lifecycle()
        var cancels = 0
        val invalid = assertNotNull(follow.begin(FollowPipeOwnership(target) { cancels++ }, 10_000))
        assertEquals(FollowPipeReceiver.Receive.RETIRED, follow.receive(invalid, byteArrayOf(10)))
        assertEquals(FollowStatus.Ended(FollowEnd.INVALID_SOURCE), follow.status(invalid))
        val clean = assertNotNull(follow.begin(FollowPipeOwnership(target) { cancels++ }, 10_000))
        assertTrue(follow.eof(clean))
        assertEquals(disconnected, follow.status(clean))
        assertEquals(2, cancels)
        assertTrue(clock.tasks.all { it.cancelled })
    }

    private class Clock(var now: Long = 0) {
        class Task(val due: Long, val call: () -> Unit, var cancelled: Boolean = false)
        val tasks = mutableListOf<Task>()

        fun lifecycle() = FollowPipeLifecycle({ now }) { delay, call ->
            val task = Task(now + delay, call)
            tasks += task
            ({ task.cancelled = true })
        }

        fun advance(millis: Long, run: Boolean = true) {
            now += TimeUnit.MILLISECONDS.toNanos(millis)
            if (run) tasks.filter { !it.cancelled && now - it.due >= 0 }.forEach { it.call() }
        }
    }

    private fun frame(delivery: Int) =
        """{"delivery":$delivery,"cursor":$delivery,"active":true,"running":false}""".plus('\n').encodeToByteArray()
}
