package com.cordiact.model.deepseek

import com.cordiact.core.conversation.MessageRole
import com.cordiact.core.conversation.ModelBackend
import com.cordiact.core.conversation.ModelCall
import com.cordiact.core.conversation.ModelDescriptor
import com.cordiact.core.conversation.ModelEvent
import com.cordiact.core.conversation.ModelGenerationRequest
import com.cordiact.core.conversation.ModelId
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

enum class ModelProtocol {
    OPENAI_CHAT_COMPLETIONS,
    ANTHROPIC_MESSAGES,
    GEMINI_GENERATE_CONTENT,
    OPENAI_RESPONSES,
}

/** Public, non-secret provider settings. API keys must be supplied separately. */
class ModelProviderConfig private constructor(
    val providerId: String,
    val displayName: String,
    val protocol: ModelProtocol,
    /** Full request URL; Gemini URLs must include a `{model}` path placeholder. */
    val endpoint: String,
    val models: List<String>,
    val connectTimeoutMillis: Int,
    val readTimeoutMillis: Int,
    internal val allowInsecureLoopbackForTests: Boolean,
) {
    constructor(
        providerId: String,
        displayName: String,
        protocol: ModelProtocol,
        endpoint: String,
        models: List<String>,
        connectTimeoutMillis: Int = 15_000,
        readTimeoutMillis: Int = 120_000,
    ) : this(providerId, displayName, protocol, endpoint, models, connectTimeoutMillis, readTimeoutMillis, false)

    internal fun duplicate(
        providerId: String = this.providerId,
        models: List<String> = this.models,
        allowInsecureLoopbackForTests: Boolean = false,
    ) = ModelProviderConfig(
        providerId, displayName, protocol, endpoint, models, connectTimeoutMillis, readTimeoutMillis, allowInsecureLoopbackForTests,
    )

    override fun equals(other: Any?): Boolean = other is ModelProviderConfig &&
        providerId == other.providerId && displayName == other.displayName && protocol == other.protocol &&
        endpoint == other.endpoint && models == other.models && connectTimeoutMillis == other.connectTimeoutMillis &&
        readTimeoutMillis == other.readTimeoutMillis && allowInsecureLoopbackForTests == other.allowInsecureLoopbackForTests

    override fun hashCode(): Int = listOf(
        providerId, displayName, protocol, endpoint, models, connectTimeoutMillis, readTimeoutMillis, allowInsecureLoopbackForTests,
    ).hashCode()

    companion object {
        internal fun forLoopbackTest(
            providerId: String,
            displayName: String,
            protocol: ModelProtocol,
            endpoint: String,
            models: List<String>,
            connectTimeoutMillis: Int = 15_000,
            readTimeoutMillis: Int = 120_000,
        ) = ModelProviderConfig(
            providerId, displayName, protocol, endpoint, models, connectTimeoutMillis, readTimeoutMillis, true,
        )
    }
    init {
        require(providerId.matches(Regex("[a-z0-9][a-z0-9._-]{1,127}"))) { "Invalid provider id" }
        require(displayName.isNotBlank() && displayName.length <= 160) { "Provider name is required" }
        require(endpoint.length <= 2048) { "Provider endpoint is too long" }
        require(models.size <= 64) { "At most 64 models may be configured per provider" }
        val uri = runCatching { URI(endpoint.replace("{model}", "model")) }.getOrNull()
        val host = uri?.host?.removePrefix("[")?.removeSuffix("]")
        require(uri != null && host != null && uri.rawUserInfo == null && uri.rawFragment == null && (uri.scheme == "https" ||
            (allowInsecureLoopbackForTests && uri.scheme == "http" && host in setOf("127.0.0.1", "localhost", "::1")))) {
            "Provider endpoint must use HTTPS without embedded credentials (loopback HTTP is test-only)"
        }
        val queryNames = uri.rawQuery.orEmpty().split('&').mapNotNull { it.substringBefore('=').takeIf(String::isNotBlank) }
            .map { runCatching { java.net.URLDecoder.decode(it, "UTF-8") }.getOrDefault(it).lowercase() }
        require(queryNames.none {
            it in setOf("key", "api_key", "apikey", "x-api-key", "x-goog-api-key", "api-key", "token", "access_token", "authorization", "auth", "secret", "password")
        }) {
            "Provider credentials must be supplied through the secure key store, not endpoint query parameters"
        }
        require(models.isNotEmpty() && models.all { it.isNotBlank() && it.length <= 256 }) { "At least one model is required" }
        require(models.distinct().size == models.size) { "Model ids must be unique" }
        require(connectTimeoutMillis > 0 && readTimeoutMillis > 0)
        if (protocol == ModelProtocol.GEMINI_GENERATE_CONTENT) {
            require(endpoint.contains("{model}")) { "Gemini endpoint must include a {model} placeholder" }
        }
    }
}

