package com.cordiact.core.context

import com.cordiact.core.conversation.ConversationMessage
import com.cordiact.core.conversation.MessageRole
import com.cordiact.core.conversation.ModelId

/**
 * Owns the "should I compact, and how" decision, separate from the turn service.
 *
 * Two entry points with different aggression:
 *  - [compactIfNeeded] runs before a request when the projected prompt is near the budget;
 *  - [emergencyCompact] runs after a provider rejected the prompt as too long, so it compacts
 *    regardless of the threshold and keeps a much smaller tail.
 *
 * Both write a durable record and supersede the previous one, so the projection always sees
 * exactly one active summary and the original messages stay untouched.
 */
class DefaultContextCompactor(
    private val store: ContextCompactionStore?,
    private val estimator: TokenEstimator,
    private val policy: ContextPolicy = ContextPolicy(),
    private val projector: ContextProjector = DefaultContextProjector(),
    private val clockMillis: () -> Long = System::currentTimeMillis,
    /**
     * Optional L0 summariser: prompt plus body messages in, summary text out.
     *
     * Null means "rules only". Returning null or throwing falls back to the deterministic levels,
     * because a failed summary must never fail the turn.
     */
    private val summarizer: ((String, List<ConversationMessage>) -> String?)? = null,
    private val candidates: CompactionCandidateStore = CompactionCandidateStore(),
) {

    fun compactIfNeeded(
        sessionId: String,
        windowTokens: Int,
        rawMessages: List<ConversationMessage>,
        reason: CompactionReason = CompactionReason.AUTO,
        force: Boolean = false,
    ): CompactionResult? {
        val compactionStore = store ?: return null
        if (!policy.enabled) return null

        val budget = policy.createBudget(windowTokens)
        val active = compactionStore.listActive(sessionId)
        val projectedTokens = estimator.estimateMessages(projector.project(sessionId, rawMessages, active))

        if (!force && projectedTokens < budget.compactAtTokens) return null

        // Prefer a candidate prepared off the turn's critical path. It is only used when the range
        // it describes is still the range that needs compressing.
        installCandidate(sessionId, windowTokens, rawMessages)?.let { return it }

        return compactWith(sessionId, rawMessages, budget, reason)
    }

    /**
     * Prepares a summary now, for a later turn to install.
     *
     * Called off the critical path (a worker, not the turn). Returns null when there is nothing
     * worth compressing yet, and drops any older candidate for the Session: two proposals for
     * different ranges would force a choice the request is better placed to make.
     */
    fun prepareCandidate(
        sessionId: String,
        windowTokens: Int,
        rawMessages: List<ConversationMessage>,
    ): CompactionCandidate? {
        if (store == null || !policy.enabled) return null

        val budget = policy.createBudget(windowTokens)
        val plan = CompactionPlanner.plan(
            messages = rawMessages,
            estimator = estimator,
            budget = budget,
            minRetainTurns = policy.minRetainTurns,
            reason = CompactionReason.BACKGROUND,
        )
        if (plan == null || plan.bodyTokens < MIN_CANDIDATE_BODY_TOKENS) {
            candidates.remove(sessionId)
            return null
        }

        val result = SummaryCompactor.compact(plan, estimator, summarizer)
        val candidate = CompactionCandidate(
            sessionId = sessionId,
            startMessageId = result.startMessageId,
            endMessageId = result.endMessageId,
            summary = result.summary,
            tokensBefore = result.tokensBefore,
            tokensAfter = result.tokensAfter,
            fallbackLevel = result.fallbackLevel,
            generatedAtMillis = clockMillis(),
        )
        candidates.put(candidate)
        return candidate
    }

    /** Installs the pending candidate when it still applies; null means "do the work now". */
    fun installCandidate(
        sessionId: String,
        windowTokens: Int,
        rawMessages: List<ConversationMessage>,
    ): CompactionResult? {
        val compactionStore = store ?: return null
        val candidate = candidates.get(sessionId) ?: return null

        val plan = CompactionPlanner.plan(
            messages = rawMessages,
            estimator = estimator,
            budget = policy.createBudget(windowTokens),
            minRetainTurns = policy.minRetainTurns,
            reason = CompactionReason.AUTO,
        )
        val verdict = CandidateValidator.validate(candidate, rawMessages, plan)
        if (verdict != CandidateVerdict.USABLE) {
            candidates.remove(sessionId)
            return null
        }

        candidates.remove(sessionId)
        return record(
            sessionId = sessionId,
            result = CompactionResult(
                recordId = newRecordId(),
                startMessageId = candidate.startMessageId,
                endMessageId = candidate.endMessageId,
                tokensBefore = candidate.tokensBefore,
                tokensAfter = candidate.tokensAfter,
                fallbackLevel = candidate.fallbackLevel,
                summary = candidate.summary,
            ),
            compactionStore = compactionStore,
        )
    }

    /**
     * Compacts hard, for the case where the provider has already refused the prompt.
     *
     * A smaller tail and a lower threshold than the proactive path: the point is to fit, not to be
     * polite about how much history survives.
     */
    /**
     * Compacts hard, for the case where the provider has already refused the prompt.
     *
     * A smaller tail and a lower threshold than the proactive path: the point is to fit, not to be
     * polite about how much history survives.
     *
     * The budget plan can legitimately find nothing to cut — our estimate may disagree with the
     * provider, and then every message looks "recent". That is exactly when a fallback cut is
     * needed: an emergency that can make no progress leaves the user stuck on the same error, so
     * this keeps only the anchors and the last exchange and summarises everything between.
     */
    fun emergencyCompact(
        sessionId: String,
        windowTokens: Int,
        rawMessages: List<ConversationMessage>,
    ): CompactionResult? {
        val compactionStore = store ?: return null
        val budget = policy.createBudget(windowTokens)
        val aggressive = budget.copy(
            retainRatio = (budget.retainRatio / 2).coerceAtLeast(0.05),
        )

        val planned = CompactionPlanner.plan(
            messages = rawMessages,
            estimator = estimator,
            budget = aggressive,
            minRetainTurns = 1,
            reason = CompactionReason.OVERFLOW,
        )

        val plan = planned ?: forcedPlan(rawMessages) ?: return null
        return record(sessionId, SummaryCompactor.compact(plan, estimator, summarizer), compactionStore)
    }

    /**
     * Last-resort split: anchors, then the final exchange, everything between is summarised.
     *
     * Ignores the token budget entirely, because the budget is the thing that just proved wrong.
     */
    private fun forcedPlan(messages: List<ConversationMessage>): CompactionPlan? {
        if (messages.size < 4) return null

        var headEnd = 0
        while (headEnd < messages.size && messages[headEnd].role == MessageRole.SYSTEM) headEnd++
        if (headEnd < messages.size && messages[headEnd].role == MessageRole.USER) headEnd++

        var tailStart = (messages.size - 2).coerceAtLeast(headEnd)
        // Never begin the tail on a tool result: its call would be left behind in the summary.
        while (tailStart < messages.size && messages[tailStart].role == MessageRole.TOOL) tailStart++

        val head = messages.subList(0, headEnd)
        val body = messages.subList(headEnd, tailStart)
        val tail = messages.subList(tailStart, messages.size)
        if (body.isEmpty()) return null

        return CompactionPlan(
            startMessageId = body.first().id,
            endMessageId = body.last().id,
            head = head,
            body = body,
            tail = tail,
            bodyTokens = body.sumOf { estimator.estimate(it.content) + 4 },
            tailTokens = tail.sumOf { estimator.estimate(it.content) + 4 },
            reason = CompactionReason.OVERFLOW,
        )
    }

    private fun compactWith(
        sessionId: String,
        rawMessages: List<ConversationMessage>,
        budget: ContextBudget,
        reason: CompactionReason,
    ): CompactionResult? {
        val compactionStore = store ?: return null
        val plan = CompactionPlanner.plan(
            messages = rawMessages,
            estimator = estimator,
            budget = budget,
            minRetainTurns = policy.minRetainTurns,
            reason = reason,
        ) ?: return null

        return record(sessionId, SummaryCompactor.compact(plan, estimator, summarizer), compactionStore)
    }

    private fun newRecordId(): String = "cmp-${java.util.UUID.randomUUID().toString().take(8)}"

    private fun record(
        sessionId: String,
        result: CompactionResult,
        compactionStore: ContextCompactionStore,
    ): CompactionResult {
        val record = CompactionRecord(
            id = result.recordId,
            sessionId = sessionId,
            startMessageId = result.startMessageId,
            endMessageId = result.endMessageId,
            summary = result.summary,
            tokensBefore = result.tokensBefore,
            tokensAfter = result.tokensAfter,
            fallbackLevel = result.fallbackLevel,
            createdAt = clockMillis(),
            supersededBy = null,
            active = true,
        )
        // Supersede first: the projection must never see two active summaries, even briefly.
        compactionStore.listActive(sessionId).forEach { compactionStore.supersede(it.id, record.id) }
        compactionStore.insert(record)
        return result
    }

    private companion object {
        /**
         * Below this, a background summary would cost more to install than it saves.
         *
         * A candidate covering a couple of short messages is not worth a record, a projection
         * change and a card in the transcript.
         */
        const val MIN_CANDIDATE_BODY_TOKENS = 400
    }
}
