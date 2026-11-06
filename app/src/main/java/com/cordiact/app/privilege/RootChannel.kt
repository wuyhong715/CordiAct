package com.cordiact.app.privilege

import com.cordiact.core.automation.PrivilegeLevel
import com.cordiact.core.automation.PrivilegeStatus
import com.cordiact.core.automation.PrivilegedChannel
import com.cordiact.core.automation.ShellLimits
import com.cordiact.core.automation.ShellResult
import java.util.concurrent.TimeUnit

/**
 * Root channel. Kept deliberately dumb: `su -c` with a hard timeout and bounded
 * output. A production build should move this to libsu's `RootService` daemon
 * to avoid one `su` spawn per call; the contract is the same either way.
 */
class RootChannel : PrivilegedChannel {

    @Volatile
    private var cached: PrivilegeStatus? = null

    override fun status(): PrivilegeStatus {
        cached?.let { return it }
        val probed = probe()
        cached = probed
        return probed
    }

    override fun exec(command: String, timeoutMs: Long): ShellResult {
        val budget = timeoutMs.coerceIn(1_000L, ShellLimits.MAX_TIMEOUT_MS)
        return try {
            val process = ProcessBuilder("su", "-c", command).start()
            val finished = process.waitFor(budget, TimeUnit.MILLISECONDS)
            if (!finished) process.destroyForcibly()
            val stdout = process.inputStream.bufferedReader().use { it.readText() }
                .take(ShellLimits.MAX_OUTPUT_CHARS)
            val stderr = process.errorStream.bufferedReader().use { it.readText() }
                .take(ShellLimits.MAX_OUTPUT_CHARS)
            ShellResult(
                exitCode = if (finished) process.exitValue() else -1,
                stdout = stdout,
                stderr = stderr,
                timedOut = !finished,
            )
        } catch (failure: Exception) {
            ShellResult(-1, "", failure.message ?: "su failed")
        }
    }

    private fun probe(): PrivilegeStatus {
        val result = runCatching {
            val process = ProcessBuilder("su", "-c", "id -u").start()
            val finished = process.waitFor(2, TimeUnit.SECONDS)
            val output = process.inputStream.bufferedReader().use { it.readText() }.trim()
            finished && output == "0"
        }.getOrDefault(false)

        return if (result) {
            PrivilegeStatus(level = PrivilegeLevel.ROOT, backend = "su")
        } else {
            PrivilegeStatus(
                level = PrivilegeLevel.NONE,
                reason = "root_not_available",
                recovery = "Root requires Magisk/KernelSU or Sui; otherwise use the Shizuku shell channel.",
                backend = "su",
            )
        }
    }
}
