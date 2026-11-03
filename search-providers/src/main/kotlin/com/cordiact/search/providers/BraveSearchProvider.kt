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
import java.net.URLEncoder

data class BraveConfig(
    val baseUrl: String = "https://api.search.brave.com/res/v1/web/search",
    val apiKey: () -> String? = { null },
    /** ISO 3166-1 alpha-2, for example "CN" or "US". */
    val country: String? = null,
    val searchLang: String? = null,
) {
    fun keyOrNull(): String? = apiKey()?.trim()?.takeIf { it.isNotEmpty() }

    fun available(): Boolean {
        val uri = runCatching { URI(baseUrl) }.getOrNull() ?: return false
        return (uri.scheme == "https" || uri.scheme == "http") && !uri.host.isNullOrBlank()
    }
}

/**
 * Brave Web Search (`GET /res/v1/web/search`).
 *
 * A query parameter API rather than JSON POST, so the URL is built here and the
 * key travels in `X-Subscription-Token`.
 */
class BraveSearchProvider(
    private val config: BraveConfig,
    private val client: HttpJsonClient = HttpJsonClient(),
) : SearchProvider {
    override val id: String = PROVIDER_ID
    override val billable: Boolean = true
    override val pricing: SearchPricing = SearchPricing(
        usdPerCall = 0.005,
        note = "Paid plans start around $5 per 1,000 queries; the free tier is a monthly credit (read 2026-11-03)",
    )

    override fun available(): Boolean = config.available() && config.keyOrNull() != null

    override fun ready(route: ModelRoute?): Boolean = available()

    override fun search(request: SearchRequest, signal: CancellationSignal): SearchResult {
        val key = config.keyOrNull()
            ?: throw SearchException(SearchException.Code.CREDENTIAL_MISSING, "Brave API key is missing")
        if (!config.available()) {
            throw SearchException(SearchException.Code.PROVIDER_UNAVAILABLE, "Brave is not configured")
        }
        val url = buildString {
            append(config.baseUrl)
            append(if (config.baseUrl.contains('?')) '&' else '?')
            append("q=").append(URLEncoder.encode(request.query, "UTF-8"))
            append("&count=").append(request.maxResults.coerceIn(1, MAX_RESULTS))
            append("&result_filter=web")
            config.country?.let { append("&country=").append(URLEncoder.encode(it, "UTF-8")) }
            config.searchLang?.let { append("&search_lang=").append(URLEncoder.encode(it, "UTF-8")) }
        }
        val response = client.getJson(
            url = url,
            headers = mapOf(
                "x-subscription-token" to key,
                "accept" to "application/json",
            ),
            timeoutMs = TIMEOUT_MS,
            signal = signal,
        )
        if (response.status !in 200..299) throw providerFailure(response.status)
        return mapResponse(response.body)
    }

    companion object {
        const val PROVIDER_ID = "brave"
        private const val MAX_RESULTS = 20
        private const val TIMEOUT_MS = 30_000L

        private fun providerFailure(status: Int): SearchException = when (status) {
            401, 403 -> SearchException(SearchException.Code.CREDENTIAL_MISSING, "Brave rejected the subscription token")
            429 -> SearchException(SearchException.Code.RATE_LIMITED, "Brave is rate limited", retryable = true)
            in 500..599 -> SearchException(SearchException.Code.PROVIDER_ERROR, "Brave is unavailable", retryable = true)
            else -> SearchException(SearchException.Code.PROVIDER_ERROR, "Brave failed with HTTP $status")
        }

        fun mapResponse(body: String): SearchResult {
            val root = parseObject(body)
            val web = root.get("web") as? JsonObject ?: return SearchResult(sources = emptyList())
            val results = web.get("results") as? JsonArray ?: return SearchResult(sources = emptyList())
            val sources = results.mapNotNull { element ->
                val item = element as? JsonObject ?: return@mapNotNull null
                val url = item.stringOrNull("url") ?: return@mapNotNull null
                if (!url.startsWith("https://") && !url.startsWith("http://")) return@mapNotNull null
                val extra = (item.get("extra_snippets") as? JsonArray)
                    ?.mapNotNull { it.takeIf { value -> value.isJsonPrimitive }?.asString }
                    .orEmpty()
                SearchSource(
                    url = url,
                    title = item.stringOrNull("title"),
                    snippet = item.stringOrNull("description"),
                    summary = extra.joinToString(" ").takeIf { it.isNotBlank() },
                    publishedAt = item.stringOrNull("age") ?: item.stringOrNull("page_age"),
                )
            }
            return SearchResult(sources = sources, truncated = false)
        }
    }
}
