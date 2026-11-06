package com.cordiact.app.privilege

import com.cordiact.core.automation.PrivilegeLevel
import com.cordiact.core.automation.PrivilegeStatus
import com.cordiact.core.automation.PrivilegedChannel
import com.cordiact.core.automation.ShellResult

/**
 * Picks the strongest available backend: root first, then Shizuku shell.
 * Both are probed lazily and cached for the lifetime of the process; a binder
 * death clears the cache on the next call.
 */
class PrivilegeRouter(
    private val shizuku: ShizukuChannel,
    private val root: RootChannel,
) : PrivilegedChannel {

    @Volatile
    private var chosen: PrivilegedChannel? = null

    override fun status(): PrivilegeStatus {
        val rootStatus = root.status()
        if (rootStatus.level == PrivilegeLevel.ROOT) {
            chosen = root
            return rootStatus
        }
        val shellStatus = shizuku.status()
        if (shellStatus.ready) {
            chosen = shizuku
            return shellStatus
        }
        chosen = null
        return shellStatus.copy(recovery = shellStatus.recovery ?: rootStatus.recovery)
    }

    override fun exec(command: String, timeoutMs: Long): ShellResult {
        val channel = chosen ?: run {
            val status = status()
            if (!status.ready) return ShellResult(-1, "", status.reason ?: "no privileged channel")
            chosen
        } ?: return ShellResult(-1, "", "no privileged channel")
        return channel.exec(command, timeoutMs)
    }
}
