package com.cordiact.search.providers

import com.cordiact.core.search.CancellationSignal
import com.cordiact.core.search.ModelRoute
import com.cordiact.core.search.SearchException
import com.cordiact.core.search.SearchProvider
import com.cordiact.core.search.SearchRequest
import com.cordiact.core.search.SearchResult
import com.cordiact.core.search.SearchSource
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import java.net.URI

/**
 * OpenAI's Responses API `web_search` tool, run as one auxiliary response call.
 *
 * Responses returns the search call and the citations separately: `url_citation`
 * annotations carry the URL and title, and the annotation's text span is the only
 * excerpt the API gives back, so that becomes the snippet.
 */
class OpenAiNativeSearchProvider(
    private val apiKey: () -> String?,
    private val baseUrl: () -> String = { DEFAULT_BASE_URL },
    private val model: () -> String? = { DEFAULT_MODEL },
    private val searchContextSize: String = "medium",
    private val client: HttpJsonClient = HttpJsonClient(),
) : SearchProvider {
    override val id: String = PROVIDER_ID
    override val native: Boolean = true
    override val routeProviderId: String = MODEL_PROVIDER_ID

    /** One native search is one billable model turn, so it counts against the call budget. */
    override val billable: Boolean = true

    override fun available(): Boolean {
        val endpoint = runCatching { URI(baseUrl()) }.getOrNull() ?: return false
        return (endpoint.scheme == "https" || endpoint.scheme == "http") &&
            !endpoint.host.isNullOrBlank() &&
            apiKey()?.isNotBlank() == true &&
            searchContextSize in setOf("low", "medium", "high")
    }

    override fun ready(route: ModelRoute?): Boolean =
        route?.providerId == MODEL_PROVIDER_ID && available()

    override fun search(request: SearchRequest, signal: CancellationSignal): SearchResult {
        val key = apiKey()?.takeIf { it.isNotBlank() }
            ?: throw SearchException(SearchException.Code.CREDENTIAL_MISSING, "OpenAI credential is missing")
        if (!available()) {
            throw SearchException(SearchException.Code.PROVIDER_UNAVAILABLE, "OpenAI native search is not configured")
        }
        val modelId = request.route?.modelId?.takeIf { it.isNotBlank() }
            ?: model()?.takeIf { it.isNotBlank() }
            ?: throw SearchException(SearchException.Code.UNSUPPORTED, "OpenAI model is unknown for this search")
        val payload = JsonObject().apply {
            addProperty("model", modelId)
            addProperty("input", "Perform a web search for the query: " + request.query)
            add("tools", JsonArray().apply {
                add(JsonObject().apply {
                    addProperty("type", NATIVE_TOOL_TYPE)
                    addProperty("search_context_size", searchContextSize)
                })
            })
            addProperty("tool_choice", "auto")
        }
        val response = client.postJson(
            url = baseUrl(),
            headers = mapOf(
                "authorization" to "Bearer " + key,
                "content-type" to "application/json",
                "accept" to "application/json",
            ),
            body = payload.toString(),
            timeoutMs = SEARCH_TIMEOUT_MS,
            signal = signal,
        )
        if (response.status !in 200..299) throw nativeFailure(response.status)
        return mapResponse(response.body)
    }

    companion object {
        const val PROVIDER_ID = "openai-native"
        const val MODEL_PROVIDER_ID = "openai"
        const val DEFAULT_BASE_URL = "https://api.openai.com/v1/responses"
        const val DEFAULT_MODEL = "gpt-4o"
        const val NATIVE_TOOL_TYPE = "web_search"
        private const val SEARCH_TIMEOUT_MS = 60_000L

        private fun nativeFailure(status: Int): SearchException = when (status) {
            401, 403 -> SearchException(SearchException.Code.CREDENTIAL_MISSING, "OpenAI rejected the credential")
            429 -> SearchException(SearchException.Code.RATE_LIMITED, "OpenAI is rate limited", retryable = true)
            in 500..599 -> SearchException(SearchException.Code.PROVIDER_ERROR, "OpenAI is unavailable", retryable = true)
            else -> SearchException(SearchException.Code.PROVIDER_ERROR, "OpenAI search failed with HTTP " + status)
        }

        /** Reads `output[].content[].annotations[]` where `type == url_citation`. */
        fun mapResponse(body: String): SearchResult {
            val root = parseObject(body)
            val output = root.get("output") as? JsonArray
                ?: throw SearchException(SearchException.Code.INVALID_RESPONSE, "OpenAI returned no output array")
            val sources = linkedMapOf<String, SearchSource>()
            var answer: String? = null
            for (element in output) {
                val item = element as? JsonObject ?: continue
                val content = item.get("content") as? JsonArray ?: continue
                for (contentElement in content) {
                    val block = contentElement as? JsonObject ?: continue
                    if (block.stringOrNull("type") == "output_text") {
                        block.stringOrNull("text")?.takeIf { it.isNotBlank() }?.let { answer = it }
                    }
                    val annotations = block.get("annotations") as? JsonArray ?: continue
                    for (annotationElement in annotations) {
                        val annotation = annotationElement as? JsonObject ?: continue
                        if (annotation.stringOrNull("type") != "url_citation") continue
                        val url = annotation.stringOrNull("url") ?: continue
                        if (!url.startsWith("https://") && !url.startsWith("http://")) continue
                        sources.putIfAbsent(
                            url,
                            SearchSource(
                                url = url,
                                title = annotation.stringOrNull("title"),
                                snippet = annotation.stringOrNull("text"),
                            ),
                        )
                    }
                }
            }
            return SearchResult(content = answer, sources = sources.values.toList(), truncated = false)
        }
    }
}
