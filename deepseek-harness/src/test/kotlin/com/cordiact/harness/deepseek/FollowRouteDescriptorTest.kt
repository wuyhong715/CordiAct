package com.cordiact.harness.deepseek

import java.security.KeyPairGenerator
import java.security.spec.ECGenParameterSpec
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FollowRouteDescriptorTest {
    private val spki = point("secp256r1")
    private val pin = "ab".repeat(32)
    private val target = FollowTarget("host", "local", "session")

    @Test fun requiresCanonicalBoundedLoopbackMetadata() {
        val line = descriptor()
        val value = assertNotNull(FollowRouteDescriptor.parse(line))
        assertEquals(target, value.target)
        assertEquals("remote", value.remoteWorkspaceId)
        assertEquals("127.0.0.1", value.address.address.hostAddress)
        assertEquals(12345, value.address.port)
        value.certificateSha256().fill(0)
        assertEquals(pin, value.certificateSha256().toHexString())
        val maximal = "cordiact-follow-route/1 ${List(4) { "a".repeat(128) }.joinToString(" ")} 127.0.0.1 65535 $pin $spki\n"
        assertEquals(FollowRouteDescriptor.MAX_LINE, maximal.length)
        assertNotNull(FollowRouteDescriptor.parse(maximal))
        for (bad in listOf(line.trimEnd(), "$line\n", line.replace("\n", "\r\n"), line.replace(" host", "  host"),
            line.replace("local", "本地"), line.replace("/1", "/2"), maximal.replaceFirst("a", "aa"),
            line.replace("127.0.0.1", "127.0.0.2"), line.replace("127.0.0.1", "localhost"),
            line.replace("12345", "012345"), line.replace("12345", "65536"), line.replace("12345", "0"),
            line.replace(pin, pin.uppercase()), line.replace(spki, point("secp384r1")),
            line.replace(spki, spki.take(54) + "00".repeat(64)), line.replace("\n", " extra\n"))) {
            assertNull(FollowRouteDescriptor.parse(bad))
        }
    }

    @Test fun scopeMismatchKeepsTheCurrentRouteAndExplicitReplacementRetiresIt() {
        val scheduler = Executors.newSingleThreadScheduledExecutor()
        try {
            FollowRouteController(scheduler, Executor { error("No reader or dial") }).use { controller ->
                val approved = assertNotNull(FollowRouteDescriptor.parse(descriptor()))
                val key = ByteArray(32) { 1 }
                val route = assertNotNull(controller.configure(target, "remote", approved, key))
                val challenge = assertNotNull(controller.challenge(route))
                val proof = assertNotNull(controller.proof(route, challenge))
                assertNull(controller.configure(target.copy(localWorkspaceId = "elsewhere"), "remote", approved, key))
                assertNull(controller.configure(target, "other-remote", approved, key))
                assertEquals(proof, controller.proof(route, challenge))
                val rotated = assertNotNull(FollowRouteDescriptor.parse(descriptor(signer = point("secp256r1"))))
                val current = assertNotNull(controller.configure(target, "remote", rotated, ByteArray(32) { 2 }))
                assertNull(controller.proof(route, challenge))
                assertFalse(controller.clear(route))
                assertTrue(controller.recheck(current))
                assertNull(controller.proof(current, challenge))
            }
        } finally { scheduler.shutdownNow() }
    }

    private fun descriptor(signer: String = spki) = "cordiact-follow-route/1 host local remote session 127.0.0.1 12345 $pin $signer\n"

    private fun point(curve: String) = KeyPairGenerator.getInstance("EC").run {
        initialize(ECGenParameterSpec(curve))
        generateKeyPair().public.encoded.toHexString()
    }
}
