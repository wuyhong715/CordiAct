package com.cordiact.search.providers

import com.cordiact.core.search.CancellationSignal
import com.cordiact.core.search.ModelRoute
import com.cordiact.core.search.SearchException
import com.cordiact.core.search.SearchProvider
import com.cordiact.core.search.SearchRequest
import com.cordiact.core.search.SearchResult
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import java.net.URI

/**
 * Anthropic's server-side `web_search_20250305` tool, run as one auxiliary Messages call.
 *
 * Same shape as the DeepSeek native provider because DeepSeek speaks the same
 * protocol, but the endpoint, credential and model route are Anthropic's.
 */
class AnthropicNativeSearchProvider(
    private val apiKey: () -> String?,
    private val baseUrl: () -> String = { DEFAULT_BASE_URL },
    private val maxUses: Int = DEFAULT_MAX_USES,
    private val maxTokens: Int = DEFAULT_MAX_TOKENS,
    private val apiVersion: String = DEFAULT_API_VERSION,
    private val client: HttpJsonClient = HttpJsonClient(),
) : SearchProvider {
    override val id: String = PROVIDER_ID
    override val native: Boolean = true
    override val routeProviderId: String = MODEL_PROVIDER_ID

    override fun available(): Boolean {
        val endpoint = runCatching { URI(baseUrl()) }.getOrNull() ?: return false
        return (endpoint.scheme == "https" || endpoint.scheme == "http") &&
            !endpoint.host.isNullOrBlank() &&
            apiKey()?.isNotBlank() == true &&
            maxUses > 0 &&
            maxTokens > 0
    }

    override fun ready(route: ModelRoute?): Boolean =
        route?.providerId == MODEL_PROVIDER_ID && available()

    override fun search(request: SearchRequest, signal: CancellationSignal): SearchResult {
        val key = apiKey()?.takeIf { it.isNotBlank() }
            ?: throw SearchException(SearchException.Code.CREDENTIAL_MISSING, "Anthropic credential is missing")
        val model = request.route?.modelId?.takeIf { it.isNotBlank() }
            ?: throw SearchException(SearchException.Code.UNSUPPORTED, "Anthropic model is unknown for this search")
        if (!available()) {
            throw SearchException(SearchException.Code.PROVIDER_UNAVAILABLE, "Anthropic native search is not configured")
        }
        val payload = JsonObject().apply {
            addProperty("model", model)
            addProperty("max_tokens", maxTokens)
            add("messages", JsonArray().apply {
                add(JsonObject().apply {
                    addProperty("role", "user")
                    add("content", JsonArray().apply {
                        add(JsonObject().apply {
                            addProperty("type", "text")
                            addProperty("text", "Perform a web search for the query: " + request.query)
                        })
                    })
                })
            })
            add("tools", JsonArray().apply {
                add(JsonObject().apply {
                    addProperty("type", NATIVE_TOOL_TYPE)
                    addProperty("name", "web_search")
                    addProperty("max_uses", maxUses)
                })
            })
        }
        val response = client.postJson(
            url = baseUrl().trimEnd('/') + "/messages",
            headers = mapOf(
                "x-api-key" to key,
                "anthropic-version" to apiVersion,
                "content-type" to "application/json",
                "accept" to "application/json",
            ),
            body = payload.toString(),
            timeoutMs = SEARCH_TIMEOUT_MS,
            signal = signal,
        )
        if (response.status !in 200..299) throw nativeFailure(response.status)
        return DeepSeekNativeSearchProvider.mapNativeResponse(response.body)
    }

    companion object {
        const val PROVIDER_ID = "anthropic-native"
        const val MODEL_PROVIDER_ID = "anthropic"
        const val DEFAULT_BASE_URL = "https://api.anthropic.com/v1"
        const val DEFAULT_API_VERSION = "2023-06-01"
        const val DEFAULT_MAX_USES = 5
        const val DEFAULT_MAX_TOKENS = 4096
        const val NATIVE_TOOL_TYPE = "web_search_20250305"
        private const val SEARCH_TIMEOUT_MS = 60_000L

        private fun nativeFailure(status: Int): SearchException = when (status) {
            401, 403 -> SearchException(SearchException.Code.CREDENTIAL_MISSING, "Anthropic rejected the credential")
            429 -> SearchException(SearchException.Code.RATE_LIMITED, "Anthropic is rate limited", retryable = true)
            in 500..599 -> SearchException(SearchException.Code.PROVIDER_ERROR, "Anthropic is unavailable", retryable = true)
            else -> SearchException(SearchException.Code.PROVIDER_ERROR, "Anthropic search failed with HTTP " + status)
        }
    }
}
