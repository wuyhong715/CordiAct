package com.cordiact.model.providers

/** Standard endpoint presets; callers supply current model ids and may override endpoint per provider. */
object ModelProviderPresets {
    fun anthropic(
        models: List<String>,
        providerId: String = "anthropic",
        displayName: String = "Anthropic Claude",
        endpoint: String = ANTHROPIC_MESSAGES_ENDPOINT,
    ) = ModelProviderConfig(providerId, displayName, ModelProtocol.ANTHROPIC_MESSAGES, endpoint, models)

    fun gemini(
        models: List<String>,
        providerId: String = "google",
        displayName: String = "Google Gemini",
        endpoint: String = GEMINI_STREAM_ENDPOINT,
    ) = ModelProviderConfig(providerId, displayName, ModelProtocol.GEMINI_GENERATE_CONTENT, endpoint, models)

    fun openAiResponses(
        models: List<String>,
        providerId: String = "openai",
        displayName: String = "OpenAI",
        endpoint: String = OPENAI_RESPONSES_ENDPOINT,
    ) = ModelProviderConfig(providerId, displayName, ModelProtocol.OPENAI_RESPONSES, endpoint, models)

    fun openAiChatCompletions(
        models: List<String>,
        providerId: String,
        displayName: String,
        endpoint: String,
    ) = ModelProviderConfig(providerId, displayName, ModelProtocol.OPENAI_CHAT_COMPLETIONS, endpoint, models)

    const val ANTHROPIC_MESSAGES_ENDPOINT = "https://api.anthropic.com/v1/messages"
    const val GEMINI_STREAM_ENDPOINT = "https://generativelanguage.googleapis.com/v1beta/models/{model}:streamGenerateContent?alt=sse"
    const val OPENAI_RESPONSES_ENDPOINT = "https://api.openai.com/v1/responses"
}
