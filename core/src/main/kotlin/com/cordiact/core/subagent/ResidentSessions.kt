package com.cordiact.core.subagent

/**
 * Keeps the number of sub-agent Sessions held in memory bounded.
 *
 * A finished child is not a closed window: its transcript, tools and grants are still addressable
 * and the parent may read it later. But "still addressable" does not mean "must stay in RAM" — a
 * fan-out of twenty children would otherwise hold twenty working sets for a session the user is
 * not looking at.
 *
 * So residency is an LRU over Sessions with an explicit veto: a Session is only evicted when the
 * host says it can be (no active turn, nothing pending in its inbox). Eviction drops the in-memory
 * state only; the Session and its messages stay in the database and reopen unchanged.
 */
class ResidentSessions(
    private val maxResident: Int = 8,
    /**
     * Whether this Session may leave memory right now.
     *
     * Supplied by the host because only it knows about active turns, queued reports and pending
     * wakes. A registry that guessed would evict a child mid-run.
     */
    private val isEvictable: (sessionId: String) -> Boolean,
) {
    init {
        require(maxResident > 0) { "maxResident must be positive" }
    }

    private val order = ArrayDeque<String>()
    private val lock = Any()

    /** Marks a Session as recently used, admitting it if it was not resident. */
    fun touch(sessionId: String) {
        synchronized(lock) {
            order.remove(sessionId)
            order.addLast(sessionId)
        }
    }

    fun isResident(sessionId: String): Boolean = synchronized(lock) { sessionId in order }

    /** Least-recently-used first. */
    fun residents(): List<String> = synchronized(lock) { order.toList() }

    fun size(): Int = synchronized(lock) { order.size }

    fun forget(sessionId: String): Boolean = synchronized(lock) { order.remove(sessionId) }

    /**
     * Evicts least-recently-used Sessions until the cap holds.
     *
     * Returns the evicted ids. A Session the host refuses to release is skipped, and so is the
     * most recent one: evicting the thing the user is looking at to satisfy a cap would be worse
     * than briefly exceeding it.
     */
    fun reap(): List<String> {
        synchronized(lock) {
            val evicted = mutableListOf<String>()
            if (order.size <= maxResident) return evicted

            val candidates = order.toList() // LRU first
            for (candidate in candidates) {
                if (order.size <= maxResident) break
                if (order.lastOrNull() == candidate) break // never evict the newest
                if (!isEvictable(candidate)) continue
                order.remove(candidate)
                evicted += candidate
            }
            return evicted
        }
    }
}
