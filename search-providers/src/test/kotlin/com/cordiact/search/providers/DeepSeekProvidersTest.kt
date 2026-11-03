package com.cordiact.search.providers

import com.cordiact.core.search.CancellationSignal
import com.cordiact.core.search.FetchRequest
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

class DeepSeekProvidersTest {
    private var server: HttpServer? = null

    @AfterTest
    fun stop() {
        server?.stop(0)
    }

    private fun start(status: Int, response: String): String {
        val capture = AtomicReference<String>()
        val path = AtomicReference<String>()
        val auth = AtomicReference<String>()
        val instance = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        instance.createContext("/") { exchange ->
            path.set(exchange.requestURI.path)
            auth.set(exchange.requestHeaders.getFirst("authorization"))
            capture.set(exchange.requestBody.readBytes().toString(Charsets.UTF_8))
            val bytes = response.toByteArray(Charsets.UTF_8)
            exchange.responseHeaders.add("content-type", "application/json")
            exchange.sendResponseHeaders(status, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        instance.start()
        server = instance
        lastPath = path
        lastAuth = auth
        lastBody = capture
        return "http://127.0.0.1:${instance.address.port}"
    }

    private lateinit var lastPath: AtomicReference<String>
    private lateinit var lastAuth: AtomicReference<String>
    private lateinit var lastBody: AtomicReference<String>

    @Test
    fun `search sends the DeepSeek contract and maps sources`() {
        val base = start(
            200,
            """
            {"results":[
              {"url":"https://a.example","title":"A","snippet":"sa","summary":"long","date":"2026-10-30"},
              {"url":"https://b.example","title":"B","snippet":"sb","summary":null,"date":null}
            ]}
            """.trimIndent(),
        )
        val provider = DeepSeekSearchApiProvider(
            DeepSeekWebConfig(baseUrl = "$base/v1/tools", apiKey = { "sk-test" }),
        )
        val result = provider.search(
            SearchRequest("kotlin", maxResults = 4, oriQuestion = "how to test"),
            CancellationSignal.NONE,
        )
        assertEquals("/v1/tools/search", lastPath.get())
        assertEquals("Bearer sk-test", lastAuth.get())
        val body = JsonParser.parseString(lastBody.get()).asJsonObject
        assertEquals("kotlin", body.get("query").asString)
        assertEquals(4, body.get("count").asInt)
        assertEquals(false, body.get("need_content").asBoolean)
        assertEquals("how to test", body.get("ori_question").asString)
        assertEquals(2, result.sources.size)
        assertEquals("2026-10-30", result.sources[0].publishedAt)
        assertEquals("long", result.sources[0].summary)
    }

    @Test
    fun `search maps 401 to a credential error`() {
        val base = start(401, """{"error":"bad key"}""")
        val provider = DeepSeekSearchApiProvider(
            DeepSeekWebConfig(baseUrl = "$base/v1/tools", apiKey = { "sk-test" }),
        )
        val failure = assertFailsWith<SearchException> {
            provider.search(SearchRequest("q", 4), CancellationSignal.NONE)
        }
        assertEquals(SearchException.Code.CREDENTIAL_MISSING, failure.code)
    }

    @Test
    fun `search rejects an invalid results body`() {
        val base = start(200, """{"results":"nope"}""")
        val provider = DeepSeekSearchApiProvider(
            DeepSeekWebConfig(baseUrl = "$base/v1/tools", apiKey = { "sk-test" }),
        )
        val failure = assertFailsWith<SearchException> {
            provider.search(SearchRequest("q", 4), CancellationSignal.NONE)
        }
        assertEquals(SearchException.Code.INVALID_RESPONSE, failure.code)
    }

    @Test
    fun `fetch posts the url and returns markdown`() {
        val base = start(200, """{"content":"# Title\n\nBody"}""")
        val provider = DeepSeekFetchApiProvider(
            DeepSeekWebConfig(baseUrl = "$base/v1/tools", apiKey = { "sk-test" }),
        )
        val result = provider.fetch(FetchRequest("https://example.com/a"), CancellationSignal.NONE)
        assertEquals("/v1/tools/fetch", lastPath.get())
        assertEquals("https://example.com/a", JsonParser.parseString(lastBody.get()).asJsonObject.get("url").asString)
        assertEquals(200, result.statusCode)
        assertTrue(result.content.startsWith("# Title"))
    }

    @Test
    fun `fetch rejects a non-http url before any request`() {
        val provider = DeepSeekFetchApiProvider(
            DeepSeekWebConfig(baseUrl = "http://127.0.0.1:1/v1/tools", apiKey = { "k" }),
        )
        val failure = assertFailsWith<SearchException> {
            provider.fetch(FetchRequest("file:///etc/passwd"), CancellationSignal.NONE)
        }
        assertEquals(SearchException.Code.INVALID_URL, failure.code)
    }

    @Test
    fun `native search sends the server tool and joins citations`() {
        val base = start(
            200,
            """
            {"content":[
              {"type":"text","text":"answer","citations":[{"url":"https://a.example","cited_text":"quote"}]},
              {"type":"web_search_tool_result","content":[
                {"type":"web_search_result","url":"https://a.example","title":"A","page_age":"2 days ago"},
                {"type":"web_search_result","url":"https://b.example","title":"B","page_age":null}
              ]}
            ]}
            """.trimIndent(),
        )
        val provider = DeepSeekNativeSearchProvider(
            apiKey = { "sk-test" },
            model = { "deepseek-flash" },
            baseUrl = { "$base/anthropic/v1" },
        )
        val result = provider.search(
            SearchRequest("q", 8, route = ModelRoute("deepseek", "deepseek-flash")),
            CancellationSignal.NONE,
        )
        assertEquals("/anthropic/v1/messages", lastPath.get())
        assertEquals("Bearer sk-test", lastAuthHeader())
        val body = JsonParser.parseString(lastBody.get()).asJsonObject
        assertEquals("deepseek-flash", body.get("model").asString)
        assertEquals("web_search_20250305", body.getAsJsonArray("tools")[0].asJsonObject.get("type").asString)
        assertEquals(5, body.getAsJsonArray("tools")[0].asJsonObject.get("max_uses").asInt)
        assertEquals(2, result.sources.size)
        assertEquals("quote", result.sources[0].snippet)
        assertEquals("2 days ago", result.sources[0].publishedAt)
    }

    @Test
    fun `native search is only ready for the DeepSeek route`() {
        val provider = DeepSeekNativeSearchProvider(
            apiKey = { "k" },
            model = { "deepseek-flash" },
        )
        assertTrue(provider.ready(ModelRoute("deepseek", "deepseek-flash")))
        assertEquals(false, provider.ready(ModelRoute("openai", "gpt")))
        assertEquals(false, provider.ready(null))
    }

    private fun lastAuthHeader(): String = lastAuth.get() ?: ""
}
