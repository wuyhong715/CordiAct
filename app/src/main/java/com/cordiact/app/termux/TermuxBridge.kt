package com.cordiact.app.termux

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.cordiact.core.execution.ExecutionResult
import com.cordiact.core.execution.TermuxCommandParams
import com.cordiact.core.execution.TermuxStatus
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Handles communication with Termux via the official RUN_COMMAND intent protocol.
 */
open class TermuxBridge(
    private val context: Context,
    private val privilegedShellRunner: ((String) -> Pair<Int, String>)? = null,
) {
    @Volatile var readinessVerified: Boolean = false
        private set
    fun testReadiness(): ExecutionResult {
        val result = executeCommand(TermuxCommandParams("/data/data/com.termux/files/usr/bin/true", timeoutMs = 10_000))
        readinessVerified = result.isSuccess
        return result
    }

    companion object {
        const val TERMUX_PACKAGE_NAME = "com.termux"
        const val TERMUX_PERMISSION = "com.termux.permission.RUN_COMMAND"
        const val ACTION_RUN_COMMAND = "com.termux.RUN_COMMAND"
        const val RUN_COMMAND_SERVICE_NAME = "com.termux.app.RunCommandService"

        const val EXTRA_RUN_COMMAND_PATH = "com.termux.RUN_COMMAND_PATH"
        const val EXTRA_RUN_COMMAND_ARGUMENTS = "com.termux.RUN_COMMAND_ARGUMENTS"
        const val EXTRA_RUN_COMMAND_WORKDIR = "com.termux.RUN_COMMAND_WORKDIR"
        const val EXTRA_RUN_COMMAND_BACKGROUND = "com.termux.RUN_COMMAND_BACKGROUND"
        const val EXTRA_PENDING_INTENT = "com.termux.RUN_COMMAND_PENDING_INTENT"

        const val DEFAULT_TERMUX_PREFIX = "/data/data/com.termux/files/usr"
        const val DEFAULT_SHELL_PATH = "$DEFAULT_TERMUX_PREFIX/bin/sh"
        const val DEFAULT_WORKDIR = "/data/data/com.termux/files/home"
    }

    open fun inspectStatus(): TermuxStatus {
        val pm = context.packageManager
        val isInstalled = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.getPackageInfo(TERMUX_PACKAGE_NAME, PackageManager.PackageInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                pm.getPackageInfo(TERMUX_PACKAGE_NAME, 0)
            }
            true
        } catch (_: PackageManager.NameNotFoundException) {
            false
        }

        if (!isInstalled) {
            return TermuxStatus(
                isInstalled = false,
                hasPermission = false,
                allowsExternalApps = false,
                message = "Termux application is not installed on this device",
            )
        }

        var hasPermission = ContextCompat.checkSelfPermission(
            context,
            TERMUX_PERMISSION
        ) == PackageManager.PERMISSION_GRANTED

        // Attempt automatic permission self-healing via privileged shell runner if available
        if (!hasPermission && privilegedShellRunner != null) {
            try {
                val (exit, _) = privilegedShellRunner.invoke("pm grant ${context.packageName} $TERMUX_PERMISSION")
                if (exit == 0) {
                    hasPermission = ContextCompat.checkSelfPermission(
                        context,
                        TERMUX_PERMISSION
                    ) == PackageManager.PERMISSION_GRANTED
                }
            } catch (_: Exception) {}
        }

        val message = if (!hasPermission) {
            "Permission $TERMUX_PERMISSION is not granted. Please grant it in system settings or via Shizuku."
        } else {
            "Termux is installed and permitted. Note: ensure 'allow-external-apps = true' is set in ~/.termux/termux.properties."
        }

        return TermuxStatus(
            isInstalled = true,
            hasPermission = hasPermission,
            allowsExternalApps = true,
            message = message,
        )
    }

    open fun executeCommand(params: TermuxCommandParams): ExecutionResult {
        val status = inspectStatus()
        if (!status.isInstalled) {
            return ExecutionResult.failure("Termux is not installed")
        }
        if (!status.hasPermission) {
            return ExecutionResult.failure("Termux permission $TERMUX_PERMISSION not granted")
        }

        val callbackAction = "com.cordiact.app.termux.CALLBACK_${UUID.randomUUID()}"
        val latch = CountDownLatch(1)
        val resultRef = AtomicReference<ExecutionResult>()

        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, intent: Intent?) {
                if (intent == null) {
                    latch.countDown()
                    return
                }
                val stdout = intent.getStringExtra("stdout").orEmpty()
                val stderr = intent.getStringExtra("stderr").orEmpty()
                val exitCode = intent.getIntExtra("exitCode", 0)
                val err = intent.getIntExtra("err", 0)
                val errmsg = intent.getStringExtra("errmsg")

                var finalStderr = if (err != 0 && !errmsg.isNullOrBlank()) {
                    if (stderr.isNotBlank()) "$stderr\n[Termux internal error $err: $errmsg]" else "[Termux internal error $err: $errmsg]"
                } else {
                    stderr
                }

                // Friendly diagnosis for common Termux configuration obstacles
                if (finalStderr.contains("Execution error: 2") || finalStderr.contains("not allowed to run commands")) {
                    finalStderr += "\n[Diagnosis: Termux 'allow-external-apps' setting may be disabled. Run 'echo allow-external-apps = true >> ~/.termux/termux.properties' inside Termux]"
                }

                val (outText, outTrunc) = ExecutionResult.truncateOutput(stdout)
                val (errText, errTrunc) = ExecutionResult.truncateOutput(finalStderr)

                resultRef.set(
                    ExecutionResult(
                        exitCode = if (err != 0 && exitCode == 0) err else exitCode,
                        stdout = outText,
                        stderr = errText,
                        timedOut = false,
                        truncated = outTrunc || errTrunc,
                    )
                )
                latch.countDown()
            }
        }

        try {
            val filter = IntentFilter(callbackAction)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
            } else {
                context.registerReceiver(receiver, filter)
            }

            val callbackIntent = Intent(callbackAction).setPackage(context.packageName)
            val pendingIntentFlags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
            val pendingIntent = PendingIntent.getBroadcast(
                context,
                0,
                callbackIntent,
                pendingIntentFlags
            )

            val serviceIntent = Intent().apply {
                setClassName(TERMUX_PACKAGE_NAME, RUN_COMMAND_SERVICE_NAME)
                action = ACTION_RUN_COMMAND
                putExtra(EXTRA_RUN_COMMAND_PATH, DEFAULT_SHELL_PATH)
                val args = if (params.arguments.isNotEmpty()) {
                    params.arguments.toTypedArray()
                } else {
                    arrayOf("-c", params.command)
                }
                putExtra(EXTRA_RUN_COMMAND_ARGUMENTS, args)
                putExtra(EXTRA_RUN_COMMAND_WORKDIR, params.workDir ?: DEFAULT_WORKDIR)
                putExtra(EXTRA_RUN_COMMAND_BACKGROUND, params.background)
                putExtra(EXTRA_PENDING_INTENT, pendingIntent)
            }

            context.startService(serviceIntent)

            val completed = latch.await(params.timeoutMs.coerceAtLeast(1000L), TimeUnit.MILLISECONDS)
            return if (!completed) {
                ExecutionResult.timedOut("Termux command timed out after ${params.timeoutMs} ms")
            } else {
                resultRef.get() ?: ExecutionResult.failure("No response received from Termux")
            }
        } catch (e: Exception) {
            return ExecutionResult.failure("Failed to dispatch Termux command: ${e.message}")
        } finally {
            try {
                context.unregisterReceiver(receiver)
            } catch (_: Exception) {}
        }
    }
}
