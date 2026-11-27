package com.cordiact.app.sandbox

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Build
import android.os.IBinder
import android.os.Looper
import android.os.RemoteException
import android.os.SystemClock
import androidx.annotation.RequiresApi
import com.cordiact.core.plugin.PACKAGE_SCRIPT_MAX_SOURCE_BYTES
import com.cordiact.core.plugin.PACKAGE_SCRIPT_MAX_TEXT_BYTES
import com.cordiact.core.plugin.PackageScriptRuntime
import com.cordiact.core.plugin.PackageScriptSession
import com.cordiact.core.plugin.PluginInstanceId
import com.cordiact.core.sandbox.sha256Hex
import com.cordiact.core.tool.ToolManagerException
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.FutureTask
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean

/** A named Android worker is leased to a single call and retired at unbind. */
internal class AndroidPackageScriptRuntime(context: Context) : PackageScriptRuntime {
    private val app = context.applicationContext
    private val calls = ThreadPoolExecutor(0, 2, 10, TimeUnit.SECONDS, SynchronousQueue()) { work ->
        Thread(work, "cordiact-package-js").apply { isDaemon = true }
    }

    override fun open(owner: PluginInstanceId): PackageScriptSession {
        check(Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) { "Script tools require Android 10 or later" }
        require(owner.workspaceId != null && owner.sessionId == null)
        return Session()
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private inner class Session : PackageScriptSession {
        private val gate = Any()
        private var active: Call? = null
        private var closed = false

        override fun evaluate(source: ByteArray, input: ByteArray): ByteArray {
            check(Looper.myLooper() != Looper.getMainLooper()) { "Script tools must run off the main thread" }
            require(source.size in 1..PACKAGE_SCRIPT_MAX_SOURCE_BYTES && input.size <= PACKAGE_SCRIPT_MAX_TEXT_BYTES)
            val call = synchronized(gate) {
                check(!closed && active == null) { "Script package is unavailable or already running a call" }
                Call(source.copyOf(), input.copyOf()).also { active = it }
            }
            try {
                calls.execute(call.task)
                val output = call.task.get(HOST_DEADLINE_MILLIS, TimeUnit.MILLISECONDS)
                synchronized(gate) { check(!closed && active === call) { "Script package was withdrawn" } }
                return output
            } catch (_: TimeoutException) {
                throw timeout()
            } catch (failure: ExecutionException) {
                if (failure.cause is TimeoutException) throw timeout()
                throw IllegalStateException("Script evaluation failed")
            } catch (_: RejectedExecutionException) {
                throw IllegalStateException("Script workers are busy")
            } catch (interrupted: InterruptedException) {
                Thread.currentThread().interrupt()
                throw interrupted
            } finally {
                try { call.close() } finally { synchronized(gate) { if (active === call) active = null } }
            }
        }

        override fun close() {
            val call = synchronized(gate) {
                closed = true
                active
            }
            call?.close()
        }
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private inner class Call(source: ByteArray, input: ByteArray) : AutoCloseable {
        private val binding = Binding()
        val task = FutureTask {
            val worker = binding.connect()
            check(worker.workerUid != app.applicationInfo.uid) { "Script worker must have a distinct Android UID" }
            val deadline = SystemClock.elapsedRealtimeNanos() + TimeUnit.MILLISECONDS.toNanos(PackageScriptWorkerService.EXECUTION_MILLIS)
            val output = try {
                worker.evaluate(PackageScriptWorkerService.PROTOCOL_VERSION, sha256Hex(source), source, input, deadline)
            } catch (failure: RemoteException) {
                if (SystemClock.elapsedRealtimeNanos() >= deadline) throw TimeoutException()
                throw failure
            }
            if (SystemClock.elapsedRealtimeNanos() >= deadline) throw TimeoutException()
            checkNotNull(output).also { check(it.size <= PACKAGE_SCRIPT_MAX_TEXT_BYTES) }
        }

        override fun close() {
            task.cancel(true)
            binding.close()
        }
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private inner class Binding : ServiceConnection, AutoCloseable {
        private val connected = CompletableFuture<IBinder>()
        private val closed = AtomicBoolean()
        private val bound = AtomicBoolean()

        fun connect(): IPackageScriptWorker {
            check(!closed.get())
            val name = "js_${UUID.randomUUID().toString().replace("-", "")}"
            val intent = Intent(app, PackageScriptWorkerService::class.java)
            val accepted = app.bindIsolatedService(intent, Context.BIND_AUTO_CREATE, name, app.mainExecutor, this)
            bound.set(true)
            if (closed.get() || !accepted) unbind()
            check(accepted) { "Android rejected the isolated script worker" }
            return checkNotNull(IPackageScriptWorker.Stub.asInterface(connected.get(STARTUP_MILLIS, TimeUnit.MILLISECONDS)))
        }

        override fun onServiceConnected(name: ComponentName, service: IBinder) { connected.complete(service) }
        override fun onServiceDisconnected(name: ComponentName) = disconnected()
        override fun onBindingDied(name: ComponentName) = disconnected()
        override fun onNullBinding(name: ComponentName) = disconnected()

        private fun disconnected() { connected.completeExceptionally(IllegalStateException("Script worker disconnected")) }

        override fun close() {
            closed.set(true)
            connected.cancel(false)
            unbind()
        }

        private fun unbind() {
            if (!bound.compareAndSet(true, false)) return
            try { app.unbindService(this) } catch (_: IllegalArgumentException) {
                // A binding rejected by Android may already be absent.
            }
        }
    }

    private companion object {
        const val STARTUP_MILLIS = 4_000L
        const val HOST_DEADLINE_MILLIS = STARTUP_MILLIS + PackageScriptWorkerService.EXECUTION_MILLIS + 1_000L

        fun timeout() = ToolManagerException(ToolManagerException.Code.TOOL_TIMEOUT, "Script worker deadline exceeded")
    }
}
