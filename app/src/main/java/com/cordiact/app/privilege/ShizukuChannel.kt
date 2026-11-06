package com.cordiact.app.privilege

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.IBinder
import android.os.SystemClock
import com.cordiact.core.automation.PrivilegeLevel
import com.cordiact.core.automation.PrivilegeStatus
import com.cordiact.core.automation.PrivilegedChannel
import com.cordiact.core.automation.ShellResult
import rikka.shizuku.Shizuku
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Shell channel backed by Shizuku's `bindUserService`.
 *
 * `Shizuku.newProcess` is deprecated and removed in recent builds, so the
 * service is bound as a user service instead. Status is computed binder-first:
 * a live binder wins even when the package is invisible (Sui, or Android 11
 * package visibility).
 */
class ShizukuChannel(private val context: Context) : PrivilegedChannel {

    private var service: IPrivilegedService? = null
    private var boundAtMillis: Long = 0

    override fun status(): PrivilegeStatus {
        val binderAlive = runCatching { Shizuku.pingBinder() }.getOrDefault(false)
        val installed = runCatching {
            context.packageManager.getPackageInfo(SHIZUKU_PACKAGE, 0)
            true
        }.getOrDefault(false)
        val granted = runCatching {
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        }.getOrDefault(false)

        return when {
            !binderAlive && !installed -> PrivilegeStatus(
                level = PrivilegeLevel.NONE,
                reason = "shizuku_not_installed",
                recovery = "Install Shizuku, start its service, then grant CordiAct permission.",
                backend = "shizuku",
            )
            !binderAlive -> PrivilegeStatus(
                level = PrivilegeLevel.NONE,
                reason = "shizuku_not_running",
                recovery = "Start Shizuku (or re-pair wireless debugging on Android 11+), then retry.",
                backend = "shizuku",
            )
            !granted -> PrivilegeStatus(
                level = PrivilegeLevel.NONE,
                reason = "shizuku_permission_denied",
                recovery = "Open Shizuku and grant CordiAct permission.",
                backend = "shizuku",
            )
            else -> {
                val uid = runCatching { Shizuku.getUid() }.getOrNull()
                PrivilegeStatus(
                    level = if (uid == 0) PrivilegeLevel.ROOT else PrivilegeLevel.SHELL,
                    backend = if (uid == 0) "sui" else "shizuku",
                )
            }
        }
    }

    override fun exec(command: String, timeoutMs: Long): ShellResult {
        val current = ensureService()
            ?: return ShellResult(-1, "", "privileged channel is not available", timedOut = false)

        return try {
            val bundle: Bundle = current.exec(command, timeoutMs)
            ShellResult(
                exitCode = bundle.getInt("exitCode", -1),
                stdout = bundle.getString("stdout").orEmpty(),
                stderr = bundle.getString("stderr").orEmpty(),
                timedOut = bundle.getBoolean("timedOut", false),
                truncated = bundle.getBoolean("truncated", false),
            )
        } catch (failure: Exception) {
            service = null
            ShellResult(-1, "", failure.message ?: "binder call failed")
        }
    }

    /** Binder to the shell user service, or null when the channel is not ready. */
    fun binder(): IPrivilegedService? {
        val cached = service
        if (cached != null && cached.asBinder().isBinderAlive) return cached
        // The user-service process can die (app update, low memory); never keep
        // handing out a dead proxy or every call degrades to requires_privilege.
        service = null
        return ensureService()
    }

    fun requestPermission(onResult: (Boolean) -> Unit) {
        runCatching { Shizuku.requestPermission(REQUEST_CODE) }
            .onFailure { onResult(false) }
    }

    private fun ensureService(): IPrivilegedService? {
        service?.let { return it }
        if (!runCatching { Shizuku.pingBinder() }.getOrDefault(false)) return null
        if (SystemClock.elapsedRealtime() - boundAtMillis < BIND_RETRY_MS) return null
        boundAtMillis = SystemClock.elapsedRealtime()

        val latch = CountDownLatch(1)
        var bound: IPrivilegedService? = null

        val connection = object : android.content.ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                bound = IPrivilegedService.Stub.asInterface(binder)
                latch.countDown()
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                service = null
                latch.countDown()
            }
        }

        return try {
            val args = Shizuku.UserServiceArgs(
                ComponentName(context.packageName, PrivilegedUserService::class.java.name),
            )
                .daemon(false)
                .processNameSuffix("priv")
                .debuggable(false)
                .version(VERSION)

            Shizuku.bindUserService(args, connection)
            latch.await(BIND_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            bound.also { service = it }
        } catch (failure: Exception) {
            null
        }
    }

    private companion object {
        const val SHIZUKU_PACKAGE = "moe.shizuku.privileged.api"
        const val REQUEST_CODE = 4213
        const val VERSION = 1
        const val BIND_TIMEOUT_MS = 8_000L
        const val BIND_RETRY_MS = 2_000L
    }
}
