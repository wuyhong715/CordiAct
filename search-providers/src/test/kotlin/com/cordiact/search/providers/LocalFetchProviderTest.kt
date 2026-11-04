package com.cordiact.search.providers

import com.cordiact.core.search.CancellationSignal
import com.cordiact.core.search.FetchRequest
import com.cordiact.core.search.SearchException
import com.sun.net.httpserver.HttpServer
import java.net.InetAddress
import java.net.InetSocketAddress
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class LocalFetchProviderTest {
    private var server: HttpServer? = null

    @AfterTest
    fun stop() {
        server?.stop(0)
    }

    /** Serves the given path map; the local guard is told 127.0.0.1 is public for the test. */
    private fun serve(routes: Map<String, (com.sun.net.httpserver.HttpExchange) -> Unit>): String {
        val instance = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        for ((path, handler) in routes) {
            instance.createContext(path) { exchange ->
                handler(exchange)
                exchange.close()
            }
        }
        instance.start()
        server = instance
        return "http://127.0.0.1:${instance.address.port}"
    }

    private fun respond(status: Int, contentType: String, body: String): (com.sun.net.httpserver.HttpExchange) -> Unit =
        { exchange ->
            val bytes = body.toByteArray(Charsets.UTF_8)
            exchange.responseHeaders.add("content-type", contentType)
            exchange.sendResponseHeaders(status, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }

    /**
     * The real guard rejects loopback literals, which is the point. The test server
     * is on 127.0.0.1, so this guard allows loopback and keeps every other rule
     * intact; the dedicated "still runs on the first request" case covers the real one.
     */
    private fun permissiveGuard() = object : SsrfGuard() {
        override fun isPublic(address: InetAddress): Boolean =
            address.isLoopbackAddress || super.isPublic(address)
    }

    private fun provider(guard: SsrfGuard = permissiveGuard()) = LocalFetchProvider(
        LocalFetchConfig(enabled = { true }, timeoutMs = 5_000),
        guard,
    )

    @Test
    fun `fetches html and strips it to readable text`() {
        val base = serve(
            mapOf(
                "/" to respond(
                    200,
                    "text/html; charset=utf-8",
                    "<html><head><style>p{}</style><script>alert(1)</script></head>" +
                        "<body><h1>Title</h1><p>Hello&nbsp;world &amp; friends</p></body></html>",
                ),
            ),
        )
        val result = provider().fetch(FetchRequest("$base/"), CancellationSignal.NONE)
        assertEquals(200, result.statusCode)
        assertTrue(result.content.contains("Title"))
        assertTrue(result.content.contains("Hello world & friends"))
        assertTrue(!result.content.contains("alert(1)"), "scripts must not survive")
        assertTrue(!result.content.contains("<"), "no tags may remain")
    }

    @Test
    fun `plain text passes through unchanged`() {
        val base = serve(mapOf("/" to respond(200, "text/plain", "raw body\nsecond line")))
        val result = provider().fetch(FetchRequest("$base/"), CancellationSignal.NONE)
        assertEquals("raw body\nsecond line", result.content)
    }

    @Test
    fun `an unsupported content type is rejected`() {
        val base = serve(mapOf("/" to respond(200, "image/png", "binary")))
        val failure = assertFailsWith<SearchException> {
            provider().fetch(FetchRequest("$base/"), CancellationSignal.NONE)
        }
        assertEquals(SearchException.Code.INVALID_URL, failure.code)
        assertTrue(failure.message!!.contains("content type"))
    }

    @Test
    fun `redirects are followed when each hop is allowed`() {
        val base = serve(
            mapOf(
                "/start" to { exchange ->
                    exchange.responseHeaders.add("location", "/final")
                    exchange.sendResponseHeaders(302, -1)
                },
                "/final" to respond(200, "text/plain", "arrived"),
            ),
        )
        val result = provider().fetch(FetchRequest("$base/start"), CancellationSignal.NONE)
        assertEquals("arrived", result.content)
        assertEquals("$base/final", result.url)
    }

    @Test
    fun `a redirect to a private address is refused`() {
        val base = serve(
            mapOf(
                "/start" to { exchange ->
                    exchange.responseHeaders.add("location", "http://169.254.169.254/latest/meta-data/")
                    exchange.sendResponseHeaders(302, -1)
                },
            ),
        )
        val failure = assertFailsWith<SearchException> {
            provider().fetch(FetchRequest("$base/start"), CancellationSignal.NONE)
        }
        assertEquals(SearchException.Code.INVALID_URL, failure.code)
        assertTrue(failure.message!!.contains("private or reserved"))
    }

    @Test
    fun `a redirect loop is capped`() {
        val base = serve(
            mapOf(
                "/loop" to { exchange ->
                    exchange.responseHeaders.add("location", "/loop")
                    exchange.sendResponseHeaders(302, -1)
                },
            ),
        )
        val failure = assertFailsWith<SearchException> {
            provider().fetch(FetchRequest("$base/loop"), CancellationSignal.NONE)
        }
        assertEquals(SearchException.Code.INVALID_URL, failure.code)
        assertTrue(failure.message!!.contains("Too many redirects"))
    }

    @Test
    fun `a redirect without a location header is rejected`() {
        val base = serve(
            mapOf(
                "/broken" to { exchange -> exchange.sendResponseHeaders(302, -1) },
            ),
        )
        val failure = assertFailsWith<SearchException> {
            provider().fetch(FetchRequest("$base/broken"), CancellationSignal.NONE)
        }
        assertEquals(SearchException.Code.INVALID_URL, failure.code)
    }

    @Test
    fun `a non 2xx response is a provider error`() {
        val base = serve(mapOf("/" to respond(503, "text/plain", "down")))
        val failure = assertFailsWith<SearchException> {
            provider().fetch(FetchRequest("$base/"), CancellationSignal.NONE)
        }
        assertEquals(SearchException.Code.PROVIDER_ERROR, failure.code)
    }

    @Test
    fun `a disabled provider refuses to fetch`() {
        val disabled = LocalFetchProvider(LocalFetchConfig(enabled = { false }), permissiveGuard())
        assertEquals(false, disabled.available())
        val failure = assertFailsWith<SearchException> {
            disabled.fetch(FetchRequest("https://example.com/"), CancellationSignal.NONE)
        }
        assertEquals(SearchException.Code.PROVIDER_UNAVAILABLE, failure.code)
    }

    @Test
    fun `the guard still runs on the first request`() {
        val strict = LocalFetchProvider(
            LocalFetchConfig(enabled = { true }, timeoutMs = 2_000),
            SsrfGuard(),
        )
        val failure = assertFailsWith<SearchException> {
            strict.fetch(FetchRequest("http://127.0.0.1:1/"), CancellationSignal.NONE)
        }
        assertEquals(SearchException.Code.INVALID_URL, failure.code)
    }
}
