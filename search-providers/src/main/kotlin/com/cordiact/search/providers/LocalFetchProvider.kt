package com.cordiact.search.providers

import com.cordiact.core.search.CancellationSignal
import com.cordiact.core.search.FetchProvider
import com.cordiact.core.search.FetchRequest
import com.cordiact.core.search.FetchResult
import com.cordiact.core.search.ModelRoute
import com.cordiact.core.search.SearchException
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URI
import java.net.URL

data class LocalFetchConfig(
    /**
     * Off by default. A device-side fetch is a network capability the operator has
     * to opt into; leaving it off also keeps provider auto-selection unambiguous,
     * because only the server-side fetch provider reports itself usable.
     */
    val enabled: () -> Boolean = { false },
    val maxResponseBytes: Int = 2_000_000,
    val maxRedirects: Int = 5,
    val timeoutMs: Long = 30_000,
)

/**
 * Fetches one URL from the device and returns readable text.
 *
 * Every hop is validated by [SsrfGuard] before it is requested, redirects are
 * followed manually so each one is checked, and only textual content types are
 * accepted. Scripts are never executed: the body is decoded and tags are
 * stripped, nothing more.
 */
class LocalFetchProvider(
    private val config: LocalFetchConfig,
    private val guard: SsrfGuard = SsrfGuard(),
    private val userAgent: String = "CordiAct/1.0 (Android)",
) : FetchProvider {
    override val id: String = PROVIDER_ID
    override val billable: Boolean = false

    override fun available(): Boolean = config.enabled()

    override fun ready(route: ModelRoute?): Boolean = available()

    override fun fetch(request: FetchRequest, signal: CancellationSignal): FetchResult {
        if (!available()) {
            throw SearchException(SearchException.Code.PROVIDER_UNAVAILABLE, "Local fetch is disabled")
        }
        var current = request.url
        var redirects = 0
        while (true) {
            if (signal.isCancelled()) throw cancelled()
            guard.requireFetchable(current)
            val response = requestOnce(current, signal)
            if (response.status in REDIRECT_CODES) {
                val location = response.location
                    ?: throw SearchException(
                        SearchException.Code.INVALID_URL,
                        "Redirect without a Location header",
                    )
                if (redirects >= config.maxRedirects) {
                    throw SearchException(SearchException.Code.INVALID_URL, "Too many redirects")
                }
                redirects++
                current = resolveRedirect(current, location)
                continue
            }
            if (response.status !in 200..299) {
                throw SearchException(
                    SearchException.Code.PROVIDER_ERROR,
                    "Fetch failed with HTTP " + response.status,
                )
            }
            val kind = classify(response.contentType)
                ?: throw SearchException(
                    SearchException.Code.INVALID_URL,
                    "Unsupported content type: " + (response.contentType ?: "unknown"),
                )
            val text = if (kind == ContentKind.HTML) htmlToText(response.body) else response.body
            return FetchResult(
                url = current,
                statusCode = response.status,
                content = text,
                truncated = response.truncated,
            )
        }
    }

    private class RawResponse(
        val status: Int,
        val location: String?,
        val contentType: String?,
        val body: String,
        val truncated: Boolean,
    )

    private fun requestOnce(url: String, signal: CancellationSignal): RawResponse {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            instanceFollowRedirects = false
            connectTimeout = minOf(5_000L, config.timeoutMs).toInt()
            readTimeout = config.timeoutMs.toInt()
            setRequestProperty("user-agent", userAgent)
            setRequestProperty("accept", "text/html,application/xhtml+xml,text/plain;q=0.9,*/*;q=0.1")
        }
        try {
            if (signal.isCancelled()) throw cancelled()
            val status = connection.responseCode
            val contentType = connection.contentType
            if (status in REDIRECT_CODES) {
                return RawResponse(status, connection.getHeaderField("Location"), contentType, "", false)
            }
            val stream: InputStream? = if (status in 200..299) connection.inputStream else connection.errorStream
            val read = stream?.use { it.readAtMost(config.maxResponseBytes) } ?: ReadResult(ByteArray(0), false)
            if (signal.isCancelled()) throw cancelled()
            return RawResponse(
                status = status,
                location = null,
                contentType = contentType,
                body = read.bytes.toString(Charsets.UTF_8),
                truncated = read.truncated,
            )
        } catch (timeout: SocketTimeoutException) {
            throw SearchException(SearchException.Code.TIMED_OUT, "Fetch timed out", retryable = true, cause = timeout)
        } catch (io: IOException) {
            if (signal.isCancelled()) throw cancelled()
            throw SearchException(SearchException.Code.PROVIDER_ERROR, "Fetch failed", retryable = true, cause = io)
        } finally {
            connection.disconnect()
        }
    }

    private class ReadResult(val bytes: ByteArray, val truncated: Boolean)

    private fun InputStream.readAtMost(limit: Int): ReadResult {
        val buffer = ByteArrayOutputStream(minOf(limit, 64 * 1024))
        val chunk = ByteArray(8 * 1024)
        var total = 0
        while (total < limit) {
            val read = read(chunk, 0, minOf(chunk.size, limit - total))
            if (read < 0) return ReadResult(buffer.toByteArray(), false)
            buffer.write(chunk, 0, read)
            total += read
        }
        return ReadResult(buffer.toByteArray(), true)
    }

    private enum class ContentKind { HTML, TEXT }

    private fun classify(contentType: String?): ContentKind? {
        val type = contentType?.substringBefore(';')?.trim()?.lowercase() ?: return ContentKind.TEXT
        return when (type) {
            "text/html", "application/xhtml+xml" -> ContentKind.HTML
            "text/plain", "text/markdown", "text/x-markdown",
            "application/json", "application/xml", "text/xml",
            "application/rss+xml", "application/atom+xml" -> ContentKind.TEXT
            else -> null
        }
    }

    private fun resolveRedirect(base: String, location: String): String =
        runCatching { URI(base).resolve(location).toString() }.getOrElse {
            throw SearchException(SearchException.Code.INVALID_URL, "Redirect target is invalid")
        }

    /** Drops scripts, styles and tags, then decodes the entities that matter for reading. */
    private fun htmlToText(html: String): String {
        var text = html
        text = SCRIPT_STYLE.replace(text, " ")
        text = BLOCK_TAGS.replace(text, "\n")
        text = TAG.replace(text, " ")
        text = text
            .replace("&nbsp;", " ")
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
        text = WHITESPACE.replace(text, " ")
        return text.lines()
            .map(String::trim)
            .filter(String::isNotEmpty)
            .joinToString("\n")
    }

    private fun cancelled() = SearchException(SearchException.Code.CANCELLED, "Fetch was cancelled")

    companion object {
        const val PROVIDER_ID = "local-fetch"
        private val REDIRECT_CODES = setOf(301, 302, 303, 307, 308)
        private val SCRIPT_STYLE = Regex(
            "<(script|style|noscript|template)\\b[^>]*>.*?</\\1>",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
        )
        private val BLOCK_TAGS = Regex(
            "</?(p|div|br|li|tr|h[1-6]|section|article|header|footer|blockquote)\\b[^>]*>",
            RegexOption.IGNORE_CASE,
        )
        private val TAG = Regex("<[^>]+>")
        private val WHITESPACE = Regex("[ \\t\\x0B\\f\\r]+")
    }
}
