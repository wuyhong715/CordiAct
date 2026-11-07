package com.cordiact.core.inbox

/**
 * Renders queued reports as one structured system block.
 *
 * Structured rather than prose so the model can tell a child's own words from the runtime's
 * account of an outcome, and so a future turn can parse what it was told. The block also says
 * plainly that it is a runtime report, because a transcript that let it read as a user message
 * would be lying about who said it.
 */
object ChildReportRenderer {

    fun render(items: List<InboxItem>): String? {
        if (items.isEmpty()) return null
        return buildString {
            appendLine("Background work you started has reported. This is a runtime report, not a user message.")
            for (item in items.sortedBy { it.seq }) {
                appendLine()
                appendLine("<child_report kind=\"${item.kind.tag()}\" source=\"${escape(item.sourceId)}\">")
                appendLine(item.heading.trim())
                item.body?.takeIf { it.isNotBlank() }?.let { body ->
                    appendLine(body.trim())
                }
                appendLine("</child_report>")
            }
        }.trimEnd()
    }

    private fun InboxItemKind.tag(): String = when (this) {
        InboxItemKind.CHILD_MESSAGE -> "message"
        InboxItemKind.CHILD_SETTLED -> "settled"
        InboxItemKind.JOB_SETTLED -> "job"
    }

    /** Keeps the attribute well-formed without letting content break out of it. */
    private fun escape(value: String): String = value
        .replace("&", "&amp;")
        .replace("\"", "&quot;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
}
