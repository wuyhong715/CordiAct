package com.cordiact.core.memory

/**
 * Device conditions the scheduler is allowed to consult.
 *
 * Deliberately narrow: anything the scheduler cannot act on does not belong here, and a signal
 * that looks plausible but has no mechanism behind it is worse than a missing one because it
 * creates the illusion of control.
 */
data class DeviceConditions(
    /** Free bytes on the volume holding the Workspace. */
    val freeBytes: Long,
    /** True when the device is on a metered connection. Only relevant for network calls. */
    val meteredNetwork: Boolean = false,
    /** True when the device is in Doze or otherwise unlikely to run tasks now. */
    val dozing: Boolean = false,
    /** Battery percentage 0..100, or null when unknown. */
    val batteryPercent: Int? = null,
    /**
     * Thermal pressure 0..N, where 0 is nominal. Android reports this through PowerManager;
     * higher values mean the device is already throttling.
     */
    val thermalStatus: Int = 0,
)

/** Why a run was allowed or deferred. Recorded so a stalled pipeline is explainable. */
sealed interface SchedulingDecision {
    data object Run : SchedulingDecision

    data class Defer(val reason: Reason, val detail: String) : SchedulingDecision {
        enum class Reason {
            /** Not enough room to write artifacts and a commit safely. */
            LOW_STORAGE,

            /** The device is under thermal pressure; running model calls now makes it worse. */
            THERMAL_PRESSURE,

            /** Awaiting an unmetered network for a run that needs one. */
            METERED,

            /** The platform is in a maintenance window where new background work should wait. */
            DOZING,

            /** The extractor model has not been chosen; extraction must not silently pick one. */
            NO_MODEL_SELECTED,
        }
    }
}

/**
 * Decides whether a memory round may run under the current conditions.
 *
 * Charging is intentionally *not* a condition. Fast charging is itself a major heat source, so
 * gating background work on "charging" would preferentially schedule model calls into the
 * hottest part of the day — the opposite of what the constraint is supposed to achieve. Thermal
 * status is the direct measurement of the thing charging was standing in for, so the policy uses
 * it instead.
 *
 * The policy is deliberately aggressive: default conditions allow a run. It only defers on a
 * condition with a concrete mechanism behind it, and every deferral names itself.
 */
data class MemorySchedulingPolicy(
    /** Below this much free space a round is deferred rather than risking a half-written commit. */
    val minFreeBytes: Long = 64L * 1024 * 1024,
    /** Thermal status at or above which memory work waits. */
    val maxThermalStatus: Int = THERMAL_STATUS_MODERATE,
    /** Whether a round may run on a metered connection. Extraction calls a model, so this matters. */
    val requireUnmeteredNetwork: Boolean = false,
) {
    init {
        require(minFreeBytes >= 0) { "minFreeBytes must not be negative" }
        require(maxThermalStatus >= 0) { "maxThermalStatus must not be negative" }
    }

    /** Evaluates [conditions]. [modelSelected] is false when the user has not chosen an extractor. */
    fun evaluate(
        conditions: DeviceConditions,
        modelSelected: Boolean,
        needsNetwork: Boolean = true,
    ): SchedulingDecision {
        // Ordered by how cheap they are to fix and how badly they fail. Model selection comes
        // first because it is a configuration gap the user can close, not a transient condition.
        if (!modelSelected) {
            return SchedulingDecision.Defer(
                SchedulingDecision.Defer.Reason.NO_MODEL_SELECTED,
                "No extraction model selected; memory extraction stays off until one is chosen",
            )
        }
        if (conditions.freeBytes < minFreeBytes) {
            return SchedulingDecision.Defer(
                SchedulingDecision.Defer.Reason.LOW_STORAGE,
                "Only ${conditions.freeBytes} bytes free, need $minFreeBytes",
            )
        }
        if (conditions.thermalStatus >= maxThermalStatus) {
            return SchedulingDecision.Defer(
                SchedulingDecision.Defer.Reason.THERMAL_PRESSURE,
                "Thermal status ${conditions.thermalStatus} at or above $maxThermalStatus",
            )
        }
        if (needsNetwork && conditions.meteredNetwork && requireUnmeteredNetwork) {
            return SchedulingDecision.Defer(
                SchedulingDecision.Defer.Reason.METERED,
                "Waiting for an unmetered connection",
            )
        }
        // Doze is advisory: a run that starts is allowed to finish, but a new one waits for the
        // window rather than competing with the platform's own maintenance.
        if (conditions.dozing) {
            return SchedulingDecision.Defer(
                SchedulingDecision.Defer.Reason.DOZING,
                "Device is dozing; deferring to the next maintenance window",
            )
        }
        return SchedulingDecision.Run
    }

    companion object {
        /** Matches PowerManager.THERMAL_STATUS_MODERATE: the device is throttling. */
        const val THERMAL_STATUS_MODERATE = 2
    }
}

/**
 * A claim on one session's extraction slot.
 *
 * Simplified from codex's lease plus 90-second heartbeat: a single-process WorkManager task has
 * nobody to heartbeat to, so a lease only needs an expiry to cover a crash. If the process dies
 * mid-extraction the lease ages out and the next run reclaims it.
 */
