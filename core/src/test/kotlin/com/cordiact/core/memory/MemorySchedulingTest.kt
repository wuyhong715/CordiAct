package com.cordiact.core.memory

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val GB = 1024L * 1024 * 1024

class MemorySchedulingPolicyTest {

    private val policy = MemorySchedulingPolicy()

    private fun conditions(
        freeBytes: Long = 4 * GB,
        metered: Boolean = false,
        dozing: Boolean = false,
        thermal: Int = 0,
    ) = DeviceConditions(
        freeBytes = freeBytes,
        meteredNetwork = metered,
        dozing = dozing,
        thermalStatus = thermal,
    )

    private fun deferReason(decision: SchedulingDecision): SchedulingDecision.Defer.Reason {
        check(decision is SchedulingDecision.Defer) { "expected a deferral, got $decision" }
        return decision.reason
    }

    @Test
    fun runsUnderOrdinaryConditions() {
        assertEquals(SchedulingDecision.Run, policy.evaluate(conditions(), modelSelected = true))
    }

    @Test
    fun chargingIsNotACondition() {
        // Fast charging is itself a heat source, so gating on it would push work into the
        // hottest part of the day. There is no charging field to set; this test documents why.
        val decision = policy.evaluate(conditions(freeBytes = 4 * GB, thermal = 0), modelSelected = true)
        assertEquals(SchedulingDecision.Run, decision)
    }

    @Test
    fun defersWhenNoModelSelected() {
        val decision = policy.evaluate(conditions(), modelSelected = false)
        assertEquals(SchedulingDecision.Defer.Reason.NO_MODEL_SELECTED, deferReason(decision))
    }

    @Test
    fun modelSelectionOutranksOtherConditions() {
        // It is a configuration gap the user can close, and it should be reported first so the
        // message is actionable rather than "storage is low" while nothing would run anyway.
        val decision = policy.evaluate(
            conditions(freeBytes = 0, thermal = 5, dozing = true),
            modelSelected = false,
        )
        assertEquals(SchedulingDecision.Defer.Reason.NO_MODEL_SELECTED, deferReason(decision))
    }

    @Test
    fun defersOnLowStorage() {
        val decision = policy.evaluate(conditions(freeBytes = 1024), modelSelected = true)
        assertEquals(SchedulingDecision.Defer.Reason.LOW_STORAGE, deferReason(decision))
    }

    @Test
    fun defersUnderThermalPressure() {
        // The direct measurement that charging was standing in for.
        val decision = policy.evaluate(conditions(thermal = 2), modelSelected = true)
        assertEquals(SchedulingDecision.Defer.Reason.THERMAL_PRESSURE, deferReason(decision))
    }

    @Test
    fun toleratesMildThermalStatus() {
        assertEquals(
            SchedulingDecision.Run,
            policy.evaluate(conditions(thermal = 1), modelSelected = true),
        )
    }

    @Test
    fun ignoresMeteredNetworkByDefault() {
        // The default policy is aggressive: metered data alone must not stop a run.
        assertEquals(
            SchedulingDecision.Run,
            policy.evaluate(conditions(metered = true), modelSelected = true),
        )
    }

    @Test
    fun honoursUnmeteredRequirementWhenEnabled() {
        val strict = MemorySchedulingPolicy(requireUnmeteredNetwork = true)
        val decision = strict.evaluate(conditions(metered = true), modelSelected = true, needsNetwork = true)
        assertEquals(SchedulingDecision.Defer.Reason.METERED, deferReason(decision))
    }

    @Test
    fun meteredRequirementIsSkippedWhenRunNeedsNoNetwork() {
        val strict = MemorySchedulingPolicy(requireUnmeteredNetwork = true)
        assertEquals(
            SchedulingDecision.Run,
            strict.evaluate(conditions(metered = true), modelSelected = true, needsNetwork = false),
        )
    }

    @Test
    fun defersWhileDozing() {
        val decision = policy.evaluate(conditions(dozing = true), modelSelected = true)
        assertEquals(SchedulingDecision.Defer.Reason.DOZING, deferReason(decision))
    }

