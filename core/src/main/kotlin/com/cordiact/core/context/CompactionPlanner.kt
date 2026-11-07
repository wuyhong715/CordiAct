package com.cordiact.core.context

import com.cordiact.core.conversation.ConversationMessage
import com.cordiact.core.conversation.MessageRole
import kotlin.math.max

object CompactionPlanner {

    /**
     * Divides a conversation's messages into Head (protected anchors), Body (compaction candidates),
     * and Tail (recent interaction window), respecting token budgets and tool-pair boundaries.
     *
     * Returns null when the conversation is too short or there are no candidate messages in Body.
     */
    fun plan(
        messages: List<ConversationMessage>,
        estimator: TokenEstimator,
        budget: ContextBudget,
        minRetainTurns: Int = 2,
        reason: CompactionReason = CompactionReason.AUTO,
    ): CompactionPlan? {
        if (messages.size < 3) return null

        // 1. Identify Head: all leading SYSTEM messages + the first USER message
        var headEndIndex = 0
        while (headEndIndex < messages.size && messages[headEndIndex].role == MessageRole.SYSTEM) {
            headEndIndex++
        }
        if (headEndIndex < messages.size && messages[headEndIndex].role == MessageRole.USER) {
            headEndIndex++
        }

        if (headEndIndex >= messages.size) return null

        // 2. Identify Tail: accumulate from the end backwards until budget and turn count are satisfied
        var tailStartIndex = messages.size
        var accumulatedTailTokens = 0
        var userTurnsSeen = 0

        for (i in messages.indices.reversed()) {
            if (i < headEndIndex) break
            val msg = messages[i]
            accumulatedTailTokens += estimator.estimate(msg.content) + 4
            if (msg.role == MessageRole.USER) {
                userTurnsSeen++
            }
            tailStartIndex = i
            if (accumulatedTailTokens >= budget.retainTailTokens && userTurnsSeen >= minRetainTurns) {
                break
            }
        }

        // 3. Balance cut point
        tailStartIndex = safeCutIndex(messages, tailStartIndex)
        tailStartIndex = max(headEndIndex, tailStartIndex)

        val head = messages.subList(0, headEndIndex)
        val body = messages.subList(headEndIndex, tailStartIndex)
        val tail = messages.subList(tailStartIndex, messages.size)

        if (body.isEmpty()) return null

        val bodyTokens = body.sumOf { estimator.estimate(it.content) + 4 }
        val tailTokens = tail.sumOf { estimator.estimate(it.content) + 4 }

        return CompactionPlan(
            startMessageId = body.first().id,
            endMessageId = body.last().id,
            head = head,
            body = body,
            tail = tail,
            bodyTokens = bodyTokens,
            tailTokens = tailTokens,
            reason = reason,
        )
    }

    /**
     * Adjusts cut point backwards if necessary so tool call and result pairs stay together.
     */
    internal fun safeCutIndex(messages: List<ConversationMessage>, desiredIndex: Int): Int {
        var idx = desiredIndex.coerceIn(0, messages.size)

        // If the cut lands on a TOOL message, push index backward before its answering ASSISTANT
        while (idx > 0 && idx < messages.size && messages[idx].role == MessageRole.TOOL) {
            idx--
        }

        return idx
    }
}
