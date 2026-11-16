package com.cordiact.model.providers

import com.cordiact.model.providers.image.OpenAiImageGenerator
import com.google.gson.JsonParser
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.Base64
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class OpenAiImageGeneratorTest {
    private val png = Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+jG6kAAAAASUVORK5CYII=")
    @Test fun generatesOnePngWithoutStreamingOrChatModelExposure() = withServer { server, config ->
        var count = 0
        server.createContext("/v1/images/generations") { e ->
            count++
            assertEquals("Bearer test", e.requestHeaders.getFirst("Authorization"))
            val body = JsonParser.parseString(e.requestBody.readBytes().toString(Charsets.UTF_8)).asJsonObject
            assertEquals("gpt-image-1.5", body["model"].asString)
            assertEquals(1, body["n"].asInt)
            assertEquals("png", body["output_format"].asString)
            assertFalse(body.has("stream")); assertFalse(body.has("response_format"))
            val bytes = """{"data":[{"b64_json":"${Base64.getEncoder().encodeToString(png)}"}]}""".toByteArray()
            e.sendResponseHeaders(200, bytes.size.toLong()); e.responseBody.use { it.write(bytes) }
        }
        val image = OpenAiImageGenerator().generate(config, ApiKeyProvider { "test" }, OpenAiImageGenerator.Request("gpt-image-1.5", "red apple"))
        assertArrayEquals(png, image.bytes); assertEquals(1, count)
        ConfigurableModelBackend(config, ApiKeyProvider { "test" }).use { assertTrue(it.models().isEmpty()) }
        assertFalse(supportsToolCalls(ModelProtocol.OPENAI_IMAGES))
    }
    @Test fun httpFailureIsNotRetriedAndDoesNotExposeResponseBody() = withServer { server, config ->
        var count = 0
        server.createContext("/v1/images/generations") { e -> count++; val bytes = "private-provider-error".toByteArray(); e.sendResponseHeaders(429, bytes.size.toLong()); e.responseBody.use { it.write(bytes) } }
        val error = assertThrows(IllegalStateException::class.java) { OpenAiImageGenerator().generate(config, ApiKeyProvider { "test" }, OpenAiImageGenerator.Request("gpt-image-1.5", "apple")) }
        assertTrue(error.message!!.contains("429")); assertFalse(error.message!!.contains("private-provider-error")); assertEquals(1, count)
    }
    @Test fun refusesUrlResponsesRatherThanFetchingUntrustedUrls() = withServer { server, config ->
        server.createContext("/v1/images/generations") { e -> val bytes = """{"data":[{"url":"http://127.0.0.1/private"}]}""".toByteArray(); e.sendResponseHeaders(200, bytes.size.toLong()); e.responseBody.use { it.write(bytes) } }
        assertThrows(IllegalStateException::class.java) { OpenAiImageGenerator().generate(config, ApiKeyProvider { "test" }, OpenAiImageGenerator.Request("gpt-image-1.5", "apple")) }
    }
    @Test fun derivesImageEndpointAndModelsEndpoint() {
        val connection = ModelProviderConnection("img", "Images", ModelProtocol.OPENAI_IMAGES, "https://api.example.com/v1/images/generations")
        assertEquals("https://api.example.com/v1/models", connection.modelsEndpoint())
        assertEquals("https://api.example.com/v1/images/generations", connection.generationEndpoint())
    }
    private fun withServer(block: (HttpServer, ModelProviderConfig) -> Unit) {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0); server.start()
        try { block(server, ModelProviderConfig.forLoopbackTest("img", "Images", ModelProtocol.OPENAI_IMAGES, "http://127.0.0.1:${server.address.port}/v1/images/generations", listOf("gpt-image-1.5"), 2000, 2000)) } finally { server.stop(0) }
    }
}
