package com.cordiact.model.providers

import com.cordiact.core.conversation.GenerationParameters
import com.cordiact.core.conversation.MessageRole
import com.cordiact.core.conversation.ModelAdapterPlugin
import com.cordiact.core.conversation.ModelEvent
import com.cordiact.core.conversation.ModelGenerationRequest
import com.cordiact.core.conversation.ModelId
import com.cordiact.core.conversation.ReasoningEffort
import com.cordiact.core.conversation.ResponseSpeed
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
    fun modelDescriptorPublishesOnlyDeclaredCapabilitiesAndMaxLabelDoesNotChangeWire() {
        val cap = ModelParameterCapabilities.openAi(setOf(ReasoningEffort.NONE, ReasoningEffort.MINIMAL, ReasoningEffort.MAX), supportsFast = true)
        val config = ModelProviderConfig.forLoopbackTest(
            providerId = "capabilities", displayName = "Capabilities", protocol = ModelProtocol.OPENAI_CHAT_COMPLETIONS,
            endpoint = "http://127.0.0.1:12345/v1/chat/completions", models = listOf("gpt-test", "plain"),
            parameterCapabilities = mapOf("gpt-test" to cap),
            defaults = GenerationParameters(reasoningEffort = ReasoningEffort.MAX, responseSpeed = ResponseSpeed.FAST))
        ConfigurableModelBackend(config, ApiKeyProvider { "test" }).use { backend ->
            val model = backend.models().first()
            assertEquals(listOf("none", "auto", "minimal", "Max"),
                com.cordiact.core.composer.ComposerRules.effortStops(model.capabilities).map(com.cordiact.core.composer.ComposerRules::effortLabel))
            assertTrue(backend.models().last().capabilities.reasoningEfforts.isEmpty())
            val auto = backend.requestBody(requestWithParameters("capabilities", "gpt-test",
                com.cordiact.core.composer.ComposerRules.parameters(com.cordiact.core.composer.ComposerRules.Tuning(ReasoningEffort.AUTO, ResponseSpeed.AUTO))))
            assertFalse(auto.has("reasoning_effort"))
            assertFalse(auto.has("service_tier"))
            val max = backend.requestBody(requestWithParameters("capabilities", "gpt-test",
                com.cordiact.core.composer.ComposerRules.parameters(com.cordiact.core.composer.ComposerRules.Tuning(ReasoningEffort.MAX, ResponseSpeed.AUTO))))
            assertEquals("max", max.get("reasoning_effort").asString)
        }
    }

    @Test
    fun mapsOpenAiEffortAndSpeedPerModelAndOmitsAuto() {
        val capabilities = mapOf(
            "gpt-test" to ModelParameterCapabilities.openAi(
                reasoningEfforts = setOf(ReasoningEffort.NONE, ReasoningEffort.LOW, ReasoningEffort.MAX),
                supportsFast = true,
                ultrafastWireValue = "ultrafast",
            ),
        )
        val config = ModelProviderConfig.forLoopbackTest(
            providerId = "openai-test",
            displayName = "OpenAI test",
            protocol = ModelProtocol.OPENAI_RESPONSES,
            endpoint = "http://127.0.0.1:12345/v1/responses",
            models = listOf("gpt-test"),
            parameterCapabilities = capabilities,
        )
        val backend = ConfigurableModelBackend(config, ApiKeyProvider { "test" })
        val request = requestWithParameters(
            "openai-test", "gpt-test",
            GenerationParameters(reasoningEffort = ReasoningEffort.MAX, responseSpeed = ResponseSpeed.FAST),
        )
        val body = backend.requestBody(request)
        assertEquals("max", body.getAsJsonObject("reasoning").get("effort").asString)
        assertEquals("fast", body.get("service_tier").asString)

        val autoBody = backend.requestBody(requestWithParameters(
            "openai-test", "gpt-test",
            GenerationParameters(reasoningEffort = ReasoningEffort.AUTO, responseSpeed = ResponseSpeed.AUTO),
        ))
        assertFalse(autoBody.has("reasoning"))
        assertFalse(autoBody.has("service_tier"))

        val chatConfig = ModelProviderConfig.forLoopbackTest(
            providerId = "openai-chat-test",
            displayName = "OpenAI Chat test",
            protocol = ModelProtocol.OPENAI_CHAT_COMPLETIONS,
            endpoint = "http://127.0.0.1:12345/v1/chat/completions",
            models = listOf("gpt-test"),
            parameterCapabilities = capabilities,
        )
        val chatBody = ConfigurableModelBackend(chatConfig, ApiKeyProvider { "test" }).use { chatBackend ->
            chatBackend.requestBody(requestWithParameters(
                "openai-chat-test", "gpt-test",
                GenerationParameters(reasoningEffort = ReasoningEffort.MAX, responseSpeed = ResponseSpeed.FAST),
            ))
        }
        assertEquals("max", chatBody.get("reasoning_effort").asString)
        assertEquals("fast", chatBody.get("service_tier").asString)

        assertThrows(IllegalArgumentException::class.java) {
            backend.requestBody(requestWithParameters(
                "openai-test", "gpt-test", GenerationParameters(reasoningEffort = ReasoningEffort.XHIGH),
            ))
        }
        backend.close()
    }

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
    fun registryRestoresPersistedProvidersOnEnable() {
        val store = InMemoryModelProviderConfigStore()
        val persisted = config(ModelProtocol.ANTHROPIC_MESSAGES, "custom", "https://api.example.test/v1/messages")
        store.save(persisted)

        val adapter = ModelAdapterPlugin()
        adapter.onEnable()
        // A second registry over the same store models an app restart: onEnable must re-attach
        // every stored provider without any extra call from the host.
        val registry = ModelProviderRegistryPlugin(adapter, { ApiKeyProvider { "test-key" } }, store)
        try {
            registry.onEnable()
            assertEquals(listOf(persisted), registry.providers())
            assertTrue(adapter.models().any { it.id == ModelId("custom", "model") })
        } finally {
            registry.onDisable()
            adapter.onDisable()
        }
    }

    @Test
    fun registryRestoresLegacyDeepSeekAsAnOrdinaryProvider() {
        val store = InMemoryModelProviderConfigStore()
        store.save(config(ModelProtocol.OPENAI_CHAT_COMPLETIONS, DeepSeekPreset.PROVIDER_ID, DeepSeekPreset.DEFAULT_ENDPOINT))
        val adapter = ModelAdapterPlugin()
        adapter.onEnable()
        try {
            val registry = ModelProviderRegistryPlugin(adapter, { ApiKeyProvider { "test-key" } }, store)
            registry.onEnable()
            assertTrue(registry.providers().any { it.providerId == "deepseek" })
            assertTrue(adapter.models().any { it.id.providerId == "deepseek" })
            registry.onDisable()
        } finally {
            adapter.onDisable()
        }
    }

    @Test
    fun providerDefaultsApplyWhenTheRequestCarriesNoParameters() {
        val server = startServer("data: {\"choices\":[{\"delta\":{\"content\":\"ok\"},\"finish_reason\":\"stop\"}]}\n\ndata: [DONE]\n\n")
        val config = ModelProviderConfig.forLoopbackTest(
            providerId = "preset",
            displayName = "Preset",
            protocol = ModelProtocol.OPENAI_CHAT_COMPLETIONS,
            endpoint = "${server.baseUrl}/v1/chat/completions",
            models = listOf("model"),
            defaults = GenerationParameters(temperature = 0.2, maxOutputTokens = 256, topP = 0.9),
        )
        val backend = ConfigurableModelBackend(config, ApiKeyProvider { "key" })
        val done = CountDownLatch(1)
        try {
            backend.generate(requestWithoutParameters("preset", "model")) { event ->
                if (event is ModelEvent.Completed || event is ModelEvent.Failed) done.countDown()
            }
            assertTrue(done.await(5, TimeUnit.SECONDS))
            val json = JsonParser.parseString(server.requestBody).asJsonObject
            assertEquals(0.2, json.get("temperature").asDouble)
            assertEquals(256, json.get("max_tokens").asInt)
            assertEquals(0.9, json.get("top_p").asDouble)
        } finally {
            backend.close()
            server.close()
        }
    }

    @Test
    fun requestParametersOverrideProviderDefaultsFieldByField() {
        val server = startServer("data: {\"choices\":[{\"delta\":{\"content\":\"ok\"},\"finish_reason\":\"stop\"}]}\n\ndata: [DONE]\n\n")
        val config = ModelProviderConfig.forLoopbackTest(
            providerId = "preset",
            displayName = "Preset",
            protocol = ModelProtocol.OPENAI_CHAT_COMPLETIONS,
            endpoint = "${server.baseUrl}/v1/chat/completions",
            models = listOf("model"),
            defaults = GenerationParameters(temperature = 0.2, maxOutputTokens = 256, topP = 0.9),
        )
        val backend = ConfigurableModelBackend(config, ApiKeyProvider { "key" })
        val done = CountDownLatch(1)
        try {
            // Only temperature is overridden; max_tokens and top_p must fall back to the provider.
            backend.generate(
                requestWithParameters("preset", "model", GenerationParameters(temperature = 1.1)),
            ) { event -> if (event is ModelEvent.Completed || event is ModelEvent.Failed) done.countDown() }
            assertTrue(done.await(5, TimeUnit.SECONDS))
            val json = JsonParser.parseString(server.requestBody).asJsonObject
            assertEquals(1.1, json.get("temperature").asDouble)
            assertEquals(256, json.get("max_tokens").asInt)
            assertEquals(0.9, json.get("top_p").asDouble)
        } finally {
            backend.close()
            server.close()
        }
    }

    @Test
    fun topPIsMappedIntoEveryProtocolBody() {
        val cases = listOf(
            ModelProtocol.OPENAI_CHAT_COMPLETIONS to "top_p",
            ModelProtocol.ANTHROPIC_MESSAGES to "top_p",
            ModelProtocol.OPENAI_RESPONSES to "top_p",
            ModelProtocol.GEMINI_GENERATE_CONTENT to "topP",
        )
        cases.forEach { (protocol, key) ->
            val response = when (protocol) {
                ModelProtocol.ANTHROPIC_MESSAGES -> "event: message_stop\ndata: {\"type\":\"message_stop\"}\n\n"
                ModelProtocol.GEMINI_GENERATE_CONTENT -> "data: {\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"ok\"}]},\"finishReason\":\"STOP\"}]}\n\n"
                ModelProtocol.OPENAI_RESPONSES -> "event: response.completed\ndata: {\"type\":\"response.completed\"}\n\n"
                else -> "data: {\"choices\":[{\"delta\":{\"content\":\"ok\"},\"finish_reason\":\"stop\"}]}\n\ndata: [DONE]\n\n"
            }
            val server = startServer(response)
            val endpoint = if (protocol == ModelProtocol.GEMINI_GENERATE_CONTENT) {
                "${server.baseUrl}/v1beta/models/{model}:streamGenerateContent?alt=sse"
            } else {
                "${server.baseUrl}/endpoint"
            }
            val config = ModelProviderConfig.forLoopbackTest(
                providerId = "prov",
                displayName = "P",
                protocol = protocol,
                endpoint = endpoint,
                models = listOf("m"),
            )
            val backend = ConfigurableModelBackend(config, ApiKeyProvider { "key" })
            val done = CountDownLatch(1)
            try {
                backend.generate(requestWithParameters("prov", "m", GenerationParameters(topP = 0.42))) { event ->
                    if (event is ModelEvent.Completed || event is ModelEvent.Failed) done.countDown()
                }
                assertTrue(done.await(5, TimeUnit.SECONDS), "$protocol did not terminate")
                val json = JsonParser.parseString(server.requestBody).asJsonObject
                val container = if (protocol == ModelProtocol.GEMINI_GENERATE_CONTENT) json.getAsJsonObject("generationConfig") else json
                assertEquals(0.42, container.get(key).asDouble, "$protocol must carry $key")
            } finally {
                backend.close()
                server.close()
            }
        }
    }

    @Test
    fun generationParametersRejectOutOfRangeValues() {
        assertThrows(IllegalArgumentException::class.java) { GenerationParameters(temperature = 2.1) }
        assertThrows(IllegalArgumentException::class.java) { GenerationParameters(temperature = -0.1) }
        assertThrows(IllegalArgumentException::class.java) { GenerationParameters(topP = 1.1) }
        assertThrows(IllegalArgumentException::class.java) { GenerationParameters(maxOutputTokens = 0) }
    }

    @Test
    fun updateProviderChangesDefaultsWithoutTouchingTheCredential() {
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
        val original = config(ModelProtocol.OPENAI_CHAT_COMPLETIONS, "custom", "https://api.example.test/v1/chat/completions")
        try {
            registry.addProvider(original)
            val tuned = ModelProviderConfig(
                providerId = "custom",
                displayName = "Custom OPENAI_CHAT_COMPLETIONS",
                protocol = ModelProtocol.OPENAI_CHAT_COMPLETIONS,
                endpoint = "https://api.example.test/v1/chat/completions",
                models = listOf("model"),
                connectTimeoutMillis = 2_000,
                readTimeoutMillis = 2_000,
                defaults = GenerationParameters(temperature = 0.7, maxOutputTokens = 1024, topP = 0.85),
            )
            registry.updateProvider(tuned)

            assertEquals(listOf(tuned), registry.providers())
            assertEquals(listOf(tuned), store.load())
            // The provider id did not change, so its credential must not be revoked.
            assertTrue(forgottenCredentials.isEmpty())
            assertTrue(adapter.models().any { it.id == ModelId("custom", "model") })
        } finally {
            registry.onDisable()
            adapter.onDisable()
        }
    }

    @Test
    fun updateProviderRejectsAnUnknownProvider() {
        val adapter = ModelAdapterPlugin()
        val registry = ModelProviderRegistryPlugin(adapter, { ApiKeyProvider { "test-key" } }, InMemoryModelProviderConfigStore())
        adapter.onEnable()
        registry.onEnable()
        try {
            val failure = assertThrows(IllegalArgumentException::class.java) {
                registry.updateProvider(config(ModelProtocol.OPENAI_CHAT_COMPLETIONS, "ghost", "https://api.example.test/v1/chat/completions"))
            }
            assertTrue(failure.message.orEmpty().contains("not configured"))
        } finally {
            registry.onDisable()
            adapter.onDisable()
        }
    }

    @Test
    fun updateProviderRebuildsTheBackendWhenTheProtocolChanges() {
        val adapter = ModelAdapterPlugin()
        val store = InMemoryModelProviderConfigStore()
        val registry = ModelProviderRegistryPlugin(adapter, { ApiKeyProvider { "test-key" } }, store)
        adapter.onEnable()
        registry.onEnable()
        val original = config(ModelProtocol.OPENAI_CHAT_COMPLETIONS, "custom", "https://api.example.test/v1/chat/completions")
        try {
            registry.addProvider(original)
            val switched = ModelProviderConfig(
                providerId = "custom",
                displayName = "Custom",
                protocol = ModelProtocol.ANTHROPIC_MESSAGES,
                endpoint = "https://api.example.test/v1/messages",
                models = listOf("model"),
                defaults = GenerationParameters(temperature = 0.1),
            )
            registry.updateProvider(switched)
            assertEquals(listOf(switched), registry.providers())
            assertTrue(adapter.models().any { it.id == ModelId("custom", "model") })
        } finally {
            registry.onDisable()
            adapter.onDisable()
        }
    }

    @Test
    fun updateProviderRejectsATestOnlyLoopbackConfig() {
        val adapter = ModelAdapterPlugin()
        val registry = ModelProviderRegistryPlugin(adapter, { ApiKeyProvider { "test-key" } }, InMemoryModelProviderConfigStore())
        adapter.onEnable()
        registry.onEnable()
        try {
            registry.addProvider(config(ModelProtocol.OPENAI_CHAT_COMPLETIONS, "custom", "https://api.example.test/v1/chat/completions"))
            val loopback = ModelProviderConfig.forLoopbackTest(
                providerId = "custom",
                displayName = "Custom",
                protocol = ModelProtocol.OPENAI_CHAT_COMPLETIONS,
                endpoint = "http://127.0.0.1:8080/v1/chat/completions",
                models = listOf("model"),
            )
            val failure = assertThrows(IllegalArgumentException::class.java) {
                registry.updateProvider(loopback)
            }
            assertTrue(failure.message.orEmpty().contains("loopback"))
        } finally {
            registry.onDisable()
            adapter.onDisable()
        }
    }

    @Test
    fun deepSeekDefaultModelRemainsFlash() {
        assertEquals("deepseek-flash", DeepSeekPreset.DEFAULT_MODEL)
        assertEquals(listOf("deepseek-flash"), DeepSeekPreset.config().models)
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

    private fun requestWithoutParameters(providerId: String, modelId: String) = ModelGenerationRequest(
        sessionId = "session",
        model = ModelId(providerId, modelId),
        messages = listOf(
            com.cordiact.core.conversation.ModelMessage(MessageRole.USER, "hello user"),
        ),
    )

    private fun requestWithParameters(providerId: String, modelId: String, parameters: GenerationParameters) =
        ModelGenerationRequest(
            sessionId = "session",
            model = ModelId(providerId, modelId),
            messages = listOf(
                com.cordiact.core.conversation.ModelMessage(MessageRole.USER, "hello user"),
            ),
            parameters = parameters,
        )

    private fun request(providerId: String, modelId: String) = ModelGenerationRequest(
        sessionId = "session",
        model = ModelId(providerId, modelId),
        messages = listOf(
            com.cordiact.core.conversation.ModelMessage(MessageRole.SYSTEM, "system instruction"),
            com.cordiact.core.conversation.ModelMessage(MessageRole.USER, "hello user"),
        ),
        parameters = GenerationParameters(temperature = 0.3, maxOutputTokens = 512),
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
