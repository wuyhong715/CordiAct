package com.cordiact.search.providers

import com.cordiact.core.search.CancellationSignal
import com.cordiact.core.search.SearchException
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URI
import java.net.URL

data class HttpJsonResponse(
    val status: Int,
    val body: String,
    val contentType: String?,
)

/**
 * Minimal blocking JSON over HTTP for provider adapters.
 *
 * Deliberately follows no redirects: a provider endpoint that redirects is a
 * configuration error, and following one could forward credentials to another
 * host. Response size is capped because search APIs should not stream megabytes.
 */
class HttpJsonClient(
    private val userAgent: String = "CordiAct/1.0 (Android)",
    private val maxResponseBytes: Int = 2_000_000,
) {
    fun postJson(
        url: String,
        headers: Map<String, String>,
        body: String,
        timeoutMs: Long,
        signal: CancellationSignal,
    ): HttpJsonResponse = request("POST", url, headers, body, timeoutMs, signal)

    fun getJson(
        url: String,
        headers: Map<String, String>,
        timeoutMs: Long,
        signal: CancellationSignal,
    ): HttpJsonResponse = request("GET", url, headers, null, timeoutMs, signal)

    private fun request(
        method: String,
        url: String,
        headers: Map<String, String>,
        body: String?,
        timeoutMs: Long,
        signal: CancellationSignal,
    ): HttpJsonResponse {
        val parsed = runCatching { URI(url) }.getOrNull()
            ?: throw SearchException(SearchException.Code.INVALID_URL, "Provider URL is invalid")
        if (parsed.scheme != "https" && parsed.scheme != "http") {
            throw SearchException(SearchException.Code.INVALID_URL, "Provider URL must be HTTP(S)")
        }
        if (parsed.userInfo != null) {
            throw SearchException(SearchException.Code.INVALID_URL, "Provider URL must not contain credentials")
        }
        if (signal.isCancelled()) throw cancelled()

        val total = timeoutMs.coerceAtLeast(1_000)
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            instanceFollowRedirects = false
            connectTimeout = minOf(5_000L, total).toInt()
            readTimeout = total.toInt()
            setRequestProperty("user-agent", userAgent)
            for ((name, value) in headers) setRequestProperty(name, value)
            if (body != null) doOutput = true
        }
        try {
            if (body != null) {
                connection.outputStream.use { stream ->
                    stream.write(body.toByteArray(Charsets.UTF_8))
                }
            }
            if (signal.isCancelled()) throw cancelled()
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val bytes = stream?.use { it.readAtMost(maxResponseBytes) } ?: ByteArray(0)
            if (signal.isCancelled()) throw cancelled()
            return HttpJsonResponse(
                status = status,
                body = bytes.toString(Charsets.UTF_8),
                contentType = connection.contentType,
            )
        } catch (timeout: SocketTimeoutException) {
            throw SearchException(SearchException.Code.TIMED_OUT, "Provider request timed out", retryable = true, cause = timeout)
        } catch (io: IOException) {
            if (signal.isCancelled()) throw cancelled()
            throw SearchException(SearchException.Code.PROVIDER_ERROR, "Provider request failed", retryable = true, cause = io)
        } finally {
            connection.disconnect()
        }
    }

    private fun InputStream.readAtMost(limit: Int): ByteArray {
        val buffer = ByteArrayOutputStream(minOf(limit, 64 * 1024))
        val chunk = ByteArray(8 * 1024)
        var total = 0
        while (total < limit) {
            val read = read(chunk, 0, minOf(chunk.size, limit - total))
            if (read < 0) break
            buffer.write(chunk, 0, read)
            total += read
        }
        return buffer.toByteArray()
    }

    private fun cancelled() = SearchException(SearchException.Code.CANCELLED, "Search was cancelled")
}
