package com.cordiact.core.plugin

import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import java.io.IOException
import java.net.InetAddress
import java.net.URI
import java.util.concurrent.CancellationException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.*

class SignedPackageDownloadTest {
    @Test fun `only direct HTTPS GET with no cookies or credentials even after Set-Cookie`() = withServer { server, transport ->
        server.enqueue(MockResponse.Builder().body("first").addHeader("Set-Cookie", "private=one; Secure; Path=/").build())
        server.enqueue(MockResponse.Builder().chunkedBody("second", 2).build())
        assertEquals("first", transport.read(server))
        assertEquals("second", transport.read(server))
        assertEquals(2, server.requestCount)
        repeat(2) {
            val request = assertNotNull(server.takeRequest(1, TimeUnit.SECONDS))
            assertEquals("GET", request.method)
            assertEquals("/packages/demo-note/1.0.0.json", request.target)
            assertEquals(0L, request.bodySize)
            assertEquals("identity", request.headers["Accept-Encoding"])
            listOf("Authorization", "Cookie", "Proxy-Authorization", "Referer", "Range").forEach {
                assertNull(request.headers[it], it)
            }
        }
    }

    @Test fun `redirects authentication challenges and Retry-After zero never send a followup`() = withServer { server, transport ->
        listOf(301, 302, 307, 308, 401, 407, 503).forEachIndexed { index, code ->
            server.enqueue(MockResponse.Builder().code(code).body("private upstream message")
                .addHeader("Location", "/unexpected").addHeader("WWW-Authenticate", "Basic realm=test")
                .addHeader("Retry-After", "0").build())
            assertEquals("CATALOG_DOWNLOAD_FAILED", assertFailsWith<IOException> { transport.read(server) }.message)
            assertEquals(index + 1, server.requestCount)
        }
    }

    @Test fun `only complete bounded identity bodies are accepted`() = withServer { server, transport ->
        val limit = ExtensionPackageSource.MAX_SOURCE_BYTES
        server.enqueue(MockResponse.Builder().chunkedBody("a".repeat(limit), 512).build())
        assertEquals(limit, transport.read(server).length)
        val invalid = listOf(
            MockResponse.Builder().code(206).body("abc").build(),
            MockResponse.Builder().body("abc").addHeader("Content-Range", "bytes 0-2/3").build(),
            MockResponse.Builder().body("abc").addHeader("Content-Encoding", "gzip").build(),
            MockResponse.Builder().body("abc").removeHeader("Content-Length").addHeader("Connection", "close").build(),
            MockResponse.Builder().body("abc").addHeader("Content-Length", 3).build(),
            MockResponse.Builder().body("abc").setHeader("Content-Length", 20).addHeader("Connection", "close").build(),
            MockResponse.Builder().body("").build(),
            MockResponse.Builder().body("a".repeat(limit + 1)).build(),
            MockResponse.Builder().chunkedBody("a".repeat(limit + 1), 512).build(),
        )
        invalid.forEach { response -> server.enqueue(response); assertFails { transport.read(server) } }
        assertEquals(1 + invalid.size, server.requestCount)
    }

    @Test fun `cancelling while response is pending closes call without delivering bytes`() = withServer(timeoutMs = 5_000) { server, transport ->
        server.enqueue(MockResponse.Builder().headersDelay(10, TimeUnit.SECONDS).body("late").build())
        val worker = Executors.newSingleThreadExecutor()
        val cancellation = CatalogDownloadCancellation()
        try {
            val result = worker.submit<Throwable?> {
                runCatching { transport.get(server.artifactUri(), cancellation) {} }.exceptionOrNull()
            }
            assertNotNull(server.takeRequest(2, TimeUnit.SECONDS))
            cancellation.cancel()
            assertIs<CancellationException>(result.get(2, TimeUnit.SECONDS))
            assertEquals(1, server.requestCount)
        } finally { cancellation.cancel(); worker.shutdownNow() }
    }

    @Test fun `call timeout spans header and body and TLS rejects an untrusted root`() = withServer(timeoutMs = 600) { server, transport ->
        assertFailsWith<IOException> { IsolatedCatalogHttps().get(server.artifactUri(), CatalogDownloadCancellation()) {} }
        assertEquals(0, server.requestCount)
        server.enqueue(MockResponse.Builder().body("late").headersDelay(400, TimeUnit.MILLISECONDS)
            .bodyDelay(400, TimeUnit.MILLISECONDS).build())
        assertFailsWith<IOException> { transport.read(server) }
        assertEquals(1, server.requestCount)
    }

    @Test fun `directory transport uses a separate fixed body cap and no followup`() = withServer { server, transport ->
        val limit = CatalogSignedDocument.MAX_FRAME_SIZE
        val endpoint = URI(server.url("/catalog/trust.cdx").toString())
        server.enqueue(MockResponse.Builder().chunkedBody("a".repeat(limit), 1_024).build())
        assertEquals(limit, transport.getDocument(endpoint, CatalogDownloadCancellation()) {}.size)
        assertEquals("application/octet-stream", assertNotNull(server.takeRequest(1, TimeUnit.SECONDS)).headers["Accept"])
        server.enqueue(MockResponse.Builder().chunkedBody("a".repeat(limit + 1), 1_024).build())
        assertFails { transport.getDocument(endpoint, CatalogDownloadCancellation()) {} }
        server.enqueue(MockResponse.Builder().code(302).addHeader("Location", "/unexpected").build())
        assertFails { transport.getDocument(endpoint, CatalogDownloadCancellation()) {} }
        assertEquals(3, server.requestCount)
    }

    @Test fun `host retirement at the final HTTP gate sends no request`() = withServer { server, transport ->
        val gate = CountDownLatch(1)
        assertFailsWith<CancellationException> {
            transport.get(server.artifactUri(), CatalogDownloadCancellation()) {
                gate.countDown()
                throw CancellationException("retired")
            }
        }
        assertEquals(0L, gate.count)
        assertEquals(0, server.requestCount)
    }

    private fun IsolatedCatalogHttps.read(server: MockWebServer) = get(server.artifactUri(), CatalogDownloadCancellation()) {}.decodeToString()
    private fun MockWebServer.artifactUri() = URI(url("/packages/demo-note/1.0.0.json").toString())

    private fun withServer(timeoutMs: Long = 2_000, run: (MockWebServer, IsolatedCatalogHttps) -> Unit) {
        val certificate = HeldCertificate.Builder().commonName("localhost").addSubjectAlternativeName("localhost")
            .addSubjectAlternativeName("127.0.0.1").build()
        val serverKeys = HandshakeCertificates.Builder().heldCertificate(certificate).build()
        val clientKeys = HandshakeCertificates.Builder().addTrustedCertificate(certificate.certificate).build()
        val transport = IsolatedCatalogHttps.withTestTrust(OkHttpClient.Builder()
            .sslSocketFactory(clientKeys.sslSocketFactory(), clientKeys.trustManager), timeoutMs)
        MockWebServer().use { server ->
            server.protocols = listOf(Protocol.HTTP_1_1)
            server.useHttps(serverKeys.sslSocketFactory())
            server.start(InetAddress.getByName("127.0.0.1"), 0)
            run(server, transport)
        }
    }
}
