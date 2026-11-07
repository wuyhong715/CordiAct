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
) : ToolOutputGate {

    override fun gate(sessionId: String, resultMessage: ModelMessage, limitChars: Int): ModelMessage {
        val content = resultMessage.content
        if (content.length <= limitChars) {
            return resultMessage
        }

        val headLen = ((limitChars * headRatio).toInt()).coerceIn(0, limitChars)
        val tailLen = (limitChars - headLen).coerceAtLeast(0)
        val omitted = content.length - headLen - tailLen

        val head = if (headLen > 0) content.substring(0, headLen) else ""
        val tail = if (tailLen > 0) content.substring(content.length - tailLen) else ""

        val marker = if (spillStore != null) {
            val toolName = resultMessage.toolName ?: "tool"
            val uri = spillStore.saveSpill(sessionId, toolName, content)
            "\n...[truncated $omitted chars; full output saved at $uri, read in pages with tool_output_read(file=\"$uri\", offset=0, limit_chars=8000)]...\n"
        } else {
            "\n...[truncated $omitted chars]...\n"
        }

        val truncatedContent = head + marker + tail
        return resultMessage.copy(content = truncatedContent)
    }
}
