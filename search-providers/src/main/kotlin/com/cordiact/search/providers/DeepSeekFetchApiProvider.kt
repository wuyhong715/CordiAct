package com.cordiact.search.providers

import com.cordiact.core.search.CancellationSignal
import com.cordiact.core.search.FetchProvider
import com.cordiact.core.search.FetchRequest
import com.cordiact.core.search.FetchResult
import com.cordiact.core.search.ModelRoute
import com.cordiact.core.search.SearchException
import com.google.gson.JsonObject

/**
 * DeepSeek's server-side Fetch API (`/v1/tools/fetch`).
 *
 * The device never connects to the target URL, so this provider cannot be used
 * to probe the local network. It still rejects non-HTTP(S) URLs.
 */
class DeepSeekFetchApiProvider(
    private val config: DeepSeekWebConfig,
    private val client: HttpJsonClient = HttpJsonClient(),
) : FetchProvider {
    override val id: String = PROVIDER_ID

    override fun available(): Boolean = config.available() && config.keyOrNull() != null

    override fun ready(route: ModelRoute?): Boolean = available()

    override fun fetch(request: FetchRequest, signal: CancellationSignal): FetchResult {
        requireHttpUrl(request.url)
        val key = config.keyOrNull()
            ?: throw SearchException(SearchException.Code.CREDENTIAL_MISSING, "DeepSeek fetch credential is missing")
        if (!config.available()) {
            throw SearchException(SearchException.Code.PROVIDER_UNAVAILABLE, "DeepSeek fetch is not configured")
        }
        val body = JsonObject().apply { addProperty("url", request.url) }
        val response = client.postJson(
            url = "${config.baseUrl.trimEnd('/')}/fetch",
            headers = mapOf(
                "authorization" to "Bearer $key",
                "content-type" to "application/json",
                "accept" to "application/json",
            ),
            body = body.toString(),
            timeoutMs = FETCH_TIMEOUT_MS,
            signal = signal,
        )
        if (response.status !in 200..299) throw deepSeekFailure(response.status)
        val root = parseObject(response.body)
        val content = root.stringOrNull("content")
            ?: throw SearchException(SearchException.Code.INVALID_RESPONSE, "DeepSeek fetch returned no content")
        return FetchResult(
            url = request.url,
            statusCode = response.status,
            content = content,
            truncated = false,
        )
    }

    companion object {
        const val PROVIDER_ID = "deepseek-fetch"
        private const val FETCH_TIMEOUT_MS = 30_000L

        fun requireHttpUrl(url: String) {
            val uri = runCatching { java.net.URI(url) }.getOrNull()
                ?: throw SearchException(SearchException.Code.INVALID_URL, "URL is invalid")
            if ((uri.scheme != "https" && uri.scheme != "http") || uri.host.isNullOrBlank() || uri.userInfo != null) {
                throw SearchException(SearchException.Code.INVALID_URL, "URL must be an HTTP(S) URL without credentials")
            }
        }
    }
}
