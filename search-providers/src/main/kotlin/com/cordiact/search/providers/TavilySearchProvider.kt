package com.cordiact.search.providers

import com.cordiact.core.search.CancellationSignal
import com.cordiact.core.search.ModelRoute
import com.cordiact.core.search.SearchException
import com.cordiact.core.search.SearchPricing
import com.cordiact.core.search.SearchProvider
import com.cordiact.core.search.SearchRequest
import com.cordiact.core.search.SearchResult
import com.cordiact.core.search.SearchSource
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import java.net.URI

data class TavilyConfig(
    val baseUrl: String = "https://api.tavily.com",
    val apiKey: () -> String? = { null },
    /** "basic" costs one credit, "advanced" costs two. */
    val searchDepth: String = "basic",
    val includeAnswer: Boolean = true,
) {
    fun keyOrNull(): String? = apiKey()?.trim()?.takeIf { it.isNotEmpty() }

    fun available(): Boolean {
        val uri = runCatching { URI(baseUrl) }.getOrNull() ?: return false
        return (uri.scheme == "https" || uri.scheme == "http") && !uri.host.isNullOrBlank() &&
            searchDepth in setOf("basic", "advanced", "fast", "ultra-fast")
    }
}

/**
 * Tavily search (`POST /search`).
 *
 * Tavily is the one provider here that returns a model-ready answer alongside
 * sources, so `content` is populated when `include_answer` is on.
 */
class TavilySearchProvider(
    private val config: TavilyConfig,
    private val client: HttpJsonClient = HttpJsonClient(),
) : SearchProvider {
    override val id: String = PROVIDER_ID
    override val billable: Boolean = true
    override val pricing: SearchPricing = SearchPricing(
        usdPerCall = if (config.searchDepth == "advanced") 0.016 else 0.008,
        note = "1 credit per basic search, 2 per advanced; $0.008/credit pay-as-you-go (read 2026-11-03)",
    )

    override fun available(): Boolean = config.available() && config.keyOrNull() != null

    override fun ready(route: ModelRoute?): Boolean = available()

    override fun search(request: SearchRequest, signal: CancellationSignal): SearchResult {
        val key = config.keyOrNull()
            ?: throw SearchException(SearchException.Code.CREDENTIAL_MISSING, "Tavily API key is missing")
        if (!config.available()) {
            throw SearchException(SearchException.Code.PROVIDER_UNAVAILABLE, "Tavily is not configured")
        }
        val body = JsonObject().apply {
            addProperty("query", request.query)
            addProperty("max_results", request.maxResults.coerceIn(1, MAX_RESULTS))
            addProperty("search_depth", config.searchDepth)
            addProperty("include_answer", config.includeAnswer)
            addProperty("include_raw_content", false)
        }
        val response = client.postJson(
            url = "${config.baseUrl.trimEnd('/')}/search",
            headers = mapOf(
                "authorization" to "Bearer $key",
                "content-type" to "application/json",
                "accept" to "application/json",
            ),
            body = body.toString(),
            timeoutMs = TIMEOUT_MS,
            signal = signal,
        )
        if (response.status !in 200..299) throw providerFailure(response.status)
        return mapResponse(response.body, config.includeAnswer)
    }

    companion object {
        const val PROVIDER_ID = "tavily"
        private const val MAX_RESULTS = 20
        private const val TIMEOUT_MS = 30_000L

        private fun providerFailure(status: Int): SearchException = when (status) {
            401, 403 -> SearchException(SearchException.Code.CREDENTIAL_MISSING, "Tavily rejected the API key")
            429 -> SearchException(SearchException.Code.RATE_LIMITED, "Tavily is rate limited", retryable = true)
            in 500..599 -> SearchException(SearchException.Code.PROVIDER_ERROR, "Tavily is unavailable", retryable = true)
            else -> SearchException(SearchException.Code.PROVIDER_ERROR, "Tavily failed with HTTP $status")
        }

        fun mapResponse(body: String, includeAnswer: Boolean): SearchResult {
            val root = parseObject(body)
            val results = root.get("results") as? JsonArray
                ?: throw SearchException(SearchException.Code.INVALID_RESPONSE, "Tavily returned no results array")
            val sources = results.mapNotNull { element ->
                val item = element as? JsonObject ?: return@mapNotNull null
                val url = item.stringOrNull("url") ?: return@mapNotNull null
                if (!url.startsWith("https://") && !url.startsWith("http://")) return@mapNotNull null
                SearchSource(
                    url = url,
                    title = item.stringOrNull("title"),
                    snippet = item.stringOrNull("content"),
                    publishedAt = item.stringOrNull("published_date"),
                )
            }
            val answer = if (includeAnswer) root.stringOrNull("answer") else null
            return SearchResult(content = answer, sources = sources, truncated = false)
        }
    }
}
