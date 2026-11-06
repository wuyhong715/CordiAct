package com.cordiact.app.privilege

import com.cordiact.core.automation.PrivilegeLevel
import com.cordiact.core.automation.PrivilegeStatus
import com.cordiact.core.automation.PrivilegedChannel
import com.cordiact.core.automation.ShellCommandPolicy
import com.cordiact.core.automation.ShellLimits
import com.cordiact.core.automation.ShellResult

/**
 * Tool-facing façade for the privileged channel. Holds the two user switches
 * that are deliberately separate from the plugin switch: "advanced commands"
 * and "root".
 */
class PrivilegeService(
    private val channel: PrivilegedChannel,
    private val settings: PrivilegeSettings = PrivilegeSettings(),
) {
    data class PrivilegeSettings(
        val advancedCommandsEnabled: Boolean = false,
        val rootEnabled: Boolean = false,
    )

    fun status(): PrivilegeStatus = channel.status()

    fun exec(command: String, timeoutMs: Long, advanced: Boolean): ShellResult {
        val status = channel.status()
        val level = status.level
        if (level == PrivilegeLevel.NONE) {
            return ShellResult(-1, "", status.reason ?: "privileged channel unavailable")
        }
        if (advanced && level != PrivilegeLevel.ROOT && !settings.advancedCommandsEnabled) {
            return ShellResult(-1, "", "advanced commands are disabled in settings")
        }
        if (!ShellCommandPolicy.isAllowed(command, level, settings.advancedCommandsEnabled)) {
            return ShellResult(-1, "", "command refused by policy")
        }
        return channel.exec(command, timeoutMs.coerceIn(1_000L, ShellLimits.MAX_TIMEOUT_MS))
    }

    fun hasRoot(): Boolean = channel.status().level == PrivilegeLevel.ROOT && settings.rootEnabled
}