    @Test
    fun everyDeferralExplainsItself() {
        val deferrals = listOf(
            policy.evaluate(conditions(), modelSelected = false),
            policy.evaluate(conditions(freeBytes = 0), modelSelected = true),
            policy.evaluate(conditions(thermal = 3), modelSelected = true),
            policy.evaluate(conditions(dozing = true), modelSelected = true),
        )

        for (decision in deferrals) {
            check(decision is SchedulingDecision.Defer)
            assertTrue(decision.detail.isNotBlank(), "a deferral without a reason is unactionable")
        }
    }

    @Test
    fun rejectsNegativeThresholds() {
        assertFailsWith<IllegalArgumentException> { MemorySchedulingPolicy(minFreeBytes = -1) }
        assertFailsWith<IllegalArgumentException> { MemorySchedulingPolicy(maxThermalStatus = -1) }
    }
}

class ExtractionLeaseTest {

    private class FakeLeaseStore : ExtractionLeaseStore {
        val leases = mutableMapOf<String, ExtractionLease>()

        private fun key(workspaceId: String, sessionId: String) = "$workspaceId/$sessionId"

        override fun active(workspaceId: String, sessionId: String) = leases[key(workspaceId, sessionId)]

        override fun claim(lease: ExtractionLease): Boolean {
            val k = key(lease.workspaceId, lease.sessionId)
            val existing = leases[k]
            // A live lease held by someone else blocks the claim; an expired one is always
            // replaceable. Rejecting on "row exists" would make an expired lease permanent.
            if (existing != null && existing.owner != lease.owner &&
                !existing.isExpired(lease.claimedAtMillis)
            ) {
                return false
            }
            leases[k] = lease
            return true
        }

        override fun release(workspaceId: String, sessionId: String, owner: String) {
            val k = key(workspaceId, sessionId)
            if (leases[k]?.owner == owner) leases.remove(k)
        }
    }

    private fun coordinator(store: FakeLeaseStore, now: Long) =
        ExtractionLeaseCoordinator(store, leaseDurationMillis = 60_000, clockMillis = { now })

    @Test
    fun grantsWhenFree() {
        val store = FakeLeaseStore()
        val result = coordinator(store, 1000).claim("ws", "s1", "owner-a")

        assertTrue(result is ClaimResult.Granted)
        assertEquals("owner-a", (result as ClaimResult.Granted).lease.owner)
    }

    @Test
    fun refusesWhileAnotherOwnerHoldsALiveLease() {
        val store = FakeLeaseStore()
        coordinator(store, 1000).claim("ws", "s1", "owner-a")

        val result = coordinator(store, 2000).claim("ws", "s1", "owner-b")

        assertTrue(result is ClaimResult.Busy)
        assertEquals("owner-a", (result as ClaimResult.Busy).heldBy)
    }

    @Test
    fun reclaimsExpiredLease() {
        // A crashed owner must not block the session forever.
        val store = FakeLeaseStore()
        coordinator(store, 1000).claim("ws", "s1", "dead-owner")

        val result = coordinator(store, 1000 + 61_000).claim("ws", "s1", "owner-b")

        assertTrue(result is ClaimResult.Granted, "an expired lease should be reclaimable, got $result")
        assertEquals("owner-b", (result as ClaimResult.Granted).lease.owner)
    }

    @Test
    fun doesNotReclaimExactlyAtBoundary() {
        val store = FakeLeaseStore()
        val lease = ExtractionLease("ws", "s1", "owner-a", claimedAtMillis = 1000, expiresAtMillis = 2000)

        assertTrue(!lease.isExpired(1999))
        assertTrue(lease.isExpired(2000))
    }

    @Test
    fun storeReplacesAnExpiredLeaseRatherThanRefusingIt() {
        // The regression this guards: a store that rejects any existing row makes a crashed
        // owner hold the session forever, which is exactly what expiry is supposed to prevent.
        val store = FakeLeaseStore()
        val expired = ExtractionLease("ws", "s1", "dead", claimedAtMillis = 0, expiresAtMillis = 100)
        store.leases["ws/s1"] = expired

        val taken = store.claim(ExtractionLease("ws", "s1", "fresh", claimedAtMillis = 200, expiresAtMillis = 300))

        assertTrue(taken, "an expired lease must be replaceable")
        assertEquals("fresh", store.active("ws", "s1")?.owner)
    }

