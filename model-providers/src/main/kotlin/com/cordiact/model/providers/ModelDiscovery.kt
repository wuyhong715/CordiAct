package com.cordiact.model.providers

import com.google.gson.JsonParser
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder

/** Provider-first input; URL may be a base URL or an existing generation endpoint. */
data class ModelProviderConnection(val providerId: String, val displayName: String, val protocol: ModelProtocol, val url: String) {
    fun config(models: List<String>): ModelProviderConfig = ModelProviderConfig(providerId, displayName, protocol, generationEndpoint(), models)

    fun generationEndpoint(): String = base().let { base -> when (protocol) {
        ModelProtocol.OPENAI_CHAT_COMPLETIONS -> "$base/chat/completions"
        ModelProtocol.OPENAI_RESPONSES -> "$base/responses"
        ModelProtocol.ANTHROPIC_MESSAGES -> "$base/messages"
        ModelProtocol.GEMINI_GENERATE_CONTENT -> "$base/models/{model}:streamGenerateContent?alt=sse"
    } }

    fun modelsEndpoint(): String = "${base()}/models"

    private fun base(): String = try {
        normalizedBase()
    } catch (_: Exception) {
        throw IllegalArgumentException("MODEL_DISCOVERY_INVALID_CONNECTION")
    }

    private fun normalizedBase(): String {
        val raw = url.trim()
        // Reuse the backend's strict HTTPS, credential-query and userinfo validation.
        ModelProviderConfig(providerId, displayName, ModelProtocol.OPENAI_CHAT_COMPLETIONS, raw, listOf("validation-only"))
        val uri = URI(raw.replace("{model}", "model"))
        require(uri.rawQuery == null || protocol == ModelProtocol.GEMINI_GENERATE_CONTENT && uri.rawQuery == "alt=sse") {
            "Model URL must not contain query parameters"
        }
        var path = uri.rawPath.orEmpty().trimEnd('/')
        path = when (protocol) {
            ModelProtocol.OPENAI_CHAT_COMPLETIONS -> path.removeSuffix("/chat/completions")
            ModelProtocol.OPENAI_RESPONSES -> path.removeSuffix("/responses")
            ModelProtocol.ANTHROPIC_MESSAGES -> path.removeSuffix("/messages")
            ModelProtocol.GEMINI_GENERATE_CONTENT -> if (path.contains("/models/")) path.substringBeforeLast("/models/") else path.removeSuffix("/models")
        }.removeSuffix("/models")
        if (path.isEmpty()) path = if (protocol == ModelProtocol.GEMINI_GENERATE_CONTENT) "/v1beta" else "/v1"
        return "${uri.scheme}://${uri.rawAuthority}$path"
    }
}

fun interface ModelDiscoveryService {
    fun discover(connection: ModelProviderConnection, apiKey: String): List<String>
}

/** Redirects are forbidden: an API key must never follow an endpoint to a different host. */
class HttpModelDiscoveryService : ModelDiscoveryService {
    override fun discover(connection: ModelProviderConnection, apiKey: String): List<String> =
        discoverAt(connection.modelsEndpoint(), connection.protocol, apiKey)

