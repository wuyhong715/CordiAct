package com.cordiact.core.context

import com.cordiact.core.conversation.ModelMessage

/**
 * Default implementation of [ToolOutputGate].
 *
 * Enforces head/tail retention so error diagnostics at the end of tool outputs
 * are preserved alongside initial header/setup content. Oversized results are
 * optionally spilled to [SpillStore] for pagination via `tool_output_read`.
 */
class DefaultToolOutputGate(
    private val spillStore: SpillStore? = null,
    private val headRatio: Double = 0.6,
    private val headChars: Int? = null,
    private val tailChars: Int? = null,
) : ToolOutputGate {

    override fun gate(sessionId: String, resultMessage: ModelMessage, limitChars: Int): ModelMessage {
        val content = resultMessage.content
        if (content.length <= limitChars) {
            return resultMessage
        }

        var headLen = (headChars ?: (limitChars * headRatio).toInt()).coerceIn(0, limitChars)
        var tailLen = (tailChars ?: (limitChars - headLen)).coerceIn(0, limitChars - headLen)
        if (headLen > 0 && content[headLen].isLowSurrogate() && content[headLen - 1].isHighSurrogate()) headLen--
        val tailStart = content.length - tailLen
        if (tailLen > 0 && content[tailStart].isLowSurrogate() && content[tailStart - 1].isHighSurrogate()) tailLen--
        val omitted = content.length - headLen - tailLen

        val head = if (headLen > 0) content.substring(0, headLen) else ""
        val tail = if (tailLen > 0) content.substring(content.length - tailLen) else ""

        val marker = if (spillStore != null) {
            val toolName = resultMessage.toolName ?: "tool"
            val spill = spillStore.appendSpill(sessionId, toolName, null, content)
            val uri = spill.uri
            val stored = if (spill.truncated) "capped output" else "full output"
            "\n...[truncated $omitted chars; $stored saved at $uri, read in pages with tool_output_read(file=\"$uri\", offset=0, limit_chars=8000)]...\n"
        } else {
            "\n...[truncated $omitted chars]...\n"
        }

        val truncatedContent = head + marker + tail
        return resultMessage.copy(content = truncatedContent)
    }
}
