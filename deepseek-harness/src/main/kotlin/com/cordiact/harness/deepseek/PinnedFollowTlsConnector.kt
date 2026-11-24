package com.cordiact.harness.deepseek

import java.io.IOException
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.Socket
import java.security.MessageDigest
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.X509TrustManager

/**
 * Direct TLS carrier for one externally approved endpoint and target. The caller must obtain the
 * address, exact leaf certificate SHA-256, target, and grant through a trusted channel. There is no
 * discovery, system-CA fallback, HTTP, proxy configuration, redirect, or automatic retry.
 * Call [connect] off-thread with a live deadline scheduler independent of the connecting thread.
 */
class PinnedFollowTlsConnector(
    private val target: FollowTarget,
    private val remoteWorkspaceId: String,
    private val address: InetSocketAddress,
    certificateSha256: ByteArray,
    private val scheduler: ScheduledExecutorService,
) {
    private val pin = certificateSha256.copyOf()

    init {
        require(!address.isUnresolved && address.port != 0) { "A resolved follow endpoint is required" }
        require(pin.size == 32) { "A SHA-256 certificate pin is required" }
        require(ID.matches(remoteWorkspaceId)) { "Invalid remote workspace identity" }
    }

    private val tls = SSLContext.getInstance("TLS").apply {
        init(null, arrayOf(object : X509TrustManager {
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
            override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {
                throw CertificateException("Client trust is not supported")
            }
            override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
                verifyLeaf(chain.firstOrNull())
            }
        }), null)
    }

    /** A one-use grant spec, bound to the connector that created it and not usable as owner auth. */
    class Grant internal constructor(
        internal val owner: PinnedFollowTlsConnector,
        token: String,
        internal val deadline: Long,
    ) : AutoCloseable {
        private val credential = AtomicReference<String?>(token)
        internal fun take(): String? = credential.getAndSet(null)
        override fun close() { credential.set(null) }
    }

    /** Bind a grant supplied by the trusted enrollment flow. No network is used here. */
    fun grant(token: String, remainingMillis: Long): Grant {
        require(TOKEN.matches(token)) { "Invalid follow grant" }
        return Grant(this, token, followDeadline(System.nanoTime(), remainingMillis))
    }

    /** Authenticate before releasing the pair. No application bytes are written by this method. */
    fun connect(grant: Grant): ApprovedFollowPipe? {
        if (grant.owner !== this) return null
        val token = grant.take() ?: return null
        val start = System.nanoTime()
        val remaining = grant.deadline - start
        if (remaining <= 0) return null
        val budget = minOf(remaining, TimeUnit.MILLISECONDS.toNanos(CONNECT_MS))
        val deadline = start + budget
        val raw = Socket(Proxy.NO_PROXY)
        val connecting = AtomicBoolean(true)
        var handedOff = false
        try {
            val timeout = scheduler.schedule({
                if (connecting.compareAndSet(true, false)) closeRaw(raw)
            }, budget, TimeUnit.NANOSECONDS)
            try {
                raw.connect(address, CONNECT_MS.toInt())
                raw.soTimeout = CONNECT_MS.toInt()
                val socket = tls.socketFactory.createSocket(raw, address.address.hostAddress, address.port, true)
                    as SSLSocket
                socket.enabledProtocols = arrayOf("TLSv1.3")
                socket.sslParameters = socket.sslParameters.apply { applicationProtocols = arrayOf(PROTOCOL) }
                socket.startHandshake()
                // Check the session leaf as well; do not rely on trust callbacks firing for resumption.
                verifyLeaf(socket.session.peerCertificates.firstOrNull() as? X509Certificate)
                if (socket.applicationProtocol != PROTOCOL || deadline - System.nanoTime() <= 0 ||
                    grant.deadline - System.nanoTime() <= 0 || !connecting.compareAndSet(true, false)) return null
                raw.soTimeout = 0
                socket.soTimeout = 0
                val pipe = ApprovedFollowPipe.until(target, remoteWorkspaceId, token, grant.deadline,
                    socket.inputStream, socket.outputStream) { closeRaw(raw) }
                handedOff = true
                return pipe
            } finally {
                connecting.set(false)
                timeout.cancel(false)
            }
        } catch (_: IOException) {
            return null
        } catch (_: CertificateException) {
            return null
        } catch (_: RejectedExecutionException) {
            return null
        } finally {
            if (!handedOff) closeRaw(raw)
        }
    }

    private fun verifyLeaf(leaf: X509Certificate?) {
        if (leaf == null || !MessageDigest.isEqual(pin, MessageDigest.getInstance("SHA-256").digest(leaf.encoded))) {
            throw CertificateException("Unapproved follow certificate")
        }
        leaf.checkValidity()
    }

    private fun closeRaw(socket: Socket) {
        try {
            socket.close()
        } catch (_: IOException) {
            // The socket has already been retired; never open another one here.
        }
    }

    internal companion object {
        const val PROTOCOL = "cordiact-follow/1"
        private const val CONNECT_MS = 3_000L
        private val ID = Regex("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}")
        private val TOKEN = Regex("caf1_[A-Za-z0-9_-]{43}")
    }
}