    internal fun discoverAt(endpoint: String, protocol: ModelProtocol, apiKey: String): List<String> {
        require(apiKey.isNotBlank() && apiKey.length <= 16 * 1024 && apiKey.none { it == '\r' || it == '\n' }) { "Invalid API key" }
        val deadline = System.nanoTime() + 60_000_000_000L
        val models = linkedSetOf<String>()
        val cursors = mutableSetOf<String>()
        var cursor: String? = null
        repeat(MAX_PAGES) {
            if (Thread.currentThread().isInterrupted) throw InterruptedException()
            val remainingMillis = ((deadline - System.nanoTime()) / 1_000_000L).coerceAtMost(30_000).toInt()
            check(remainingMillis > 0) { "MODEL_DISCOVERY_TIMEOUT" }
            val parameter = if (protocol == ModelProtocol.GEMINI_GENERATE_CONTENT) "pageToken" else "after_id"
            val address = endpoint + (cursor?.let { "?$parameter=${URLEncoder.encode(it, "UTF-8")}" } ?: "")
            val http = URI(address).toURL().openConnection() as HttpURLConnection
            try {
                http.instanceFollowRedirects = false
                http.connectTimeout = minOf(15_000, remainingMillis)
                http.readTimeout = remainingMillis
                http.setRequestProperty("Accept", "application/json")
                when (protocol) {
                    ModelProtocol.ANTHROPIC_MESSAGES -> {
                        http.setRequestProperty("x-api-key", apiKey)
                        http.setRequestProperty("anthropic-version", "2023-06-01")
                    }
                    ModelProtocol.GEMINI_GENERATE_CONTENT -> http.setRequestProperty("x-goog-api-key", apiKey)
                    else -> http.setRequestProperty("Authorization", "Bearer $apiKey")
                }
                check(http.responseCode == 200) { "MODEL_DISCOVERY_HTTP_${http.responseCode}" }
                val bytes = http.inputStream.use { input ->
                    val output = java.io.ByteArrayOutputStream()
                    val buffer = ByteArray(8192)
                    while (true) {
                        check(System.nanoTime() < deadline) { "MODEL_DISCOVERY_TIMEOUT" }
                        val count = input.read(buffer)
                        if (count < 0) break
                        check(output.size() + count <= MAX_RESPONSE_BYTES) { "MODEL_DISCOVERY_RESPONSE_TOO_LARGE" }
                        output.write(buffer, 0, count)
                    }
                    output.toByteArray()
                }
                check(bytes.size <= MAX_RESPONSE_BYTES) { "MODEL_DISCOVERY_RESPONSE_TOO_LARGE" }
                val root = try { JsonParser.parseString(bytes.toString(Charsets.UTF_8)).asJsonObject }
                    catch (_: Exception) { error("MODEL_DISCOVERY_INVALID_JSON") }
                val data = root.get(if (protocol == ModelProtocol.GEMINI_GENERATE_CONTENT) "models" else "data")
                check(data != null && data.isJsonArray) { "MODEL_DISCOVERY_INVALID_LIST" }
                data.asJsonArray.forEach { element ->
                    check(element.isJsonObject) { "MODEL_DISCOVERY_INVALID_MODEL" }
                    val item = element.asJsonObject
                    if (protocol == ModelProtocol.GEMINI_GENERATE_CONTENT) {
                        val methods = item.getAsJsonArray("supportedGenerationMethods")
                        if (methods == null || methods.none { it.asString == "generateContent" || it.asString == "streamGenerateContent" }) return@forEach
                    }
                    val field = item.get(if (protocol == ModelProtocol.GEMINI_GENERATE_CONTENT) "name" else "id")
                    check(field != null && field.isJsonPrimitive && field.asJsonPrimitive.isString) { "MODEL_DISCOVERY_INVALID_MODEL_ID" }
                    val id = field.asString.let { if (protocol == ModelProtocol.GEMINI_GENERATE_CONTENT) it.removePrefix("models/") else it }
                    check(id.isNotBlank() && id.length <= 256 && id.none(Char::isISOControl)) { "MODEL_DISCOVERY_INVALID_MODEL_ID" }
                    models += id
                    check(models.size <= MAX_MODELS) { "MODEL_DISCOVERY_TOO_MANY_MODELS" }
                }
                cursor = when {
                    protocol == ModelProtocol.GEMINI_GENERATE_CONTENT -> root.get("nextPageToken")?.takeUnless { it.isJsonNull }?.asString?.takeIf(String::isNotBlank)
                    protocol == ModelProtocol.ANTHROPIC_MESSAGES && root.get("has_more")?.asBoolean == true ->
                        root.get("last_id")?.asString?.takeIf(String::isNotBlank) ?: error("MODEL_DISCOVERY_MISSING_CURSOR")
                    else -> null
                }
                if (cursor == null) {
                    check(models.isNotEmpty()) { "MODEL_DISCOVERY_EMPTY" }
                    return models.toList()
                }
                check(cursor.length <= 4096 && cursors.add(cursor)) { "MODEL_DISCOVERY_INVALID_CURSOR" }
            } catch (failure: InterruptedException) { throw failure }
            catch (failure: Exception) {
                // Do not surface remote bodies, URLs, headers or nested transport error messages.
                val safe = failure.message?.takeIf { it.startsWith("MODEL_DISCOVERY_") } ?: "MODEL_DISCOVERY_FAILED"
                throw IllegalStateException(safe)
            } finally { http.disconnect() }
        }
        error("MODEL_DISCOVERY_TOO_MANY_PAGES")
    }

    companion object {
        const val MAX_MODELS = 1024
        private const val MAX_PAGES = 32
        private const val MAX_RESPONSE_BYTES = 2 * 1024 * 1024
    }
}
