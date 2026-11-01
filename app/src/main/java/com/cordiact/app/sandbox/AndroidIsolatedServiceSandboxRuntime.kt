package com.cordiact.app.sandbox

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Build
import android.os.IBinder
import android.os.Parcel
import android.os.Looper
import android.os.RemoteException
import com.cordiact.core.sandbox.IsolatedSandboxRuntime
import com.cordiact.core.sandbox.SandboxIsolationKind
import com.cordiact.core.sandbox.SandboxProfile
import com.cordiact.core.sandbox.SandboxRuntimeSession
import com.cordiact.core.sandbox.SandboxTermination
import java.security.SecureRandom
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.Semaphore
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Android S2 proof adapter for the fixed-probe isolated service. It is not wired into production
 * app dispatch until instrumentation tests pass on supported Android versions.
 */
class AndroidIsolatedServiceSandboxRuntime(context: Context) : IsolatedSandboxRuntime {
    private val appContext = context.applicationContext
    override val isolationKind = SandboxIsolationKind.ANDROID_ISOLATED_SERVICE

    override fun start(profile: SandboxProfile): SandboxRuntimeSession {
        check(Looper.myLooper() != Looper.getMainLooper()) { "Sandbox service binding must not block the main thread" }
        check(Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            "Named isolated service instances require Android 10/API 29; refusing fallback"
        }
        require(profile.sessionId.matches(SESSION_PATTERN)) { "Unsupported sandbox session id" }
        require(profile.plugin.sha256.matches(SHA256_PATTERN)) { "Invalid plugin digest" }

        val connected = CountDownLatch(1)
        val dead = CountDownLatch(1)
        val isDisconnected = AtomicBoolean(false)
        val bound = AtomicBoolean(false)
        val connectionRef = arrayOfNulls<ServiceConnection>(1)
        val binderRef = arrayOfNulls<IBinder>(1)
        val probeRef = arrayOfNulls<ISandboxProbe>(1)
        val nonce = randomNonce()

        fun unbindOnce() {
            if (bound.compareAndSet(true, false)) {
                runCatching { appContext.unbindService(connectionRef[0]!!) }
            }
        }

        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, binder: IBinder) {
                if (isDisconnected.get()) return
                binderRef[0] = binder
                probeRef[0] = ISandboxProbe.Stub.asInterface(binder)
                try {
                    binder.linkToDeath({
                        isDisconnected.set(true)
                        dead.countDown()
                        unbindOnce()
                    }, 0)
                } catch (_: RemoteException) {
                    isDisconnected.set(true)
                    dead.countDown()
                    connected.countDown()
                    unbindOnce()
                    return
                }
                connected.countDown()
            }

            override fun onServiceDisconnected(name: ComponentName) {
                isDisconnected.set(true)
                dead.countDown()
                unbindOnce()
            }

            override fun onBindingDied(name: ComponentName) {
                isDisconnected.set(true)
                dead.countDown()
                connected.countDown()
                unbindOnce()
            }

