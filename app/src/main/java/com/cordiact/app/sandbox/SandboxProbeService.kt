package com.cordiact.app.sandbox

import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Process
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

    private data class ProbeIdentity(
        val sessionId: String,
        val nonce: String,
        val pluginId: String,
        val pluginVersion: String,
        val sha256: String,
    )

    private val binder = object : ISandboxProbe.Stub() {
        override fun getProcessUid(): Int {
            enforceHostCaller()
            return Process.myUid()
        }

        override fun isProcessIsolated(): Boolean {
            enforceHostCaller()
            return Process.isIsolated()
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
                check(session == null) { "This isolated process already has a session" }
                session = ProbeIdentity(sessionId, nonce, pluginId, pluginVersion, sha256)
                closing.set(false)
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
            requireSession(sessionId, nonce)
            require(requestId.matches(REQUEST_PATTERN))
            require(payload.toByteArray(Charsets.UTF_8).size <= MAX_REQUEST_BYTES) { "Probe input exceeds frame limit" }
            require(deadlineMillis in 1..MAX_DEADLINE_MILLIS) { "Probe deadline exceeds host limit" }
            check(!closing.get()) { "Sandbox session is closing" }

            val currentThread = Thread.currentThread()
            check(requests.putIfAbsent(requestId, currentThread) == null) { "Duplicate probe request id" }
            val deadline = Runnable {
                if (requests.containsKey(requestId)) {
                    // The service main looper is independent of this Binder worker thread.
                    Process.killProcess(Process.myPid())
                }
            }
            watchdogs[requestId] = deadline
            mainHandler.postDelayed(deadline, deadlineMillis)
            try {
                return when (operation) {
                    OP_ECHO -> payload
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
                requests.remove(requestId, currentThread)
                watchdogs.remove(requestId)?.let(mainHandler::removeCallbacks)
            }
        }

        override fun cancelProbe(sessionId: String, nonce: String, requestId: String) {
            enforceHostCaller()
            requireSession(sessionId, nonce)
            requests[requestId]?.interrupt()
        }

        override fun closeSession(sessionId: String, nonce: String) {
            enforceHostCaller()
            requireSession(sessionId, nonce)
            if (closing.compareAndSet(false, true)) {
                requests.values.forEach(Thread::interrupt)
                // Reply first; then terminate this dedicated isolated process.
                mainHandler.postDelayed({ Process.killProcess(Process.myPid()) }, CLOSE_KILL_DELAY_MILLIS)
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
        const val OP_SLEEP = "sleep"
        const val OP_HANG = "hang"
        const val OP_CRASH = "crash"
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
