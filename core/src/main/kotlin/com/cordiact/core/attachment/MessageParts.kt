package com.cordiact.core.attachment

/**
 * One ordered segment of a user or model message.
 *
 * Attachments are references, never bytes and never paths. The order of [MessagePart]s is the
 * order the user selected and the order the model must receive.
 */
sealed interface MessagePart {
    data class Text(val text: String) : MessagePart {
        init {
            require(text.isNotEmpty()) { "A text part must not be empty; omit it instead" }
        }
    }

    data class Attachment(
        val id: AttachmentId,
        val interpretation: Interpretation,
        val renditionId: String? = null,
    ) : MessagePart {
        init {
            require(renditionId == null || renditionId.isNotBlank()) {
                "A rendition id must be non-blank when present"
            }
        }
    }
}

object MessageParts {
    /** Text used in the legacy `content` column, search index and notification previews. */
    fun plainText(parts: List<MessagePart>): String =
        parts.filterIsInstance<MessagePart.Text>().joinToString("\n") { it.text }

    fun attachmentParts(parts: List<MessagePart>): List<MessagePart.Attachment> =
        parts.filterIsInstance<MessagePart.Attachment>()

    /**
     * Structure-level validation, independent of any endpoint.
     *
     * A [Interpretation.LOCAL_ONLY] part is legal in a stored message (the user chose "keep local")
     * but not in a transmission plan; [AttachmentPlanner] rejects it there.
     */
    fun validate(parts: List<MessagePart>) {
        if (parts.isEmpty()) {
            throw AttachmentException(AttachmentErrorCode.EMPTY_MESSAGE, "A message must contain text or an attachment")
        }
        var hasContent = false
        for (part in parts) {
            when (part) {
                is MessagePart.Text -> if (part.text.isNotBlank()) hasContent = true
                is MessagePart.Attachment -> hasContent = true
            }
        }
        if (!hasContent) {
            throw AttachmentException(AttachmentErrorCode.EMPTY_MESSAGE, "A message must contain non-blank text or an attachment")
        }
    }

    /** Converts an old text-only message into the new representation, idempotently. */
    fun fromLegacyText(content: String): List<MessagePart> =
        if (content.isEmpty()) emptyList() else listOf(MessagePart.Text(content))
}
