package com.cordiact.core.inbox

/**
 * Counts automatic wakes per Session over a sliding window.
 *
 * Waking a Session spends the user's tokens without the user having asked anything, so the budget
 * is a hard cap rather than a hint: past it, items stay queued and the next natural turn sees them.
 */
class SlidingWindowWakeLedger(
    private val policy: WakePolicy,
) : WakeLedger {

    private val timestamps = mutableMapOf<String, MutableList<Long>>()
    private val lock = Any()

    override fun tryAcquire(sessionId: String, nowMillis: Long): Boolean {
        synchronized(lock) {
            val recent = prune(sessionId, nowMillis)
            if (recent.size >= policy.maxAutoWakesPerHour) return false
            recent += nowMillis
            return true
        }
    }

    override fun recentWakes(sessionId: String, nowMillis: Long): Int = synchronized(lock) {
        prune(sessionId, nowMillis).size
    }

    private fun prune(sessionId: String, nowMillis: Long): MutableList<Long> {
        val list = timestamps.getOrPut(sessionId) { mutableListOf() }
        list.removeAll { nowMillis - it >= policy.windowMillis }
        return list
    }
}
