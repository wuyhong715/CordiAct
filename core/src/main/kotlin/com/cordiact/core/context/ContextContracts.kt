package com.cordiact.core.context

import com.cordiact.core.conversation.ConversationMessage
import com.cordiact.core.conversation.ModelDescriptor
import com.cordiact.core.conversation.ModelId
import com.cordiact.core.conversation.ModelMessage

/**
 * Budget snapshot for a single turn's prompt assembly and tool executions.
 */
data class ContextBudget(
    val contextWindowTokens: Int,
    val maxOutputTokens: Int = 8_192,
    val safetyMarginTokens: Int = 2_048,
    val thresholdRatio: Double = 0.8,
    val retainRatio: Double = 0.16,
) {
    init {
        require(contextWindowTokens > 0) { "contextWindowTokens must be positive" }
        require(maxOutputTokens in 1..<contextWindowTokens) { "maxOutputTokens must be within 1..<contextWindowTokens" }
        require(safetyMarginTokens >= 0) { "safetyMarginTokens must not be negative" }
        require(thresholdRatio in 0.1..0.95) { "thresholdRatio must be within 0.1..0.95" }
        require(retainRatio in 0.05..0.5) { "retainRatio must be within 0.05..0.5" }
    }

    val usableInputTokens: Int
        get() = (contextWindowTokens - maxOutputTokens - safetyMarginTokens).coerceAtLeast(1)

    val compactAtTokens: Int
        get() = (usableInputTokens * thresholdRatio).toInt().coerceAtLeast(1)

    val retainTailTokens: Int
        get() = (contextWindowTokens * retainRatio).toInt().coerceAtLeast(1)
}

/**
 * Global and per-session context policy parameters.
 */
data class ContextPolicy(
    val enabled: Boolean = true,
    val contextWindowOverride: Int? = null,
    val maxOutputTokens: Int = 8_192,
    val thresholdRatio: Double = 0.8,
    val retainRatio: Double = 0.16,
    val minRetainTurns: Int = 2,
    val maxInlineToolChars: Int = 8_192,
    val toolHeadChars: Int = 4_096,
    val toolTailChars: Int = 2_048,
    val foldAfterRounds: Int = 2,
    val summaryModelId: String? = null,
    val summaryTimeoutMs: Long = 20_000,
    val spillRetentionDays: Int = 7,
) {
    init {
        require(maxOutputTokens > 0) { "maxOutputTokens must be positive" }
        require(minRetainTurns >= 1) { "minRetainTurns must be >= 1" }
        require(maxInlineToolChars > 0) { "maxInlineToolChars must be positive" }
        require(toolHeadChars >= 0) { "toolHeadChars must be non-negative" }
        require(toolTailChars >= 0) { "toolTailChars must be non-negative" }
        require(toolHeadChars + toolTailChars <= maxInlineToolChars) {
            "toolHeadChars + toolTailChars must not exceed maxInlineToolChars"
        }
    }
}

/**
 * Built-in lookup table for model context windows when not explicitly specified on [ModelDescriptor].
 */
object DefaultContextWindows {
    private const val FALLBACK_WINDOW = 32_768

    fun resolve(descriptor: ModelDescriptor, policyOverride: Int? = null): Int {
        if (policyOverride != null && policyOverride > 0) return policyOverride
        if (descriptor.contextWindowTokens != null && descriptor.contextWindowTokens > 0) {
            return descriptor.contextWindowTokens
        }
        return resolveByModelId(descriptor.id)
    }

    fun resolveByModelId(modelId: ModelId): Int {
        val id = modelId.modelId.lowercase()
        val provider = modelId.providerId.lowercase()

        return when {
            id.contains("gemini-1.5") || id.contains("gemini-2.0") || id.contains("gemini-exp") -> 1_048_576
            id.contains("claude-3-5") || id.contains("claude-3-7") || id.contains("claude-3") -> 200_000
            id.contains("deepseek-chat") || id.contains("deepseek-coder") || id.contains("deepseek-r1") || id.contains("deepseek") -> 128_000
            id.contains("gpt-4o") || id.contains("gpt-4-turbo") || id.contains("o1") || id.contains("o3") -> 128_000
            id.contains("qwen") || id.contains("glm") -> 128_000
            provider.contains("deepseek") -> 128_000
            provider.contains("anthropic") -> 200_000
            provider.contains("google") -> 1_048_576
            provider.contains("openai") -> 128_000
            else -> FALLBACK_WINDOW
        }
    }
}

/**
 * Estimation interface for token usage calculation and online calibration.
 */
interface TokenEstimator {
    fun estimate(text: String): Int
    fun estimateMessages(messages: List<ModelMessage>, toolsJson: String? = null): Int
    fun calibrate(actualInputTokens: Long, estimatedInputTokens: Int, modelId: String)
}

/**
 * Storage for oversized tool output spills.
 */
interface SpillStore {
    /**
     * Writes content to a spill file for the given session.
     * Returns a spill reference URI, e.g. "spill://<sessionId>/<filename>".
     */
    fun saveSpill(sessionId: String, toolName: String, content: String): String

    /**
     * Reads a slice from a spill file.
     * Returns null if not found or unauthorized.
     */
    fun readSpill(spillUri: String, offset: Long = 0, limitChars: Int = 8_000): SpillReadResult?
}

data class SpillReadResult(
    val content: String,
    val offset: Long,
    val bytesRead: Int,
    val totalChars: Long,
    val hasMore: Boolean,
)

/**
 * Gate that protects the tool-loop from intra-turn context blowup.
 */
interface ToolOutputGate {
    /**
     * Inspects and potentially truncates / spills a tool execution result.
     * Returns the transformed [ModelMessage].
     */
    fun gate(sessionId: String, resultMessage: ModelMessage, limitChars: Int): ModelMessage
}

/**
 * Plan for candidate history compaction.
 */
data class CompactionPlan(
    val head: List<ModelMessage>,
    val body: List<ModelMessage>,
    val tail: List<ModelMessage>,
    val bodyTokens: Int,
    val tailTokens: Int,
    val reason: CompactionReason,
)

enum class CompactionReason { AUTO, MANUAL, OVERFLOW, BACKGROUND }

data class CompactionResult(
    val recordId: String,
    val tokensBefore: Int,
    val tokensAfter: Int,
    val fallbackLevel: Int,
    val summary: String,
)

/**
 * Projection record corresponding to a row in context_compactions table.
 */
data class CompactionRecord(
    val id: String,
    val sessionId: String,
    val startMessageId: String,
    val endMessageId: String,
    val summary: String,
    val tokensBefore: Int,
    val tokensAfter: Int,
    val fallbackLevel: Int,
    val createdAt: Long,
    val supersededBy: String?,
    val active: Boolean,
)

interface ContextProjector {
    fun project(
        sessionId: String,
        history: List<ConversationMessage>,
        records: List<CompactionRecord>,
    ): List<ModelMessage>
}
