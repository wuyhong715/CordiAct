package com.cordiact.core.plugin

import okhttp3.Authenticator
import okhttp3.CacheControl
import okhttp3.ConnectionPool
import okhttp3.CookieJar
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import java.io.IOException
import java.net.ProtocolException
import java.net.Proxy
import java.net.URI
import java.util.concurrent.CancellationException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Host-only, explicit artifact download. This never refreshes an index, installs, enables or retries. */
class SignedPackageDownload internal constructor(
    private val catalog: SignedPackageCatalog,
    private val transport: CatalogArtifactTransport,
) {
    constructor(catalog: SignedPackageCatalog) : this(catalog, IsolatedCatalogHttps())

    /** The callback must test the *captured* page, workspace, policy and foreground identity, never select a new one. */
    fun prepare(selection: SignedPackageCatalog.Selection, isCurrent: () -> Boolean): Operation {
        checkCurrent(isCurrent)
        catalog.checkDownload(selection)
        return Operation { cancellation ->
            fun checkScope() {
                cancellation.checkActive()
                checkCurrent(isCurrent)
            }
            fun admit() {
                checkScope()
                catalog.checkDownload(selection)
                checkScope()
            }
            admit()
            val bytes = transport.get(selection.artifactUri, cancellation, ::admit)
            checkScope()
            val candidate = catalog.verify(selection, bytes)
            checkScope()
            candidate
        }
    }

    class Operation internal constructor(private val execute: (CatalogDownloadCancellation) -> SignedPackageCatalog.Verified) {
        private val used = AtomicBoolean()
        private val cancellation = CatalogDownloadCancellation()
        fun fetch(): SignedPackageCatalog.Verified {
            check(used.compareAndSet(false, true)) { DOWNLOAD_USED }
            return execute(cancellation)
        }
        fun cancel() = cancellation.cancel()
    }

    private fun checkCurrent(isCurrent: () -> Boolean) {
        if (Thread.currentThread().isInterrupted || !isCurrent()) throw CancellationException(DOWNLOAD_RETIRED)
    }
}

internal fun interface CatalogArtifactTransport {
    /** beforeSend is called at most once, directly at the HTTP request boundary. */
    fun get(uri: URI, cancellation: CatalogDownloadCancellation, beforeSend: () -> Unit): ByteArray
}

internal class CatalogDownloadCancellation {
    private val lock = Any()
    @Volatile private var cancelled = false
    private var cancelCall: (() -> Unit)? = null

    fun checkActive() {
        if (cancelled || Thread.currentThread().isInterrupted) throw CancellationException(DOWNLOAD_RETIRED)
    }
    fun attach(action: () -> Unit) = synchronized(lock) { checkActive(); cancelCall = action }
    fun detach() = synchronized(lock) { cancelCall = null }
    fun cancel() {
        val action = synchronized(lock) { cancelled = true; cancelCall }
        action?.invoke()
    }
}

