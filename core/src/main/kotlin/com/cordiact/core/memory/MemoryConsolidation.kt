package com.cordiact.core.memory

/**
 * One consolidation candidate, as produced by Phase 1 and scored by usage.
 *
 * [usageCount] and [lastUsedAtMillis] come from the coordination store, not from the artifact:
 * they answer "was this memory ever useful" and drive both ordering and eviction.
 */
data class MemoryCandidate(
    val sessionId: String,
    val title: String,
    val body: String,
    val usageCount: Int = 0,
    val lastUsedAtMillis: Long? = null,
    val generatedAtMillis: Long,
)

/**
 * Which sessions entered and left this consolidation.
 *
 * Phase 2 is not append-only. A session that was removed must take its memory with it, which
 * is why the input is a diff set rather than a fresh list of everything known: without the
 * removed side, "forget" is unimplementable and memory only ever grows.
 */
data class ConsolidationDiff(
    val added: Set<String> = emptySet(),
    val retained: Set<String> = emptySet(),
    val removed: Set<String> = emptySet(),
) {
    init {
        require(added.intersect(retained).isEmpty()) { "A session cannot be both added and retained" }
        require(added.intersect(removed).isEmpty()) { "A session cannot be both added and removed" }
        require(retained.intersect(removed).isEmpty()) { "A session cannot be both retained and removed" }
    }

    /** Sessions whose memory survives this round. */
    val surviving: Set<String> get() = added + retained
}

data class ConsolidationPolicy(
    /** Candidates are dropped once they have gone this long without being used. */
    val maxUnusedDays: Int = 30,
    /** Upper bound on candidates entering one round, after ordering. */
    val maxCandidates: Int = 256,
    /** Hard cap on the rendered manual. */
    val maxManualChars: Int = 200_000,
    /** Hard cap on the rendered summary. */
    val maxSummaryChars: Int = 20_000,
) {
    init {
        require(maxUnusedDays > 0) { "maxUnusedDays must be positive" }
        require(maxCandidates > 0) { "maxCandidates must be positive" }
        require(maxSummaryChars <= maxManualChars) { "Summary must not exceed the manual it indexes" }
    }
}

/**
 * Orders and prunes candidates before rendering.
 *
 * Ordering is usage-first, not recency-first: a memory that has been used twenty times is
 * more load-bearing than one written yesterday and never read. Candidates never used fall
 * back to generation time, so a brand new memory is not immediately evicted for having a
 * zero usage count.
 */
object MemoryCandidateRanker {

    fun rank(
        candidates: List<MemoryCandidate>,
        policy: ConsolidationPolicy,
        nowMillis: Long,
    ): List<MemoryCandidate> {
        val cutoff = nowMillis - policy.maxUnusedDays.toLong() * MILLIS_PER_DAY
        return candidates
            .asSequence()
            .filter { !isExpired(it, cutoff) }
            .sortedWith(
                compareByDescending<MemoryCandidate> { it.usageCount }
                    // Within equal usage, the more recently used comes first; a never-used
                    // candidate falls back to when it was generated.
                    .thenByDescending { it.lastUsedAtMillis ?: it.generatedAtMillis }
                    .thenBy { it.sessionId },
            )
            .take(policy.maxCandidates)
            .toList()
    }

    private fun isExpired(candidate: MemoryCandidate, cutoffMillis: Long): Boolean {
        // A never-used candidate is judged by its age since generation, otherwise every new
        // memory would be evicted immediately for having no usage timestamp.
        val reference = candidate.lastUsedAtMillis ?: candidate.generatedAtMillis
        return reference < cutoffMillis
    }

    private const val MILLIS_PER_DAY = 24L * 60 * 60 * 1000
}

/** The artifacts a consolidation produced, before they are validated and committed. */
data class ConsolidatedArtifacts(
    val summary: String,
    val manual: String,
    val evidence: Map<String, String>,
    /** Sessions dropped by this round; their evidence files must be deleted. */
    val evictedSessions: Set<String>,
)

/**
 * Renders artifacts from ranked candidates.
 *
 * Deterministic: the same candidates in the same order always produce byte-identical output,
 * so a no-op consolidation produces no diff and therefore no commit.
 */
object MemoryConsolidator {

    fun render(
        ranked: List<MemoryCandidate>,
        diff: ConsolidationDiff,
        policy: ConsolidationPolicy,
    ): ConsolidatedArtifacts {
        val surviving = ranked.filter { it.sessionId in diff.surviving }
        val evicted = diff.removed

        val evidence = surviving.associate { candidate ->
            MemoryPaths.evidencePath(candidate.sessionId) to renderEvidence(candidate)
        }

        val manual = renderManual(surviving)
        val summary = renderSummary(
            surviving.map { candidate ->
                SummaryEntry(
                    kind = "session",
                    title = candidate.title,
                    evidencePath = MemoryPaths.evidencePath(candidate.sessionId),
                    line = 1,
                )
            },
        )

        if (manual.length > policy.maxManualChars) {
            throw MemoryArtifactException(
                MemoryArtifactException.Code.OVER_BUDGET,
                "Consolidated manual would be ${manual.length} chars, over ${policy.maxManualChars}",
            )
        }
        if (summary.length > policy.maxSummaryChars) {
            throw MemoryArtifactException(
                MemoryArtifactException.Code.OVER_BUDGET,
                "Consolidated summary would be ${summary.length} chars, over ${policy.maxSummaryChars}",
            )
        }

        return ConsolidatedArtifacts(
            summary = summary,
            manual = manual,
            evidence = evidence,
            evictedSessions = evicted,
        )
    }

    private fun renderEvidence(candidate: MemoryCandidate): String = buildString {
        append("# ").appendLine(candidate.title)
        appendLine()
        appendLine(candidate.body)
        appendLine()
        append(
            renderCitations(
                listOf(
                    MemoryCitation(
                        file = MemoryPaths.evidencePath(candidate.sessionId),
                        line = 1,
                        session = candidate.sessionId,
                    ),
                ),
            ),
        )
    }

    private fun renderManual(candidates: List<MemoryCandidate>): String = buildString {
        appendLine("# Memory")
        appendLine()
        for (candidate in candidates) {
            append("## ").appendLine(candidate.title)
            appendLine()
            appendLine(candidate.body)
            appendLine()
            append(
                renderCitations(
                    listOf(
                        MemoryCitation(
                            file = MemoryPaths.evidencePath(candidate.sessionId),
                            line = 1,
                            session = candidate.sessionId,
                        ),
                    ),
                ),
            )
            appendLine()
        }
    }
}

/**
 * Outcome of a consolidation attempt.
 *
 * [Rejected] carries the reason so the caller can log why memory did not advance. It is not an
 * exception because a rejected consolidation is an expected state: the previous artifacts stay
 * in place and the next round retries.
 */
sealed interface ConsolidationOutcome {
    data class Committed(val commit: String?) : ConsolidationOutcome
    data class Rejected(val reason: String, val cause: MemoryArtifactException? = null) : ConsolidationOutcome
}
