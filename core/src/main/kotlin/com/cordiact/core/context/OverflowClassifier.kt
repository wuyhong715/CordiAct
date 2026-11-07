package com.cordiact.core.context

/** Why a model call failed, in the only terms the turn service acts on. */
sealed interface ModelFailure {
    data class ContextOverflow(val providerCode: String?, val message: String) : ModelFailure
    data class RateLimited(val providerCode: String?, val message: String) : ModelFailure
    data class Other(val providerCode: String, val message: String) : ModelFailure
}

/**
 * Classifies a provider failure as "the prompt was too long" or something else.
 *
 * String matching is unavoidable here — providers do not share an error vocabulary — so the
 * patterns are per provider and the generic fallback is deliberately narrow: a 400/413 that
 * mentions both a token-ish word and a length-ish word. A plain 400 (bad argument, bad tool
 * schema) must NOT be treated as overflow, or a bug would be "fixed" by compacting context
 * until the real error is buried.
 */
object OverflowClassifier {

    fun classify(providerCode: String?, message: String, providerId: String? = null): ModelFailure {
        val code = providerCode.orEmpty()
        val text = message.lowercase()
        val provider = providerId.orEmpty().lowercase()

        if (isOverflow(code, text, provider)) {
            return ModelFailure.ContextOverflow(providerCode, message)
        }
        if (code == "429" || text.contains("rate limit") || text.contains("too many requests")) {
            return ModelFailure.RateLimited(providerCode, message)
        }
        return ModelFailure.Other(code, message)
    }

    private fun isOverflow(code: String, text: String, provider: String): Boolean {
        // OpenAI-compatible (OpenAI, DeepSeek, most gateways)
        if (code == "context_length_exceeded" || text.contains("context_length_exceeded")) return true
        if (text.contains("maximum context length")) return true
        if (text.contains("reduce the length of the messages")) return true
        // Anthropic
        if (text.contains("prompt is too long")) return true
        if (text.contains("input length and `max_tokens` exceed context limit")) return true
        // Gemini
        if (text.contains("exceeds the maximum number of tokens")) return true
        if (code == "invalid_argument" && text.contains("token")) return true
        // Generic: a request error that names both a length and a token notion.
        val requestError = code == "400" || code == "413" || code == "invalid_request_error"
        val lengthish = text.contains("context") || text.contains("length") || text.contains("too long")
        val tokenish = text.contains("token") || text.contains("prompt")
        if (requestError && lengthish && tokenish) return true
        // Provider hint alone is never enough; kept explicit so the intent is visible.
        return provider.isNotEmpty() && text.contains("context window")
    }
}
