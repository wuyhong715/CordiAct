package com.cordiact.harness.deepseek

import java.io.InputStream
import java.net.ConnectException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.file.Files
import java.nio.file.Path
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.util.Base64
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Opt-in: real Node Host transport, short-lived synthetic grants, and only loopback traffic. */
class FollowTlsInteropTest {
    private val node = checkNotNull(System.getProperty("cordiact.follow.node"))
    private val fixture = Path.of("host-follow", "test", "jvm-tls-interop.fixture.mjs").toAbsolutePath()
    private val loopback = InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1))
    private val target = FollowTarget("host-a", "local-a", "session-a")
    private val disconnected = FollowStatus.Ended(FollowEnd.DISCONNECTED)

    @Test fun publishesBaselineAndUpdateThenHostRevocationDisconnects() = withHost { host, client ->
        host.controller(client).use { controller ->
            val oldRoute = host.configure(controller, target)
            val old = host.followPaired(controller, oldRoute)
            assertEquals(FollowStatus.Current(FollowSessionState(2, true, false)), awaitStatus({ controller.status(old) }) { it is FollowStatus.Current })
            val route = host.configure(controller, target)
            assertEquals(null, controller.status(old))
            assertEquals(null, controller.challenge(oldRoute))
            assertEquals(false, controller.clear(oldRoute))
            val attempt = host.followPaired(controller, route)
            assertEquals(FollowStatus.Current(FollowSessionState(2, true, false)), awaitStatus({ controller.status(attempt) }) { it is FollowStatus.Current })
            host.command("update")
            val updated = FollowStatus.Current(FollowSessionState(9, true, true))
            assertEquals(updated, awaitStatus({ controller.status(attempt) }) { it == updated })
            host.command("revoke")
            assertEquals(disconnected, awaitStatus({ controller.status(attempt) }) { it is FollowStatus.Ended })
        }
    }

    @Test fun onlyExactTargetCanReadAndListenerStopClearsEstablishedAndPendingPeers() = withHost { host, client ->
        // Different authenticated TLS connection, same unused grant. Wrong scopes do not emit a baseline.
        val wrongWorkspace = assertNotNull(host.follow(client, 1, target, "remote-b"))
        assertEquals(disconnected, awaitStatus(client, wrongWorkspace, forbidCurrent = true) { it is FollowStatus.Ended })
        val wrongSession = assertNotNull(host.follow(client, 1, target.copy(remoteSessionId = "session-b")))
        assertEquals(disconnected, awaitStatus(client, wrongSession, forbidCurrent = true) { it is FollowStatus.Ended })
        val live = assertNotNull(host.follow(client, 1, target))
        assertTrue(awaitStatus(client, live) { it is FollowStatus.Current } is FollowStatus.Current)
        Socket().use { pending ->
            pending.connect(host.address, 1_000)
            pending.soTimeout = 2_000
            host.command("stop")
            assertEquals(disconnected, awaitStatus(client, live) { it is FollowStatus.Ended })
            assertEquals(-1, pending.getInputStream().read())
            Socket().use { closed -> assertFailsWith<ConnectException> { closed.connect(host.address, 1_000) } }
        }
    }

    @Test fun idleTcpPeerIsRetiredBeforeApplicationAdmission() = withHost { host, _ ->
        Socket().use { pending ->
            pending.connect(host.address, 1_000)
            pending.soTimeout = 5_000
            assertEquals(-1, pending.getInputStream().read())
        }
    }

    @Test fun locallyExpiresWhenRelayHidesHostRevocationAndEof() = withHost("silent-relay", timeoutSeconds = 24) { host, client ->
        host.controller(client).use { controller ->
            val route = host.configure(controller, target)
            val beforeChallenge = System.nanoTime()
            val attempt = host.followPaired(controller, route)
            val baseline = FollowStatus.Current(FollowSessionState(2, true, false))
            assertEquals(baseline, awaitStatus({ controller.status(attempt) }) { it == baseline })
            host.command("freeze") // Acknowledged only once the upstream stream closes and the front remains open.
            assertEquals(baseline, controller.status(attempt))
            host.command("client-closed") // No status polling: the timer must actually close the transport.
            assertTrue(System.nanoTime() - beforeChallenge >= TimeUnit.SECONDS.toNanos(15))
            assertEquals(disconnected, controller.status(attempt))
        }
    }

    @Test fun foregroundRecheckUsesOneElapsedClockAcrossPairingTlsAndProjection() = withHost("silent-relay") { host, _ ->
        // A different epoch catches accidental comparisons against System.nanoTime in any layer.
        val elapsed = AtomicLong(Long.MIN_VALUE + TimeUnit.SECONDS.toNanos(30))
        val reader = Executors.newSingleThreadExecutor()
        try {
            FollowRouteController(host.scheduler, reader, elapsed::get).use { controller ->
                val oldRoute = host.configure(controller, target)
                val route = host.configure(controller, target)
                val attempt = host.followPaired(controller, route)
                val baseline = FollowStatus.Current(FollowSessionState(2, true, false))
                assertEquals(baseline, awaitStatus({ controller.status(attempt) }) { it == baseline })
                host.command("freeze")
                assertEquals(baseline, controller.status(attempt))
                val unblockTimer = CountDownLatch(1)
                host.scheduler.execute { unblockTimer.await() }
                try {
                    elapsed.addAndGet(TimeUnit.SECONDS.toNanos(15))
                    assertEquals(false, controller.recheck(oldRoute))
                    assertTrue(controller.recheck(route))
                    host.command("client-closed") // No timer or subsequent status read may perform the close.
                    assertEquals(disconnected, controller.status(attempt))
                } finally { unblockTimer.countDown() }
            }
        } finally { reader.shutdownNow() }
    }

    private fun awaitStatus(
        client: FollowPipeClient,
        handle: FollowPipeLifecycle.Handle,
        forbidCurrent: Boolean = false,
        done: (FollowStatus?) -> Boolean,
    ): FollowStatus? = awaitStatus({ client.status(handle) }, forbidCurrent, done)

    private fun awaitStatus(
        read: () -> FollowStatus?,
        forbidCurrent: Boolean = false,
        done: (FollowStatus?) -> Boolean,
    ): FollowStatus? {
        val until = System.nanoTime() + TimeUnit.SECONDS.toNanos(4)
        while (true) {
            val status = read()
            if (forbidCurrent) assertTrue(status !is FollowStatus.Current)
            if (done(status)) return status
            check(System.nanoTime() - until < 0) { "Timed out waiting for follow state: $status" }
            Thread.sleep(5)
        }
    }

    private class Host(
        private val child: Process,
        val address: InetSocketAddress,
        private val grants: List<String>,
        private val pin: ByteArray,
        private val enrollmentKey: ECPublicKey,
        private val deliveryKey: ByteArray,
        val scheduler: ScheduledExecutorService,
    ) {
        fun follow(client: FollowPipeClient, index: Int, target: FollowTarget, remoteWorkspaceId: String = "remote-a"): FollowPipeLifecycle.Handle? {
            val connector = PinnedFollowTlsConnector(target, remoteWorkspaceId, address, pin, scheduler)
            val approved = checkNotNull(connector.connect(connector.grant(grants[index], 12_000))) { "Synthetic TLS handshake failed" }
            return approved.use { client.begin(it) }
        }

        fun controller(client: FollowPipeClient) = FollowRouteController(scheduler, client, FollowPairing.Approval::connect)

        fun configure(controller: FollowRouteController, target: FollowTarget) =
            checkNotNull(controller.configure(target, "remote-a", address, pin, enrollmentKey, deliveryKey))

        fun followPaired(controller: FollowRouteController, route: FollowRouteController.Route): FollowRouteController.Attempt {
            val challenge = checkNotNull(controller.challenge(route))
            child.outputStream.write("pair ${challenge.nonce}\n".encodeToByteArray())
            child.outputStream.flush()
            val envelope = Base64.getDecoder().decode(line(child.inputStream, 1_160))
            child.outputStream.write("pair ${challenge.nonce}\n".encodeToByteArray())
            child.outputStream.flush()
            assertEquals("denied", line(child.inputStream, 32))
            val attempt = checkNotNull(controller.approve(route, challenge, envelope)) { "Synthetic sealed offer rejected" }
            check(controller.connect(attempt)) { "Synthetic paired TLS handshake failed" }
            check(!controller.connect(attempt))
            return attempt
        }

        fun command(command: String) {
            child.outputStream.write("$command\n".encodeToByteArray())
            child.outputStream.flush()
            assertEquals(command, line(child.inputStream, 32))
        }
    }

    private fun withHost(mode: String = "direct", timeoutSeconds: Long = 18, test: (Host, FollowPipeClient) -> Unit) {
        val directory = Files.createTempDirectory("cordiact-node-tls-")
        val store = directory.resolve("fixture.p12")
        val signer = directory.resolve("pairing-key.der")
        val cert = directory.resolve("fixture-cert.der")
        val delivery = directory.resolve("fixture-delivery.bin")
        val deliveryKey = ByteArray(32).also(SecureRandom()::nextBytes)
        val password = "synthetic-only"
        val control = Executors.newSingleThreadExecutor { task -> Thread(task, "follow-tls-fixture").apply { isDaemon = true } }
        val reads = Executors.newSingleThreadExecutor { task -> Thread(task, "follow-tls-reader").apply { isDaemon = true } }
        val scheduler = Executors.newSingleThreadScheduledExecutor()
        try {
            val keytool = Path.of(System.getProperty("java.home"), "bin", "keytool").toString()
            val keys = ProcessBuilder(keytool, "-genkeypair", "-alias", "host", "-keyalg", "EC",
                "-groupname", "secp256r1", "-dname", "CN=Synthetic Node Follow", "-validity", "2", "-storetype", "PKCS12",
                "-keystore", store.toString(), "-storepass", password).redirectErrorStream(true).start()
            try {
                check(keys.waitFor(10, TimeUnit.SECONDS) && keys.exitValue() == 0) { "Could not generate synthetic TLS identity" }
            } finally {
                if (keys.isAlive) keys.destroyForcibly()
                keys.inputStream.close()
                keys.outputStream.close()
            }
            val ks = KeyStore.getInstance("PKCS12")
            Files.newInputStream(store).use { ks.load(it, password.toCharArray()) }
            val encodedCert = ks.getCertificate("host").encoded
            Files.write(cert, encodedCert)
            val pin = MessageDigest.getInstance("SHA-256").digest(encodedCert)
            val signingKey = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
            Files.write(signer, signingKey.private.encoded)
            Files.write(delivery, deliveryKey)
            val child = ProcessBuilder(node, fixture.toString(), store.toString(), signer.toString(), cert.toString(), delivery.toString(), mode)
                .apply { environment().clear() }.start()
            try {
                control.submit(Callable {
                    val fields = line(child.inputStream, 256).split(' ')
                    val port = fields.firstOrNull()?.toIntOrNull()
                    val grants = fields.drop(1)
                    check(port != null && port in 1..65535 && grants.size == 2 && grants.all { GRANT.matches(it) }) {
                        "Invalid synthetic TLS control envelope"
                    }
                    FollowPipeClient(scheduler, reads).use {
                        test(Host(child, InetSocketAddress(loopback, port), grants, pin, signingKey.public as ECPublicKey, deliveryKey, scheduler), it)
                    }
                    child.outputStream.close()
                    check(child.waitFor(3, TimeUnit.SECONDS) && child.exitValue() == 0) { "Synthetic TLS Host did not exit cleanly" }
                }).get(timeoutSeconds, TimeUnit.SECONDS)
            } finally {
                if (child.isAlive) child.destroyForcibly()
                child.waitFor(2, TimeUnit.SECONDS)
                child.inputStream.close()
                child.outputStream.close()
                child.errorStream.close()
            }
        } finally {
            control.shutdownNow()
            reads.shutdownNow()
            scheduler.shutdownNow()
            deliveryKey.fill(0)
            Files.deleteIfExists(delivery)
            Files.deleteIfExists(signer)
            Files.deleteIfExists(cert)
            Files.deleteIfExists(store)
            Files.deleteIfExists(directory)
        }
    }

    private companion object {
        val GRANT = Regex("caf1_[A-Za-z0-9_-]{43}")
        fun line(input: InputStream, limit: Int): String {
            val line = StringBuilder()
            repeat(limit) {
                val byte = input.read()
                if (byte == 10) return line.toString()
                check(byte in 32..126) { "Incomplete synthetic TLS control envelope" }
                line.append(byte.toChar())
            }
            error("Oversized synthetic TLS control envelope")
        }
    }
}
