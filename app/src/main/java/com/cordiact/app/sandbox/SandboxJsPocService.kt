package com.cordiact.app.sandbox

import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Parcel
import android.os.Process
import android.os.SystemClock
import com.cordiact.app.R
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** S3 test-only JS proof. This service must never be bound outside an isolated process. */
class SandboxJsPocService : Service() {
    private lateinit var watchdogThread: HandlerThread
    private lateinit var watchdogHandler: Handler
    private val executionActive = AtomicBoolean(false)

    private val binder = object : ISandboxJsPoc.Stub() {
        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            enforceHostCaller()
            val scalarArguments = when (code) {
                IBinder.INTERFACE_TRANSACTION -> return super.onTransact(code, data, reply, flags)
                else -> when (code - IBinder.FIRST_CALL_TRANSACTION) {
                    0, 1 -> 0
                    2, 3, 4, 5 -> 1
                    else -> return false
                }
            }
            require(data.dataSize() <= MAX_BINDER_FRAME_BYTES) { "JS PoC Binder frame exceeds bound" }
            val savedPosition = data.dataPosition()
            try {
                data.enforceInterface(PROBE_DESCRIPTOR)
                repeat(scalarArguments) { data.readLong() }
                require(data.dataAvail() == 0) { "Trailing JS PoC Binder frame data" }
            } finally {
                data.setDataPosition(savedPosition)
            }
            return super.onTransact(code, data, reply, flags)
        }

        override fun getWorkerUid(): Int {
            enforceHostCaller()
            return Process.myUid()
        }

        override fun isIsolated(): Boolean {
            enforceHostCaller()
            return Process.isIsolated()
        }

        override fun runSample(timeoutMillis: Long): String =
            runFixedResource(R.raw.sandbox_s3_fixed, FIXED_SHA256, timeoutMillis)

        override fun runMemoryLimitProbe(timeoutMillis: Long): String =
            runFixedResource(R.raw.sandbox_s3_memory, MEMORY_SHA256, timeoutMillis)

        override fun runStackLimitProbe(timeoutMillis: Long): String =
            runFixedResource(R.raw.sandbox_s3_stack, STACK_SHA256, timeoutMillis)

        override fun runTimeoutProbe(timeoutMillis: Long): String =
            runFixedResource(R.raw.sandbox_s3_timeout, TIMEOUT_SHA256, timeoutMillis)
    }

    override fun onCreate() {
        super.onCreate()
        check(Process.isIsolated()) { "SandboxJsPocService refuses non-isolated execution" }
        System.loadLibrary("cordiact_qjs_poc")
        watchdogThread = HandlerThread("cordiact-s3-watchdog").apply { start() }
        watchdogHandler = Handler(watchdogThread.looper)
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onDestroy() {
        if (::watchdogThread.isInitialized) watchdogThread.quitSafely()
        super.onDestroy()
    }

    private fun runFixedResource(resourceId: Int, expectedSha256: String, timeoutMillis: Long): String {
        require(timeoutMillis in MIN_TIMEOUT_MILLIS..MAX_TIMEOUT_MILLIS) { "Timeout outside PoC bound" }
        check(executionActive.compareAndSet(false, true)) { "A JS evaluation is already active" }
        try {
            val source = resources.openRawResource(resourceId).use { it.readBytes() }
            require(source.size in 1..MAX_SOURCE_BYTES) { "Bundled script exceeds source bound" }
            check(sha256(source) == expectedSha256) { "Bundled script digest mismatch" }

            val deadlineNanos = SystemClock.elapsedRealtimeNanos() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis)
            val watchdog = Runnable { Process.killProcess(Process.myPid()) }
            watchdogHandler.postDelayed(watchdog, timeoutMillis + WATCHDOG_GRACE_MILLIS)
            try {
                val result = nativeEvaluate(source, deadlineNanos)
                if (SystemClock.elapsedRealtimeNanos() >= deadlineNanos) {
                    // The interpreter deadline is cooperative; this hard wall deadline kills the
                    // isolated process even if native execution fails to return promptly.
                    Process.killProcess(Process.myPid())
                    throw IllegalStateException("Sandbox JS deadline expired")
                }
                return result
            } finally {
                watchdogHandler.removeCallbacks(watchdog)
            }
        } finally {
            executionActive.set(false)
        }
    }

    private fun enforceHostCaller() {
        if (!Process.isIsolated()) throw SecurityException("JS PoC must run in isolated process")
        if (Binder.getCallingUid() != applicationInfo.uid) {
            throw SecurityException("JS PoC accepts Binder only from the owning app UID")
        }
    }

    private external fun nativeEvaluate(source: ByteArray, deadlineNanos: Long): String

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString("") { "%02x".format(it) }

    private companion object {
        const val MIN_TIMEOUT_MILLIS = 100L
        const val MAX_TIMEOUT_MILLIS = 5_000L
        const val WATCHDOG_GRACE_MILLIS = 300L
        const val MAX_SOURCE_BYTES = 16 * 1024
        const val MAX_BINDER_FRAME_BYTES = 4 * 1024
        const val PROBE_DESCRIPTOR = "com.cordiact.app.sandbox.ISandboxJsPoc"
        const val FIXED_SHA256 = "702e554c1431094f8e5c4c7260f545cdb80b6ff5a53eab8902375123d2866953"
        const val TIMEOUT_SHA256 = "2d63ea6d8fbc0786bad2ca1e6d4d42082ab8ff34e9f8e921474d53041a1f561c"
        const val MEMORY_SHA256 = "5c4a8c8e4ffc4962729c54893b1e59875f35b65c6d74e6d9ce36768299f476b4"
        const val STACK_SHA256 = "a7c2ee51c346aa4a3fd8d53de56d72f75c7a034dccbec8785e6155c44cd99d15"
    }
}
