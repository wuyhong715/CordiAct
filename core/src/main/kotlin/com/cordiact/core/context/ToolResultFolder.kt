package com.cordiact.core.context

import com.cordiact.core.conversation.MessageRole
import com.cordiact.core.conversation.ModelMessage

/**
 * Folds tool results that the model has already finished with into one-line stubs.
 *
 * A turn with twenty-four tool calls carries every result in full until it ends, and most of that
 * text stopped mattering the moment the next result arrived. Folding keeps the *shape* of what
 * happened (which tool, how much, where the full text is) while dropping the bulk.
 *
 * Two invariants:
 *  - the `toolCallId`/`toolName` survive, or the provider rejects a tool result with no call;
 *  - the most recent results stay verbatim, because the model is still reasoning over them.
 */
object ToolResultFolder {

    fun fold(
        messages: List<ModelMessage>,
        keepRecentToolResults: Int,
        maxStubChars: Int = 240,
    ): List<ModelMessage> {
        if (keepRecentToolResults < 0) return messages
        val toolIndices = messages.indices.filter { messages[it].role == MessageRole.TOOL }
        if (toolIndices.size <= keepRecentToolResults) return messages

        val foldable = toolIndices.dropLast(keepRecentToolResults).toSet()
        if (foldable.isEmpty()) return messages

        return messages.mapIndexed { index, message ->
            if (index in foldable && message.content.length > maxStubChars) {
                message.copy(content = stubFor(message))
            } else {
                message
            }
        }
    }

    private fun stubFor(message: ModelMessage): String {
        val name = message.toolName ?: "tool"
        val spill = SPILL_PATTERN.find(message.content)?.value
        return if (spill != null) {
            "[$name ok · ${message.content.length} chars folded · full text: $spill · " +
                "call tool_output_read with that URI if it matters again]"
        } else {
            "[$name ok · ${message.content.length} chars folded · this result was already used " +
                "and is no longer in context]"
        }
    }

    private val SPILL_PATTERN = Regex("spill://[^\\s,\\]\"']+")
}
