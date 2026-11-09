package com.cordiact.app.privilege

import com.cordiact.core.automation.PrivilegeLevel
import com.cordiact.core.automation.PrivilegeStatus
import com.cordiact.core.automation.PrivilegedChannel
import com.cordiact.core.automation.ShellResult

/**
 * Routes an explicit privilege level to the matching backend.
 *
 * The router never picks the strongest channel implicitly: [status] and
 * [exec] are the ordinary shell channel, and root is only reachable through
 * [statusFor] / [execAs] with [PrivilegeLevel.ROOT]. Callers (PrivilegeService)
 * remain responsible for checking the user's root switch before asking for
 * ROOT; this class only prevents silent root-first selection.
 */
class PrivilegeRouter(
    private val shell: PrivilegedChannel,
    private val root: PrivilegedChannel? = null,
) : PrivilegedChannel {

    override fun status(): PrivilegeStatus = shell.status()

    /** Ordinary shell execution. Never upgraded to root. */
    override fun exec(command: String, timeoutMs: Long): ShellResult = shell.exec(command, timeoutMs)

    fun statusFor(level: PrivilegeLevel): PrivilegeStatus = when (level) {
        PrivilegeLevel.ROOT -> root?.status() ?: PrivilegeStatus(
            level = PrivilegeLevel.NONE,
            reason = "root channel is not configured",
        )
        PrivilegeLevel.SHELL -> shell.status()
        PrivilegeLevel.NONE -> PrivilegeStatus(PrivilegeLevel.NONE, reason = "no privileged channel requested")
    }

    fun execAs(level: PrivilegeLevel, command: String, timeoutMs: Long): ShellResult = when (level) {
        PrivilegeLevel.ROOT -> root?.exec(command, timeoutMs)
            ?: ShellResult(-1, "", "root channel is not configured")
        PrivilegeLevel.SHELL -> shell.exec(command, timeoutMs)
        PrivilegeLevel.NONE -> ShellResult(-1, "", "no privileged channel requested")
    }
}
