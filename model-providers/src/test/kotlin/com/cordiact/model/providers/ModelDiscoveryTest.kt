package com.cordiact.model.providers

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class ModelDiscoveryTest {
    @Test fun normalizesBaseAndGenerationUrlsForEveryProtocol() {
        val urls = mapOf(
            ModelProtocol.OPENAI_CHAT_COMPLETIONS to "chat/completions",
            ModelProtocol.OPENAI_RESPONSES to "responses",
            ModelProtocol.ANTHROPIC_MESSAGES to "messages",
            ModelProtocol.GEMINI_GENERATE_CONTENT to "models/{model}:streamGenerateContent?alt=sse",
        )
        urls.forEach { (protocol, suffix) ->
            val base = if (protocol == ModelProtocol.GEMINI_GENERATE_CONTENT) "https://example.com/v1beta" else "https://example.com/v1"
            listOf(base, "$base/", "$base/$suffix", "$base/models").forEach { url ->
                val input = ModelProviderConnection("test", "Test", protocol, url)
                assertEquals("$base/models", input.modelsEndpoint())
                assertEquals("$base/$suffix", input.generationEndpoint())
            }
            val root = ModelProviderConnection("test", "Test", protocol, "https://example.com")
            assertEquals("$base/models", root.modelsEndpoint())
        }
    }

    @Test fun rejectsInsecureAndCredentialBearingUrls() {
        listOf("http://example.com", "https://user:secret@example.com/v1", "https://example.com/v1?key=secret", "https://example.com/#secret").forEach { url ->
            assertThrows(IllegalArgumentException::class.java) { ModelProviderConnection("p", "P", ModelProtocol.OPENAI_RESPONSES, url).modelsEndpoint() }
        }
    }

    @Test fun openAiListDeduplicatesAndUsesBearerHeader() = server { server, endpoint ->
        server.createContext("/models") { exchange ->
            assertEquals("Bearer test-key", exchange.requestHeaders.getFirst("Authorization"))
            respond(exchange, """{"data":[{"id":"model-a"},{"id":"model-a"},{"id":"model-b"}]}""")
        }
        assertEquals(listOf("model-a", "model-b"), HttpModelDiscoveryService().discoverAt(endpoint, ModelProtocol.OPENAI_RESPONSES, "test-key"))
    }

    @Test fun anthropicPaginatesOnSameEndpointAndUsesCorrectHeaders() = server { server, endpoint ->
        val queries = mutableListOf<String?>()
        server.createContext("/models") { exchange ->
            assertEquals("test-key", exchange.requestHeaders.getFirst("x-api-key"))
            assertEquals("2023-06-01", exchange.requestHeaders.getFirst("anthropic-version"))
            queries += exchange.requestURI.rawQuery
            respond(exchange, if (queries.size == 1) """{"data":[{"id":"claude-a"}],"has_more":true,"last_id":"claude-a"}"""
                else """{"data":[{"id":"claude-b"}],"has_more":false}""")
        }
        assertEquals(listOf("claude-a", "claude-b"), HttpModelDiscoveryService().discoverAt(endpoint, ModelProtocol.ANTHROPIC_MESSAGES, "test-key"))
        assertEquals(listOf(null, "after_id=claude-a"), queries)
    }

    @Test fun geminiPaginatesFiltersEmbeddingAndStripsModelPrefix() = server { server, endpoint ->
        server.createContext("/models") { exchange ->
            assertEquals("test-key", exchange.requestHeaders.getFirst("x-goog-api-key"))
            assertNull(exchange.requestURI.rawQuery?.takeIf { it.contains("key=") })
            respond(exchange, if (exchange.requestURI.rawQuery == null)
                """{"models":[{"name":"models/gemini-a","supportedGenerationMethods":["generateContent"]},{"name":"models/embed","supportedGenerationMethods":["embedContent"]}],"nextPageToken":"a&b"}"""
                else """{"models":[{"name":"models/gemini-b","supportedGenerationMethods":["generateContent"]}]}""")
        }
        assertEquals(listOf("gemini-a", "gemini-b"), HttpModelDiscoveryService().discoverAt(endpoint, ModelProtocol.GEMINI_GENERATE_CONTENT, "test-key"))
    }

    @Test fun redirectIsNotFollowedAndErrorsNeverEchoRemoteBody() = server { server, endpoint ->
        var leaked = false
        server.createContext("/models") { exchange ->
            exchange.responseHeaders.set("Location", "$endpoint/leak")
            exchange.sendResponseHeaders(302, -1); exchange.close()
        }
        server.createContext("/models/leak") { exchange -> leaked = true; respond(exchange, "{}") }
        val failure = assertThrows(IllegalStateException::class.java) { HttpModelDiscoveryService().discoverAt(endpoint, ModelProtocol.OPENAI_RESPONSES, "test-key") }
        assertEquals("MODEL_DISCOVERY_HTTP_302", failure.message)
        assertFalse(leaked)
    }

    @Test fun malformedEmptyAndOversizedResponsesFailClosed() {
        listOf("not-json", "{}", """{"data":[]}""", """{"data":[{"id":1}]}""", """{"data":[{"id":""}]}""", " ".repeat(2 * 1024 * 1024 + 1)).forEach { body ->
            server { server, endpoint ->
                server.createContext("/models") { respond(it, body) }
                assertThrows(IllegalStateException::class.java) { HttpModelDiscoveryService().discoverAt(endpoint, ModelProtocol.OPENAI_RESPONSES, "test-key") }
            }
        }
    }

    @Test fun repeatingCursorStopsInsteadOfPartialSuccess() = server { server, endpoint ->
        server.createContext("/models") { respond(it, """{"data":[{"id":"a"}],"has_more":true,"last_id":"a"}""") }
        assertThrows(IllegalStateException::class.java) { HttpModelDiscoveryService().discoverAt(endpoint, ModelProtocol.ANTHROPIC_MESSAGES, "test-key") }
    }

    private fun server(block: (HttpServer, String) -> Unit) {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.start()
        try { block(server, "http://127.0.0.1:${server.address.port}/models") } finally { server.stop(0) }
    }
    private fun respond(exchange: com.sun.net.httpserver.HttpExchange, body: String) {
        val bytes = body.toByteArray()
        exchange.sendResponseHeaders(200, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
        exchange.close()
    }
}
