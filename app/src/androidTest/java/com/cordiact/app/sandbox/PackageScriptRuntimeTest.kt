package com.cordiact.app.sandbox

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Build
import android.os.IBinder
import android.os.Process
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.cordiact.core.plugin.PluginInstanceId
import com.cordiact.core.sandbox.sha256Hex
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class PackageScriptRuntimeTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val owner = PluginInstanceId("pkg.script-test", "workspace")

    @Test
    fun binderChecksExactDigestAndRetiresAfterOneEvaluation() {
        assumeTrue(Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
        val connected = CompletableFuture<IBinder>()
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, service: IBinder) { connected.complete(service) }
            override fun onServiceDisconnected(name: ComponentName) = Unit
        }
        val instance = "verify_${UUID.randomUUID().toString().replace("-", "")}"
        assertTrue(context.bindIsolatedService(Intent(context, PackageScriptWorkerService::class.java), Context.BIND_AUTO_CREATE,
            instance, context.mainExecutor, connection))
        val retired = CountDownLatch(1)
        try {
            val binder = connected.get(5, TimeUnit.SECONDS)
            binder.linkToDeath({ retired.countDown() }, 0)
            val worker = IPackageScriptWorker.Stub.asInterface(binder)
            assertNotEquals(Process.myUid(), worker.workerUid)
            val source = "(text) => text".encodeToByteArray()
            val input = "scoped".encodeToByteArray()
            fun evaluate(version: Int, digest: String) = worker.evaluate(version, digest, source, input,
                SystemClock.elapsedRealtimeNanos() + TimeUnit.SECONDS.toNanos(1))
            val version = PackageScriptWorkerService.PROTOCOL_VERSION
            val digest = sha256Hex(source)
            assertThrows(IllegalArgumentException::class.java) { evaluate(version + 1, digest) }
            assertThrows(IllegalArgumentException::class.java) { evaluate(version, "0".repeat(64)) }
            assertEquals("scoped", evaluate(version, digest).decodeToString())
            assertThrows(IllegalStateException::class.java) { evaluate(version, digest) }
        } finally {
            context.unbindService(connection)
        }
        assertTrue("isolated worker should die after unbind", retired.await(5, TimeUnit.SECONDS))
    }

    @Test
    fun textRoundTripsThroughDistinctIsolatedWorkersWithoutHostApisOrSharedState() {
        assumeTrue(Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
        AndroidPackageScriptRuntime(context).open(owner).use { session ->
            val source = """(text) => {
                globalThis.calls = (globalThis.calls || 0) + 1;
                return [calls, typeof require, typeof std, typeof os, text].join("|");
            }""".encodeToByteArray()
            val input = "🌱\u0000中文".encodeToByteArray()
            repeat(2) {
                assertEquals("1|undefined|undefined|undefined|🌱\u0000中文", session.evaluate(source, input).decodeToString())
            }
        }
    }

    @Test
    fun scriptErrorsAndDeadlineDoNotLeakDetailsOrPreventNextCall() {
        assumeTrue(Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
        AndroidPackageScriptRuntime(context).open(owner).use { session ->
            for (source in listOf("() => { throw new Error('private marker'); }", "() => 'a'.repeat(4097)", "() => { while (true) {} }")) {
                val failure = assertThrows(RuntimeException::class.java) { session.evaluate(source.encodeToByteArray(), byteArrayOf()) }
                assertFalse(failure.message.orEmpty().contains("private marker"))
                assertEquals("ready", session.evaluate("() => 'ready'".encodeToByteArray(), byteArrayOf()).decodeToString())
            }
        }
    }

    @Test
    fun closedPackageScopeRefusesNewEvaluation() {
        assumeTrue(Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
        val session = AndroidPackageScriptRuntime(context).open(owner)
        session.close()
        assertThrows(IllegalStateException::class.java) { session.evaluate("() => 'closed'".encodeToByteArray(), byteArrayOf()) }
    }
}
