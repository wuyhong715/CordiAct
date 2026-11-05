package com.cordiact.app.render

import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.snapshots.SnapshotStateList

/** Who put this in the inbox; shown so the user knows whether to trust the content. */
enum class PreviewOrigin { USER, AGENT }

/**
 * One thing waiting to be looked at. Deliberately inert: an item carries text and a viewport,
 * nothing else. Nothing is parsed, nothing is loaded, and no WebView exists until the user asks
 * for one — that is the whole point of moving the surface out of the chat stream.
 */
data class PreviewItem(
    val id: String,
    val sessionId: String,
    val title: String,
    val html: String,
    val css: String = "",
    val viewport: PreviewViewport = PreviewViewport.Phone,
    val origin: PreviewOrigin = PreviewOrigin.USER,
    val createdAtMillis: Long = System.currentTimeMillis(),
    val seen: Boolean = false,
)

/**
 * Session-scoped queue of things the agent (or the user) wants to show.
 *
 * A preview is an expensive, optional act: rendering every message the agent writes would burn
 * CPU and memory on things nobody looks at. So the agent's job is only to *drop* content here,
 * and rendering happens when a human opens the window and presses 渲染. The inbox is plain
 * snapshot state so the window and the badge can both observe it without a coroutine dance.
 */
object PreviewInbox {

    private val _items = mutableStateListOf<PreviewItem>()
    val items: SnapshotStateList<PreviewItem> get() = _items

    fun forSession(sessionId: String?): List<PreviewItem> {
        val key = keyOf(sessionId)
        return _items.filter { it.sessionId == key }.sortedByDescending { it.createdAtMillis }
    }

    fun unseenCount(sessionId: String?): Int = forSession(sessionId).count { !it.seen }

    /** Called by the agent's tool and by the dev "投放示例" action. Newest first. */
    fun push(
        sessionId: String?,
        title: String,
        html: String,
        css: String = "",
        viewport: PreviewViewport = PreviewViewport.Phone,
        origin: PreviewOrigin = PreviewOrigin.USER,
    ): PreviewItem {
        val item = PreviewItem(
            id = "pv-${System.currentTimeMillis()}-${_items.size}",
            sessionId = keyOf(sessionId),
            title = title.ifBlank { "未命名预览" },
            html = html,
            css = css,
            viewport = viewport.clamped(),
            origin = origin,
        )
        _items.add(0, item)
        // Keeping every version of every preview alive would defeat the memory budget; the last
        // few are enough to compare an iteration or two.
        while (_items.size > MAX_ITEMS) _items.removeAt(_items.lastIndex)
        return item
    }

    fun markSeen(id: String) {
        val index = _items.indexOfFirst { it.id == id }
        if (index >= 0 && !_items[index].seen) _items[index] = _items[index].copy(seen = true)
    }

    fun remove(id: String) {
        _items.removeAll { it.id == id }
    }

    fun clear(sessionId: String? = null) {
        if (sessionId == null) _items.clear() else _items.removeAll { it.sessionId == keyOf(sessionId) }
    }

    private fun keyOf(sessionId: String?): String = sessionId ?: "local"

    private const val MAX_ITEMS = 12
}
