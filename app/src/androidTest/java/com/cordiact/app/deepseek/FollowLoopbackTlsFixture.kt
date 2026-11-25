package com.cordiact.app.deepseek

import com.cordiact.harness.deepseek.FollowEnd
import com.cordiact.harness.deepseek.FollowPipeClient
import com.cordiact.harness.deepseek.FollowSessionState
import com.cordiact.harness.deepseek.FollowStatus
import com.cordiact.harness.deepseek.FollowTarget
import com.cordiact.harness.deepseek.PinnedFollowTlsConnector
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketTimeoutException
import java.security.KeyFactory
import java.security.KeyStore
import java.security.MessageDigest
import java.security.cert.CertificateFactory
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Base64
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.SSLSocket
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue

/** Uses the platform TLS provider on both ends and never listens off loopback. */
internal object FollowLoopbackTlsFixture {
    private const val ALPN = "cordiact-follow/1"
    private val target = FollowTarget("fixture-host", "local-workspace", "fixture-session")
    private val token = "caf1_${"A".repeat(43)}"
    private val loopback = InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1))

    fun acceptExactPinAndAlpn() {
        val scheduler = Executors.newSingleThreadScheduledExecutor()
        val reads = Executors.newSingleThreadExecutor()
        // Keep the server open while the client checks the projection, then verify EOF cleanup.
        val baseline = CountDownLatch(1)
        try {
            withPeer(ALPN, serve = { peer ->
                peer.startHandshake()
                assertEquals("TLSv1.3", peer.session.protocol)
                assertEquals(ALPN, peer.applicationProtocol)
                val expected = "$ALPN remote-workspace fixture-session $token\n".encodeToByteArray()
                val actual = ByteArray(expected.size)
                java.io.DataInputStream(peer.inputStream).readFully(actual)
                assertTrue(actual.contentEquals(expected))
                peer.outputStream.write("""{"delivery":0,"cursor":2,"active":true,"running":false}""".plus('\n').encodeToByteArray())
                peer.outputStream.flush()
                check(baseline.await(4, TimeUnit.SECONDS)) { "Client did not see the baseline" }
            }) { address, pin ->
                val connector = PinnedFollowTlsConnector(target, "remote-workspace", address, pin, scheduler)
                val grant = connector.grant(token, 12_000)
                checkNotNull(connector.connect(grant)).use { pipe ->
                    FollowPipeClient(scheduler, reads).use { client ->
                        val handle = checkNotNull(client.begin(pipe))
                        awaitStatus(FollowStatus.Current(FollowSessionState(2, true, false))) { client.status(handle) }
                        baseline.countDown()
                        awaitStatus(FollowStatus.Ended(FollowEnd.DISCONNECTED)) { client.status(handle) }
                        assertNull(connector.connect(grant))
                    }
                }
            }
        } finally {
            baseline.countDown()
            reads.shutdownNow()
            scheduler.shutdownNow()
        }
    }

    fun rejectWrongPinAndMissingAlpn() {
        val scheduler = Executors.newSingleThreadScheduledExecutor()
        try {
            for (wrongPin in listOf(true, false)) {
                withPeer(if (wrongPin) ALPN else null, serve = { peer ->
                    if (!wrongPin) {
                        // Require a completed TLS handshake, so a provider error cannot pass this case.
                        peer.startHandshake()
                        assertEquals("TLSv1.3", peer.session.protocol)
                        assertTrue(peer.applicationProtocol.isNullOrEmpty())
                    }
                    val received = try {
                        peer.inputStream.read()
                    } catch (timeout: SocketTimeoutException) {
                        throw AssertionError("Rejected peer was not closed", timeout)
                    } catch (_: IOException) {
                        -1
                    }
                    assertEquals(-1, received)
                }) { address, pin ->
                    if (wrongPin) pin[0] = (pin[0].toInt() xor 1).toByte()
                    val connector = PinnedFollowTlsConnector(target, "remote-workspace", address, pin, scheduler)
                    val grant = connector.grant(token, 8_000)
                    assertNull(connector.connect(grant)?.also { it.close() })
                    assertNull(connector.connect(grant)?.also { it.close() })
                }
            }
        } finally { scheduler.shutdownNow() }
    }

    private fun awaitStatus(expected: FollowStatus, status: () -> FollowStatus?) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
        var actual = status()
        while (actual != expected && deadline - System.nanoTime() > 0) {
            Thread.sleep(5)
            actual = status()
        }
        assertEquals(expected, actual)
    }

    private fun withPeer(
        protocol: String?,
        serve: (SSLSocket) -> Unit,
        client: (InetSocketAddress, ByteArray) -> Unit,
    ) {
        val decoder = Base64.getDecoder()
        val cert = CertificateFactory.getInstance("X.509").generateCertificate(decoder.decode(CERT).inputStream())
        val key = KeyFactory.getInstance("EC").generatePrivate(PKCS8EncodedKeySpec(decoder.decode(KEY)))
        val password = "test-fixture".toCharArray()
        val store = KeyStore.getInstance("PKCS12").apply {
            load(null, null)
            setKeyEntry("fixture", key, password, arrayOf(cert))
        }
        val managers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply { init(store, password) }
        val context = SSLContext.getInstance("TLS").apply { init(managers.keyManagers, null, null) }
        val serverThread = Executors.newSingleThreadExecutor()
        try {
            (context.serverSocketFactory.createServerSocket(0, 1, loopback) as SSLServerSocket).use { server ->
                server.soTimeout = 5_000
                val task = serverThread.submit(Callable {
                    (server.accept() as SSLSocket).use { peer ->
                        peer.soTimeout = 5_000
                        peer.enabledProtocols = arrayOf("TLSv1.3")
                        if (protocol != null) peer.sslParameters = peer.sslParameters.apply {
                            applicationProtocols = arrayOf(protocol)
                        }
                        serve(peer)
                    }
                })
                client(server.localSocketAddress as InetSocketAddress, MessageDigest.getInstance("SHA-256").digest(cert.encoded))
                task.get(6, TimeUnit.SECONDS)
            }
        } finally { serverThread.shutdownNow() }
    }

    // Public, synthetic EC key and self-signed certificate, valid 2020–2049. Never provision to an app or Host.
    private const val KEY = "MEECAQAwEwYHKoZIzj0CAQYIKoZIzj0DAQcEJzAlAgEBBCAEcZDzVA5VeOiu1Hvz25X47aJlvr1d6tI5oce62hSIzA=="
    private const val CERT = "MIIBeDCCAR6gAwIBAgIJAKj0hcMzY1y/MAoGCCqGSM49BAMCMDAxLjAsBgNVBAMTJUNvcmRpQWN0IEluc3RydW1lbnRhdGlvbiBGaXh0dXJlIE9ubHkwHhcNMjAwMTAxMDAwMDAwWhcNNDkxMjMxMDAwMDAwWjAwMS4wLAYDVQQDEyVDb3JkaUFjdCBJbnN0cnVtZW50YXRpb24gRml4dHVyZSBPbmx5MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEN/Vwmt/nQGMYj6crSuXKVHUVAHzxMCIOEZ9ntF+BNrlGpq+iy2dtVQ/XKr0BLwkny4wvNxX+wnRCxJ1oHK4nIqMhMB8wHQYDVR0OBBYEFJep0l6c/SpoBH2z+rKXqCJ5hjDtMAoGCCqGSM49BAMCA0gAMEUCIQC8t41C2yGqFeniRRVtTcux+vcbs9r8AlEKk0c0uxLP6QIgLaDEbUFckVBxFcgQlX+7FzMURbl/uOLA/Fk59v3ZSl0="
}
