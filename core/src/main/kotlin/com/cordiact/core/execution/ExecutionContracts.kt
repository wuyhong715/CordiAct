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

        /**
         * Truncate output by keeping both head and tail, omitting the middle.
         *
         * Command failures usually put stack traces or exit errors at the tail, while setup
         * details reside at the head. Preserving both allows the model to diagnose issues
         * accurately.
         */
        fun truncateOutput(
            text: String,
            limit: Int = MAX_OUTPUT_CHARS,
            headRatio: Double = 0.6,
        ): Pair<String, Boolean> {
            if (text.length <= limit) return text to false
            val headLen = ((limit * headRatio).toInt()).coerceIn(0, limit)
            val tailLen = (limit - headLen).coerceAtLeast(0)
            val omitted = text.length - headLen - tailLen
            val headPart = if (headLen > 0) text.substring(0, headLen) else ""
            val tailPart = if (tailLen > 0) text.substring(text.length - tailLen) else ""
            val separator = "\n...[truncated $omitted chars]...\n"
            return (headPart + separator + tailPart) to true
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
