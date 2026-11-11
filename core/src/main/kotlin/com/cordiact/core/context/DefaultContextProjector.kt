package com.cordiact.core.context

import com.cordiact.core.conversation.ConversationMessage
import com.cordiact.core.conversation.MessageRole
import com.cordiact.core.conversation.ModelMessage

/**
 * Default pure implementation of [ContextProjector].
 *
 * Maps a full sequence of [ConversationMessage] from storage into [ModelMessage] for prompt dispatch,
 * replacing messages covered by active [CompactionRecord]s with structured summary messages.
 */
class DefaultContextProjector : ContextProjector {

    override fun project(
        sessionId: String,
        history: List<ConversationMessage>,
        records: List<CompactionRecord>,
    ): List<ModelMessage> {
        if (history.isEmpty()) return emptyList()

        val activeRecords = records.filter { it.sessionId == sessionId && it.active }
            .sortedBy { it.createdAt }

        if (activeRecords.isEmpty()) {
            return history.map { it.toModelMessage() }
        }

        val result = mutableListOf<ModelMessage>()

        // Find the index spans for all active records
        val messageIndexMap = history.withIndex().associate { it.value.id to it.index }

        // We only support non-overlapping active compactions (the newest supersedes previous ones)
        val latestRecord = activeRecords.last()
        val startIndex = messageIndexMap[latestRecord.startMessageId]
        val endIndex = messageIndexMap[latestRecord.endMessageId]

        if (startIndex == null || endIndex == null || startIndex > endIndex) {
            // Cannot cleanly map bounds; fallback to verbatim projection
            return history.map { it.toModelMessage() }
        }

        // 1. Head (all messages before startIndex)
        for (i in 0 until startIndex) {
            result.add(history[i].toModelMessage())
        }

        // 2. Summary node (injected as SYSTEM context block)
        val fallbackBadge = when (latestRecord.fallbackLevel) {
            0 -> "模型智能摘要"
            1 -> "规则确定性摘要"
            else -> "硬截断紧凑摘要"
        }
        val summaryContent = """
[上下文压缩摘要 · $fallbackBadge]
${latestRecord.summary}
""".trimIndent()
        result.add(ModelMessage(MessageRole.SYSTEM, summaryContent))

        // 3. Tail (all messages after endIndex)
        for (i in (endIndex + 1) until history.size) {
            result.add(history[i].toModelMessage())
        }

        return result
    }

    /**
     * Keeps the ordered parts of a message.
     *
     * Dropping them here would turn an attachment message into a text-only one between the store
     * and the provider — the exact silent downgrade the attachment design forbids.
     */
    private fun ConversationMessage.toModelMessage(): ModelMessage =
        ModelMessage(role = role, content = content, parts = parts)
}
