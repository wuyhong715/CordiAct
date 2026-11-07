package com.cordiact.core.execution

/**
 * Standard execution result across different runtime backends (SSH, Termux, Local Shell).
 */
data class ExecutionResult(
    val exitCode: Int,
    val stdout: String,
    val stderr: String = "",
    val timedOut: Boolean = false,
    val truncated: Boolean = false,
) {
    val isSuccess: Boolean get() = !timedOut && exitCode == 0

    companion object {
        const val MAX_OUTPUT_CHARS = 20_000

        fun failure(message: String, exitCode: Int = -1): ExecutionResult = ExecutionResult(
            exitCode = exitCode,
            stdout = "",
            stderr = message,
            timedOut = false,
            truncated = false,
        )

        fun timedOut(message: String = "Command timed out"): ExecutionResult = ExecutionResult(
            exitCode = -1,
            stdout = "",
            stderr = message,
            timedOut = true,
            truncated = false,
        )

        fun truncateOutput(text: String, limit: Int = MAX_OUTPUT_CHARS): Pair<String, Boolean> {
            return if (text.length > limit) {
                text.substring(0, limit) + "\n...[truncated ${text.length - limit} chars]" to true
            } else {
                text to false
            }
        }
    }
}

/**
 * Identification of backend kinds.
 */
enum class BackendKind {
    SSH,
    TERMUX,
    LOCAL_SHELL,
}
