package com.cordiact.harness.deepseek

/** Local routing identities, not endpoints, credentials, or evidence of server authorization. */
data class FollowTarget(
    val connectionId: String,
    val localWorkspaceId: String,
    val remoteSessionId: String,
) {
    init {
        require(listOf(connectionId, localWorkspaceId, remoteSessionId).all(FOLLOW_ID::matches)) {
            "Invalid follow routing identity"
        }
    }
}

/** Minimal display data. The pinned DSH state payload's queue and pending input are not retained. */
data class FollowSessionState(val cursor: Long, val active: Boolean, val running: Boolean)

enum class FollowEnd { DISCONNECTED, ACCESS_LOST, INVALID_SOURCE }

sealed interface FollowStatus {
    data object AwaitingBaseline : FollowStatus
    data class Current(val state: FollowSessionState) : FollowStatus
    data class Ended(val reason: FollowEnd) : FollowStatus
}

enum class FollowUpdate { APPLIED, IGNORED, RETIRED }

/**
 * In-memory reducer for one selected display. No transport, storage, command, or authorization port.
 * A future authenticated adapter must capture the exact generation and target when opening its
 * read, and submit ordered complete replacements; the target does not come from a DSH frame.
 */
class ReadOnlySessionFollow {
    /** Reference identity is local to this reducer; even selecting the same target retires it. */
    class Generation internal constructor(val target: FollowTarget)

    private class Selection(val generation: Generation) {
        var status: FollowStatus = FollowStatus.AwaitingBaseline
        var lastDelivery = -1L
    }

    private var selected: Selection? = null

    /** Start with no visible remote data. Reconnect also requires a fresh baseline. */
    @Synchronized
    fun begin(target: FollowTarget): Generation = Generation(target).also { selected = Selection(it) }

    /** Old listeners cannot read a newer selection, even when the target tuple is identical. */
    @Synchronized
    fun status(generation: Generation): FollowStatus? = matching(generation)?.status

    /**
     * Ordinals are assigned by the read adapter in delivery order, starting at zero. They are not
     * DSH log cursors: the passive state can change at the same cursor or coalesce multiple changes.
     */
    @Synchronized
    fun accept(
        generation: Generation,
        receivedFor: FollowTarget,
        delivery: Long,
        state: FollowSessionState,
    ): FollowUpdate {
        val current = matching(generation) ?: return FollowUpdate.IGNORED
        if (current.status is FollowStatus.Ended) return FollowUpdate.IGNORED
        if (receivedFor != generation.target || delivery < 0) return retireInvalid(current)
        if (delivery <= current.lastDelivery) return FollowUpdate.IGNORED
        val previous = current.status as? FollowStatus.Current
        if (delivery != current.lastDelivery + 1 || state.cursor !in -1..MAX_SAFE_DSH_CURSOR ||
            (state.running && !state.active) || (previous != null && state.cursor < previous.state.cursor)) {
            return retireInvalid(current)
        }
        current.lastDelivery = delivery
        current.status = FollowStatus.Current(state)
        return FollowUpdate.APPLIED
    }

    /** End first, before the caller performs any asynchronous transport or view cleanup. */
    @Synchronized
    fun end(generation: Generation, reason: FollowEnd): Boolean {
        val current = matching(generation) ?: return false
        if (current.status is FollowStatus.Ended) return false
        current.status = FollowStatus.Ended(reason)
        return true
    }

    /** No data or status from a closed view remains accessible through this reducer. */
    @Synchronized
    fun clear(generation: Generation): Boolean {
        matching(generation) ?: return false
        selected = null
        return true
    }

    private fun matching(generation: Generation): Selection? = selected?.takeIf { it.generation === generation }

    private fun retireInvalid(current: Selection): FollowUpdate {
        current.status = FollowStatus.Ended(FollowEnd.INVALID_SOURCE)
        return FollowUpdate.RETIRED
    }
}

private val FOLLOW_ID = Regex("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}")
private const val MAX_SAFE_DSH_CURSOR = 9_007_199_254_740_991L