/** Dedicated client: no ambient proxy, cookie jar, authentication, redirects, cache or call retry. */
internal class IsolatedCatalogHttps private constructor(builder: OkHttpClient.Builder, private val timeoutMillis: Long) :
    CatalogArtifactTransport, CatalogDocumentTransport {
    constructor() : this(OkHttpClient.Builder(), 15_000)

    private class Gate(val admit: () -> Unit) {
        private val entered = AtomicBoolean()
        fun consume() {
            if (!entered.compareAndSet(false, true)) throw IOException(DOWNLOAD_FOLLOW_UP)
            admit()
        }
    }

    private val client = builder
        .proxy(Proxy.NO_PROXY)
        .cookieJar(CookieJar.NO_COOKIES)
        .authenticator(Authenticator.NONE)
        .proxyAuthenticator(Authenticator.NONE)
        .cache(null)
        .followRedirects(false)
        .followSslRedirects(false)
        .retryOnConnectionFailure(false)
        .protocols(listOf(Protocol.HTTP_1_1))
        .connectionPool(ConnectionPool(0, 1, TimeUnit.SECONDS))
        .callTimeout(timeoutMillis, TimeUnit.MILLISECONDS)
        .connectTimeout(minOf(5_000, timeoutMillis), TimeUnit.MILLISECONDS)
        .readTimeout(minOf(5_000, timeoutMillis), TimeUnit.MILLISECONDS)
        .addNetworkInterceptor { chain ->
            // A status-based follow-up is separate from retryOnConnectionFailure (e.g. 503).
            checkNotNull(chain.request().tag(Gate::class.java)).consume()
            val response = chain.proceed(chain.request())
            // Keep all non-200 responses out of the redirect/authentication/retry layer.
            if (response.code != 200) {
                chain.call().cancel()
                response.close()
                throw ProtocolException(DOWNLOAD_RESPONSE)
            }
            response
        }.build()

    override fun get(uri: URI, cancellation: CatalogDownloadCancellation, beforeSend: () -> Unit): ByteArray =
        read(uri, cancellation, ExtensionPackageSource.MAX_SOURCE_BYTES, "application/json", beforeSend)

    override fun getDocument(uri: URI, cancellation: CatalogDownloadCancellation, beforeSend: () -> Unit): ByteArray =
        read(uri, cancellation, CatalogSignedDocument.MAX_FRAME_SIZE, "application/octet-stream", beforeSend)

    private fun read(uri: URI, cancellation: CatalogDownloadCancellation, limit: Int, accept: String, beforeSend: () -> Unit): ByteArray {
        require(uri.scheme == "https" && uri.host != null && uri.rawUserInfo == null && uri.rawQuery == null && uri.rawFragment == null)
        val request = Request.Builder().url(uri.toURL()).get()
            .cacheControl(CacheControl.Builder().noCache().noStore().build())
            .header("Accept", accept)
            .header("Accept-Encoding", "identity")
            .header("Connection", "close")
            .header("User-Agent", "CordiAct-Catalog/1")
            .tag(Gate::class.java, Gate(beforeSend))
            .build()
        val call = client.newCall(request)
        val start = System.nanoTime()
        fun checkDeadline() {
            cancellation.checkActive()
            if (System.nanoTime() - start >= TimeUnit.MILLISECONDS.toNanos(timeoutMillis)) throw IOException(DOWNLOAD_FAILED)
        }
        cancellation.attach(call::cancel)
        return try {
            val response = call.execute()
            try {
                checkDeadline()
                require(response.code == 200 && response.headers.values("Content-Range").isEmpty()) { DOWNLOAD_RESPONSE }
                val encodings = response.headers.values("Content-Encoding")
                require(encodings.isEmpty() || encodings.singleOrNull()?.equals("identity", ignoreCase = true) == true) { DOWNLOAD_RESPONSE }
                val lengths = response.headers.values("Content-Length")
                val transfer = response.headers.values("Transfer-Encoding")
                require((lengths.isNotEmpty() && transfer.isEmpty()) ||
                    (lengths.isEmpty() && transfer.singleOrNull()?.equals("chunked", ignoreCase = true) == true)) { DOWNLOAD_RESPONSE }
                val expected = lengths.takeUnless(List<String>::isEmpty)?.let {
                    require(it.size == 1 && it.single().matches(DECIMAL_LENGTH)) { DOWNLOAD_RESPONSE }
                    it.single().toLong().also { size -> require(size in 1L..limit.toLong()) { DOWNLOAD_SIZE } }
                }
                val input = response.body.byteStream()
                val bounded = ByteArray(limit + 1)
                var total = 0
                while (total < bounded.size) {
                    checkDeadline()
                    val count = input.read(bounded, total, bounded.size - total)
                    if (count < 0) break
                    check(count > 0) { DOWNLOAD_RESPONSE }
                    total += count
                }
                checkDeadline()
                require(total in 1..limit) { DOWNLOAD_SIZE }
                require(expected == null || expected == total.toLong()) { DOWNLOAD_RESPONSE }
                bounded.copyOf(total)
            } finally {
                // Prevent HTTP/1.1 close from draining a rejected or oversize body for connection reuse.
                call.cancel()
                response.close()
            }
        } catch (_: IOException) {
            cancellation.checkActive()
            throw IOException(DOWNLOAD_FAILED) // Never disclose origin, raw headers or response body.
        } finally {
            cancellation.detach()
            call.cancel()
        }
    }

    companion object {
        private val DECIMAL_LENGTH = Regex("[0-9]{1,18}")
        /** Tests may install a local CA; production always uses platform TLS verification. */
        internal fun withTestTrust(builder: OkHttpClient.Builder, timeoutMillis: Long = 2_000) = IsolatedCatalogHttps(builder, timeoutMillis)
    }
}

private const val DOWNLOAD_USED = "CATALOG_DOWNLOAD_ALREADY_USED"
private const val DOWNLOAD_RETIRED = "CATALOG_DOWNLOAD_RETIRED"
private const val DOWNLOAD_FOLLOW_UP = "CATALOG_DOWNLOAD_FOLLOW_UP"
private const val DOWNLOAD_FAILED = "CATALOG_DOWNLOAD_FAILED"
private const val DOWNLOAD_RESPONSE = "CATALOG_DOWNLOAD_INVALID_RESPONSE"
private const val DOWNLOAD_SIZE = "CATALOG_DOWNLOAD_INVALID_SIZE"
