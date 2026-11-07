package com.cordiact.core.inbox

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class AsyncTurnInboxTest {

    @Test
    fun `messages and settlement share one sequence so a settlement cannot overtake a message`() {
        val inbox = DefaultAgentInbox()

        val message = inbox.enqueue(
            InboxItemKind.CHILD_MESSAGE, "s1", "child-1", "child found something", "the config is wrong",
        )
        val settled = inbox.enqueue(
            InboxItemKind.CHILD_SETTLED, "s1", "child-1", "child finished", "done",
        )

        assertTrue(message.seq < settled.seq, "the earlier item must hold the lower sequence")

        val drained = inbox.drain("s1")
        assertEquals(listOf(message.seq, settled.seq), drained.map { it.seq })
        assertEquals(
            listOf(InboxItemKind.CHILD_MESSAGE, InboxItemKind.CHILD_SETTLED),
            drained.map { it.kind },
        )
        assertEquals(0, inbox.size("s1"))
    }

    @Test
    fun `drain respects the per-round cap and leaves the rest queued`() {
        val inbox = DefaultAgentInbox()
        repeat(5) { index ->
            inbox.enqueue(InboxItemKind.JOB_SETTLED, "s1", "job-$index", "job $index finished")
        }

        val first = inbox.drain("s1", limit = 3)
        assertEquals(3, first.size)
        assertEquals(2, inbox.size("s1"))

        val second = inbox.drain("s1", limit = 3)
        assertEquals(2, second.size)
        // Ordering survives the split.
        assertTrue(first.last().seq < second.first().seq)
    }

    @Test
    fun `requeue puts items back in sequence order without duplicating them`() {
        val inbox = DefaultAgentInbox()
        val a = inbox.enqueue(InboxItemKind.CHILD_SETTLED, "s1", "child-a", "a finished")
        val b = inbox.enqueue(InboxItemKind.CHILD_SETTLED, "s1", "child-b", "b finished")

        val drained = inbox.drain("s1")
        assertEquals(2, drained.size)

        inbox.requeue(drained)
        inbox.requeue(drained) // idempotent: a retried requeue must not double the queue

        val after = inbox.peek("s1")
        assertEquals(listOf(a.seq, b.seq), after.map { it.seq })
    }

    @Test
    fun `inboxes are per session`() {
        val inbox = DefaultAgentInbox()
        inbox.enqueue(InboxItemKind.CHILD_SETTLED, "s1", "child-1", "for s1")
        inbox.enqueue(InboxItemKind.CHILD_SETTLED, "s2", "child-2", "for s2")

        assertEquals(1, inbox.size("s1"))
        assertEquals(1, inbox.size("s2"))
        assertEquals("for s1", inbox.drain("s1").single().heading)
        assertEquals(1, inbox.size("s2"))
    }

    @Test
    fun `wake ledger enforces the hourly budget and forgets old wakes`() {
        val policy = WakePolicy(maxAutoWakesPerHour = 2, windowMillis = 60_000L)
        val ledger = SlidingWindowWakeLedger(policy)

        assertTrue(ledger.tryAcquire("s1", nowMillis = 0L))
        assertTrue(ledger.tryAcquire("s1", nowMillis = 1_000L))
        assertFalse(ledger.tryAcquire("s1", nowMillis = 2_000L), "third wake inside the window is refused")
        assertEquals(2, ledger.recentWakes("s1", nowMillis = 2_000L))

        // Another session has its own budget.
        assertTrue(ledger.tryAcquire("s2", nowMillis = 2_000L))

        // Once the window has passed, the budget is available again.
        assertTrue(ledger.tryAcquire("s1", nowMillis = 61_001L))
    }

    @Test
    fun `coordinator wakes an idle session once and queues while it is busy`() {
        val policy = WakePolicy(maxAutoWakesPerHour = 4)
        val ledger = SlidingWindowWakeLedger(policy)
        var running = false
        val started = mutableListOf<String>()
        val coordinator = AsyncTurnCoordinator(
            policy = policy,
            ledger = ledger,
            activity = SessionActivity { running },
            startTurn = { started += it },
            clockMillis = { 1_000L },
        )

        val settled = InboxItem(1, InboxItemKind.CHILD_SETTLED, "s1", "child-1", "finished")
        assertEquals(WakeDecision.WOKE, coordinator.onEnqueued(settled))
        assertEquals(listOf("s1"), started)

        running = true
        val whileBusy = InboxItem(2, InboxItemKind.CHILD_SETTLED, "s1", "child-2", "finished")
        assertEquals(WakeDecision.QUEUED_SESSION_BUSY, coordinator.onEnqueued(whileBusy))
        assertEquals(1, started.size)
    }

    @Test
    fun `coordinator refuses to wake on intermediate messages or when disabled`() {
        val policy = WakePolicy()
        val ledger = SlidingWindowWakeLedger(policy)
        val started = mutableListOf<String>()
        val coordinator = AsyncTurnCoordinator(
            policy = policy,
            ledger = ledger,
            activity = SessionActivity { false },
            startTurn = { started += it },
            clockMillis = { 1_000L },
        )

        val message = InboxItem(1, InboxItemKind.CHILD_MESSAGE, "s1", "child-1", "a thought")
        assertEquals(WakeDecision.QUEUED_KIND, coordinator.onEnqueued(message))
        assertTrue(started.isEmpty())

        val disabled = AsyncTurnCoordinator(
            policy = WakePolicy(enabled = false),
            ledger = ledger,
            activity = SessionActivity { false },
            startTurn = { started += it },
            clockMillis = { 1_000L },
        )
        val settled = InboxItem(2, InboxItemKind.CHILD_SETTLED, "s1", "child-1", "finished")
        assertEquals(WakeDecision.QUEUED_DISABLED, disabled.onEnqueued(settled))
        assertTrue(started.isEmpty())
    }

    @Test
    fun `coordinator stops waking once the quota is spent`() {
        val policy = WakePolicy(maxAutoWakesPerHour = 1)
        val ledger = SlidingWindowWakeLedger(policy)
        var started = 0
        val coordinator = AsyncTurnCoordinator(
            policy = policy,
            ledger = ledger,
            activity = SessionActivity { false },
            startTurn = { started++ },
            clockMillis = { 1_000L },
        )

        assertEquals(WakeDecision.WOKE, coordinator.onEnqueued(InboxItem(1, InboxItemKind.CHILD_SETTLED, "s1", "c1", "ok")))
        assertEquals(
            WakeDecision.QUEUED_QUOTA,
            coordinator.onEnqueued(InboxItem(2, InboxItemKind.CHILD_SETTLED, "s1", "c2", "ok")),
        )
        assertEquals(1, started)
    }

    @Test
    fun `renderer emits structured blocks that keep kind and source distinguishable`() {
        val items = listOf(
            InboxItem(2, InboxItemKind.CHILD_SETTLED, "s1", "child-1", "\"调研\" 已完成", "3 个模块"),
            InboxItem(1, InboxItemKind.CHILD_MESSAGE, "s1", "child-1", "补充线索", "maxResultChars 未被读取"),
        )

        val rendered = ChildReportRenderer.render(items)
        assertTrue(rendered != null)
        // Sorted by seq: the message (seq 1) precedes the settlement (seq 2).
        val messageIndex = rendered!!.indexOf("kind=\"message\"")
        val settledIndex = rendered.indexOf("kind=\"settled\"")
        assertTrue(messageIndex in 0..<settledIndex)
        assertTrue(rendered.contains("This is a runtime report, not a user message."))
        assertTrue(rendered.contains("maxResultChars 未被读取"))

        assertNull(ChildReportRenderer.render(emptyList()))
    }
}
