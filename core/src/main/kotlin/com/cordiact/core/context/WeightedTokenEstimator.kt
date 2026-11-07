package com.cordiact.core.context

import com.cordiact.core.conversation.ModelMessage
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.ceil

/**
 * Character-class weighted token estimator with online EWMA calibration.
 *
 * Runs in O(n) time with zero extra allocations, suitable for high-frequency checks
 * on mobile devices without relying on native BPE tokenizers.
 */
class WeightedTokenEstimator(
    private val defaultSafetyFactor: Double = 1.10,
) : TokenEstimator {

    private val calibrationFactors = ConcurrentHashMap<String, Double>()

    override fun estimate(text: String): Int {
        if (text.isEmpty()) return 0
        var weightSum = 0.0
        var i = 0
        val len = text.length
        while (i < len) {
            val cp = text.codePointAt(i)
            weightSum += characterWeight(cp)
            i += Character.charCount(cp)
        }
        return ceil(weightSum).toInt().coerceAtLeast(1)
    }

    override fun estimateMessages(messages: List<ModelMessage>, toolsJson: String?): Int {
        var total = 0
        for (msg in messages) {
            // Per-message framing overhead (role delimiter, header tokens)
            total += 4
            total += estimate(msg.content)
            for (call in msg.toolCalls) {
                total += 4
                total += estimate(call.name)
                total += estimate(call.argumentsJson)
            }
        }
        if (!toolsJson.isNullOrBlank()) {
            total += (toolsJson.length / 4) + 200
        }
        return total
    }

    override fun calibrate(actualInputTokens: Long, estimatedInputTokens: Int, modelId: String) {
        if (actualInputTokens <= 0 || estimatedInputTokens <= 0) return
        val currentFactor = calibrationFactors[modelId] ?: defaultFactorForModel(modelId)
        val observedRatio = actualInputTokens.toDouble() / estimatedInputTokens.toDouble()
        // Exponential moving average: 80% previous factor + 20% newest observation
        val updatedFactor = (0.8 * currentFactor + 0.2 * observedRatio).coerceIn(0.8, 1.6)
        calibrationFactors[modelId] = updatedFactor
    }

    fun currentFactor(modelId: String): Double {
        return calibrationFactors[modelId] ?: defaultFactorForModel(modelId)
    }

    private fun defaultFactorForModel(modelId: String): Double {
        val lower = modelId.lowercase()
        return when {
            lower.contains("deepseek") -> 1.05
            lower.contains("claude") || lower.contains("anthropic") -> 1.15
            lower.contains("gemini") -> 1.10
            else -> defaultSafetyFactor
        }
    }

    private fun characterWeight(cp: Int): Double = when {
        // CJK Unified Ideographs, Hiragana/Katakana, Hangul: ~1 token per char
        cp in 0x4E00..0x9FFF || cp in 0x3040..0x30FF || cp in 0xAC00..0xD7AF -> 1.0
        // ASCII Alphanumeric: ~4 chars per token -> 0.25
        cp in 0x30..0x39 || cp in 0x41..0x5A || cp in 0x61..0x7A -> 0.25
        // Whitespace, newline, tab -> 0.10
        cp == 0x20 || cp == 0x0A || cp == 0x09 || cp == 0x0D -> 0.10
        // Standard ASCII punctuation & symbols -> 0.5
        cp < 0x80 -> 0.5
        // Other Unicode scripts / emojis / symbols -> 0.75
        else -> 0.75
    }
}