data class ExtractionLease(
    val workspaceId: String,
    val sessionId: String,
    val owner: String,
    val claimedAtMillis: Long,
    val expiresAtMillis: Long,
) {
    init {
        require(workspaceId.isNotBlank()) { "Lease workspace must not be blank" }
        require(sessionId.isNotBlank()) { "Lease session must not be blank" }
        require(owner.isNotBlank()) { "Lease owner must not be blank" }
        require(expiresAtMillis > claimedAtMillis) { "Lease must expire after it was claimed" }
    }

    fun isExpired(nowMillis: Long): Boolean = nowMillis >= expiresAtMillis
}

/**
 * Lease storage. Backed by the same coordination database as the rest of the pipeline.
 *
 * [claim] is a takeover, not a check-then-write: the store must replace an existing lease that
 * is expired at [ExtractionLease.claimedAtMillis]. Implementing it as "reject when a row
 * exists" makes an expired lease permanent, which is the exact failure the expiry exists to
 * prevent. An implementation must perform the expiry test and the write atomically.
 */
interface ExtractionLeaseStore {
    fun active(workspaceId: String, sessionId: String): ExtractionLease?

    /**
     * Attempts to take the lease.
     *
     * Returns false only when a live lease held by a different owner blocks the claim.
     * An expired lease is always replaceable.
     */
    fun claim(lease: ExtractionLease): Boolean

    fun release(workspaceId: String, sessionId: String, owner: String)
}

sealed interface ClaimResult {
    data class Granted(val lease: ExtractionLease) : ClaimResult
    /** Another owner holds a live lease. */
    data class Busy(val heldBy: String, val expiresAtMillis: Long) : ClaimResult
}

/**
 * Hands out extraction leases with crash recovery.
 *
 * The only interesting case is expiration: a dead owner's lease must be reclaimable, otherwise
 * one crashed run blocks that session forever. Reclaiming is explicit rather than implicit so
 * the caller can record that a takeover happened.
 */
class ExtractionLeaseCoordinator(
    private val store: ExtractionLeaseStore,
    private val leaseDurationMillis: Long = DEFAULT_LEASE_MILLIS,
    private val clockMillis: () -> Long = System::currentTimeMillis,
) {

    fun claim(workspaceId: String, sessionId: String, owner: String): ClaimResult {
        val now = clockMillis()
        val existing = store.active(workspaceId, sessionId)
        if (existing != null && !existing.isExpired(now)) {
            return ClaimResult.Busy(existing.owner, existing.expiresAtMillis)
        }
        val lease = ExtractionLease(
            workspaceId = workspaceId,
            sessionId = sessionId,
            owner = owner,
            claimedAtMillis = now,
            expiresAtMillis = now + leaseDurationMillis,
        )
        // The store decides atomically, using claimedAtMillis to test expiry. Passing the
        // expiry decision down instead of assuming the store will honour it keeps a crashed
        // owner from becoming a permanent lock.
        return if (store.claim(lease)) {
            ClaimResult.Granted(lease)
        } else {
            // Lost a race with another claimant; report the winner rather than pretending to hold it.
            val winner = store.active(workspaceId, sessionId)
            ClaimResult.Busy(winner?.owner ?: "unknown", winner?.expiresAtMillis ?: 0)
        }
    }

    fun release(workspaceId: String, sessionId: String, owner: String) {
        store.release(workspaceId, sessionId, owner)
    }

    companion object {
        /** Short enough that a crash unblocks quickly, long enough for one extraction call. */
        const val DEFAULT_LEASE_MILLIS = 10L * 60 * 1000
    }
}

/**
 * Exponential backoff for runs that failed.
 *
 * Without a cap and a reset rule, a repeatedly failing session becomes a hot retry loop that
 * burns battery to fail. Attempts are counted per session and cleared on success.
 */
data class RetryBackoff(
    val baseDelayMillis: Long = 30_000,
    val maxDelayMillis: Long = 6L * 60 * 60 * 1000,
    val maxAttempts: Int = 8,
) {
    init {
        require(baseDelayMillis > 0) { "baseDelayMillis must be positive" }
        require(maxDelayMillis >= baseDelayMillis) { "maxDelayMillis must not be below the base" }
        require(maxAttempts > 0) { "maxAttempts must be positive" }
    }

    /** Delay before the next attempt, or null when attempts are exhausted. */
    fun delayFor(attempts: Int): Long? {
        if (attempts < 0) return baseDelayMillis
        if (attempts >= maxAttempts) return null
        // Cap the shift before it overflows: 2^62 on a Long is not a number of milliseconds.
        val shift = attempts.coerceAtMost(MAX_SHIFT)
        val delay = baseDelayMillis shl shift
        return if (delay <= 0 || delay > maxDelayMillis) maxDelayMillis else delay
    }

    fun giveUp(attempts: Int): Boolean = delayFor(attempts) == null

    private companion object {
        const val MAX_SHIFT = 20
    }
}

/** Whether a failed run should be retried, and when. */
data class RetryPlan(val attempts: Int, val nextAttemptAtMillis: Long?)

object RetryPlanner {
    fun plan(
        attemptsSoFar: Int,
        backoff: RetryBackoff,
        nowMillis: Long,
        succeeded: Boolean,
    ): RetryPlan {
        if (succeeded) {
            // Success clears the counter; otherwise a session that failed once would carry that
            // penalty forever.
            return RetryPlan(attempts = 0, nextAttemptAtMillis = null)
        }
        val next = attemptsSoFar + 1
        val delay = backoff.delayFor(attemptsSoFar)
        return RetryPlan(
            attempts = next,
            nextAttemptAtMillis = delay?.let { nowMillis + it },
        )
    }
}
