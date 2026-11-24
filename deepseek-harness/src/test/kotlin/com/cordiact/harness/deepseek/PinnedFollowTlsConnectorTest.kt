package com.cordiact.harness.deepseek

import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.SocketTimeoutException
import java.nio.file.Files
import java.nio.file.Path
import java.security.KeyStore
import java.security.MessageDigest
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.SSLSocket
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PinnedFollowTlsConnectorTest {
    private val target = FollowTarget("host-a", "local-workspace", "session-a")
    private val token = "caf1_" + "A".repeat(43)
    private val loopback = InetAddress.getByName("127.0.0.1")

    @Test fun exactPinAndAlpnReleaseOnlyTheBoundGrantAndTransport() = withKeys { context, pin, scheduler ->
        withTlsServer(context, PinnedFollowTlsConnector.PROTOCOL, { peer ->
            val expected = "cordiact-follow/1 remote-workspace session-a $token\n".encodeToByteArray()
            assertTrue(peer.inputStream.readNBytes(expected.size).contentEquals(expected))
            peer.outputStream.write("""{"delivery":0,"cursor":2,"active":true,"running":false}""".plus('\n').encodeToByteArray())
            peer.outputStream.flush()
            assertEquals(-1, peer.inputStream.read())
        }) { address ->
            val supplied = pin.copyOf()
            val connector = connector(address, supplied, scheduler)
            supplied.fill(0) // The configured pin must not follow mutable caller data.
            val grant = connector.grant(token, 8_000)
            assertNull(connector(address, pin, scheduler).connect(grant))
            val approved = assertNotNull(connector.connect(grant))
            assertNull(connector.connect(grant))
            val reads = Executors.newSingleThreadExecutor()
            try {
                approved.use { pipe ->
                    assertEquals(grant.deadline, pipe.deadlineNanos)
                    FollowPipeClient(scheduler, reads).use { client ->
                        val handle = assertNotNull(client.begin(pipe))
                        val end = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
                        var status = client.status(handle)
                        while (status == FollowStatus.AwaitingBaseline && System.nanoTime() - end < 0) {
                            Thread.sleep(5)
                            status = client.status(handle)
                        }
                        assertEquals(FollowStatus.Current(FollowSessionState(2, true, false)), status)
                        assertTrue(client.revoke(handle))
                        assertEquals(FollowStatus.Ended(FollowEnd.ACCESS_LOST), client.status(handle))
                    }
                }
            } finally {
                reads.shutdownNow()
                assertTrue(reads.awaitTermination(2, TimeUnit.SECONDS))
            }
        }
    }

    @Test fun wrongPinOrMissingApplicationProtocolNeverSendsTheGrant() = withKeys { context, pin, scheduler ->
        for ((offered, approvedPin) in listOf(
            PinnedFollowTlsConnector.PROTOCOL to pin.copyOf().apply { this[0] = (this[0].toInt() xor 1).toByte() },
            null to pin,
        )) {
            withTlsServer(context, offered, { peer ->
                val byte = try {
                    peer.inputStream.read()
                } catch (timeout: SocketTimeoutException) {
                    throw AssertionError("Rejected peer was not closed", timeout)
                } catch (_: IOException) {
                    -1
                }
                assertEquals(-1, byte)
            }) { address ->
                val connector = connector(address, approvedPin, scheduler)
                val grant = connector.grant(token, 8_000)
                assertNull(connector.connect(grant))
                assertNull(connector.connect(grant))
            }
        }
    }

    @Test fun stalledHandshakeClosesAtShorterGrantDeadline() {
        val scheduler = Executors.newSingleThreadScheduledExecutor()
        val peer = Executors.newSingleThreadExecutor()
        val accepted = CountDownLatch(1)
        try {
            ServerSocket().use { server ->
                server.bind(InetSocketAddress(loopback, 0))
                server.soTimeout = 4_000
                val closed = peer.submit(Callable {
                    server.accept().use { socket ->
                        accepted.countDown()
                        socket.soTimeout = 4_000
                        // This side never writes a TLS handshake. Read only until the client closes.
                        var received = 0
                        while (socket.inputStream.read() != -1) {
                            check(++received < 64 * 1024) { "Unexpected handshake size" }
                        }
                    }
                })
                val connector = connector(server.localSocketAddress as InetSocketAddress, ByteArray(32), scheduler)
                val grant = connector.grant(token, 500)
                val start = System.nanoTime()
                assertNull(connector.connect(grant))
                assertTrue(System.nanoTime() - start < TimeUnit.SECONDS.toNanos(2))
                assertTrue(accepted.await(2, TimeUnit.SECONDS))
                closed.get(3, TimeUnit.SECONDS)
            }
        } finally {
            scheduler.shutdownNow()
            peer.shutdownNow()
        }
    }

    private fun connector(address: InetSocketAddress, pin: ByteArray, scheduler: ScheduledExecutorService) =
        PinnedFollowTlsConnector(target, "remote-workspace", address, pin, scheduler)

    private fun withTlsServer(context: SSLContext, protocol: String?, serve: (SSLSocket) -> Unit,
                              client: (InetSocketAddress) -> Unit) {
        val executor = Executors.newSingleThreadExecutor()
        try {
            (context.serverSocketFactory.createServerSocket(0, 1, loopback) as SSLServerSocket).use { server ->
                server.soTimeout = 5_000
                server.enabledProtocols = arrayOf("TLSv1.3")
                if (protocol != null) server.sslParameters = server.sslParameters.apply { applicationProtocols = arrayOf(protocol) }
                val host = executor.submit(Callable {
                    (server.accept() as SSLSocket).use { socket ->
                        socket.soTimeout = 5_000
                        serve(socket)
                    }
                })
                client(server.localSocketAddress as InetSocketAddress)
                host.get(6, TimeUnit.SECONDS)
            }
        } finally {
            executor.shutdownNow()
        }
    }

    private fun withKeys(test: (SSLContext, ByteArray, ScheduledExecutorService) -> Unit) {
        val directory = Files.createTempDirectory("cordiact-follow-tls-")
        val store = directory.resolve("fixture.p12")
        val scheduler = Executors.newSingleThreadScheduledExecutor()
        try {
            val keytool = Path.of(System.getProperty("java.home"), "bin", "keytool").toString()
            val password = "synthetic-only"
            val process = ProcessBuilder(keytool, "-genkeypair", "-alias", "host", "-keyalg", "EC",
                "-groupname", "secp256r1", "-dname", "CN=Synthetic Follow", "-validity", "2", "-storetype", "PKCS12",
                "-keystore", store.toString(), "-storepass", password).redirectErrorStream(true).start()
            try {
                check(process.waitFor(10, TimeUnit.SECONDS) && process.exitValue() == 0) { "Could not create synthetic certificate" }
            } finally {
                if (process.isAlive) process.destroyForcibly()
            }
            val ks = KeyStore.getInstance("PKCS12")
            Files.newInputStream(store).use { ks.load(it, password.toCharArray()) }
            val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
            kmf.init(ks, password.toCharArray())
            val context = SSLContext.getInstance("TLS").apply { init(kmf.keyManagers, null, null) }
            test(context, MessageDigest.getInstance("SHA-256").digest(ks.getCertificate("host").encoded), scheduler)
        } finally {
            scheduler.shutdownNow()
            Files.deleteIfExists(store)
            Files.deleteIfExists(directory)
        }
    }
}
