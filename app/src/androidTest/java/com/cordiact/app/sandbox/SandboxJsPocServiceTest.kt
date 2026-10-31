package com.cordiact.app.sandbox

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Build
import android.os.IBinder
import android.os.Parcel
import android.os.Process
import android.os.RemoteException
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

@RunWith(AndroidJUnit4::class)
class SandboxJsPocServiceTest {
    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun bundledJavaScriptRunsOnlyInAnIsolatedProcess() {
        assumeTrue(Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
        val session = bindPocService()
        try {
            assertTrue("Worker did not attest isolated", session.api.isIsolated())
            assertNotEquals("Worker UID must differ from host", Process.myUid(), session.api.getWorkerUid())
            assertEquals(
                "{\"engine\":\"QuickJS-NG/0.17.0\",\"proof\":\"signed-fixed-script\",\"sum\":499500,\"hostFree\":true}",
                session.api.runSample(2_000),
            )
        } finally {
            session.close()
        }
    }

    @Test
    fun rawBinderRejectsUnknownTrailingAndOversizedFrames() {
        assumeTrue(Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
        val session = bindPocService()
        try {
            for (kind in listOf("unknown", "wrong-token", "trailing", "oversized")) {
                assertTrue("Raw S3 frame was accepted: $kind", rawFrameRejected(session.binder, kind))
                assertTrue("S3 worker died after rejecting $kind", session.binder.isBinderAlive)
                assertTrue(session.api.runSample(2_000).contains("\"hostFree\":true"))
            }
        } finally {
            session.close()
        }
    }

    @Test
    fun engineHeapAndStackBoundsRejectFixedStressVectorsWithoutHostFailure() {
        assumeTrue(Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
        val session = bindPocService()
        try {
            assertTrue(session.api.isIsolated())
            val memoryResult = session.api.runMemoryLimitProbe(2_500)
            assertTrue("Expected QuickJS allocator bound, got: $memoryResult", memoryResult.startsWith("ERROR:"))
            val stackResult = session.api.runStackLimitProbe(2_500)
            assertTrue("Expected QuickJS stack bound, got: $stackResult", stackResult.startsWith("ERROR:"))
            assertTrue("PoC worker died after a bounded exception", session.binder.isBinderAlive)
            assertTrue(session.api.isIsolated())
            assertTrue(session.api.runSample(2_000).contains("\"hostFree\":true"))
        } finally {
            session.close()
        }
    }

    @Test
    fun fixedTimeoutVectorKillsOnlyTheIsolatedWorker() {
        assumeTrue(Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
        val session = bindPocService()
        val hostPid = Process.myPid()
        try {
            assertTrue(session.api.isIsolated())
            assertNotEquals(Process.myUid(), session.api.getWorkerUid())
            try {
                session.api.runTimeoutProbe(750)
                throw AssertionError("Expected the fixed timeout vector to kill the worker")
            } catch (_: RemoteException) {
                // The isolated process is deliberately killed at its execution deadline.
            }
            assertTrue("Isolated worker binder did not die", waitUntil(5_000) { !session.binder.isBinderAlive })
            assertEquals("Host process must survive isolated timeout", hostPid, Process.myPid())
        } finally {
            session.close()
        }
    }

    private fun rawFrameRejected(binder: IBinder, kind: String): Boolean {
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            val transaction = when (kind) {
                "unknown" -> IBinder.FIRST_CALL_TRANSACTION + 99
                "oversized" -> IBinder.FIRST_CALL_TRANSACTION + 2
                else -> IBinder.FIRST_CALL_TRANSACTION
            }
            data.writeInterfaceToken(
                if (kind == "wrong-token") "not.cordiact.ISandboxJsPoc"
                else "com.cordiact.app.sandbox.ISandboxJsPoc",
            )
            when (kind) {
                "trailing" -> data.writeInt(7)
                "oversized" -> {
                    data.writeLong(2_000L)
                    data.writeString("x".repeat(5_000))
                }
            }
            return try {
                if (!binder.transact(transaction, data, reply, 0)) {
                    true
                } else {
                    try {
                        reply.readException()
                        false
                    } catch (_: Exception) {
                        true
                    }
                }
            } catch (_: Exception) {
                true
            }
        } finally {
            reply.recycle()
            data.recycle()
        }
    }

    private fun bindPocService(): PocSession {
        val connected = CountDownLatch(1)
        val apiRef = AtomicReference<ISandboxJsPoc?>()
        val binderRef = AtomicReference<IBinder?>()
        val executor = Executors.newSingleThreadExecutor()
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                binderRef.set(service)
                apiRef.set(service?.let(ISandboxJsPoc.Stub::asInterface))
                connected.countDown()
            }

            override fun onServiceDisconnected(name: ComponentName?) = Unit
        }
        val intent = Intent().setComponent(
            ComponentName(context.packageName, "com.cordiact.app.sandbox.SandboxJsPocService"),
        )
        val instanceName = "s3_${UUID.randomUUID().toString().replace("-", "")}"
        val bound = context.bindIsolatedService(
            intent,
            Context.BIND_AUTO_CREATE,
            instanceName,
            executor,
            connection,
        )
        assertTrue("bindIsolatedService refused the PoC service", bound)
        assertTrue("Timed out binding the isolated JS service", connected.await(10, TimeUnit.SECONDS))
        val api = requireNotNull(apiRef.get())
        val binder = requireNotNull(binderRef.get())
        return PocSession(context, connection, executor, api, binder)
    }

    private fun waitUntil(timeoutMillis: Long, predicate: () -> Boolean): Boolean {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis)
        while (System.nanoTime() < deadline) {
            if (predicate()) return true
            Thread.sleep(25)
        }
        return predicate()
    }

    private data class PocSession(
        val context: Context,
        val connection: ServiceConnection,
        val executor: ExecutorService,
        val api: ISandboxJsPoc,
        val binder: IBinder,
    ) {
        fun close() {
            runCatching { context.unbindService(connection) }
            executor.shutdownNow()
        }
    }
}