            override fun onNullBinding(name: ComponentName) {
                isDisconnected.set(true)
                connected.countDown()
                unbindOnce()
            }
        }
        connectionRef[0] = connection

        val intent = Intent(appContext, SandboxProbeService::class.java)
        val instanceName = "ca_${profile.sessionId.filter(Char::isLetterOrDigit).take(28)}"
        val didBind = appContext.bindIsolatedService(
            intent,
            Context.BIND_AUTO_CREATE,
            instanceName,
            appContext.mainExecutor,
            connection,
        )
        check(didBind) { "Android rejected isolated sandbox service binding" }
        bound.set(true)

        try {
            check(connected.await(STARTUP_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)) {
                "Timed out waiting for isolated sandbox service"
            }
            check(!isDisconnected.get()) { "Isolated sandbox service disconnected during startup" }
            val probe = checkNotNull(probeRef[0]) { "Isolated service returned a null Binder" }
            val remoteUid = probe.processUid
            check(probe.isProcessIsolated) { "Android service does not report an isolated process" }
            check(remoteUid != appContext.applicationInfo.uid) { "Android isolated service shares the app UID" }
            check(probe.openSession(
                profile.sessionId,
                nonce,
                profile.plugin.pluginId,
                profile.plugin.version,
                profile.plugin.sha256,
            )) { "Isolated service rejected sandbox session handshake" }
            return ProbeSession(
                context = appContext,
                sessionId = profile.sessionId,
                nonce = nonce,
                processUid = remoteUid,
                reportsIsolated = probe.isProcessIsolated,
                probe = probe,
                binder = checkNotNull(binderRef[0]),
                connection = connection,
                connected = connected,
                dead = dead,
                disconnected = isDisconnected,
                bound = bound,
                unbind = ::unbindOnce,
            )
        } catch (failure: Exception) {
            isDisconnected.set(true)
            runCatching { probeRef[0]?.closeSession(profile.sessionId, nonce) }
            unbindOnce()
            throw failure
        }
    }

    class ProbeSession internal constructor(
        private val context: Context,
        private val sessionId: String,
        private val nonce: String,
        override val processUid: Int,
        val reportsIsolated: Boolean,
        private val probe: ISandboxProbe,
        private val binder: IBinder,
        @Suppress("unused") private val connection: ServiceConnection,
        @Suppress("unused") private val connected: CountDownLatch,
        private val dead: CountDownLatch,
        private val disconnected: AtomicBoolean,
        private val bound: AtomicBoolean,
        private val unbind: () -> Unit,
    ) : SandboxRuntimeSession {
        // One admitted caller; one worker; one handoff slot allows sequential calls even when
        // Future.get() completes before the previous worker thread has returned to the pool.
        private val callPermit = Semaphore(1, true)
        private val calls = ThreadPoolExecutor(
            1, 1, 0, TimeUnit.MILLISECONDS, ArrayBlockingQueue(1),
            { runnable -> Thread(runnable, "cordiact-sandbox-probe-call").apply { isDaemon = true } },
            ThreadPoolExecutor.AbortPolicy(),
        )
        override val isolationKind = SandboxIsolationKind.ANDROID_ISOLATED_SERVICE
        override val isAlive: Boolean get() = !disconnected.get() && binder.isBinderAlive

        /** S2 test hook; fixed operations only, not a plugin evaluation API. */
        fun executeProbe(operation: String, payload: String, deadlineMillis: Long): String {
            check(isAlive) { "Sandbox process is not alive" }
            require(deadlineMillis in 1..MAX_PROBE_DEADLINE_MILLIS)
            if (!callPermit.tryAcquire()) throw RejectedExecutionException("Sandbox client already has an active call")
            try {
                val requestId = java.util.UUID.randomUUID().toString()
                val future = calls.submit<String> {
                    probe.executeProbe(sessionId, nonce, requestId, operation, payload, deadlineMillis)
                }
                try {
                    return future.get(deadlineMillis + HOST_TIMEOUT_GRACE_MILLIS, TimeUnit.MILLISECONDS)
                } catch (timeout: TimeoutException) {
                    future.cancel(true)
                    runCatching { probe.cancelProbe(sessionId, nonce, requestId) }
                    requestSelfTermination()
                    awaitDeath(deadlineMillis.coerceAtMost(MAX_DEATH_WAIT_MILLIS))
                    throw TimeoutException("Sandbox probe exceeded ${deadlineMillis}ms; processAlive=$isAlive")
                } catch (failure: ExecutionException) {
                    if (!isAlive) dead.countDown()
                    throw (failure.cause ?: failure)
                }
            } finally {
                callPermit.release()
            }
        }

        /** S2.1 direct Binder probes; do not expose these fixed operations as a plugin API. */
        fun activeProbeCountForTest(): Int = probe.activeProbeCount
        fun executeRawForTest(requestId: String, operation: String, payload: String, deadlineMillis: Long): String =
            probe.executeProbe(sessionId, nonce, requestId, operation, payload, deadlineMillis)
        fun cancelRawForTest(requestId: String) = probe.cancelProbe(sessionId, nonce, requestId)

        /** Bounded raw AIDL-frame mutations for instrumentation only, never a plugin API. */
        fun malformedBinderFrameRejectedForTest(kind: String, mutationSeed: Int = 0): Boolean {
            // executeProbe is the fifth method in ISandboxProbe.aidl. This corpus is
            // deliberately fixed and bounded; it is not a general-purpose parcel fuzzer.
            val code = if (kind == "wrong-transaction") {
                IBinder.FIRST_CALL_TRANSACTION + 42
            } else {
                IBinder.FIRST_CALL_TRANSACTION + 4
            }
            val data = Parcel.obtain()
            val reply = Parcel.obtain()
            try {
                if (kind == "wrong-token") {
                    data.writeInterfaceToken("not.cordiact.ISandboxProbe")
                } else {
                    data.writeInterfaceToken("com.cordiact.app.sandbox.ISandboxProbe")
                    data.writeString(if (kind == "bad-session") "other-session" else sessionId)
                    if (kind != "truncated") {
                        data.writeString(if (kind == "bad-nonce") "not-a-nonce" else nonce)
                        data.writeString(if (kind == "bad-request-id") "!" else "raw-frame")
                        data.writeString(if (kind == "bad-operation") "not-supported" else "echo")
                        data.writeString(if (kind == "large-input") "x".repeat(4_100 + (mutationSeed and 511)) else "ok")
                        data.writeLong(
                            when (kind) {
                                "negative-deadline" -> -1L
                                "over-deadline" -> 60_000L
                                else -> 2_000L
                            },
                        )
                        when (kind) {
                            "trailing-data" -> {
                                repeat(1 + (mutationSeed and 7)) { index ->
                                    data.writeInt(mutationSeed xor index)
                                    data.writeByteArray(byteArrayOf(
                                        (mutationSeed ushr 24).toByte(), (mutationSeed ushr 16).toByte(),
                                        (mutationSeed ushr 8).toByte(), (mutationSeed + index).toByte(),
                                    ))
                                }
                            }
                            "large-input" -> data.writeString("x".repeat(4_100 + (mutationSeed and 511)))
                            "oversized-frame" -> data.writeString("x".repeat(7_000))
                        }
                    }
                }
                require(kind in setOf(
                    "valid-control", "wrong-transaction", "wrong-token", "truncated", "large-input", "oversized-frame",
                    "trailing-data", "bad-session", "bad-nonce", "bad-request-id", "bad-operation",
                    "negative-deadline", "over-deadline",
                ))
                var accepted = false
                try {
                    if (binder.transact(code, data, reply, 0)) {
                        reply.readException()
                        accepted = true
                    }
                } catch (_: Exception) {
                    // Rejection is expected. The test also checks that the worker survives.
                }
                return !accepted
            } finally {
                reply.recycle()
                data.recycle()
            }
        }

        /** S2 test hook for verifying an unguessable session nonce is enforced by the service. */
        fun executeWithNonceForTest(testNonce: String, operation: String, payload: String): String =
            probe.executeProbe(sessionId, testNonce, java.util.UUID.randomUUID().toString(), operation, payload, 1_000)

        override fun terminate(gracePeriodMillis: Long): SandboxTermination {
            if (isAlive) requestSelfTermination()
            if (!awaitDeath(gracePeriodMillis.coerceAtMost(MAX_DEATH_WAIT_MILLIS))) {
                unbind()
            }
            calls.shutdownNow()
            return SandboxTermination(processStopped = !isAlive)
        }

        override fun kill() {
            if (isAlive) requestSelfTermination()
            awaitDeath(FORCE_KILL_WAIT_MILLIS)
            unbind()
            calls.shutdownNow()
        }

        private fun requestSelfTermination() {
            if (!isAlive) return
            runCatching { probe.closeSession(sessionId, nonce) }
            // If the probe does not comply, dropping the last isolated-service binding asks Android
            // to tear down this bound-only service. No hidden ActivityManager kill API is used.
            if (!awaitDeath(SELF_KILL_WAIT_MILLIS)) unbind()
        }

        private fun awaitDeath(timeoutMillis: Long): Boolean =
            !isAlive || dead.await(timeoutMillis.coerceAtLeast(1), TimeUnit.MILLISECONDS) || !isAlive

        companion object {
            const val HOST_TIMEOUT_GRACE_MILLIS = 1_000L
            const val MAX_PROBE_DEADLINE_MILLIS = 30_000L
            const val MAX_DEATH_WAIT_MILLIS = 5_000L
            const val FORCE_KILL_WAIT_MILLIS = 2_000L
            const val SELF_KILL_WAIT_MILLIS = 500L
        }
    }

    private companion object {
        const val STARTUP_TIMEOUT_MILLIS = 10_000L
        val SESSION_PATTERN = Regex("[A-Za-z0-9._:-]{1,128}")
        val SHA256_PATTERN = Regex("[a-f0-9]{64}")
        fun randomNonce(): String = ByteArray(32).also(SecureRandom()::nextBytes)
            .joinToString("") { "%02x".format(it) }
    }
}
