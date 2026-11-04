package com.cordiact.search.providers

import com.cordiact.core.search.CancellationSignal
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
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ProviderAdaptersTest {
    private var server: HttpServer? = null
    private var lastBody = AtomicReference<String>()
    private var lastQuery = AtomicReference<String>()
    private var lastAuth = AtomicReference<String>()

    @AfterTest
    fun stop() {
        server?.stop(0)
    }

    private fun start(status: Int, response: String): String {
        val instance = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        instance.createContext("/") { exchange ->
            lastQuery.set(exchange.requestURI.query)
            lastAuth.set(
                exchange.requestHeaders.getFirst("authorization")
                    ?: exchange.requestHeaders.getFirst("x-subscription-token")
                    ?: exchange.requestHeaders.getFirst("x-api-key"),
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

    @Test
    fun `tavily maps answer and sources`() {
        val base = start(
            200,
            """
            {"answer":"short answer","results":[
              {"url":"https://a.example","title":"A","content":"snippet a","published_date":"2026-10-01"},
              {"url":"https://b.example","title":"B","content":"snippet b"}
            ]}
            """.trimIndent(),
        )
        val provider = TavilySearchProvider(
            TavilyConfig(baseUrl = base, apiKey = { "tvly-test" }, includeAnswer = true),
        )
        val result = provider.search(SearchRequest("kotlin", 5), CancellationSignal.NONE)
        assertEquals("Bearer tvly-test", lastAuth.get())
        val body = JsonParser.parseString(lastBody.get()).asJsonObject
        assertEquals("kotlin", body.get("query").asString)
        assertEquals(5, body.get("max_results").asInt)
        assertEquals("basic", body.get("search_depth").asString)
        assertEquals("short answer", result.content)
        assertEquals(2, result.sources.size)
        assertEquals("2026-10-01", result.sources[0].publishedAt)
        assertTrue(provider.billable)
    }

    @Test
    fun `tavily maps 429 to rate limited`() {
        val base = start(429, "{}")
        val provider = TavilySearchProvider(TavilyConfig(baseUrl = base, apiKey = { "k" }))
        val failure = assertFailsWith<SearchException> {
            provider.search(SearchRequest("q", 5), CancellationSignal.NONE)
        }
        assertEquals(SearchException.Code.RATE_LIMITED, failure.code)
        assertTrue(failure.retryable)
    }

    @Test
    fun `brave sends the token header and maps web results`() {
        val base = start(
            200,
            """
            {"web":{"results":[
              {"url":"https://a.example","title":"A","description":"desc a","age":"2 days ago","extra_snippets":["more"]}
            ]}}
            """.trimIndent(),
        )
        val provider = BraveSearchProvider(
            BraveConfig(baseUrl = "$base/res/v1/web/search", apiKey = { "brave-key" }, country = "CN"),
        )
        val result = provider.search(SearchRequest("kotlin", 3), CancellationSignal.NONE)
        assertEquals("brave-key", lastAuth.get())
        val query = lastQuery.get()
        assertTrue(query.contains("q=kotlin"), "query string was $query")
        assertTrue(query.contains("count=3"))
        assertTrue(query.contains("country=CN"))
        assertEquals("https://a.example", result.sources.single().url)
        assertEquals("2 days ago", result.sources.single().publishedAt)
    }

    @Test
    fun `brave handles an empty web block`() {
        val base = start(200, """{"query":{"original":"x"}}""")
        val provider = BraveSearchProvider(BraveConfig(baseUrl = "$base/res/v1/web/search", apiKey = { "k" }))
        val result = provider.search(SearchRequest("x", 3), CancellationSignal.NONE)
        assertTrue(result.sources.isEmpty())
    }

    @Test
    fun `exa reads highlights as snippet and summary`() {
        val base = start(
            200,
            """
            {"results":[
              {"url":"https://a.example","title":"A","highlights":["first","second"],"publishedDate":"2026-09-09"}
            ]}
            """.trimIndent(),
        )
        val provider = ExaSearchProvider(ExaConfig(baseUrl = base, apiKey = { "exa-key" }))
        val result = provider.search(SearchRequest("q", 2), CancellationSignal.NONE)
        assertEquals("exa-key", lastAuth.get())
        val source = result.sources.single()
        assertEquals("first", source.snippet)
        assertEquals("second", source.summary)
        assertEquals("2026-09-09", source.publishedAt)
    }

    @Test
    fun `bocha reads webPages value and summary`() {
        val base = start(
            200,
            """
            {"code":200,"data":{"webPages":{"value":[
              {"name":"标题","url":"https://a.example","snippet":"摘要","summary":"长摘要","datePublished":"2026-10-02"}
            ]}}}
            """.trimIndent(),
        )
        val provider = BochaSearchProvider(BochaConfig(baseUrl = base, apiKey = { "bk" }))
        val result = provider.search(SearchRequest("查询", 3), CancellationSignal.NONE)
        assertEquals("标题", result.sources.single().title)
        assertEquals("摘要", result.sources.single().snippet)
        assertEquals("长摘要", result.sources.single().summary)
        assertEquals("2026-10-02", result.sources.single().publishedAt)
    }

    @Test
    fun `searxng builds the json query and maps results`() {
        val base = start(
            200,
            """
            {"results":[
              {"url":"https://a.example","title":"A","content":"body","publishedDate":"2026-10-03"}
            ]}
            """.trimIndent(),
        )
        val provider = SearxngSearchProvider(
            SearxngConfig(baseUrl = base, engines = "google,bing"),
        )
        val result = provider.search(SearchRequest("q", 3), CancellationSignal.NONE)
        val query = lastQuery.get()
        assertTrue(query.contains("format=json"), "query string was $query")
        // requestURI.query is the decoded form, so the comma arrives unescaped.
        assertTrue(query.contains("engines=google,bing"), "query string was $query")
        assertEquals("https://a.example", result.sources.single().url)
        assertEquals(false, provider.billable)
        assertNull(provider.pricing.usdPerCall)
    }

    @Test
    fun `searxng reports a clear error when json is disabled`() {
        val base = start(200, """{"results":"nope"}""")
        val provider = SearxngSearchProvider(SearxngConfig(baseUrl = base))
        val failure = assertFailsWith<SearchException> {
            provider.search(SearchRequest("q", 3), CancellationSignal.NONE)
        }
        assertEquals(SearchException.Code.INVALID_RESPONSE, failure.code)
        assertTrue(failure.message!!.contains("settings.yml"))
    }
}
