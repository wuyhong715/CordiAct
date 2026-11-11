package com.cordiact.core.attachment

import com.cordiact.core.conversation.ModelMessage

/** LOCAL_ONLY is explicit consent to store, not transmit. Replace it with a visible text marker;
 * all transmittable attachment parts still undergo the provider's full preflight, on every turn. */
object AttachmentHistoryPolicy {

    fun normalize(messages: List<ModelMessage>): List<ModelMessage> {
        return messages.map { message ->
            if (message.parts.none { it is MessagePart.Attachment }) message
            else downgrade(message)
        }
    }

    private fun downgrade(message: ModelMessage): ModelMessage {
        var changed = false
        val parts = message.parts.map { part ->
            if (part is MessagePart.Attachment && part.interpretation == Interpretation.LOCAL_ONLY) {
                changed = true
                MessagePart.Text(marker(part))
            } else part
        }
        if (!changed) return message
        val merged = mergeText(parts)
        return message.copy(content = MessageParts.plainText(merged), parts = merged)
    }

    private fun marker(part: MessagePart.Attachment): String =
        "[附件 ${part.id.value} 仅保存在工作区，未发送给模型]"

    /** Adjacent text parts are joined so the result still passes [MessageParts.validate]. */
    private fun mergeText(parts: List<MessagePart>): List<MessagePart> {
        val out = ArrayList<MessagePart>(parts.size)
        for (part in parts) {
            val last = out.lastOrNull()
            if (part is MessagePart.Text && last is MessagePart.Text) {
                out[out.lastIndex] = MessagePart.Text(last.text + "\n" + part.text)
            } else out += part
        }
        return out
    }
}
