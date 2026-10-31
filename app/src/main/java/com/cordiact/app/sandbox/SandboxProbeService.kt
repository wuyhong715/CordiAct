package com.cordiact.app.sandbox

import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Process
import android.os.Parcel
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * S2 only: fixed echo/sleep/hang/crash probes in an Android isolated UID.
 * No plugin JS, files, network, app Context, credentials, or capabilities are exposed here.
 */
class SandboxProbeService : Service() {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val requests = ConcurrentHashMap<String, Thread>()
    private val watchdogs = ConcurrentHashMap<String, Runnable>()
    private val sessionLock = Any()
    private var session: ProbeIdentity? = null
    private val closing = AtomicBoolean(false)
    // Admission and close share sessionLock; only one Binder operation per isolated instance.
    private var activeRequest = false

    private data class ProbeIdentity(
        val sessionId: String,
        val nonce: String,
        val pluginId: String,
        val pluginVersion: String,
        val sha256: String,
    )

    private val binder = object : ISandboxProbe.Stub() {
        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            val argumentCount = when (code - IBinder.FIRST_CALL_TRANSACTION) {
                0, 1, 2 -> 0 // UID, isolation state, active request count
                3, 4 -> 5 // openSession or executeProbe
                5 -> 3 // cancelProbe
                6 -> 2 // closeSession
                else -> return super.onTransact(code, data, reply, flags)
            }
            enforceHostCaller()
            // AIDL-generated stubs in supported Android builds may ignore extra trailing
            // bytes. Validate the *whole* frame before invoking any generated dispatch.
            require(data.dataSize() <= MAX_BINDER_FRAME_BYTES) { "Binder frame exceeds limit" }
            val savedPosition = data.dataPosition()
            try {
                data.enforceInterface(PROBE_DESCRIPTOR)
                repeat(argumentCount) { data.readString() }
                if (code == IBinder.FIRST_CALL_TRANSACTION + 4) data.readLong()
                require(data.dataAvail() == 0) { "Trailing Binder frame data" }
            } finally {
                data.setDataPosition(savedPosition)
            }
            return super.onTransact(code, data, reply, flags)
        }

        override fun getProcessUid(): Int {
            enforceHostCaller()
            return Process.myUid()
        }

        override fun isProcessIsolated(): Boolean {
            enforceHostCaller()
            return Process.isIsolated()
        }

        override fun getActiveProbeCount(): Int {
            enforceHostCaller()
            return synchronized(sessionLock) { if (activeRequest) 1 else 0 }
        }

        override fun openSession(
            sessionId: String,
            nonce: String,
            pluginId: String,
            pluginVersion: String,
            sha256: String,
        ): Boolean {
            enforceHostCaller()
            check(Process.isIsolated()) { "Service process is not isolated" }
            check(Process.myUid() != applicationInfo.uid) { "Isolated service shares the app UID" }
            require(sessionId.matches(SESSION_PATTERN))
            require(nonce.matches(NONCE_PATTERN))
            require(pluginId.matches(PLUGIN_ID_PATTERN))
            require(pluginVersion.isNotBlank() && pluginVersion.length <= 64)
            require(sha256.matches(SHA256_PATTERN))
            synchronized(sessionLock) {
                check(session == null && !closing.get()) { "This isolated process already has a session" }
                session = ProbeIdentity(sessionId, nonce, pluginId, pluginVersion, sha256)
            }
            return true
        }

