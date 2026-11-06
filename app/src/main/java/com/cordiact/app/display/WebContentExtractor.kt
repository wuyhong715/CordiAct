package com.cordiact.app.display

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import com.cordiact.core.automation.ScreenRect
import com.cordiact.core.automation.ScreenWebDocument
import com.cordiact.core.automation.ScreenWebLink

/**
 * Reads the *rendered* content of a WebView from an accessibility tree.
 *
 * This is the only channel available for a WebView that lives inside another
 * app on a screen: there is no debugging socket, so the DOM is out of reach and
 * the page comes back as the text, links and editables the WebView exposes to
 * accessibility. Nodes are read once and never clicked; anything returned here
 * is page content and must be treated as untrusted data.
 */
internal object WebContentExtractor {

    private const val MAX_LINKS = 60
    private const val MAX_VISITED = 4_000

    fun fromRoot(root: AccessibilityNodeInfo, handle: String, maxChars: Int = 4_000): ScreenWebDocument? {
        val web = findWebView(root) ?: return null
        val title = web.contentDescription?.toString()?.trim().orEmpty()
            .ifBlank { web.text?.toString()?.trim().orEmpty() }
        val url = urlOf(web)
        val links = mutableListOf<ScreenWebLink>()
        val text = StringBuilder()
        var visited = 0
        var truncated = false

        fun visit(node: AccessibilityNodeInfo) {
            if (visited++ > MAX_VISITED || truncated) return
            val label = (node.text ?: node.contentDescription)?.toString()?.trim().orEmpty()
            if (label.isNotEmpty()) {
                val separator = if (text.isEmpty()) "" else "\n"
                if (text.length + label.length + separator.length > maxChars) {
                    truncated = true
                    return
                }
                text.append(separator).append(label)
                if (node.isClickable && links.size < MAX_LINKS) {
                    val bounds = Rect().also { node.getBoundsInScreen(it) }
                    links += ScreenWebLink(label, ScreenRect(bounds.left, bounds.top, bounds.right, bounds.bottom))
                }
            }
            for (index in 0 until node.childCount) {
                val child = node.getChild(index) ?: continue
                visit(child)
                @Suppress("DEPRECATION")
                runCatching { child.recycle() }
            }
        }

        visit(web)
        return ScreenWebDocument(
            handle = handle,
            title = title,
            url = url,
            text = text.toString(),
            links = links,
            elements = visited,
            truncated = truncated,
        )
    }

    private fun findWebView(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var visited = 0
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue += root
        while (queue.isNotEmpty() && visited++ < MAX_VISITED) {
            val node = queue.removeFirst()
            if (isWebView(node)) return node
            for (index in 0 until node.childCount) {
                node.getChild(index)?.let(queue::addLast)
            }
        }
        return null
    }

    private fun isWebView(node: AccessibilityNodeInfo): Boolean {
        val className = node.className?.toString().orEmpty()
        return className.contains("WebView", ignoreCase = true) ||
            className.contains("chromium", ignoreCase = true)
    }

    /** WebViews rarely publish a URL; take one only when it is plainly a URL. */
    private fun urlOf(node: AccessibilityNodeInfo): String? {
        val extras = runCatching { node.extras }.getOrNull()
        val candidates = buildList {
            extras?.getCharSequence("url")?.let { add(it.toString()) }
            extras?.getString("AccessibilityNodeInfo.url")?.let { add(it) }
            node.contentDescription?.toString()?.let { add(it) }
            node.text?.toString()?.let { add(it) }
        }
        return candidates.firstOrNull { it.startsWith("http://") || it.startsWith("https://") }
    }
}
