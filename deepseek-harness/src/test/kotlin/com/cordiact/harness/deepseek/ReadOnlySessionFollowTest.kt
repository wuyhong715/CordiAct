package com.cordiact.harness.deepseek

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ReadOnlySessionFollowTest {
    private val target = FollowTarget("connection-a", "workspace-a", "session-a")
    private val inactive = FollowSessionState(8, active = false, running = false)
    private val running = FollowSessionState(8, active = true, running = true)

    @Test fun completeStatesCanReplaceAtSameCursorOrSkipLogEntries() {
        val follow = ReadOnlySessionFollow()
        val g = follow.begin(target)
        assertEquals(FollowStatus.AwaitingBaseline, follow.status(g))
        assertEquals(FollowUpdate.APPLIED, follow.accept(g, target, 0, inactive))
        assertEquals(FollowUpdate.APPLIED, follow.accept(g, target, 1, running))
        assertEquals(FollowStatus.Current(running), follow.status(g))
        val next = inactive.copy(cursor = 20)
        assertEquals(FollowUpdate.APPLIED, follow.accept(g, target, 2, next))
        assertEquals(FollowStatus.Current(next), follow.status(g))
    }

    @Test fun disconnectClearsStateAndSameTargetReconnectNeedsNewBaseline() {
        val follow = ReadOnlySessionFollow()
        val first = follow.begin(target)
        assertEquals(FollowUpdate.APPLIED, follow.accept(first, target, 0, running))
        assertTrue(follow.end(first, FollowEnd.DISCONNECTED))
        assertFalse(follow.end(first, FollowEnd.INVALID_SOURCE))
        assertEquals(FollowStatus.Ended(FollowEnd.DISCONNECTED), follow.status(first))
        assertEquals(FollowUpdate.IGNORED, follow.accept(first, target, 1, inactive))
        val next = follow.begin(target)
        assertNull(follow.status(first))
        assertEquals(FollowStatus.AwaitingBaseline, follow.status(next))
        assertEquals(FollowUpdate.IGNORED, follow.accept(first, target, 4, running))
        assertFalse(follow.clear(first))
        assertEquals(FollowUpdate.APPLIED, follow.accept(next, target, 0, inactive.copy(cursor = 1)))
        assertEquals(FollowStatus.Current(inactive.copy(cursor = 1)), follow.status(next))
    }

    @Test fun staleGenerationAndAnotherReducerCannotObserveOrPublishEvenWithEqualTargets() {
        val follow = ReadOnlySessionFollow()
        val a1 = follow.begin(target)
        val b = target.copy(localWorkspaceId = "workspace-b")
        val bGeneration = follow.begin(b)
        val a2 = follow.begin(target)
        val alien = ReadOnlySessionFollow().begin(target)
        for (old in listOf(a1, bGeneration, alien)) {
            assertNull(follow.status(old))
            assertFalse(follow.end(old, FollowEnd.ACCESS_LOST))
            assertFalse(follow.clear(old))
            assertEquals(FollowUpdate.IGNORED, follow.accept(old, old.target, 0, inactive))
        }
        assertEquals(FollowStatus.AwaitingBaseline, follow.status(a2))
    }

    @Test fun dataMisroutedFromAnyOtherConnectionWorkspaceOrSessionRetiresCurrentSource() {
        val follow = ReadOnlySessionFollow()
        for (wrong in listOf(target.copy(connectionId = "connection-b"), target.copy(localWorkspaceId = "workspace-b"),
            target.copy(remoteSessionId = "session-b"))) {
            val g = follow.begin(target)
            assertEquals(FollowUpdate.APPLIED, follow.accept(g, target, 0, inactive))
            assertEquals(FollowUpdate.RETIRED, follow.accept(g, wrong, 1, running))
            assertEquals(FollowStatus.Ended(FollowEnd.INVALID_SOURCE), follow.status(g))
            assertEquals(FollowUpdate.IGNORED, follow.accept(g, target, 1, running))
        }
    }

    @Test fun duplicateAndOldDeliveriesCannotReplaceCurrentState() {
        val follow = ReadOnlySessionFollow()
        val g = follow.begin(target)
        follow.accept(g, target, 0, inactive)
        follow.accept(g, target, 1, running)
        assertEquals(FollowUpdate.IGNORED, follow.accept(g, target, 0, inactive))
        assertEquals(FollowUpdate.IGNORED, follow.accept(g, target, 1, inactive))
        assertEquals(FollowStatus.Current(running), follow.status(g))
    }

    @Test fun malformedOrBackwardStateAndNonconsecutiveDeliveryFailClosed() {
        val follow = ReadOnlySessionFollow()
        for ((delivery, state) in listOf(-1L to inactive, 2L to inactive, 1L to inactive.copy(cursor = 7),
            1L to inactive.copy(cursor = 9_007_199_254_740_992L), 1L to inactive.copy(cursor = -2),
            1L to inactive.copy(running = true))) {
            val g = follow.begin(target)
            follow.accept(g, target, 0, inactive)
            assertEquals(FollowUpdate.RETIRED, follow.accept(g, target, delivery, state))
            assertEquals(FollowStatus.Ended(FollowEnd.INVALID_SOURCE), follow.status(g))
        }
        val missing = follow.begin(target)
        assertEquals(FollowUpdate.RETIRED, follow.accept(missing, target, 1, inactive))
        val empty = follow.begin(target)
        assertEquals(FollowUpdate.APPLIED, follow.accept(empty, target, 0, inactive.copy(cursor = -1)))
    }

    @Test fun accessWithdrawalAndClearDiscardProjectionWithoutRestoringOnLateDelivery() {
        val follow = ReadOnlySessionFollow()
        val g = follow.begin(target)
        follow.accept(g, target, 0, running)
        assertTrue(follow.end(g, FollowEnd.ACCESS_LOST))
        assertEquals(FollowStatus.Ended(FollowEnd.ACCESS_LOST), follow.status(g))
        assertEquals(FollowUpdate.IGNORED, follow.accept(g, target, 1, inactive))
        assertTrue(follow.clear(g))
        assertFalse(follow.clear(g))
        assertNull(follow.status(g))
        assertEquals(FollowUpdate.IGNORED, follow.accept(g, target, 2, running))
    }

    @Test fun routingIdsAreBoundedAndAreNotUrls() {
        for (invalid in listOf("", "a/b", "a?b", "a\nb", "a".repeat(129))) {
            assertFailsWith<IllegalArgumentException> { target.copy(connectionId = invalid) }
            assertFailsWith<IllegalArgumentException> { target.copy(localWorkspaceId = invalid) }
            assertFailsWith<IllegalArgumentException> { target.copy(remoteSessionId = invalid) }
        }
    }
}
