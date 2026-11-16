package com.cordiact.model.providers.image

import com.cordiact.model.providers.ApiKeyProvider
import com.cordiact.model.providers.ModelProtocol
import com.cordiact.model.providers.ModelProviderConfig
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.net.HttpURLConnection
import java.net.URI
import java.util.Base64

/** One POST only. An ambiguous timeout must never resubmit a potentially billable request. */
class OpenAiImageGenerator {
    data class Request(val model: String, val prompt: String, val size: String = "auto", val quality: String = "auto")
    data class Image(val bytes: ByteArray, val mimeType: String, val extension: String)

    fun generate(config: ModelProviderConfig, keyProvider: ApiKeyProvider, request: Request): Image {
        require(config.protocol == ModelProtocol.OPENAI_IMAGES) { "IMAGE_PROVIDER_REQUIRED" }
        require(request.model in config.models && request.model.startsWith("gpt-image-")) { "IMAGE_MODEL_NOT_ENABLED" }
        require(request.prompt.isNotBlank() && request.prompt.length <= 32_000) { "IMAGE_PROMPT_INVALID" }
        require(request.size in setOf("auto", "1024x1024", "1536x1024", "1024x1536")) { "IMAGE_SIZE_INVALID" }
        require(request.quality in setOf("auto", "low", "medium", "high")) { "IMAGE_QUALITY_INVALID" }
        val key = keyProvider.apiKey()?.trim().orEmpty()
        require(key.isNotBlank() && key.length <= 16 * 1024 && key.none { it == '\r' || it == '\n' }) { "IMAGE_API_KEY_REQUIRED" }
        val payload = JsonObject().apply {
            addProperty("model", request.model); addProperty("prompt", request.prompt)
            addProperty("n", 1); addProperty("size", request.size); addProperty("quality", request.quality)
            addProperty("output_format", "png")
        }.toString().toByteArray(Charsets.UTF_8)
        val http = URI(config.endpoint).toURL().openConnection() as HttpURLConnection
        try {
            http.instanceFollowRedirects = false
            http.connectTimeout = config.connectTimeoutMillis
            http.readTimeout = config.readTimeoutMillis
            http.requestMethod = "POST"; http.doOutput = true
            http.setRequestProperty("Authorization", "Bearer $key")
            http.setRequestProperty("Content-Type", "application/json")
            http.setFixedLengthStreamingMode(payload.size)
            http.outputStream.use { it.write(payload) }
            val status = http.responseCode
            check(status in 200..299) { "IMAGE_HTTP_$status: generation was not retried" }
            val encoded = http.inputStream.use { stream ->
                val out = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    if (Thread.currentThread().isInterrupted) throw InterruptedException()
                    val count = stream.read(buffer)
                    if (count < 0) break
                    check(out.size() + count <= MAX_RESPONSE_BYTES) { "IMAGE_RESPONSE_TOO_LARGE" }
                    out.write(buffer, 0, count)
                }
                out.toString("UTF-8")
            }
            val root = JsonParser.parseString(encoded).asJsonObject
            val data = root.getAsJsonArray("data")
            check(data != null && data.size() == 1) { "IMAGE_RESPONSE_INVALID_COUNT" }
            val b64 = data[0].asJsonObject.get("b64_json")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString
            check(!b64.isNullOrBlank()) { "IMAGE_BASE64_REQUIRED: URL responses are not downloaded" }
            check(b64.length <= ((MAX_IMAGE_BYTES + 2) / 3) * 4) { "IMAGE_TOO_LARGE" }
            val bytes = try { Base64.getDecoder().decode(b64) } catch (_: IllegalArgumentException) { error("IMAGE_BASE64_INVALID") }
            check(bytes.size in 8..MAX_IMAGE_BYTES) { "IMAGE_SIZE_INVALID" }
            check(bytes.copyOfRange(0, 8).contentEquals(byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10))) { "IMAGE_PNG_REQUIRED" }
            return Image(bytes, "image/png", "png")
        } finally { http.disconnect() }
    }

    companion object {
        const val MAX_IMAGE_BYTES = 10 * 1024 * 1024
        const val MAX_RESPONSE_BYTES = 15 * 1024 * 1024
    }
}
