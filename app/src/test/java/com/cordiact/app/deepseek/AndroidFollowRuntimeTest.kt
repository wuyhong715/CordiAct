package com.cordiact.app.deepseek

import com.cordiact.harness.deepseek.FollowRouteDescriptor
import com.cordiact.harness.deepseek.FollowTarget
import java.security.KeyPairGenerator
import java.security.spec.ECGenParameterSpec
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidFollowRuntimeTest {
    @Test
    fun factoryRequiresApi29AndUsesTheSuppliedElapsedClockWithoutOpeningIo() {
        val scheduler = Executors.newSingleThreadScheduledExecutor()
        val elapsed = AtomicLong(50)
        val noReads = Executor { error("Factory and challenge must not dispatch I/O") }
        try {
            assertNull(AndroidFollowRuntime.createForPlatform(28, { error("Unsupported platform must not read clock") }, scheduler, noReads))
            checkNotNull(AndroidFollowRuntime.createForPlatform(29, elapsed::get, scheduler, noReads)).use { controller ->
                val target = FollowTarget("host", "local", "session")
                val signer = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
                val descriptor = checkNotNull(FollowRouteDescriptor.parse(
                    "cordiact-follow-route/1 host local remote session 127.0.0.1 12345 ${"ab".repeat(32)} ${signer.public.encoded.toHexString()}\n",
                ))
                val route = checkNotNull(controller.configure(target, "remote", descriptor, ByteArray(32) { 1 }))
                val challenge = checkNotNull(controller.challenge(route))
                assertNotNull(controller.proof(route, challenge))
                elapsed.addAndGet(TimeUnit.SECONDS.toNanos(15))
                assertTrue(controller.recheck(route))
                assertNull(controller.proof(route, challenge))
            }
        } finally { scheduler.shutdownNow() }
    }
}
