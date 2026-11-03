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
 * The model vendor's own web search, executed as one auxiliary Messages call.
 *
 * DeepSeek exposes an Anthropic-compatible endpoint, so the server-side
 * `web_search_20250305` tool is available there. This is intentionally not a
 * main-chat request augmentation: one search is one extra model turn, which
 * keeps the main loop and message persistence unchanged.
 */
class DeepSeekNativeSearchProvider(
    private val apiKey: () -> String?,
    private val model: () -> String?,
    private val baseUrl: () -> String = { DEFAULT_BASE_URL },
    private val maxUses: Int = DEFAULT_MAX_USES,
    private val maxTokens: Int = DEFAULT_MAX_TOKENS,
    private val apiVersion: String = DEFAULT_API_VERSION,
    private val client: HttpJsonClient = HttpJsonClient(),
) : SearchProvider {
    override val id: String = PROVIDER_ID
    override val native: Boolean = true
    override val routeProviderId: String = DEEPSEEK_MODEL_PROVIDER_ID

    override fun available(): Boolean {
        val endpoint = runCatching { URI(baseUrl()) }.getOrNull() ?: return false
        return (endpoint.scheme == "https" || endpoint.scheme == "http") &&
            !endpoint.host.isNullOrBlank() &&
            apiKey()?.isNotBlank() == true &&
            maxUses > 0 &&
            maxTokens > 0
    }

    override fun ready(route: ModelRoute?): Boolean =
        route?.providerId == DEEPSEEK_MODEL_PROVIDER_ID && available()

    override fun search(request: SearchRequest, signal: CancellationSignal): SearchResult {
        val routeModel = request.route?.modelId?.takeIf { it.isNotBlank() }
            ?: model()?.takeIf { it.isNotBlank() }
            ?: throw SearchException(SearchException.Code.CREDENTIAL_MISSING, "DeepSeek model is not configured")
        val key = apiKey()?.takeIf { it.isNotBlank() }
            ?: throw SearchException(SearchException.Code.CREDENTIAL_MISSING, "DeepSeek credential is missing")
        if (!available()) {
            throw SearchException(SearchException.Code.PROVIDER_UNAVAILABLE, "DeepSeek native search is not configured")
        }
        val payload = JsonObject().apply {
            addProperty("model", routeModel)
            addProperty("max_tokens", maxTokens)
            add("messages", JsonArray().apply {
                add(JsonObject().apply {
                    addProperty("role", "user")
                    add("content", JsonArray().apply {
                        add(JsonObject().apply {
                            addProperty("type", "text")
                            addProperty("text", "Perform a web search for the query: ${request.query}")
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
            add("thinking", JsonObject().apply { addProperty("type", "disabled") })
        }
        val response = client.postJson(
            url = "${baseUrl().trimEnd('/')}/messages",
            headers = mapOf(
                "x-api-key" to key,
                "authorization" to "Bearer $key",
                "anthropic-version" to apiVersion,
                "content-type" to "application/json",
                "accept" to "application/json",
            ),
            body = payload.toString(),
            timeoutMs = SEARCH_TIMEOUT_MS,
            signal = signal,
        )
        if (response.status !in 200..299) throw nativeFailure(response.status)
        return mapNativeResponse(response.body)
    }

    companion object {
        const val PROVIDER_ID = "deepseek-native"
        const val DEEPSEEK_MODEL_PROVIDER_ID = "deepseek"
        const val DEFAULT_BASE_URL = "https://api.deepseek.com/anthropic/v1"
        const val DEFAULT_API_VERSION = "2023-06-01"
        const val DEFAULT_MAX_USES = 5
        const val DEFAULT_MAX_TOKENS = 4096
        const val NATIVE_TOOL_TYPE = "web_search_20250305"
        private const val SEARCH_TIMEOUT_MS = 60_000L

        private fun nativeFailure(status: Int): SearchException = when (status) {
            401, 403 -> SearchException(SearchException.Code.CREDENTIAL_MISSING, "DeepSeek rejected the credential")
            429 -> SearchException(SearchException.Code.RATE_LIMITED, "DeepSeek native search is rate limited", retryable = true)
            in 300..399 -> SearchException(SearchException.Code.PROVIDER_ERROR, "DeepSeek native search redirected unexpectedly")
            in 500..599 -> SearchException(SearchException.Code.PROVIDER_ERROR, "DeepSeek native search is unavailable", retryable = true)
            else -> SearchException(SearchException.Code.PROVIDER_ERROR, "DeepSeek native search failed with HTTP $status")
        }

        fun mapNativeResponse(body: String): SearchResult {
            val root = parseObject(body)
            val blocks = root.get("content") as? JsonArray
                ?: throw SearchException(SearchException.Code.INVALID_RESPONSE, "DeepSeek native search returned no content blocks")

            val cited = linkedMapOf<String, String>()
            for (element in blocks) {
                val block = element as? JsonObject ?: continue
                if (block.stringOrNull("type") != "text") continue
                val citations = block.get("citations") as? JsonArray ?: continue
                for (citationElement in citations) {
                    val citation = citationElement as? JsonObject ?: continue
                    val url = citation.stringOrNull("url") ?: continue
                    val text = citation.stringOrNull("cited_text") ?: continue
                    cited.putIfAbsent(url, text)
                }
            }

            val sources = mutableListOf<SearchSource>()
            val seen = mutableSetOf<String>()
            var sawToolResult = false
            for (element in blocks) {
                val block = element as? JsonObject ?: continue
                if (block.stringOrNull("type") != "web_search_tool_result") continue
                sawToolResult = true
                val results = block.get("content") as? JsonArray ?: continue
                for (resultElement in results) {
                    val item = resultElement as? JsonObject ?: continue
                    if (item.stringOrNull("type") != "web_search_result") continue
                    val url = item.stringOrNull("url") ?: continue
                    if (!url.startsWith("https://") && !url.startsWith("http://")) continue
                    if (!seen.add(url)) continue
                    sources += SearchSource(
                        url = url,
                        title = item.stringOrNull("title"),
                        snippet = cited[url],
                        publishedAt = item.stringOrNull("page_age"),
                    )
                }
            }
            if (!sawToolResult) {
                throw SearchException(
                    SearchException.Code.PROVIDER_ERROR,
                    "DeepSeek returned no web_search_tool_result; the model may not have triggered native search",
                )
            }
            return SearchResult(sources = sources, truncated = false)
        }
    }
}
