package com.cordiact.search.providers

import com.cordiact.core.search.CancellationSignal
import com.cordiact.core.search.ModelRoute
import com.cordiact.core.search.SearchException
import com.cordiact.core.search.SearchRequest
import com.google.gson.JsonParser
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class NativeProvidersTest {
    private var server: HttpServer? = null
    private val lastBody = AtomicReference<String>()
    private val lastPath = AtomicReference<String>()
    private val lastKey = AtomicReference<String>()

    @AfterTest
    fun stop() {
        server?.stop(0)
    }

    private fun start(status: Int, response: String): String {
        val instance = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        instance.createContext("/") { exchange ->
            lastPath.set(exchange.requestURI.path)
            lastKey.set(
                exchange.requestHeaders.getFirst("x-api-key")
                    ?: exchange.requestHeaders.getFirst("x-goog-api-key")
                    ?: exchange.requestHeaders.getFirst("authorization"),
            )
            lastBody.set(exchange.requestBody.readBytes().toString(Charsets.UTF_8))
            val bytes = response.toByteArray(Charsets.UTF_8)
            exchange.responseHeaders.add("content-type", "application/json")
            exchange.sendResponseHeaders(status, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        instance.start()
        server = instance
        return "http://127.0.0.1:${instance.address.port}"
    }

    private val anthropicRoute = ModelRoute("anthropic", "claude-sonnet-4")
    private val openAiRoute = ModelRoute("openai", "gpt-4o")
    private val geminiRoute = ModelRoute("google", "gemini-2.5-flash")

    @Test
    fun `anthropic sends the server tool and maps results`() {
        val base = start(
            200,
            """
            {"content":[
              {"type":"text","text":"answer","citations":[{"url":"https://a.example","cited_text":"quote"}]},
              {"type":"web_search_tool_result","content":[
                {"type":"web_search_result","url":"https://a.example","title":"A","page_age":"1 day ago"}
              ]}
            ]}
            """.trimIndent(),
        )
        val provider = AnthropicNativeSearchProvider(apiKey = { "sk-ant" }, baseUrl = { "$base/v1" })
        val result = provider.search(SearchRequest("q", 8, route = anthropicRoute), CancellationSignal.NONE)
        assertEquals("/v1/messages", lastPath.get())
        assertEquals("sk-ant", lastKey.get())
        val body = JsonParser.parseString(lastBody.get()).asJsonObject
        assertEquals("claude-sonnet-4", body.get("model").asString)
        assertEquals("web_search_20250305", body.getAsJsonArray("tools")[0].asJsonObject.get("type").asString)
        assertEquals("quote", result.sources.single().snippet)
    }

    @Test
    fun `anthropic is only ready for its own route`() {
        val provider = AnthropicNativeSearchProvider(apiKey = { "k" })
        assertTrue(provider.ready(anthropicRoute))
        assertEquals(false, provider.ready(openAiRoute))
    }

    @Test
    fun `openai reads url citations from responses output`() {
        val base = start(
            200,
            """
            {"output":[
              {"type":"web_search_call","id":"ws_1","status":"completed"},
              {"type":"message","role":"assistant","content":[
                {"type":"output_text","text":"the answer","annotations":[
                  {"type":"url_citation","url":"https://a.example","title":"A","text":"excerpt"},
                  {"type":"url_citation","url":"https://a.example","title":"A again","text":"dup"}
                ]}
              ]}
            ]}
            """.trimIndent(),
        )
        val provider = OpenAiNativeSearchProvider(apiKey = { "sk-oa" }, baseUrl = { "$base/v1/responses" })
        val result = provider.search(SearchRequest("q", 8, route = openAiRoute), CancellationSignal.NONE)
        assertEquals("Bearer sk-oa", lastKey.get())
        val body = JsonParser.parseString(lastBody.get()).asJsonObject
        assertEquals("web_search", body.getAsJsonArray("tools")[0].asJsonObject.get("type").asString)
        assertEquals("the answer", result.content)
        assertEquals(1, result.sources.size)
        assertEquals("excerpt", result.sources.single().snippet)
    }

    @Test
    fun `openai rejects a response without output`() {
        val base = start(200, """{"id":"resp_1"}""")
        val provider = OpenAiNativeSearchProvider(apiKey = { "k" }, baseUrl = { "$base/v1/responses" })
        val failure = assertFailsWith<SearchException> {
            provider.search(SearchRequest("q", 8, route = openAiRoute), CancellationSignal.NONE)
        }
        assertEquals(SearchException.Code.INVALID_RESPONSE, failure.code)
    }

    @Test
    fun `gemini maps grounding chunks and supports`() {
        val base = start(
            200,
            """
            {"candidates":[{
              "content":{"parts":[{"text":"gemini answer"}]},
              "groundingMetadata":{
                "groundingChunks":[
                  {"web":{"uri":"https://a.example","title":"A"}},
                  {"web":{"uri":"https://b.example","title":"B"}}
                ],
                "groundingSupports":[
                  {"segment":{"text":"excerpt a"},"groundingChunkIndices":[0]},
                  {"segment":{"text":"excerpt b"},"groundingChunkIndices":[1]}
                ]
              }
            }]}
            """.trimIndent(),
        )
        val provider = GeminiNativeSearchProvider(
            apiKey = { "gm-key" },
            baseUrlTemplate = { "$base/v1beta/models/{model}:generateContent" },
        )
        val result = provider.search(SearchRequest("q", 8, route = geminiRoute), CancellationSignal.NONE)
        assertEquals("/v1beta/models/gemini-2.5-flash:generateContent", lastPath.get())
        assertEquals("gm-key", lastKey.get())
        val body = JsonParser.parseString(lastBody.get()).asJsonObject
        assertTrue(body.getAsJsonArray("tools")[0].asJsonObject.has("google_search"))
        assertEquals("gemini answer", result.content)
        assertEquals(2, result.sources.size)
        assertEquals("excerpt a", result.sources[0].snippet)
    }

    @Test
    fun `gemini without grounding metadata returns no sources`() {
        val base = start(200, """{"candidates":[{"content":{"parts":[{"text":"plain"}]}}]}""")
        val provider = GeminiNativeSearchProvider(
            apiKey = { "k" },
            baseUrlTemplate = { "$base/v1beta/models/{model}:generateContent" },
        )
        val result = provider.search(SearchRequest("q", 8, route = geminiRoute), CancellationSignal.NONE)
        assertEquals("plain", result.content)
        assertTrue(result.sources.isEmpty())
    }

    @Test
    fun `gemini requires a model placeholder in the endpoint`() {
        val provider = GeminiNativeSearchProvider(apiKey = { "k" }, baseUrlTemplate = { "http://127.0.0.1:1/fixed" })
        assertEquals(false, provider.available())
    }
}
