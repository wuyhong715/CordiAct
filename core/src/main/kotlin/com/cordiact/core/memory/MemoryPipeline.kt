package com.cordiact.core.memory

import com.cordiact.core.workspace.WorkspaceGitService
import java.io.File

/** What one pipeline pass did, per session. */
sealed interface SessionMemoryOutcome {
    data class Extracted(val sessionId: String) : SessionMemoryOutcome
    data object NoContent : SessionMemoryOutcome
    data class Skipped(val sessionId: String, val reason: String) : SessionMemoryOutcome
    data class Failed(val sessionId: String, val reason: String, val retryable: Boolean) : SessionMemoryOutcome
}

data class MemoryPipelineReport(
    val decision: SchedulingDecision,
    val sessions: List<SessionMemoryOutcome>,
    val consolidation: ConsolidationOutcome?,
    /** Patterns grouped across the sessions extracted this pass. */
    val patterns: List<DetectedPattern> = emptyList(),
    /** Skills written from those patterns. */
    val procedural: ProceduralMemoryResult? = null,
) {
    val extractedCount: Int get() = sessions.count { it is SessionMemoryOutcome.Extracted }
}

/**
 * Runs one memory pass: decide, extract, consolidate.
 *
 * The order is fixed by cost. The scheduling decision is free and gates everything; extraction
 * calls a model per session and is the expensive part; consolidation is local and only runs if
 * extraction produced something. Reversing the first two would pay for model calls that a
 * deferral would have prevented.
 *
 * The pipeline is deliberately not a plugin. It owns no state and enforces no policy of its
 * own: scheduling, extraction, consolidation and version control are each already a contract,
 * and this only sequences them. A plugin here would be a fifth place to look for the same
 * decision.
 */
class MemoryPipeline(
    private val extraction: MemoryExtractionRunner,
    private val consolidation: MemoryConsolidationRunner,
    private val leaseCoordinator: ExtractionLeaseCoordinator,
    private val policy: MemorySchedulingPolicy = MemorySchedulingPolicy(),
    private val backoff: RetryBackoff = RetryBackoff(),
    private val detector: PatternDetector = PatternDetector(),
    private val procedural: ProceduralMemoryWriter? = null,
    private val userSkillNames: (String) -> Set<String> = { emptySet() },
    private val clockMillis: () -> Long = System::currentTimeMillis,
) {

    /**
     * Runs one pass over [sessionIds].
     *
     * [owner] identifies this run for lease purposes; a second concurrent run cannot take a
     * session it already holds, which is what stops duplicate extraction when a scheduled job
     * overlaps a manual trigger.
     */
    fun run(
        workspaceId: String,
        sessionIds: List<String>,
        conditions: DeviceConditions,
        owner: String,
        modelSelected: Boolean,
        diff: ConsolidationDiff,
        git: WorkspaceGitService? = null,
        workspaceRoot: File? = null,
    ): MemoryPipelineReport {
        val decision = policy.evaluate(conditions, modelSelected, needsNetwork = true)
        if (decision is SchedulingDecision.Defer) {
            // Nothing is attempted, so no session is marked failed and no backoff is burned.
            return MemoryPipelineReport(decision, emptyList(), null)
        }

        val outcomes = mutableListOf<SessionMemoryOutcome>()
        val candidates = mutableListOf<MemoryCandidate>()
        val patternCandidates = mutableListOf<PatternCandidate>()
        val now = clockMillis()

        for (sessionId in sessionIds) {
            when (val claim = leaseCoordinator.claim(workspaceId, sessionId, owner)) {
                is ClaimResult.Busy -> {
                    outcomes += SessionMemoryOutcome.Skipped(sessionId, "Another run holds this session")
                    continue
                }
                is ClaimResult.Granted -> Unit
            }

            val outcome = try {
                extraction.extract(workspaceId, sessionId, conditions)
            } finally {
                // Released even on failure: a failed extraction must not block the session until
                // the lease expires, or one bad session stalls the whole pipeline.
                leaseCoordinator.release(workspaceId, sessionId, owner)
            }

            when (outcome) {
                is ExtractionOutcome.Extracted -> {
                    outcomes += SessionMemoryOutcome.Extracted(sessionId)
                    candidates += MemoryCandidate(
                        sessionId = sessionId,
                        title = outcome.result.rolloutSummary,
                        body = outcome.result.rawMemory,
                        generatedAtMillis = now,
                    )
                    // Only sessions that named a task family contribute to procedural memory.
                    // Most sessions are not a procedure, and inventing a family for them would
                    // fill the skill index with one-offs.
                    outcome.result.taskFamily?.let { family ->
                        patternCandidates += PatternCandidate(
                            sessionId = sessionId,
                            name = family,
                            description = outcome.result.rolloutSummary,
                            steps = outcome.result.steps,
                            citation = MemoryCitation(
                                file = MemoryPaths.evidencePath(sessionId),
                                line = 1,
                                session = sessionId,
                            ),
                        )
                    }
                }
                ExtractionOutcome.NoContent -> outcomes += SessionMemoryOutcome.NoContent
                is ExtractionOutcome.Deferred ->
                    outcomes += SessionMemoryOutcome.Skipped(sessionId, outcome.reason)
                is ExtractionOutcome.Failed ->
                    outcomes += SessionMemoryOutcome.Failed(sessionId, outcome.reason, outcome.retryable)
            }
        }

        // Consolidation runs even with no new candidates: the diff set may have removals, and
        // skipping it would leave a forgotten session's evidence on disk forever.
        val consolidated = consolidation.consolidate(workspaceId, candidates, diff, git, workspaceRoot)

        // Procedural memory runs after consolidation because a generated skill cites evidence
        // files, and consolidation is what guarantees those files exist.
        val patterns = detector.detect(patternCandidates)
        val proceduralResult = procedural?.let { writer ->
            // User skills are passed in so a generated skill cannot shadow one. The writer
            // refuses rather than renaming: a renamed procedure is a different procedure.
            writer.promote(workspaceId, patterns, userSkillNames(workspaceId))
        }

        return MemoryPipelineReport(decision, outcomes, consolidated, patterns, proceduralResult)
    }

    /**
     * The diff set for a pass.
     *
     * Only sessions that produced candidates are "added"; everything else that still exists is
     * "retained". A session the caller no longer lists is "removed", which is what makes
     * forgetting possible.
     */
    fun diffFor(
        present: Set<String>,
        extracted: Set<String>,
        previouslyKnown: Set<String>,
    ): ConsolidationDiff = ConsolidationDiff(
        added = extracted.intersect(present),
        retained = (present - extracted).intersect(previouslyKnown),
        removed = previouslyKnown - present,
    )
}