/** Cancellable streaming adapter for the supported text-only model protocols. */
private class StreamLimitException : IllegalStateException()

class ConfigurableModelBackend(
    private val config: ModelProviderConfig,
    private val credentials: ApiKeyProvider,
    private val executor: ExecutorService = Executors.newCachedThreadPool(),
) : ModelBackend, AutoCloseable {
    private class RunningCall {
        val cancelled = AtomicBoolean(false)
        val connection = AtomicReference<HttpURLConnection?>()
        val future = AtomicReference<Future<*>?>()
        private val callbackLock = Any()

        fun emit(callback: (ModelEvent) -> Unit, event: ModelEvent) {
            synchronized(callbackLock) {
                if (cancelled.get()) return
                try { callback(event) } catch (_: Exception) { }
            }
        }

        fun attachFuture(value: Future<*>) {
            synchronized(callbackLock) {
                if (cancelled.get()) value.cancel(true) else future.set(value)
            }
        }

        fun attachConnection(value: HttpURLConnection): Boolean = synchronized(callbackLock) {
            if (cancelled.get()) {
                value.disconnect()
                false
            } else {
                connection.set(value)
                true
            }
        }

        fun cancel() {
            synchronized(callbackLock) {
                if (cancelled.compareAndSet(false, true)) {
                    connection.get()?.disconnect()
                    future.get()?.cancel(true)
                }
            }
        }
    }

    private val activeCalls = ConcurrentHashMap.newKeySet<RunningCall>()
    override val providerId: String get() = config.providerId

    override fun models(): List<ModelDescriptor> = config.models.map {
        ModelDescriptor(ModelId(providerId, it), "${config.displayName} - $it", supportsStreaming = true)
    }

    override fun generate(request: ModelGenerationRequest, onEvent: (ModelEvent) -> Unit): ModelCall {
        val running = RunningCall()
        fun emitEvent(event: ModelEvent) = running.emit(onEvent, event)
        val key = try {
            credentials.apiKey()?.trim().orEmpty()
        } catch (_: Exception) {
            emitEvent(ModelEvent.Failed("credentials_unavailable", "${config.displayName} credentials are unavailable"))
            return ModelCall {}
        }
        if (key.isEmpty()) {
            emitEvent(ModelEvent.Failed("missing_credentials", "${config.displayName} API key is not configured"))
            return ModelCall {}
        }
        if (request.model.providerId != providerId || config.models.none { it == request.model.modelId }) {
            emitEvent(ModelEvent.Failed("unsupported_model", "The requested ${config.displayName} model is not configured"))
            return ModelCall {}
        }
        if (request.messages.isEmpty()) {
            emitEvent(ModelEvent.Failed("invalid_request", "At least one message is required"))
            return ModelCall {}
        }
        if (config.protocol in setOf(ModelProtocol.ANTHROPIC_MESSAGES, ModelProtocol.GEMINI_GENERATE_CONTENT) &&
            request.messages.none { it.role != MessageRole.SYSTEM }
        ) {
            emitEvent(ModelEvent.Failed("invalid_request", "${config.displayName} requires at least one non-system message"))
            return ModelCall {}
        }
        if (request.messages.any { it.role == MessageRole.TOOL }) {
            emitEvent(ModelEvent.Failed("unsupported_message", "Tool messages are not supported by the text backend"))
            return ModelCall {}
        }

        activeCalls.add(running)
        try {
            val future = executor.submit {
                var terminal = false
                try {
                    if (running.cancelled.get()) return@submit
                    val connection = URI(requestUrl(request.model.modelId)).toURL().openConnection() as HttpURLConnection
                    if (!running.attachConnection(connection)) return@submit
                    connection.requestMethod = "POST"
                    connection.instanceFollowRedirects = false
                    connection.connectTimeout = config.connectTimeoutMillis
                    connection.readTimeout = config.readTimeoutMillis
                    connection.doOutput = true
                    connection.setRequestProperty("Content-Type", "application/json")
                    connection.setRequestProperty("Accept", "text/event-stream")
                    setAuthHeaders(connection, key)
                    connection.outputStream.use { it.write(requestBody(request).toString().toByteArray(StandardCharsets.UTF_8)) }
                    if (running.cancelled.get()) return@submit
                    val status = connection.responseCode
                    if (status !in 200..299) {
                        connection.errorStream?.close()
                        if (!running.cancelled.get()) {
                            emitEvent(ModelEvent.Failed("http_$status", "${config.displayName} returned HTTP $status", status == 429 || status >= 500))
                            terminal = true
                        }
                        return@submit
                    }
                    if (!connection.contentType.orEmpty().contains("text/event-stream", ignoreCase = true)) {
                        if (config.protocol == ModelProtocol.GEMINI_GENERATE_CONTENT) {
                            val body = connection.inputStream.bufferedReader(StandardCharsets.UTF_8).use { readBoundedBody(it, MAX_RESPONSE_CHARS) }
                            val json = runCatching { JsonParser.parseString(body).asJsonObject }.getOrNull()
                            if (!running.cancelled.get()) {
                                if (json == null) {
                                    emitEvent(ModelEvent.Failed("invalid_response", "${config.displayName} returned an invalid response"))
                                } else if (json.has("error") && !json.get("error").isJsonNull) {
                                    emitEvent(ModelEvent.Failed("provider_error", "${config.displayName} returned an error"))
                                } else {
                                    extractGeminiText(json).takeIf(String::isNotEmpty)?.let { text ->
                                        emitEvent(ModelEvent.Delta(text))
                                    }
                                    emitEvent(ModelEvent.Completed())
                                }
                                terminal = true
                            }
                        } else {
                            connection.inputStream.close()
                            if (!running.cancelled.get()) {
                                emitEvent(ModelEvent.Failed("invalid_content_type", "${config.displayName} did not return an event stream"))
                                terminal = true
                            }
                        }
                        return@submit
                    }

                    var completed = false
                    var failed = false
                    var streamedTextChars = 0
                    var eventName = ""
                    var data = StringBuilder()
                    fun emitDelta(text: String) {
                        if (text.length > MAX_STREAM_TEXT_CHARS - streamedTextChars) throw StreamLimitException()
                        streamedTextChars += text.length
                        emitEvent(ModelEvent.Delta(text))
                    }
                    connection.inputStream.bufferedReader(StandardCharsets.UTF_8).use { reader ->
                        fun dispatch() {
                            val payload = data.toString().trim()
                            val event = eventName
                            data = StringBuilder()
                            eventName = ""
                            if (payload.isEmpty()) return
                            if (payload == "[DONE]") {
                                completed = true
                                return
                            }
                            val json = runCatching { JsonParser.parseString(payload).asJsonObject }.getOrNull()
                            if (json == null) {
                                failed = true
                                emitEvent(ModelEvent.Failed("invalid_stream", "${config.displayName} sent an invalid event"))
                                return
                            }
                            val type = json.string("type") ?: event
                            when (config.protocol) {
                                ModelProtocol.OPENAI_CHAT_COMPLETIONS -> {
                                    val delta = json.getAsJsonArray("choices")?.firstOrNull()?.asJsonObject
                                        ?.getAsJsonObject("delta")?.get("content")
                                    if (delta?.isJsonPrimitive == true && delta.asJsonPrimitive.isString) {
                                        delta.asString.takeIf(String::isNotEmpty)?.let(::emitDelta)
                                    }
                                }
                                ModelProtocol.ANTHROPIC_MESSAGES -> when (type) {
                                    "content_block_delta" -> {
                                        val delta = json.getAsJsonObject("delta")
                                        if (delta?.string("type") == "text_delta") delta.string("text")
                                            ?.takeIf(String::isNotEmpty)?.let(::emitDelta)
                                    }
                                    "message_stop" -> completed = true
                                    "error" -> {
                                        failed = true
                                        emitEvent(ModelEvent.Failed("provider_stream_error", "${config.displayName} reported a stream error", true))
                                    }
                                }
                                ModelProtocol.GEMINI_GENERATE_CONTENT -> {
                                    json.getAsJsonArray("candidates")?.forEach { candidate ->
                                        val candidateObject = candidate.asJsonObject
                                        candidateObject.getAsJsonObject("content")?.getAsJsonArray("parts")?.forEach { part ->
                                            part.asJsonObject.string("text")?.takeIf(String::isNotEmpty)
                                                ?.let(::emitDelta)
                                        }
                                        if (candidateObject.get("finishReason")?.isJsonNull == false) completed = true
                                    }
                                    if (json.has("error") && !json.get("error").isJsonNull) {
                                        failed = true
                                        emitEvent(ModelEvent.Failed("provider_stream_error", "${config.displayName} reported a stream error", true))
                                    }
                                }
                                ModelProtocol.OPENAI_RESPONSES -> when (type) {
                                    "response.output_text.delta" -> json.string("delta")?.takeIf(String::isNotEmpty)
                                        ?.let(::emitDelta)
                                    "response.completed" -> completed = true
                                    "response.failed", "response.incomplete", "error" -> {
                                        failed = true
                                        emitEvent(ModelEvent.Failed("provider_stream_error", "${config.displayName} reported a stream error", true))
                                    }
                                }
                            }
                        }
                        while (!running.cancelled.get() && !completed && !failed) {
                            val line = readBoundedLine(reader, MAX_SSE_EVENT_CHARS) ?: break
                            if (data.length + line.length > MAX_SSE_EVENT_CHARS) {
                                failed = true
                                emitEvent(ModelEvent.Failed("stream_limit_exceeded", "${config.displayName} sent an oversized stream event"))
                                break
                            }
                            if (line.isEmpty()) dispatch()
                            else if (line.startsWith("data:")) {
                                if (data.isNotEmpty()) data.append('\n')
                                data.append(line.substringAfter(':').trimStart())
                            } else if (line.startsWith("event:")) eventName = line.substringAfter(':').trim()
                        }
                        if (data.isNotEmpty() && !completed && !failed) dispatch()
                    }
                    if (!running.cancelled.get()) {
                        when {
                            failed -> terminal = true
                            completed -> {
                                emitEvent(ModelEvent.Completed())
                                terminal = true
                            }
                            else -> {
                                emitEvent(ModelEvent.Failed("incomplete_stream", "${config.displayName} stream ended before completion", true))
                                terminal = true
                            }
                        }
                    }
                } catch (failure: Exception) {
                    if (!running.cancelled.get()) {
                        if (failure is StreamLimitException) {
                            emitEvent(ModelEvent.Failed("stream_limit_exceeded", "${config.displayName} sent an oversized stream event"))
                        } else {
                            emitEvent(ModelEvent.Failed("transport_error", "${config.displayName} request failed", true))
                        }
                        terminal = true
                    }
                } finally {
                    running.connection.getAndSet(null)?.disconnect()
                    activeCalls.remove(running)
                    if (!terminal && !running.cancelled.get()) {
                        emitEvent(ModelEvent.Failed("incomplete_stream", "${config.displayName} stream ended before completion", true))
                    }
                }
            }
            running.attachFuture(future)
        } catch (_: Exception) {
            activeCalls.remove(running)
            emitEvent(ModelEvent.Failed("backend_unavailable", "${config.displayName} backend is unavailable", true))
            return ModelCall {}
        }
        return ModelCall {
            running.cancel()
            activeCalls.remove(running)
        }
    }

    fun cancelActiveCalls() = activeCalls.toList().forEach { it.cancel() }

    override fun close() {
        cancelActiveCalls()
        executor.shutdownNow()
    }

    private fun requestUrl(modelId: String): String = when (config.protocol) {
        ModelProtocol.GEMINI_GENERATE_CONTENT -> config.endpoint
            .replace("{model}", URLEncoder.encode(modelId, "UTF-8").replace("+", "%20"))
        else -> config.endpoint
    }

    private fun setAuthHeaders(connection: HttpURLConnection, key: String) {
        when (config.protocol) {
            ModelProtocol.OPENAI_CHAT_COMPLETIONS, ModelProtocol.OPENAI_RESPONSES -> connection.setRequestProperty("Authorization", "Bearer $key")
            ModelProtocol.ANTHROPIC_MESSAGES -> {
                connection.setRequestProperty("x-api-key", key)
                connection.setRequestProperty("anthropic-version", "2023-06-01")
            }
            ModelProtocol.GEMINI_GENERATE_CONTENT -> connection.setRequestProperty("x-goog-api-key", key)
        }
    }

    private fun requestBody(request: ModelGenerationRequest): JsonObject = when (config.protocol) {
        ModelProtocol.OPENAI_CHAT_COMPLETIONS -> JsonObject().apply {
            addProperty("model", request.model.modelId)
            addProperty("stream", true)
            request.temperature?.let { addProperty("temperature", it) }
            request.maxOutputTokens?.let { addProperty("max_tokens", it) }
            add("messages", JsonArray().apply {
                request.messages.forEach { message -> add(JsonObject().apply {
                    addProperty("role", openAiRole(message.role))
                    addProperty("content", message.content)
                }) }
            })
        }
        ModelProtocol.ANTHROPIC_MESSAGES -> JsonObject().apply {
            addProperty("model", request.model.modelId)
            addProperty("max_tokens", request.maxOutputTokens ?: DEFAULT_MAX_OUTPUT_TOKENS)
            addProperty("stream", true)
            val system = request.messages.filter { it.role == MessageRole.SYSTEM }.joinToString("\n") { it.content }
            if (system.isNotEmpty()) addProperty("system", system)
            request.temperature?.let { addProperty("temperature", it) }
            add("messages", JsonArray().apply {
                request.messages.filter { it.role != MessageRole.SYSTEM }.forEach { message -> add(JsonObject().apply {
                    addProperty("role", if (message.role == MessageRole.ASSISTANT) "assistant" else "user")
                    addProperty("content", message.content)
                }) }
            })
        }
        ModelProtocol.GEMINI_GENERATE_CONTENT -> JsonObject().apply {
            val system = request.messages.filter { it.role == MessageRole.SYSTEM }
            if (system.isNotEmpty()) add("systemInstruction", JsonObject().apply {
                add("parts", JsonArray().apply { system.forEach { add(JsonObject().apply { addProperty("text", it.content) }) } })
            })
            add("contents", JsonArray().apply {
                request.messages.filter { it.role != MessageRole.SYSTEM }.forEach { message -> add(JsonObject().apply {
                    addProperty("role", if (message.role == MessageRole.ASSISTANT) "model" else "user")
                    add("parts", JsonArray().apply { add(JsonObject().apply { addProperty("text", message.content) }) })
                }) }
            })
            request.temperature?.let { temperature -> add("generationConfig", JsonObject().apply { addProperty("temperature", temperature) }) }
            request.maxOutputTokens?.let { max ->
                val generation = getAsJsonObject("generationConfig") ?: JsonObject().also { add("generationConfig", it) }
                generation.addProperty("maxOutputTokens", max)
            }
        }
        ModelProtocol.OPENAI_RESPONSES -> JsonObject().apply {
            addProperty("model", request.model.modelId)
            addProperty("stream", true)
            request.maxOutputTokens?.let { addProperty("max_output_tokens", it) }
            request.temperature?.let { addProperty("temperature", it) }
            add("input", JsonArray().apply {
                request.messages.forEach { message -> add(JsonObject().apply {
                    addProperty("role", when (message.role) {
                        MessageRole.SYSTEM -> "system"
                        MessageRole.USER -> "user"
                        MessageRole.ASSISTANT -> "assistant"
                        MessageRole.TOOL -> "tool"
                    })
                    addProperty("content", message.content)
                }) }
            })
        }
    }

    private fun openAiRole(role: MessageRole): String = when (role) {
        MessageRole.SYSTEM -> "system"
        MessageRole.USER -> "user"
        MessageRole.ASSISTANT -> "assistant"
        MessageRole.TOOL -> "tool"
    }

    private fun JsonObject.string(name: String): String? = get(name)
        ?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }
        ?.asString

    private fun extractGeminiText(json: JsonObject): String = buildString {
        json.getAsJsonArray("candidates")?.forEach { candidate ->
            candidate.asJsonObject.getAsJsonObject("content")?.getAsJsonArray("parts")?.forEach { part ->
                part.asJsonObject.string("text")?.let(::append)
            }
        }
    }

    private fun readBoundedBody(reader: java.io.BufferedReader, maxChars: Int): String {
        val body = StringBuilder()
        val buffer = CharArray(4096)
        while (body.length <= maxChars) {
            val count = reader.read(buffer, 0, minOf(buffer.size, maxChars + 1 - body.length))
            if (count < 0) return body.toString()
            body.append(buffer, 0, count)
        }
        throw StreamLimitException()
    }

    private fun readBoundedLine(reader: java.io.BufferedReader, maxChars: Int): String? {
        val line = StringBuilder()
        while (true) {
            val next = reader.read()
            if (next < 0) return if (line.isEmpty()) null else line.toString()
            if (next == '\n'.code) return line.toString().removeSuffix(13.toChar().toString())
            if (line.length == maxChars) throw StreamLimitException()
            line.append(next.toChar())
        }
    }

    private fun emit(onEvent: (ModelEvent) -> Unit, event: ModelEvent) {
        try { onEvent(event) } catch (_: Exception) { }
    }

    companion object {
        private const val DEFAULT_MAX_OUTPUT_TOKENS = 1024
        private const val MAX_SSE_EVENT_CHARS = 4 * 1024 * 1024
        private const val MAX_RESPONSE_CHARS = 4 * 1024 * 1024
        private const val MAX_STREAM_TEXT_CHARS = 16 * 1024 * 1024
    }
}
