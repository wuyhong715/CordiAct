package com.cordiact.core.context

import com.cordiact.core.conversation.ConversationMessage

/** Why a pre-computed candidate was used or thrown away. */
enum class CandidateVerdict {
    /** The range it describes is still the range that would be compressed. */
    USABLE,

    /** One of its boundary messages no longer exists. */
    RANGE_GONE,

    /** Newer messages have pushed its range into the protected tail. */
    RANGE_OUTGROWN,

    /** Nothing to compress any more; the candidate is pointless. */
    NOTHING_TO_COMPACT,
}

/**
 * Decides whether a candidate may still be installed.
 *
 * The candidate was generated against an older view of the history, and history only grows. So the
 * question is never "is it identical" but "does it still describe a compressible range": both
 * boundaries must exist, and the range must still end before the tail the planner would protect
 * today. A candidate that would now swallow the recent exchange is stale, and installing it would
 * silently drop exactly the context the model needs.
 */
object CandidateValidator {

    fun validate(
        candidate: CompactionCandidate,
        history: List<ConversationMessage>,
        plan: CompactionPlan?,
    ): CandidateVerdict {
        val startIndex = history.indexOfFirst { it.id == candidate.startMessageId }
        val endIndex = history.indexOfFirst { it.id == candidate.endMessageId }
        if (startIndex < 0 || endIndex < 0 || startIndex > endIndex) return CandidateVerdict.RANGE_GONE

        val currentPlan = plan ?: return CandidateVerdict.NOTHING_TO_COMPACT
        if (currentPlan.body.isEmpty()) return CandidateVerdict.NOTHING_TO_COMPACT

        val currentStart = history.indexOfFirst { it.id == currentPlan.startMessageId }
        val currentEnd = history.indexOfFirst { it.id == currentPlan.endMessageId }
        if (currentStart < 0 || currentEnd < 0) return CandidateVerdict.RANGE_OUTGROWN

        // The candidate must still sit inside today's compressible body.
        if (endIndex > currentEnd) return CandidateVerdict.RANGE_OUTGROWN
        if (startIndex < currentStart) return CandidateVerdict.RANGE_GONE

        return CandidateVerdict.USABLE
    }
}
