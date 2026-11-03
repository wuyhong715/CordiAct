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

/** DeepSeek's standalone Search API (`/v1/tools/search`). */
class DeepSeekSearchApiProvider(
    private val config: DeepSeekWebConfig,
    private val client: HttpJsonClient = HttpJsonClient(),
) : SearchProvider {
    override val id: String = PROVIDER_ID

    override fun available(): Boolean = config.available() && config.keyOrNull() != null

    override fun ready(route: ModelRoute?): Boolean = available()

    override fun search(request: SearchRequest, signal: CancellationSignal): SearchResult {
        val key = config.keyOrNull()
            ?: throw SearchException(SearchException.Code.CREDENTIAL_MISSING, "DeepSeek search credential is missing")
        if (!config.available()) {
            throw SearchException(SearchException.Code.PROVIDER_UNAVAILABLE, "DeepSeek search is not configured")
        }
        val body = JsonObject().apply {
            addProperty("query", request.query)
            addProperty("count", request.maxResults.coerceIn(1, MAX_RESULTS))
            addProperty("need_content", false)
            request.oriQuestion?.take(MAX_ORI_QUESTION)?.takeIf { it.isNotBlank() }?.let { addProperty("ori_question", it) }
        }
        val response = client.postJson(
            url = "${config.baseUrl.trimEnd('/')}/search",
            headers = mapOf(
                "authorization" to "Bearer $key",
                "content-type" to "application/json",
                "accept" to "application/json",
            ),
            body = body.toString(),
            timeoutMs = SEARCH_TIMEOUT_MS,
            signal = signal,
        )
        if (response.status !in 200..299) throw deepSeekFailure(response.status)
        return mapSearchResponse(response.body)
    }

    companion object {
        const val PROVIDER_ID = "deepseek-search"
        private const val MAX_RESULTS = 50
        private const val MAX_ORI_QUESTION = 128
        private const val SEARCH_TIMEOUT_MS = 30_000L

        fun mapSearchResponse(body: String): SearchResult {
            val root = parseObject(body)
            val results = root.get("results") as? JsonArray
                ?: throw SearchException(SearchException.Code.INVALID_RESPONSE, "DeepSeek search returned no results array")
            val sources = results.mapNotNull { element ->
                val item = element as? JsonObject ?: return@mapNotNull null
                val url = item.stringOrNull("url") ?: return@mapNotNull null
                if (!url.startsWith("https://") && !url.startsWith("http://")) return@mapNotNull null
                val title = item.stringOrNull("title")
                val snippet = item.stringOrNull("snippet")
                SearchSource(
                    url = url,
                    title = title,
                    snippet = snippet,
                    summary = item.stringOrNull("summary"),
                    publishedAt = item.stringOrNull("date"),
                )
            }
            return SearchResult(sources = sources, truncated = false)
        }
    }
}
