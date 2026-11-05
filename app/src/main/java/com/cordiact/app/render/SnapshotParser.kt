package com.cordiact.app.render

import com.cordiact.core.render.SnapshotNode
import com.cordiact.core.render.SnapshotRect
import com.cordiact.core.render.SnapshotStyle
import com.cordiact.core.render.SnapshotViewport
import com.cordiact.core.render.UiSnapshot
import org.json.JSONObject

/**
 * Turns the page's snapshot JSON into the core model.
 *
 * The payload is produced by a WebView the agent can inject into, so every number is treated
 * as untrusted: sizes are clamped, text is truncated, and a missing field stays `null` instead
 * of being defaulted into a value that [com.cordiact.core.render.UiSnapshotChecks] would then
 * report as a layout bug.
 */
internal object SnapshotParser {

    private const val MAX_NODES = 400
    private const val MAX_TEXT = 200
    private const val MAX_SELECTOR = 300

    /** Returns null when the payload has no usable viewport — the caller reports that as an error. */
    fun parse(root: JSONObject): UiSnapshot? {
        val viewportJson = root.optJSONObject("viewport") ?: return null
        val width = viewportJson.optNullableDouble("w") ?: return null
        val height = viewportJson.optNullableDouble("h") ?: return null
        val viewport = SnapshotViewport(
            width = width.coerceIn(80.0, 3000.0),
            height = height.coerceIn(80.0, 3000.0),
            density = (viewportJson.optNullableDouble("density") ?: 1.0).coerceIn(0.5, 4.0),
        )

        val nodesJson = root.optJSONArray("nodes")
        val nodes = buildList {
            if (nodesJson == null) return@buildList
            val limit = minOf(nodesJson.length(), MAX_NODES)
            for (index in 0 until limit) {
                val nodeJson = nodesJson.optJSONObject(index) ?: continue
                node(nodeJson)?.let(::add)
            }
        }

        return UiSnapshot(
            viewport = viewport,
            nodes = nodes,
            backgroundColor = root.optString("backgroundColor").takeIf { it.startsWith("#") }
                ?: "#ffffff",
        )
    }

    private fun node(json: JSONObject): SnapshotNode? {
        val selector = json.optString("selector").take(MAX_SELECTOR)
        if (selector.isEmpty()) return null
        val rectJson = json.optJSONObject("rect") ?: return null
        val x = rectJson.optNullableDouble("x") ?: return null
        val y = rectJson.optNullableDouble("y") ?: return null
        val w = rectJson.optNullableDouble("w") ?: return null
        val h = rectJson.optNullableDouble("h") ?: return null

        val styleJson = json.optJSONObject("style")
        return SnapshotNode(
            selector = selector,
            tag = json.optString("tag").take(40),
            parentSelector = json.optStringOrNull("parentSelector")?.take(MAX_SELECTOR),
            text = json.optStringOrNull("text")?.take(MAX_TEXT),
            rect = SnapshotRect(x = x, y = y, w = w.coerceAtLeast(0.0), h = h.coerceAtLeast(0.0)),
            scrollWidth = json.optNullableDouble("scrollWidth"),
            clientWidth = json.optNullableDouble("clientWidth"),
            scrollHeight = json.optNullableDouble("scrollHeight"),
            clientHeight = json.optNullableDouble("clientHeight"),
            positioned = json.optBoolean("positioned", false),
            interactive = json.optBoolean("interactive", false),
            style = if (styleJson == null) {
                SnapshotStyle()
            } else {
                SnapshotStyle(
                    color = styleJson.optStringOrNull("color"),
                    backgroundColor = styleJson.optStringOrNull("backgroundColor"),
                    fontSizePx = styleJson.optNullableDouble("fontSizePx"),
                    overflowX = styleJson.optStringOrNull("overflowX"),
                    overflowY = styleJson.optStringOrNull("overflowY"),
                )
            },
        )
    }

    private fun JSONObject.optStringOrNull(key: String): String? =
        if (isNull(key)) null else optString(key).takeIf { it.isNotEmpty() }

    private fun JSONObject.optNullableDouble(key: String): Double? {
        if (isNull(key) || !has(key)) return null
        val value = optDouble(key, Double.NaN)
        return value.takeIf { !it.isNaN() && !it.isInfinite() }
    }
}
