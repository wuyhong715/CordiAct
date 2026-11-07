package com.cordiact.app.termux

import com.cordiact.core.execution.TermuxCommandParams
import com.cordiact.core.execution.TermuxSessionAction
import com.cordiact.core.execution.TermuxSessionParams
import com.cordiact.core.execution.TermuxSessionResult
import java.util.regex.Pattern

/**
 * Manages stateful, interactive terminal sessions inside Termux using tmux.
 */
class TermuxSessionManager(
    private val bridge: TermuxBridge,
) {
    fun executeSessionAction(params: TermuxSessionParams): TermuxSessionResult {
        return when (params.action) {
            TermuxSessionAction.START -> startSession(params.sessionName)
            TermuxSessionAction.SEND -> sendInput(params)
            TermuxSessionAction.READ -> readScreen(params)
            TermuxSessionAction.KILL -> killSession(params.sessionName)
            TermuxSessionAction.LIST -> listSessions()
        }
    }

    private fun startSession(name: String): TermuxSessionResult {
        val check = bridge.executeCommand(
            TermuxCommandParams(
                command = "tmux has-session -t ${escapeArg(name)} 2>/dev/null",
                timeoutMs = 5000L,
            )
        )
        if (check.exitCode == 0) {
            return TermuxSessionResult(
                sessionName = name,
                active = true,
                screenOutput = "Session already exists",
            )
        }

        val create = bridge.executeCommand(
            TermuxCommandParams(
                command = "tmux new-session -d -s ${escapeArg(name)}",
                timeoutMs = 10000L,
            )
        )
        return if (create.exitCode == 0) {
            TermuxSessionResult(
                sessionName = name,
                active = true,
                screenOutput = "Session started",
            )
        } else {
            TermuxSessionResult(
                sessionName = name,
                active = false,
                error = "Failed to start tmux session: ${create.stderr.ifBlank { create.stdout }}",
            )
        }
    }

    private fun sendInput(params: TermuxSessionParams): TermuxSessionResult {
        val input = params.input.orEmpty()
        val cmd = "tmux send-keys -t ${escapeArg(params.sessionName)} -l ${escapeArg(input)} && " +
                "tmux send-keys -t ${escapeArg(params.sessionName)} C-m"
        val sendRes = bridge.executeCommand(
            TermuxCommandParams(
                command = cmd,
                timeoutMs = 5000L,
            )
        )
        if (sendRes.exitCode != 0) {
            return TermuxSessionResult(
                sessionName = params.sessionName,
                active = false,
                error = "Failed to send keys: ${sendRes.stderr}",
            )
        }

        // If waitForPattern is requested, poll until match or timeout
        if (!params.waitForPattern.isNullOrBlank()) {
            return pollForPattern(params)
        }

        // Settle detection: poll until output stabilizes (no change for settleMs)
        return pollForSettle(params)
    }

    private fun pollForSettle(params: TermuxSessionParams): TermuxSessionResult {
        val startTime = System.currentTimeMillis()
        var lastOutput = ""
        var stableSince = System.currentTimeMillis()
        val pollIntervalMs = 150L
        val settleTargetMs = params.settleMs.coerceIn(200L, 5000L)

        while (System.currentTimeMillis() - startTime < params.timeoutMs) {
            val readRes = readScreen(params)
            if (!readRes.active) return readRes
            val currentOutput = readRes.screenOutput

            if (currentOutput != lastOutput) {
                lastOutput = currentOutput
                stableSince = System.currentTimeMillis()
            } else if (currentOutput.isNotBlank() && System.currentTimeMillis() - stableSince >= settleTargetMs) {
                return TermuxSessionResult(
                    sessionName = params.sessionName,
                    active = true,
                    screenOutput = currentOutput,
                    matched = false,
                    reason = "SETTLED",
                )
            }
            Thread.sleep(pollIntervalMs)
        }

        return TermuxSessionResult(
            sessionName = params.sessionName,
            active = true,
            screenOutput = lastOutput,
            matched = false,
            reason = "TIMEOUT",
        )
    }

    private fun readScreen(params: TermuxSessionParams): TermuxSessionResult {
        val lines = params.maxLines.coerceIn(10, 500)
        val readCmd = "tmux capture-pane -p -t ${escapeArg(params.sessionName)} -S -$lines"
        val res = bridge.executeCommand(
            TermuxCommandParams(
                command = readCmd,
                timeoutMs = 5000L,
            )
        )
        return if (res.exitCode == 0) {
            TermuxSessionResult(
                sessionName = params.sessionName,
                active = true,
                screenOutput = res.stdout,
            )
        } else {
            TermuxSessionResult(
                sessionName = params.sessionName,
                active = false,
                error = "Failed to read session screen: ${res.stderr}",
            )
        }
    }

    private fun pollForPattern(params: TermuxSessionParams): TermuxSessionResult {
        val pattern = Pattern.compile(params.waitForPattern!!)
        val startTime = System.currentTimeMillis()
        var lastOutput = ""

        while (System.currentTimeMillis() - startTime < params.timeoutMs) {
            val readRes = readScreen(params)
            if (!readRes.active) return readRes
            lastOutput = readRes.screenOutput
            if (pattern.matcher(lastOutput).find()) {
                return TermuxSessionResult(
                    sessionName = params.sessionName,
                    active = true,
                    screenOutput = lastOutput,
                    matched = true,
                )
            }
            Thread.sleep(200)
        }

        return TermuxSessionResult(
            sessionName = params.sessionName,
            active = true,
            screenOutput = lastOutput,
            matched = false,
            error = "Pattern '${params.waitForPattern}' not matched within ${params.timeoutMs} ms",
        )
    }

    private fun killSession(name: String): TermuxSessionResult {
        val res = bridge.executeCommand(
            TermuxCommandParams(
                command = "tmux kill-session -t ${escapeArg(name)}",
                timeoutMs = 5000L,
            )
        )
        return TermuxSessionResult(
            sessionName = name,
            active = false,
            screenOutput = if (res.exitCode == 0) "Session terminated" else "Failed to terminate: ${res.stderr}",
        )
    }

    private fun listSessions(): TermuxSessionResult {
        val res = bridge.executeCommand(
            TermuxCommandParams(
                command = "tmux list-sessions",
                timeoutMs = 5000L,
            )
        )
        return TermuxSessionResult(
            sessionName = "",
            active = res.exitCode == 0,
            screenOutput = if (res.exitCode == 0) res.stdout else "No active sessions",
        )
    }

    private fun escapeArg(arg: String): String {
        return "'" + arg.replace("'", "'\\''") + "'"
    }
}
