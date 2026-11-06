package com.cordiact.core.automation

/**
 * Renders a [ScreenSnapshot] as the compact numbered text the model sees.
 *
 * Design borrowed from DSH Go's `ScreenFormat`: numbered elements, top/middle/
 * bottom zones, a hard character budget, `+` for elements that appeared since
 * the previous snapshot, `⚠` for sensitive ones, and passwords never rendered.
 *
 * Kept in `core` (pure Kotlin, no Android types) so the formatter can be unit
 * tested without a device.
 */
object ScreenFormat {

    const val DEFAULT_MAX_CHARS = 3_000
    const val DEFAULT_MAX_NODES = 60
    private const val LABEL_LIMIT = 60

    fun render(
        snapshot: ScreenSnapshot,
        maxChars: Int = DEFAULT_MAX_CHARS,
        maxNodes: Int = DEFAULT_MAX_NODES,
        previousFingerprints: Set<String>? = null,
    ): String {
        snapshot.error?.let { error ->
            return buildString {
                append(snapshot.handle).append(' ').append(snapshot.packageName)
                append('\n').append(error.code).append(": ").append(error.message)
                error.recovery?.let { append('\n').append("recovery: ").append(it) }
            }
        }

        val candidates = snapshot.elements
            .asSequence()
            .filter { it.enabled }
            .filter { it.label.isNotBlank() || it.clickable || it.editable || it.scrollable }
            .sortedWith(compareBy({ it.bounds.top }, { it.bounds.left }))
            .take(maxNodes)
            .toList()

        val header = buildString {
            append(snapshot.handle).append("  ")
            append(snapshot.packageName)
            snapshot.title?.takeIf { it.isNotBlank() }?.let { append("  ").append(it) }
            append("  ").append(snapshot.width).append('x').append(snapshot.height)
        }

        val lines = ArrayList<String>(candidates.size + 4)
        lines += header

        var currentZone: Zone? = null
        var used = header.length
        var rendered = 0

        for (element in candidates) {
            val zone = zoneOf(element, snapshot.height)
            if (zone != currentZone) {
                val zoneHeader = zone.label
                if (used + zoneHeader.length + 1 > maxChars) break
                lines += zoneHeader
                used += zoneHeader.length + 1
                currentZone = zone
            }
            val line = renderElement(element, previousFingerprints)
            if (used + line.length + 1 > maxChars) break
            lines += line
            used += line.length + 1
            rendered++
        }

        val remaining = snapshot.elements.size - rendered
        if (remaining > 0) {
            val note = if (candidates.size < snapshot.elements.size) {
                "另有 $remaining 项，滚动或提高 max_nodes 查看。"
            } else {
                "另有 $remaining 项（预算不足），滚动或提高 max_nodes 查看。"
            }
            if (used + note.length + 1 <= maxChars) lines += note else lines += "另有 $remaining 项"
        }

        return lines.joinToString("\n")
    }

    private fun renderElement(element: ScreenElement, previous: Set<String>?): String {
        val label = when {
            element.password -> "密码框（不可读）"
            element.label.isBlank() -> element.role
            else -> element.label.replace('\n', ' ').take(LABEL_LIMIT)
        }
        val marker = buildString {
            if (element.sensitive && !element.password) append(" ⚠")
            if (previous != null && element.fingerprint !in previous) append(" +")
        }
        val role = element.role.take(24)
        val bounds = "(${element.bounds.left},${element.bounds.top})-(${element.bounds.right},${element.bounds.bottom})"
        return "  [${element.index}] $label  $role  $bounds$marker"
    }

    private fun zoneOf(element: ScreenElement, screenHeight: Int): Zone {
        if (screenHeight <= 0) return Zone.MIDDLE
        return when {
            element.bounds.centerY < screenHeight * 0.25f -> Zone.TOP
            element.bounds.centerY > screenHeight * 0.78f -> Zone.BOTTOM
            else -> Zone.MIDDLE
        }
    }

    private enum class Zone(val label: String) {
        TOP("顶"),
        MIDDLE("中"),
        BOTTOM("底"),
    }
}