        override fun executeProbe(
            sessionId: String,
            nonce: String,
            requestId: String,
            operation: String,
            payload: String,
            deadlineMillis: Long,
        ): String {
            enforceHostCaller()
            require(requestId.matches(REQUEST_PATTERN)) { "Invalid probe request id" }
            require(payload.length <= MAX_REQUEST_BYTES && payload.toByteArray(Charsets.UTF_8).size <= MAX_REQUEST_BYTES) {
                "Probe input exceeds frame limit"
            }
            require(deadlineMillis in 1..MAX_DEADLINE_MILLIS) { "Probe deadline exceeds host limit" }

            val currentThread = Thread.currentThread()
            synchronized(sessionLock) {
                requireSession(sessionId, nonce)
                check(!closing.get()) { "Sandbox session is closing" }
                check(!activeRequest) { "Sandbox concurrent request limit reached" }
                check(requests.putIfAbsent(requestId, currentThread) == null) { "Duplicate probe request id" }
                activeRequest = true
            }
            val deadline = Runnable {
                if (requests.containsKey(requestId)) {
                    // The service main looper is independent of this Binder worker thread.
                    Process.killProcess(Process.myPid())
                }
            }
            try {
                watchdogs[requestId] = deadline
                check(mainHandler.postDelayed(deadline, deadlineMillis)) { "Sandbox watchdog unavailable" }
                return when (operation) {
                    OP_ECHO -> payload
                    // Fixed 8193-byte probe verifies the output frame guard without user code.
                    OP_OVERSIZED_OUTPUT -> "X".repeat(MAX_RESPONSE_BYTES + 1)
                    OP_SLEEP -> {
                        val sleepMillis = payload.toLongOrNull() ?: throw IllegalArgumentException("sleep payload must be milliseconds")
                        require(sleepMillis in 0..MAX_SLEEP_MILLIS)
                        Thread.sleep(sleepMillis)
                        "slept:$sleepMillis"
                    }
                    OP_HANG -> {
                        // Deliberately ignores interruption. The independent watchdog must kill this process.
                        while (true) {
                            try { Thread.sleep(1_000) } catch (_: InterruptedException) { /* keep hanging */ }
                        }
                        @Suppress("UNREACHABLE_CODE") "unreachable"
                    }
                    OP_CRASH -> Process.killProcess(Process.myPid()).let { "unreachable" }
                    else -> throw IllegalArgumentException("Unknown fixed probe operation")
                }.also { result ->
                    require(result.toByteArray(Charsets.UTF_8).size <= MAX_RESPONSE_BYTES) { "Probe output exceeds frame limit" }
                }
            } finally {
                synchronized(sessionLock) {
                    requests.remove(requestId, currentThread)
                    activeRequest = false
                }
                watchdogs.remove(requestId)?.let(mainHandler::removeCallbacks)
            }
        }

        override fun cancelProbe(sessionId: String, nonce: String, requestId: String) {
            enforceHostCaller()
            require(requestId.matches(REQUEST_PATTERN)) { "Invalid probe request id" }
            requireSession(sessionId, nonce)
            requests[requestId]?.interrupt()
        }

        override fun closeSession(sessionId: String, nonce: String) {
            enforceHostCaller()
            val toInterrupt = synchronized(sessionLock) {
                requireSession(sessionId, nonce)
                if (closing.compareAndSet(false, true)) requests.values.toList() else emptyList()
            }
            toInterrupt.forEach(Thread::interrupt)
            // Termination is not optional: a failed handler post kills the worker immediately.
            if (!mainHandler.postDelayed({ Process.killProcess(Process.myPid()) }, CLOSE_KILL_DELAY_MILLIS)) {
                Process.killProcess(Process.myPid())
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onDestroy() {
        closing.set(true)
        watchdogs.values.forEach(mainHandler::removeCallbacks)
        requests.values.forEach(Thread::interrupt)
        super.onDestroy()
    }

    private fun enforceHostCaller() {
        if (Binder.getCallingUid() != applicationInfo.uid) {
            throw SecurityException("Sandbox probe accepts IPC only from its owning app UID")
        }
    }

    private fun requireSession(sessionId: String, nonce: String) {
        val active = synchronized(sessionLock) { session }
            ?: throw SecurityException("Sandbox session is not open")
        if (active.sessionId != sessionId || !constantTimeEquals(active.nonce, nonce)) {
            throw SecurityException("Sandbox session identity mismatch")
        }
    }

    private fun constantTimeEquals(first: String, second: String): Boolean =
        MessageDigest.isEqual(first.toByteArray(Charsets.UTF_8), second.toByteArray(Charsets.UTF_8))

    private companion object {
        const val OP_ECHO = "echo"
        const val OP_OVERSIZED_OUTPUT = "oversized-output"
        const val OP_SLEEP = "sleep"
        const val OP_HANG = "hang"
        const val OP_CRASH = "crash"
        // Parcel writes strings as UTF-16 plus interface/token and metadata overhead.
        const val MAX_BINDER_FRAME_BYTES = 12 * 1024
        const val PROBE_DESCRIPTOR = "com.cordiact.app.sandbox.ISandboxProbe"
        const val MAX_REQUEST_BYTES = 4 * 1024
        const val MAX_RESPONSE_BYTES = 8 * 1024
        const val MAX_DEADLINE_MILLIS = 30_000L
        const val MAX_SLEEP_MILLIS = 60_000L
        const val CLOSE_KILL_DELAY_MILLIS = 50L
        val SESSION_PATTERN = Regex("[A-Za-z0-9._:-]{1,128}")
        val NONCE_PATTERN = Regex("[a-f0-9]{64}")
        val REQUEST_PATTERN = Regex("[A-Za-z0-9._:-]{1,128}")
        val PLUGIN_ID_PATTERN = Regex("[a-z0-9][a-z0-9._-]{1,127}")
        val SHA256_PATTERN = Regex("[a-f0-9]{64}")
    }
}
