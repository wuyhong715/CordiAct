package com.cordiact.core.execution

/**
 * Parameters for executing a command via Termux.
 */
data class TermuxCommandParams(
    val command: String,
    val arguments: List<String> = emptyList(),
    val workDir: String? = null,
    val background: Boolean = false,
    val sessionAction: String? = null,
    val timeoutMs: Long = 30_000,
)

/**
 * Inspection status of the Termux environment on Android.
 */
data class TermuxStatus(
    val isInstalled: Boolean,
    val hasPermission: Boolean,
    val allowsExternalApps: Boolean = true,
    val message: String = "OK",
) {
    val isAvailable: Boolean get() = isInstalled && hasPermission && allowsExternalApps
}

/**
 * Action type for interactive Termux sessions (tmux powered).
 */
enum class TermuxSessionAction {
    START,
    SEND,
    READ,
    KILL,
    LIST,
}

/**
 * Parameters for interacting with a persistent Termux tmux session.
 */
data class TermuxSessionParams(
    val sessionName: String,
    val action: TermuxSessionAction,
    val input: String? = null,
    val waitForPattern: String? = null,
    val timeoutMs: Long = 10_000,
    val maxLines: Int = 100,
)

/**
 * Result of a session operation.
 */
data class TermuxSessionResult(
    val sessionName: String,
    val active: Boolean,
    val screenOutput: String = "",
    val matched: Boolean = false,
    val error: String? = null,
)

/**
 * Service contract for Termux execution.
 */
interface TermuxExecutionService {
    fun inspectStatus(): TermuxStatus
    fun execute(params: TermuxCommandParams): ExecutionResult
    fun interactSession(params: TermuxSessionParams): TermuxSessionResult
}
