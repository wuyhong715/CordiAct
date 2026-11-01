package com.cordiact.model.deepseek

import com.cordiact.core.conversation.MessageRole
import com.cordiact.core.conversation.ModelAdapterPlugin
import com.cordiact.core.conversation.ModelEvent
import com.cordiact.core.conversation.ModelGenerationRequest
import com.cordiact.core.conversation.ModelId
import com.google.gson.JsonParser
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class ConfigurableModelBackendTest {
    @Test
    fun streamsEveryProtocolAndUsesItsExpectedRequestAndAuthentication() {
        val cases = listOf(
            Case(ModelProtocol.OPENAI_CHAT_COMPLETIONS, "/v1/chat/completions", "chat-provider", "model", "Authorization", "Bearer test-secret",
                "data: {\"choices\":[{\"delta\":{\"content\":\"hello\"},\"finish_reason\":null}]}\n\n" +
                    "data: {\"choices\":[{\"delta\":{},\"finish_reason\":\"stop\"}]}\n\n" + "data: [DONE]\n\n"),
            Case(ModelProtocol.ANTHROPIC_MESSAGES, "/v1/messages", "anthropic-provider", "model", "x-api-key", "test-secret",
                "event: content_block_delta\ndata: {\"type\":\"content_block_delta\",\"delta\":{\"type\":\"text_delta\",\"text\":\"hello\"}}\n\n" +
                    "event: message_stop\ndata: {\"type\":\"message_stop\"}\n\n"),
            Case(ModelProtocol.GEMINI_GENERATE_CONTENT, "/v1beta/models/{model}:streamGenerateContent?alt=sse", "gemini-provider", "gemini-test", "x-goog-api-key", "test-secret",
                "data: {\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"hello\"}]},\"finishReason\":\"STOP\"}]}\n\n"),
            Case(ModelProtocol.OPENAI_RESPONSES, "/v1/responses", "responses-provider", "model", "Authorization", "Bearer test-secret",
                "event: response.output_text.delta\ndata: {\"type\":\"response.output_text.delta\",\"delta\":\"hello\"}\n\n" +
                    "event: response.completed\ndata: {\"type\":\"response.completed\"}\n\n"),
        )

        cases.forEach { case -> runCase(case) }
    }

    @Test
    fun missingCredentialsFailBeforeOpeningTheEndpoint() {
        val server = startServer("data: [DONE]\n\n")
        val backend = ConfigurableModelBackend(
            config(
                ModelProtocol.OPENAI_RESPONSES,
                "empty-key",
                "${server.baseUrl}/v1/responses",
                allowInsecureLoopbackForTests = true,
            ),
            ApiKeyProvider { null },
        )
        try {
            val events = Collections.synchronizedList(mutableListOf<ModelEvent>())
            backend.generate(request("empty-key", "model")) { events += it }
            assertEquals("missing_credentials", (events.single() as ModelEvent.Failed).code)
            assertEquals(0, server.requests)
        } finally {
            backend.close()
            server.close()
        }
    }

    @Test
    fun geminiGenerateContentJsonResponseIsAdaptedWhenEndpointUsesGenerateContent() {
        val response = "{\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"one shot\"}]},\"finishReason\":\"STOP\"}]}"
        val server = startServer(response, "application/json")
        val endpoint = "${server.baseUrl}/v1beta/models/{model}:generateContent"
        val backend = ConfigurableModelBackend(
            config(ModelProtocol.GEMINI_GENERATE_CONTENT, "gemini-json", endpoint, allowInsecureLoopbackForTests = true),
            ApiKeyProvider { "test-secret" },
        )
        val events = Collections.synchronizedList(mutableListOf<ModelEvent>())
        val done = CountDownLatch(1)
        try {
            backend.generate(request("gemini-json", "gemini-test")) { event ->
                events += event
                if (event is ModelEvent.Completed || event is ModelEvent.Failed) done.countDown()
            }
            assertTrue(done.await(5, TimeUnit.SECONDS))
            assertEquals("one shot", events.filterIsInstance<ModelEvent.Delta>().joinToString("") { it.text })
            assertTrue(events.last() is ModelEvent.Completed)
            assertTrue(server.requestPath.contains("gemini-test:generateContent"))
            assertEquals("test-secret", server.authHeaders["x-goog-api-key"])
        } finally {
            backend.close()
            server.close()
        }
    }

    @Test
    fun validatesHttpsExceptForLoopbackTestsAndRequiresGeminiModelPlaceholder() {
        assertThrows(IllegalArgumentException::class.java) {
            config(ModelProtocol.OPENAI_RESPONSES, "custom", "http://api.example.test/v1/responses")
        }
        assertThrows(IllegalArgumentException::class.java) {
            config(ModelProtocol.GEMINI_GENERATE_CONTENT, "gemini", "https://generativelanguage.googleapis.com/v1beta/models/model:streamGenerateContent?alt=sse")
        }
        assertThrows(IllegalArgumentException::class.java) {
            config(ModelProtocol.OPENAI_RESPONSES, "secret", "https://api.example.test/v1/responses?api_key=secret")
        }
        assertThrows(IllegalArgumentException::class.java) {
            config(ModelProtocol.OPENAI_RESPONSES, "local", "http://127.0.0.1:1234/v1/responses")
        }
        assertEquals(
            "local",
            config(
                ModelProtocol.OPENAI_RESPONSES,
                "local",
                "http://127.0.0.1:1234/v1/responses",
                allowInsecureLoopbackForTests = true,
            ).providerId,
        )
    }

    @Test
    fun registryAddsAndRemovesCustomProvidersThroughModelAdapter() {
        val adapter = ModelAdapterPlugin()
        val store = InMemoryModelProviderConfigStore()
        val forgottenCredentials = mutableListOf<String>()
        val registry = ModelProviderRegistryPlugin(
            adapter,
            { ApiKeyProvider { "test-key" } },
            store,
            { forgottenCredentials.add(it) },
        )
        adapter.onEnable()
        registry.onEnable()
        val provider = config(ModelProtocol.OPENAI_RESPONSES, "custom", "https://api.example.test/v1/responses")
        try {
            assertThrows(IllegalArgumentException::class.java) {
                registry.addProvider(provider.duplicate(providerId = "deepseek"))
            }
            registry.addProvider(provider)
            assertEquals(listOf(provider), registry.providers())
            assertTrue(adapter.models().any { it.id == ModelId("custom", "model") })
            assertEquals(listOf(provider), store.load())
            registry.removeProvider("custom")
            assertEquals(listOf("custom"), forgottenCredentials)
            assertTrue(registry.providers().isEmpty())
            assertFalse(adapter.models().any { it.id.providerId == "custom" })
            assertTrue(store.load().isEmpty())
        } finally {
            registry.onDisable()
            adapter.onDisable()
        }
    }

    @Test
    fun deepSeekDefaultModelRemainsFlash() {
        assertEquals("deepseek-flash", DeepSeekBackendConfig.DEFAULT_MODEL)
        assertEquals(listOf("deepseek-flash"), DeepSeekBackendConfig().models)
    }

    private fun runCase(case: Case) {
        val server = startServer(case.response)
        val endpoint = if (case.protocol == ModelProtocol.GEMINI_GENERATE_CONTENT) {
            server.baseUrl + case.endpoint
        } else server.baseUrl + case.endpoint
        val config = config(case.protocol, case.providerId, endpoint, case.modelId, allowInsecureLoopbackForTests = true)
        val backend = ConfigurableModelBackend(config, ApiKeyProvider { "test-secret" })
        val events = Collections.synchronizedList(mutableListOf<ModelEvent>())
        val done = CountDownLatch(1)
        try {
            backend.generate(request(case.providerId, case.modelId), { event ->
                events += event
                if (event is ModelEvent.Completed || event is ModelEvent.Failed) done.countDown()
            })
            assertTrue(done.await(5, TimeUnit.SECONDS), "${case.protocol} did not terminate")
            assertEquals("hello", events.filterIsInstance<ModelEvent.Delta>().joinToString("") { it.text })
            assertTrue(events.last() is ModelEvent.Completed, "${case.protocol}: $events")
            assertEquals(1, server.requests)
            assertEquals(case.authValue, server.authHeaders[case.authHeader.lowercase()])
            assertTrue(server.requestBody.contains("\"model\":\"${case.modelId}\"") || case.protocol == ModelProtocol.GEMINI_GENERATE_CONTENT)
            assertTrue(server.requestBody.contains("hello user"))
            if (case.protocol == ModelProtocol.ANTHROPIC_MESSAGES) {
                assertEquals("2023-06-01", server.authHeaders["anthropic-version"])
                assertTrue(server.requestBody.contains("\"max_tokens\""))
            }
            if (case.protocol == ModelProtocol.OPENAI_RESPONSES) assertTrue(server.requestBody.contains("\"input\""))
            if (case.protocol == ModelProtocol.GEMINI_GENERATE_CONTENT) {
                assertTrue(server.requestPath.contains("gemini-test:streamGenerateContent"))
                assertTrue(server.requestBody.contains("\"contents\""))
            }
            JsonParser.parseString(server.requestBody).asJsonObject
        } finally {
            backend.close()
            server.close()
        }
    }

    @Test
    fun cancellingAnOpenStreamDisconnectsWithoutEmittingTerminalEvents() {
        val headersSent = CountDownLatch(1)
        val releaseServer = CountDownLatch(1)
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            exchange.requestBody.close()
            exchange.responseHeaders.add("Content-Type", "text/event-stream")
            exchange.sendResponseHeaders(200, 0)
            exchange.responseBody.use { output ->
                output.write("data: {\"choices\":[{\"delta\":{\"content\":\"first\"}}]}\n\n".toByteArray())
                output.flush()
                headersSent.countDown()
                releaseServer.await(3, TimeUnit.SECONDS)
                runCatching { output.write("data: [DONE]\n\n".toByteArray()); output.flush() }
            }
        }
        server.start()
        val config = config(
            ModelProtocol.OPENAI_CHAT_COMPLETIONS,
            "cancel-provider",
            "http://127.0.0.1:${server.address.port}/chat/completions",
            allowInsecureLoopbackForTests = true,
        )
        val backend = ConfigurableModelBackend(config, ApiKeyProvider { "test-secret" })
        val events = Collections.synchronizedList(mutableListOf<ModelEvent>())
        val firstDelta = CountDownLatch(1)
        try {
            val call = backend.generate(request("cancel-provider", "model")) { event ->
                events += event
                if (event is ModelEvent.Delta) firstDelta.countDown()
            }
            assertTrue(headersSent.await(3, TimeUnit.SECONDS))
            assertTrue(firstDelta.await(3, TimeUnit.SECONDS))
            call.cancel()
            releaseServer.countDown()
            assertEquals(1, events.filterIsInstance<ModelEvent.Delta>().size)
            assertTrue(events.none { it is ModelEvent.Completed || it is ModelEvent.Failed })
        } finally {
            releaseServer.countDown()
            backend.close()
            server.stop(0)
        }
    }

    private fun config(
        protocol: ModelProtocol,
        providerId: String,
        endpoint: String,
        modelId: String = if (protocol == ModelProtocol.GEMINI_GENERATE_CONTENT) "gemini-test" else "model",
        allowInsecureLoopbackForTests: Boolean = false,
    ): ModelProviderConfig {
        val displayName = "Custom ${protocol.name}"
        val models = listOf(modelId)
        return if (allowInsecureLoopbackForTests) {
            ModelProviderConfig.forLoopbackTest(
                providerId, displayName, protocol, endpoint, models, 2_000, 2_000,
            )
        } else {
            ModelProviderConfig(
                providerId, displayName, protocol, endpoint, models, 2_000, 2_000,
            )
        }
    }

    private fun request(providerId: String, modelId: String) = ModelGenerationRequest(
        sessionId = "session",
        model = ModelId(providerId, modelId),
        messages = listOf(
            com.cordiact.core.conversation.ModelMessage(MessageRole.SYSTEM, "system instruction"),
            com.cordiact.core.conversation.ModelMessage(MessageRole.USER, "hello user"),
        ),
        temperature = 0.3,
        maxOutputTokens = 512,
    )

    private fun startServer(response: String, contentType: String = "text/event-stream; charset=utf-8"): TestServer {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val state = TestServer(server)
        server.createContext("/") { exchange ->
            state.requests++
            state.requestPath = exchange.requestURI.toString()
            state.requestBody = exchange.requestBody.use { it.readBytes().toString(StandardCharsets.UTF_8) }
            exchange.requestHeaders.forEach { (name, values) -> state.authHeaders[name.lowercase()] = values.firstOrNull().orEmpty() }
            val bytes = response.toByteArray(StandardCharsets.UTF_8)
            exchange.responseHeaders.add("Content-Type", contentType)
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        state.baseUrl = "http://127.0.0.1:${server.address.port}"
        return state
    }

    private data class Case(
        val protocol: ModelProtocol,
        val endpoint: String,
        val providerId: String,
        val modelId: String,
        val authHeader: String,
        val authValue: String,
        val response: String,
    )

    private class TestServer(private val server: HttpServer) : AutoCloseable {
        @Volatile var baseUrl = ""
        @Volatile var requests = 0
        @Volatile var requestPath = ""
        @Volatile var requestBody = ""
        val authHeaders = java.util.concurrent.ConcurrentHashMap<String, String>()
        override fun close() = server.stop(0)
    }
}
