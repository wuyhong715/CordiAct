package com.cordiact.app.security

import com.cordiact.core.vault.ControlledHttpExecutor
import com.cordiact.core.vault.ControlledUseUnavailableException
import com.cordiact.core.vault.CredentialAuthMode
import com.cordiact.core.vault.CredentialPayload
import com.cordiact.core.vault.CredentialType
import com.cordiact.core.vault.CredentialUseRequest
import com.cordiact.core.vault.CredentialUseResult
import java.net.HttpURLConnection
import java.net.URL
import javax.net.ssl.HttpsURLConnection

/**
 * P0 controlled-use adapter.
 *
 * The credential value is injected into a header at the last moment. Redirects are never followed,
 * the secret is never placed in the URL, and a response that echoes the exact secret is redacted
 * before it reaches the model. This is not a guarantee against transformed/encoded echoes.
 */
class HttpsCredentialExecutor(
    private val connectTimeoutMillis: Int = 10_000,
    private val readTimeoutMillis: Int = 30_000,
    private val maxResponseBytes: Int = 64 * 1024,
) : ControlledHttpExecutor {
    override fun execute(payload: CredentialPayload, request: CredentialUseRequest): CredentialUseResult {
        val url = URL(buildUrl(request))
        val connection = url.openConnection() as HttpsURLConnection
        connection.requestMethod = request.target.method
        connection.instanceFollowRedirects = false
        connection.connectTimeout = connectTimeoutMillis
        connection.readTimeout = readTimeoutMillis
        connection.setRequestProperty("Accept", "application/json, text/plain;q=0.9, */*;q=0.8")

        val secretForRedaction = applyAuth(connection, payload, request)
        val requestBody = request.body
        if (requestBody != null) {
            val body = requestBody.toByteArray(Charsets.UTF_8)
            require(body.size <= maxResponseBytes) { "request body too large" }
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            connection.outputStream.use { it.write(body) }
        }

        val status = connection.responseCode
        val stream = if (status in 200..299) connection.inputStream else connection.errorStream
        val bytes = stream?.let(::readLimited) ?: ByteArray(0)
        val redacted = redact(bytes, secretForRedaction)
        val summary = buildString {
            append("HTTP ").append(status)
            connection.contentType?.takeIf { it.isNotBlank() }?.let { append(" · ").append(it) }
            append(" · ").append(redacted.size).append(" bytes")
            if (status in 300..399) append(" · redirect not followed")
        }
        connection.disconnect()
        return CredentialUseResult(status, summary, redacted)
    }

    private fun buildUrl(request: CredentialUseRequest): String = buildString {
        append(request.target.origin())
        append(request.target.path)
        request.target.query?.takeIf { it.isNotBlank() }?.let { append('?').append(it) }
    }

    private fun applyAuth(
        connection: HttpsURLConnection,
        payload: CredentialPayload,
        request: CredentialUseRequest,
    ): String? = when (payload) {
        is CredentialPayload.Secret -> {
            val secret = payload.bytes.toString(Charsets.UTF_8)
            val mode = request.authMode
            val type = request.credentialType
            when {
                mode == CredentialAuthMode.BEARER ||
                    (mode == CredentialAuthMode.AUTO && type == CredentialType.BEARER_TOKEN) -> {
                    connection.setRequestProperty("Authorization", "Bearer $secret")
                }
                mode == CredentialAuthMode.HEADER ||
                    (mode == CredentialAuthMode.AUTO && type == CredentialType.API_KEY) -> {
                    val header = request.headerName ?: "X-API-Key"
                    require(header.matches(Regex("[A-Za-z0-9-]{1,100}"))) { "invalid auth header name" }
                    connection.setRequestProperty(header, secret)
                }
                mode == CredentialAuthMode.AUTO && type == CredentialType.GENERIC_SECRET -> {
                    val header = request.headerName
                        ?: throw ControlledUseUnavailableException("GENERIC_SECRET requires headerName")
                    require(header.matches(Regex("[A-Za-z0-9-]{1,100}"))) { "invalid auth header name" }
                    connection.setRequestProperty(header, secret)
                }
                else -> throw ControlledUseUnavailableException("unsupported credential auth mode")
            }
            secret
        }
        is CredentialPayload.CookieJar -> {
            val host = request.target.host.lowercase()
            val path = request.target.path
            val cookies = payload.cookies.filter { cookie ->
                cookie.secure && matchesDomain(host, cookie.domain, cookie.hostOnly) &&
                    path.startsWith(cookie.path)
            }
            if (cookies.isEmpty()) throw ControlledUseUnavailableException("NO_MATCHING_COOKIE")
            val header = cookies.joinToString("; ") { "${it.name}=${it.value}" }
            connection.setRequestProperty("Cookie", header)
            cookies.joinToString("|") { it.value }
        }
        is CredentialPayload.SshKey -> throw ControlledUseUnavailableException("SSH_KEY is not supported by the HTTPS adapter")
    }

    private fun matchesDomain(host: String, domain: String, hostOnly: Boolean): Boolean {
        val normalized = domain.lowercase().removePrefix(".")
        return if (hostOnly) host == normalized else host == normalized || host.endsWith(".$normalized")
    }

    private fun readLimited(stream: java.io.InputStream): ByteArray {
        val buffer = ByteArray(8 * 1024)
        val out = java.io.ByteArrayOutputStream()
        var total = 0
        while (true) {
            val read = stream.read(buffer)
            if (read < 0) break
            val allowed = minOf(read, maxResponseBytes - total)
            if (allowed <= 0) break
            out.write(buffer, 0, allowed)
            total += allowed
            if (total >= maxResponseBytes) break
        }
        return out.toByteArray()
    }

    private fun redact(bytes: ByteArray, secret: String?): ByteArray {
        if (secret.isNullOrEmpty()) return bytes
        val text = bytes.toString(Charsets.UTF_8)
        return text.replace(secret, "[REDACTED]").toByteArray(Charsets.UTF_8)
    }
}
