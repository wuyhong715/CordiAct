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

data class ExaConfig(
    val baseUrl: String = "https://api.exa.ai",
    val apiKey: () -> String? = { null },
    /** keyword | neural | auto | fast */
    val type: String = "auto",
    /** Request page text and highlights alongside results. */
    val includeContents: Boolean = true,
) {
    fun keyOrNull(): String? = apiKey()?.trim()?.takeIf { it.isNotEmpty() }

    fun available(): Boolean {
        val uri = runCatching { URI(baseUrl) }.getOrNull() ?: return false
        return (uri.scheme == "https" || uri.scheme == "http") && !uri.host.isNullOrBlank() &&
            type in setOf("keyword", "neural", "auto", "fast")
    }
}

/** Exa search (`POST /search`) with optional `contents.highlights`. */
class ExaSearchProvider(
    private val config: ExaConfig,
    private val client: HttpJsonClient = HttpJsonClient(),
) : SearchProvider {
    override val id: String = PROVIDER_ID
    override val billable: Boolean = true
    override val pricing: SearchPricing = SearchPricing(
        usdPerCall = if (config.includeContents) 0.008 else 0.007,
        note = "Search $7 per 1,000 requests (<=10 results); contents adds $1 per 1,000 pages per type (read 2026-11-03)",
    )

    override fun available(): Boolean = config.available() && config.keyOrNull() != null

    override fun ready(route: ModelRoute?): Boolean = available()

    override fun search(request: SearchRequest, signal: CancellationSignal): SearchResult {
        val key = config.keyOrNull()
            ?: throw SearchException(SearchException.Code.CREDENTIAL_MISSING, "Exa API key is missing")
        if (!config.available()) {
            throw SearchException(SearchException.Code.PROVIDER_UNAVAILABLE, "Exa is not configured")
        }
        val body = JsonObject().apply {
            addProperty("query", request.query)
            addProperty("numResults", request.maxResults.coerceIn(1, MAX_RESULTS))
            addProperty("type", config.type)
            if (config.includeContents) {
                add("contents", JsonObject().apply { addProperty("highlights", true) })
            }
        }
        val response = client.postJson(
            url = "${config.baseUrl.trimEnd('/')}/search",
            headers = mapOf(
                "x-api-key" to key,
                "content-type" to "application/json",
                "accept" to "application/json",
            ),
            body = body.toString(),
            timeoutMs = TIMEOUT_MS,
            signal = signal,
        )
        if (response.status !in 200..299) throw providerFailure(response.status)
        return mapResponse(response.body)
    }

    companion object {
        const val PROVIDER_ID = "exa"
        private const val MAX_RESULTS = 20
        private const val TIMEOUT_MS = 30_000L

        private fun providerFailure(status: Int): SearchException = when (status) {
            401, 403 -> SearchException(SearchException.Code.CREDENTIAL_MISSING, "Exa rejected the API key")
            429 -> SearchException(SearchException.Code.RATE_LIMITED, "Exa is rate limited", retryable = true)
            in 500..599 -> SearchException(SearchException.Code.PROVIDER_ERROR, "Exa is unavailable", retryable = true)
            else -> SearchException(SearchException.Code.PROVIDER_ERROR, "Exa failed with HTTP $status")
        }

        fun mapResponse(body: String): SearchResult {
            val root = parseObject(body)
            val results = root.get("results") as? JsonArray
                ?: throw SearchException(SearchException.Code.INVALID_RESPONSE, "Exa returned no results array")
            val sources = results.mapNotNull { element ->
                val item = element as? JsonObject ?: return@mapNotNull null
                val url = item.stringOrNull("url") ?: return@mapNotNull null
                if (!url.startsWith("https://") && !url.startsWith("http://")) return@mapNotNull null
                val highlights = (item.get("highlights") as? JsonArray)
                    ?.mapNotNull { it.takeIf { value -> value.isJsonPrimitive }?.asString }
                    .orEmpty()
                SearchSource(
                    url = url,
                    title = item.stringOrNull("title"),
                    snippet = highlights.firstOrNull(),
                    summary = highlights.drop(1).joinToString(" ").takeIf { it.isNotBlank() },
                    publishedAt = item.stringOrNull("publishedDate"),
                )
            }
            return SearchResult(sources = sources, truncated = false)
        }
    }
}
