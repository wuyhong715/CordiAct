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

data class BochaConfig(
    val baseUrl: String = "https://api.bochaai.com/v1/web-search",
    val apiKey: () -> String? = { null },
    /** true asks Bocha for its own summary of each result. */
    val withSummary: Boolean = true,
    /** day | week | month | year | noLimit */
    val freshness: String = "noLimit",
) {
    fun keyOrNull(): String? = apiKey()?.trim()?.takeIf { it.isNotEmpty() }

    fun available(): Boolean {
        val uri = runCatching { URI(baseUrl) }.getOrNull() ?: return false
        return (uri.scheme == "https" || uri.scheme == "http") && !uri.host.isNullOrBlank()
    }
}

/** Bocha Web Search (`POST /v1/web-search`), the usual choice for Simplified Chinese results. */
class BochaSearchProvider(
    private val config: BochaConfig,
    private val client: HttpJsonClient = HttpJsonClient(),
) : SearchProvider {
    override val id: String = PROVIDER_ID
    override val billable: Boolean = true
    override val pricing: SearchPricing = SearchPricing(
        usdPerCall = 0.005,
        note = "Billed per successful request; second-hand pricing around CNY 0.03-0.036 per call (unverified, 2026-11-03)",
    )

    override fun available(): Boolean = config.available() && config.keyOrNull() != null

    override fun ready(route: ModelRoute?): Boolean = available()

    override fun search(request: SearchRequest, signal: CancellationSignal): SearchResult {
        val key = config.keyOrNull()
            ?: throw SearchException(SearchException.Code.CREDENTIAL_MISSING, "Bocha API key is missing")
        if (!config.available()) {
            throw SearchException(SearchException.Code.PROVIDER_UNAVAILABLE, "Bocha is not configured")
        }
        val body = JsonObject().apply {
            addProperty("query", request.query)
            addProperty("count", request.maxResults.coerceIn(1, MAX_RESULTS))
            addProperty("summary", config.withSummary)
            addProperty("freshness", config.freshness)
        }
        val response = client.postJson(
            url = config.baseUrl,
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
        return mapResponse(response.body)
    }

    companion object {
        const val PROVIDER_ID = "bocha"
        private const val MAX_RESULTS = 50
        private const val TIMEOUT_MS = 30_000L

        private fun providerFailure(status: Int): SearchException = when (status) {
            401, 403 -> SearchException(SearchException.Code.CREDENTIAL_MISSING, "Bocha rejected the API key")
            429 -> SearchException(SearchException.Code.RATE_LIMITED, "Bocha is rate limited", retryable = true)
            in 500..599 -> SearchException(SearchException.Code.PROVIDER_ERROR, "Bocha is unavailable", retryable = true)
            else -> SearchException(SearchException.Code.PROVIDER_ERROR, "Bocha failed with HTTP $status")
        }

        fun mapResponse(body: String): SearchResult {
            val root = parseObject(body)
            val payload = root.get("data") as? JsonObject ?: root
            val webPages = payload.get("webPages") as? JsonObject
                ?: throw SearchException(SearchException.Code.INVALID_RESPONSE, "Bocha returned no webPages object")
            val values = webPages.get("value") as? JsonArray
                ?: throw SearchException(SearchException.Code.INVALID_RESPONSE, "Bocha returned no webPages.value array")
            val sources = values.mapNotNull { element ->
                val item = element as? JsonObject ?: return@mapNotNull null
                val url = item.stringOrNull("url") ?: return@mapNotNull null
                if (!url.startsWith("https://") && !url.startsWith("http://")) return@mapNotNull null
                SearchSource(
                    url = url,
                    title = item.stringOrNull("name"),
                    snippet = item.stringOrNull("snippet"),
                    summary = item.stringOrNull("summary"),
                    publishedAt = item.stringOrNull("datePublished"),
                )
            }
            return SearchResult(sources = sources, truncated = false)
        }
    }
}
