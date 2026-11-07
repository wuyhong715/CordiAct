package com.cordiact.core.context

/**
 * A summary prepared ahead of time, for a range that was compressible when it was generated.
 *
 * The whole point is to move the summary off the turn's critical path: the user is waiting, and a
 * summary call is the one part of compaction that costs wall-clock time. A candidate is only ever
 * a *proposal* — it is installed at request time only if the range it describes is still the range
 * that needs compressing.
 */
data class CompactionCandidate(
    val sessionId: String,
    val startMessageId: String,
    val endMessageId: String,
    val summary: String,
    val tokensBefore: Int,
    val tokensAfter: Int,
    val fallbackLevel: Int,
    val generatedAtMillis: Long,
)

/**
 * Holds at most one candidate per Session.
 *
 * One, not many: a second candidate for the same Session describes a different range, and keeping
 * both would force a choice at request time that the request itself is better equipped to make.
 */
class CompactionCandidateStore {

    private val candidates = mutableMapOf<String, CompactionCandidate>()
    private val lock = Any()

    fun put(candidate: CompactionCandidate) {
        synchronized(lock) { candidates[candidate.sessionId] = candidate }
    }

    fun get(sessionId: String): CompactionCandidate? = synchronized(lock) { candidates[sessionId] }

    fun remove(sessionId: String) {
        synchronized(lock) { candidates.remove(sessionId) }
    }

    fun clear() {
        synchronized(lock) { candidates.clear() }
    }
}
