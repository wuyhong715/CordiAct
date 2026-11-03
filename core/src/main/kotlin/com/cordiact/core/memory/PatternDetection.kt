package com.cordiact.core.memory

/** A candidate extracted from one session, before cross-session grouping. */
data class PatternCandidate(
    val sessionId: String,
    /** Normalized task family, e.g. "fix-kotlin-build". */
    val name: String,
    val description: String,
    val steps: List<String>,
    val citation: MemoryCitation? = null,
)

/**
 * Groups per-session candidates into cross-session patterns.
 *
 * Detection is mechanical on purpose: it counts how many distinct sessions produced the same
 * normalized name and trusts the extractor for the content. Deciding sameness by comparing step
 * text would make one procedure look different every time it was worded slightly differently,
 * which is the normal case.
 *
 * Steps are taken from the most recent session that reported the pattern rather than merged.
 * Two sessions describing the same task can disagree about the steps, and a merge would produce
 * a procedure nobody actually performed.
 */
class PatternDetector {

    fun detect(candidates: List<PatternCandidate>): List<DetectedPattern> {
        return candidates
            .groupBy { it.name }
            .mapNotNull { (name, group) ->
                val latest = group.last()
                val sessions = group.map { it.sessionId }.toSet()
                if (sessions.isEmpty()) return@mapNotNull null
                DetectedPattern(
                    name = name,
                    description = latest.description,
                    sessionIds = sessions,
                    steps = latest.steps,
                    citations = group.mapNotNull { it.citation }.distinct(),
                )
            }
            .sortedWith(compareByDescending<DetectedPattern> { it.repeats }.thenBy { it.name })
    }
}

/** What one promotion pass wrote, so the caller can commit exactly those paths. */
data class ProceduralMemoryResult(
    val written: List<GeneratedSkill>,
    val rejected: List<Pair<String, PromotionDecision.Reject>>,
) {
    val isEmpty: Boolean get() = written.isEmpty()
}

/**
 * Applies promotion decisions and writes the resulting skills.
 *
 * Writing goes through [MemoryArtifactStore] like every other memory artifact, so the memory
 * subtree rules, the git path check and the size budget all still apply. A generated skill is
 * not a special kind of file that gets its own I/O path.
 */
class ProceduralMemoryWriter(
    private val store: MemoryArtifactStore,
    private val promoter: ProceduralMemoryPromoter = ProceduralMemoryPromoter(),
) {

    fun promote(
        workspaceId: String,
        patterns: List<DetectedPattern>,
        userSkillNames: Set<String>,
    ): ProceduralMemoryResult {
        val written = mutableListOf<GeneratedSkill>()
        val rejected = mutableListOf<Pair<String, PromotionDecision.Reject>>()

        for (pattern in patterns) {
            val existing = store.read(workspaceId, pathFor(pattern.name), MAX_SKILL_CHARS)
            when (val decision = promoter.decide(pattern, existing, userSkillNames)) {
                is PromotionDecision.Promote -> {
                    store.write(workspaceId, decision.skill.path, promoter.document(decision.skill))
                    written += decision.skill
                }
                is PromotionDecision.Reject -> rejected += pattern.name to decision
            }
        }
        return ProceduralMemoryResult(written, rejected)
    }

    private fun pathFor(name: String) =
        ProceduralMemoryPromoter.SKILLS_PREFIX + name + "/SKILL.md"

    private companion object {
        const val MAX_SKILL_CHARS = 64 * 1024
    }
}
