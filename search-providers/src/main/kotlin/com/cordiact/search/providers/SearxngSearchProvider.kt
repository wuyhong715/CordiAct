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

data class SearxngConfig(
    /** Base instance URL, for example `https://searx.example.org`. */
    val baseUrl: String = "",
    /** Optional token for an instance behind a proxy; SearXNG itself has no auth. */
    val token: () -> String? = { null },
    /** Comma-separated engine list; blank uses the instance default. */
    val engines: String? = null,
) {
    fun tokenOrNull(): String? = token()?.trim()?.takeIf { it.isNotEmpty() }

    fun available(): Boolean {
        val uri = runCatching { URI(baseUrl) }.getOrNull() ?: return false
        return (uri.scheme == "https" || uri.scheme == "http") && !uri.host.isNullOrBlank()
    }
}

/**
 * A self-hosted SearXNG instance (`GET /search?format=json`).
 *
 * Self-hosted instances need `search.formats: [json]` in `settings.yml`; without
 * it the endpoint answers HTML and this provider reports an invalid response.
 * Not billable, by definition.
 */
class SearxngSearchProvider(
    private val config: SearxngConfig,
    private val client: HttpJsonClient = HttpJsonClient(),
) : SearchProvider {
    override val id: String = PROVIDER_ID
    override val billable: Boolean = false
    override val pricing: SearchPricing = SearchPricing(note = "Self-hosted; no per-call cost")

    override fun available(): Boolean = config.available()

    override fun ready(route: ModelRoute?): Boolean = available()

    override fun search(request: SearchRequest, signal: CancellationSignal): SearchResult {
        if (!config.available()) {
            throw SearchException(SearchException.Code.PROVIDER_UNAVAILABLE, "SearXNG instance URL is not configured")
        }
        val url = buildString {
            append(config.baseUrl.trimEnd('/'))
            append("/search?q=").append(URLEncoder.encode(request.query, "UTF-8"))
            append("&format=json")
            config.engines?.takeIf { it.isNotBlank() }?.let {
                append("&engines=").append(URLEncoder.encode(it, "UTF-8"))
            }
        }
        val headers = buildMap {
            put("accept", "application/json")
            config.tokenOrNull()?.let { put("authorization", "Bearer $it") }
        }
        val response = client.getJson(url = url, headers = headers, timeoutMs = TIMEOUT_MS, signal = signal)
        if (response.status !in 200..299) throw providerFailure(response.status)
        return mapResponse(response.body)
    }

    companion object {
        const val PROVIDER_ID = "searxng"
        private const val TIMEOUT_MS = 30_000L

        private fun providerFailure(status: Int): SearchException = when (status) {
            401, 403 -> SearchException(SearchException.Code.CREDENTIAL_MISSING, "SearXNG rejected the request")
            429 -> SearchException(SearchException.Code.RATE_LIMITED, "SearXNG is rate limited", retryable = true)
            in 500..599 -> SearchException(SearchException.Code.PROVIDER_ERROR, "SearXNG is unavailable", retryable = true)
            else -> SearchException(SearchException.Code.PROVIDER_ERROR, "SearXNG failed with HTTP $status")
        }

        fun mapResponse(body: String): SearchResult {
            val root = parseObject(body)
            val results = root.get("results") as? JsonArray
                ?: throw SearchException(
                    SearchException.Code.INVALID_RESPONSE,
                    "SearXNG did not return JSON results; enable the json format in settings.yml",
                )
            val sources = results.mapNotNull { element ->
                val item = element as? JsonObject ?: return@mapNotNull null
                val url = item.stringOrNull("url") ?: return@mapNotNull null
                if (!url.startsWith("https://") && !url.startsWith("http://")) return@mapNotNull null
                SearchSource(
                    url = url,
                    title = item.stringOrNull("title"),
                    snippet = item.stringOrNull("content"),
                    publishedAt = item.stringOrNull("publishedDate"),
                )
            }
            return SearchResult(sources = sources, truncated = false)
        }
    }
}
