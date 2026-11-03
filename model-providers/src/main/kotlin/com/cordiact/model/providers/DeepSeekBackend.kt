package com.cordiact.model.providers

fun interface ApiKeyProvider {
    fun apiKey(): String?
}

/**
 * DeepSeek is a preset of [ConfigurableModelBackend] using the OpenAI Chat Completions protocol.
 * It deliberately has no dedicated transport: one SSE implementation serves every provider.
 */
object DeepSeekPreset {
    const val PROVIDER_ID = "deepseek"
    const val DISPLAY_NAME = "DeepSeek"
    const val DEFAULT_MODEL = "deepseek-flash"
    const val DEFAULT_ENDPOINT = "https://api.deepseek.com/chat/completions"

    fun models(): List<String> = listOf(DEFAULT_MODEL)

    fun config(
        models: List<String> = models(),
        endpoint: String = DEFAULT_ENDPOINT,
        connectTimeoutMillis: Int = 15_000,
        readTimeoutMillis: Int = 120_000,
    ) = ModelProviderConfig(
        providerId = PROVIDER_ID,
        displayName = DISPLAY_NAME,
        protocol = ModelProtocol.OPENAI_CHAT_COMPLETIONS,
        endpoint = endpoint,
        models = models,
        connectTimeoutMillis = connectTimeoutMillis,
        readTimeoutMillis = readTimeoutMillis,
    )

    /** Loopback endpoint used by tests; never available to the app registry. */
    internal fun configForLoopbackTest(
        endpoint: String,
        models: List<String> = models(),
    ) = ModelProviderConfig.forLoopbackTest(
        providerId = PROVIDER_ID,
        displayName = DISPLAY_NAME,
        protocol = ModelProtocol.OPENAI_CHAT_COMPLETIONS,
        endpoint = endpoint,
        models = models,
    )
}
