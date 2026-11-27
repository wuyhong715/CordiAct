package com.cordiact.app.sandbox

import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Parcel
import android.os.Process
import android.os.SystemClock
import com.cordiact.core.plugin.PACKAGE_SCRIPT_MAX_SOURCE_BYTES
import com.cordiact.core.plugin.PACKAGE_SCRIPT_MAX_TEXT_BYTES
import com.cordiact.core.sandbox.sha256Hex
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Loads the interpreter only in the isolated UID. The only script input and output is text. */
class PackageScriptWorkerService : Service() {
    private val used = AtomicBoolean()
    private val watchdog by lazy { Handler(Looper.getMainLooper()) }
    private val killWorker = Runnable { Process.killProcess(Process.myPid()) }

    private val worker = object : IPackageScriptWorker.Stub() {
        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            enforceOwner()
            require(data.dataSize() <= MAX_TRANSACTION_BYTES) { "Oversized script transaction" }
            return super.onTransact(code, data, reply, flags)
        }

        override fun getWorkerUid(): Int {
            enforceOwner()
            return Process.myUid()
        }

        override fun evaluate(version: Int, sourceDigest: String, source: ByteArray, input: ByteArray, deadlineNanos: Long): ByteArray {
            enforceOwner()
            require(version == PROTOCOL_VERSION) { "Unsupported script protocol" }
            require(source.size in 1..PACKAGE_SCRIPT_MAX_SOURCE_BYTES && input.size <= PACKAGE_SCRIPT_MAX_TEXT_BYTES)
            require(sourceDigest == sha256Hex(source)) { "Script digest mismatch" }
            val remaining = deadlineNanos - SystemClock.elapsedRealtimeNanos()
            require(deadlineNanos > 0 && remaining in 1..TimeUnit.MILLISECONDS.toNanos(EXECUTION_MILLIS))
            check(used.compareAndSet(false, true)) { "Script worker was already used" }

            watchdog.postDelayed(killWorker, TimeUnit.NANOSECONDS.toMillis(remaining) + WATCHDOG_GRACE_MILLIS)
            try {
                val output = nativeEvaluate(source, input, deadlineNanos)
                if (SystemClock.elapsedRealtimeNanos() >= deadlineNanos) {
                    killWorker.run()
                    error("Script deadline exceeded")
                }
                return checkNotNull(output) { "Script evaluation failed" }
            } finally {
                watchdog.removeCallbacks(killWorker)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        check(Process.isIsolated()) { "Package script worker requires an isolated process" }
        System.loadLibrary("cordiact_qjs_poc")
    }

    override fun onBind(intent: Intent?): IBinder = worker

    override fun onUnbind(intent: Intent?): Boolean {
        if (Process.isIsolated()) killWorker.run()
        return false
    }

    private fun enforceOwner() {
        if (!Process.isIsolated() || Binder.getCallingUid() != applicationInfo.uid) {
            throw SecurityException("Script worker accepts calls from the owning app into an isolated UID")
        }
    }

    private external fun nativeEvaluate(source: ByteArray, input: ByteArray, deadlineNanos: Long): ByteArray?

    internal companion object {
        const val PROTOCOL_VERSION = 1
        const val EXECUTION_MILLIS = 2_000L
        private const val WATCHDOG_GRACE_MILLIS = 300L
        private const val MAX_TRANSACTION_BYTES = 32 * 1024
    }
}
