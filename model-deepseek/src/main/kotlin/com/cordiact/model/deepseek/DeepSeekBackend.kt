package com.cordiact.model.deepseek

import com.cordiact.core.conversation.MessageRole
import com.cordiact.core.conversation.ModelBackend
import com.cordiact.core.conversation.ModelCall
import com.cordiact.core.conversation.ModelDescriptor
import com.cordiact.core.conversation.ModelEvent
import com.cordiact.core.conversation.ModelGenerationRequest
import com.cordiact.core.conversation.ModelId
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.net.HttpURLConnection
import java.net.URI
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

fun interface ApiKeyProvider {
    fun apiKey(): String?
}

class DeepSeekBackendConfig private constructor(
    val endpoint: String,
    val models: List<String>,
    val connectTimeoutMillis: Int,
    val readTimeoutMillis: Int,
    internal val allowInsecureLoopbackForTests: Boolean,
) {
    constructor(
        endpoint: String = DEFAULT_ENDPOINT,
        models: List<String> = listOf(DEFAULT_MODEL),
        connectTimeoutMillis: Int = 15_000,
        readTimeoutMillis: Int = 120_000,
    ) : this(endpoint, models, connectTimeoutMillis, readTimeoutMillis, false)

    companion object {
        internal fun forLoopbackTest(
            endpoint: String,
            models: List<String> = listOf(DEFAULT_MODEL),
            connectTimeoutMillis: Int = 15_000,
            readTimeoutMillis: Int = 120_000,
        ) = DeepSeekBackendConfig(endpoint, models, connectTimeoutMillis, readTimeoutMillis, true)

        const val DEFAULT_ENDPOINT = "https://api.deepseek.com/chat/completions"
        const val DEFAULT_MODEL = "deepseek-flash"
    }

    init {
        val uri = runCatching { URI(endpoint) }.getOrNull()
        val host = uri?.host?.removePrefix("[")?.removeSuffix("]")
        require(uri != null && host != null && uri.rawUserInfo == null && uri.rawFragment == null && (uri.scheme == "https" ||
            (allowInsecureLoopbackForTests && uri.scheme == "http" && host in setOf("127.0.0.1", "localhost", "::1")))) {
            "DeepSeek endpoint must use HTTPS without embedded credentials (loopback HTTP is test-only)"
        }
        val queryNames = uri.rawQuery.orEmpty().split('&').mapNotNull { it.substringBefore('=').takeIf(String::isNotBlank) }
            .map { runCatching { java.net.URLDecoder.decode(it, "UTF-8") }.getOrDefault(it).lowercase() }
        require(queryNames.none {
            it in setOf("key", "api_key", "apikey", "x-api-key", "x-goog-api-key", "api-key", "token", "access_token", "authorization", "auth", "secret", "password")
        }) { "DeepSeek API credentials must be supplied through the secure key store" }
        require(models.isNotEmpty() && models.all(String::isNotBlank))
        require(connectTimeoutMillis > 0 && readTimeoutMillis > 0)
    }

}

