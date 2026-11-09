package com.cordiact.app.privilege

import com.cordiact.core.automation.PrivilegeLevel
import com.cordiact.core.automation.PrivilegeStatus
import com.cordiact.core.automation.ShellCommandPolicy
import com.cordiact.core.automation.ShellLimits
import com.cordiact.core.automation.ShellResult

/**
 * Tool-facing façade for privileged execution. Holds the two user switches
 * that are deliberately separate from the plugin switch: "advanced commands"
 * and "root".
 *
 * Security invariants enforced here, at the last point before the backend:
 *  - shell/priv_exec use the SHELL channel only, never an implicit root channel;
 *  - root execution requires rootEnabled == true and an actually ROOT-level backend;
 *  - advanced commands require advancedCommandsEnabled even when the backend is ROOT;
 *  - every refusal happens before any backend call (zero side effects).
 */
class PrivilegeService(
    private val router: PrivilegeRouter,
    private val settings: PrivilegeSettings = PrivilegeSettings(),
) {
    data class PrivilegeSettings(
        val advancedCommandsEnabled: Boolean = false,
        val rootEnabled: Boolean = false,
    )

    fun status(): PrivilegeStatus {
        if (settings.rootEnabled) {
            val rootStatus = router.statusFor(PrivilegeLevel.ROOT)
            if (rootStatus.level == PrivilegeLevel.ROOT) return rootStatus
        }
        return router.status()
    }

    fun exec(
        command: String,
        timeoutMs: Long,
        advanced: Boolean,
        requireRoot: Boolean = false,
    ): ShellResult {
        if (advanced && !settings.advancedCommandsEnabled) {
            return refused("advanced commands are disabled in settings")
        }
        val level = if (requireRoot) PrivilegeLevel.ROOT else PrivilegeLevel.SHELL
        if (level == PrivilegeLevel.ROOT && !settings.rootEnabled) {
            return refused("root is disabled in settings")
        }
        val status = router.statusFor(level)
        if (!status.ready) {
            return refused(status.reason ?: "privileged channel unavailable")
        }
        if (level == PrivilegeLevel.ROOT && status.level != PrivilegeLevel.ROOT) {
            return refused("root channel is not available at uid 0")
        }
        // A shell request must never silently run as root just because the
        // backing service itself happens to be root-started.
        if (level == PrivilegeLevel.SHELL && status.level == PrivilegeLevel.ROOT) {
            return refused("shell channel is root; explicit priv_root is required")
        }
        if (!ShellCommandPolicy.isAllowed(command, status.level, settings.advancedCommandsEnabled)) {
            return refused("command refused by policy")
        }
        return router.execAs(level, command, timeoutMs.coerceIn(1_000L, ShellLimits.MAX_TIMEOUT_MS))
    }

    fun hasRoot(): Boolean = settings.rootEnabled &&
        router.statusFor(PrivilegeLevel.ROOT).level == PrivilegeLevel.ROOT

    private fun refused(message: String): ShellResult = ShellResult(-1, "", message)
}