    @Test
    fun releaseOnlyAffectsTheHoldingOwner() {
        val store = FakeLeaseStore()
        coordinator(store, 1000).claim("ws", "s1", "owner-a")

        // A non-holder must not be able to free someone else's lease.
        coordinator(store, 1000).release("ws", "s1", "owner-b")
        assertTrue(store.active("ws", "s1") != null)

        coordinator(store, 1000).release("ws", "s1", "owner-a")
        assertNull(store.active("ws", "s1"))
    }

    @Test
    fun leasesAreScopedByWorkspace() {
        val store = FakeLeaseStore()
        coordinator(store, 1000).claim("ws-1", "s1", "owner-a")

        val other = coordinator(store, 1000).claim("ws-2", "s1", "owner-b")

        assertTrue(other is ClaimResult.Granted, "same session id in another Workspace is independent")
    }

    @Test
    fun rejectsInvalidLease() {
        assertFailsWith<IllegalArgumentException> {
            ExtractionLease("ws", "s1", "o", claimedAtMillis = 1000, expiresAtMillis = 1000)
        }
    }
}

class RetryBackoffTest {

    @Test
    fun growsExponentially() {
        val backoff = RetryBackoff(baseDelayMillis = 1000, maxDelayMillis = 100_000)

        assertEquals(1000, backoff.delayFor(0))
        assertEquals(2000, backoff.delayFor(1))
        assertEquals(4000, backoff.delayFor(2))
        assertEquals(8000, backoff.delayFor(3))
    }

    @Test
    fun capsAtMaximum() {
        val backoff = RetryBackoff(baseDelayMillis = 1000, maxDelayMillis = 5000)

        assertEquals(5000, backoff.delayFor(5))
    }

    @Test
    fun givesUpAfterMaxAttempts() {
        val backoff = RetryBackoff(maxAttempts = 3)

        assertNull(backoff.delayFor(3))
        assertTrue(backoff.giveUp(3))
        assertTrue(!backoff.giveUp(2))
    }

    @Test
    fun doesNotOverflowOnLargeAttempts() {
        // A naive 1L shl attempts turns negative around attempt 63 and produces a "delay" in
        // the past, which is a hot retry loop.
        val backoff = RetryBackoff(baseDelayMillis = 1000, maxDelayMillis = 10_000, maxAttempts = 100)

        for (attempts in listOf(40, 62, 63, 64, 90)) {
            val delay = backoff.delayFor(attempts)
            assertTrue(delay == null || delay > 0, "delay for $attempts must be positive or null, was $delay")
        }
    }

    @Test
    fun rejectsBadConfiguration() {
        assertFailsWith<IllegalArgumentException> { RetryBackoff(baseDelayMillis = 0) }
        assertFailsWith<IllegalArgumentException> { RetryBackoff(maxDelayMillis = 10) }
        assertFailsWith<IllegalArgumentException> { RetryBackoff(maxAttempts = 0) }
    }

    @Test
    fun successResetsTheCounter() {
        val plan = RetryPlanner.plan(attemptsSoFar = 5, RetryBackoff(), nowMillis = 1000, succeeded = true)

        assertEquals(0, plan.attempts)
        assertNull(plan.nextAttemptAtMillis)
    }

    @Test
    fun failureSchedulesTheNextAttempt() {
        val plan = RetryPlanner.plan(attemptsSoFar = 0, RetryBackoff(baseDelayMillis = 1000), nowMillis = 5000, succeeded = false)

        assertEquals(1, plan.attempts)
        assertEquals(6000, plan.nextAttemptAtMillis)
    }

    @Test
    fun exhaustedRetriesScheduleNothing() {
        val backoff = RetryBackoff(maxAttempts = 2)
        val plan = RetryPlanner.plan(attemptsSoFar = 2, backoff, nowMillis = 1000, succeeded = false)

        assertEquals(3, plan.attempts)
        assertNull(plan.nextAttemptAtMillis, "an exhausted session must stop retrying")
    }
}