/** OpenAI-compatible DeepSeek Chat Completions backend with cancellable SSE streaming. */
class DeepSeekBackend(
    private val credentials: ApiKeyProvider,
    private val config: DeepSeekBackendConfig = DeepSeekBackendConfig(),
    private val executor: ExecutorService = Executors.newCachedThreadPool(),
) : ModelBackend, AutoCloseable {
    private class RunningCall {
        val cancelled = AtomicBoolean(false)
        val connection = AtomicReference<HttpURLConnection?>()
        val future = AtomicReference<Future<*>?>()

        fun cancel() {
            if (cancelled.compareAndSet(false, true)) {
                connection.get()?.disconnect()
                future.get()?.cancel(true)
            }
        }
    }

    private val activeCalls = ConcurrentHashMap.newKeySet<RunningCall>()
    override val providerId: String = PROVIDER_ID

    override fun models(): List<ModelDescriptor> = config.models.map { model ->
        ModelDescriptor(ModelId(providerId, model), model, supportsStreaming = true)
    }

    override fun generate(request: ModelGenerationRequest, onEvent: (ModelEvent) -> Unit): ModelCall {
        val key = try {
            credentials.apiKey()?.trim().orEmpty()
        } catch (_: Exception) {
            emit(onEvent, ModelEvent.Failed("credentials_unavailable", "DeepSeek credentials are unavailable"))
            return ModelCall {}
        }
        if (key.isEmpty()) {
            emit(onEvent, ModelEvent.Failed("missing_credentials", "DeepSeek API key is not configured"))
            return ModelCall {}
        }
        if (request.model.providerId != providerId || config.models.none { it == request.model.modelId }) {
            emit(onEvent, ModelEvent.Failed("unsupported_model", "The requested DeepSeek model is not configured"))
            return ModelCall {}
        }
        if (request.messages.isEmpty()) {
            emit(onEvent, ModelEvent.Failed("invalid_request", "At least one message is required"))
            return ModelCall {}
        }
        if (request.messages.any { it.role == MessageRole.TOOL }) {
            emit(onEvent, ModelEvent.Failed("unsupported_message", "Tool messages are not supported by the basic DeepSeek backend"))
            return ModelCall {}
        }

        val running = RunningCall()
        activeCalls.add(running)
        try {
            running.future.set(executor.submit {
                var terminal = false
                try {
                    if (running.cancelled.get()) return@submit
                    val connection = URI(config.endpoint).toURL().openConnection() as HttpURLConnection
                    running.connection.set(connection)
                    connection.requestMethod = "POST"
                    connection.instanceFollowRedirects = false
                    connection.connectTimeout = config.connectTimeoutMillis
                    connection.readTimeout = config.readTimeoutMillis
                    connection.doOutput = true
                    connection.setRequestProperty("Authorization", "Bearer $key")
                    connection.setRequestProperty("Content-Type", "application/json")
                    connection.setRequestProperty("Accept", "text/event-stream")
                    connection.outputStream.use { output ->
                        output.write(buildRequest(request).toString().toByteArray(StandardCharsets.UTF_8))
                    }
                    if (running.cancelled.get()) return@submit
                    val status = connection.responseCode
                    if (status !in 200..299) {
                        connection.errorStream?.close()
                        if (!running.cancelled.get()) {
                            emit(onEvent, ModelEvent.Failed("http_$status", "DeepSeek returned HTTP $status", status == 429 || status >= 500))
                            terminal = true
                        }
                        return@submit
                    }
                    val contentType = connection.contentType.orEmpty()
                    if (!contentType.contains("text/event-stream", ignoreCase = true)) {
                        val body = connection.inputStream.bufferedReader(StandardCharsets.UTF_8).use { reader ->
                            val result = StringBuilder()
                            val buffer = CharArray(4096)
                            while (result.length <= MAX_RESPONSE_CHARS) {
                                val count = reader.read(buffer, 0, minOf(buffer.size, MAX_RESPONSE_CHARS + 1 - result.length))
                                if (count < 0) break
                                result.append(buffer, 0, count)
                            }
                            result.toString()
                        }
                        if (body.length > MAX_RESPONSE_CHARS) throw IllegalStateException("DeepSeek response exceeded the size limit")
                        val text = parseCompletion(body)
                        if (!running.cancelled.get()) {
                            if (text.isNotEmpty()) emit(onEvent, ModelEvent.Delta(text))
                            emit(onEvent, ModelEvent.Completed())
                            terminal = true
                        }
                        return@submit
                    }

                    var inputTokens: Long? = null
                    var outputTokens: Long? = null
                    var sawDone = false
                    connection.inputStream.bufferedReader(StandardCharsets.UTF_8).use { reader ->
                        var data = StringBuilder()
                        while (!running.cancelled.get()) {
                            val line = reader.readLine() ?: break
                            if (line.isEmpty()) {
                                val payload = data.toString().trim()
                                data = StringBuilder()
                                if (payload.isNotEmpty()) {
                                    if (payload == "[DONE]") {
                                        sawDone = true
                                        break
                                    }
                                    val chunk = JsonParser.parseString(payload).asJsonObject
                                    chunk.getAsJsonArray("choices")?.firstOrNull()?.asJsonObject
                                        ?.getAsJsonObject("delta")?.get("content")
                                        ?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }
                                        ?.asString?.takeIf(String::isNotEmpty)?.let { emit(onEvent, ModelEvent.Delta(it)) }
                                    chunk.getAsJsonObject("usage")?.let { usage ->
                                        inputTokens = usage.longOrNull("prompt_tokens") ?: inputTokens
                                        outputTokens = usage.longOrNull("completion_tokens") ?: outputTokens
                                    }
                                }
                            } else if (line.startsWith("data:")) {
                                if (data.isNotEmpty()) data.append('\n')
                                data.append(line.substringAfter(':').trimStart())
                            }
                        }
                    }
                    if (!running.cancelled.get()) {
                        if (sawDone) {
                            emit(onEvent, ModelEvent.Completed(inputTokens, outputTokens))
                            terminal = true
                        } else {
                            emit(onEvent, ModelEvent.Failed("incomplete_stream", "DeepSeek stream ended before [DONE]", true))
                            terminal = true
                        }
                    }
                } catch (_: Exception) {
                    if (!running.cancelled.get()) {
                        emit(onEvent, ModelEvent.Failed("transport_error", "DeepSeek request failed", true))
                        terminal = true
                    }
                } finally {
                    running.connection.getAndSet(null)?.disconnect()
                    activeCalls.remove(running)
                    if (!terminal && !running.cancelled.get()) {
                        emit(onEvent, ModelEvent.Failed("incomplete_stream", "DeepSeek stream ended before completion", true))
                    }
                }
            })
        } catch (_: Exception) {
            activeCalls.remove(running)
            emit(onEvent, ModelEvent.Failed("backend_unavailable", "DeepSeek backend is unavailable", true))
            return ModelCall {}
        }
        return ModelCall {
            running.cancel()
            activeCalls.remove(running)
        }
    }

    private fun buildRequest(request: ModelGenerationRequest): JsonObject = JsonObject().apply {
        addProperty("model", request.model.modelId)
        addProperty("stream", true)
        request.temperature?.let { addProperty("temperature", it) }
        request.maxOutputTokens?.let { addProperty("max_tokens", it) }
        add("messages", com.google.gson.JsonArray().apply {
            request.messages.forEach { message ->
                add(JsonObject().apply {
                    addProperty("role", when (message.role) {
                        MessageRole.SYSTEM -> "system"
                        MessageRole.USER -> "user"
                        MessageRole.ASSISTANT -> "assistant"
                        MessageRole.TOOL -> "tool"
                    })
                    addProperty("content", message.content)
                })
            }
        })
    }

    private fun parseCompletion(body: String): String = try {
        JsonParser.parseString(body).asJsonObject.getAsJsonArray("choices")
            .firstOrNull()?.asJsonObject?.getAsJsonObject("message")?.get("content")
            ?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString.orEmpty()
    } catch (_: Exception) {
        throw IllegalStateException("DeepSeek returned an invalid completion response")
    }

    private fun emit(listener: (ModelEvent) -> Unit, event: ModelEvent) {
        try { listener(event) } catch (_: Throwable) { }
    }

    fun cancelActiveCalls() {
        activeCalls.toList().forEach { it.cancel() }
        activeCalls.clear()
    }

    override fun close() {
        cancelActiveCalls()
        executor.shutdownNow()
    }

    private fun JsonObject.longOrNull(name: String): Long? = get(name)?.takeIf { it.isJsonPrimitive }?.let {
        runCatching { it.asLong }.getOrNull()
    }

    companion object {
        const val PROVIDER_ID = "deepseek"
        private const val MAX_RESPONSE_CHARS = 4 * 1024 * 1024
    }
}
