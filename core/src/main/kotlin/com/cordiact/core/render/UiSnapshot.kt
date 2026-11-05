package com.cordiact.core.render

/**
 * Structural snapshot of a rendered preview, taken at a point in time.
 *
 * A renderer surface (WebView today) can answer "what is on screen and where", but it cannot
 * answer "why does it look wrong". Pixels answer the first question at the cost of a
 * composited surface; this model answers it with plain numbers, which is what the agent
 * actually needs to fix a layout, and it is available even when nothing is visible.
 *
 * The producer is responsible for only describing what it can measure. Fields it cannot fill
 * stay null rather than being guessed — a wrong `scrollWidth` is worse than a missing one,
 * because [UiSnapshotChecks] would then report a layout bug that does not exist.
 *
 * Units are CSS pixels of the preview viewport; [SnapshotViewport.density] carries the mapping
 * used when the preview emulates a device.
 */
data class UiSnapshot(
    val viewport: SnapshotViewport,
    val nodes: List<SnapshotNode>,
    /** Page background behind every node, as `#rrggbb`. Defaults to white. */
    val backgroundColor: String = "#ffffff",
)

data class SnapshotViewport(
    val width: Double,
    val height: Double,
    /**
     * CSS pixels per dp. `1.0` means the preview is laid out so that one CSS pixel stands for
     * one dp, which is how the device presets (360/390/768) are defined.
     */
    val density: Double = 1.0,
)

data class SnapshotRect(
    val x: Double,
    val y: Double,
    val w: Double,
    val h: Double,
) {
    val right: Double get() = x + w
    val bottom: Double get() = y + h
    val area: Double get() = if (w > 0 && h > 0) w * h else 0.0

    fun intersects(other: SnapshotRect): Boolean =
        x < other.right && other.x < right && y < other.bottom && other.y < bottom

    /** Overlap area; `0.0` when the rectangles only share an edge. */
    fun overlapArea(other: SnapshotRect): Double {
        val w = minOf(right, other.right) - maxOf(x, other.x)
        val h = minOf(bottom, other.bottom) - maxOf(y, other.y)
        return if (w > 0 && h > 0) w * h else 0.0
    }
}

data class SnapshotNode(
    val selector: String,
    val tag: String,
    val parentSelector: String? = null,
    val text: String? = null,
    val rect: SnapshotRect,
    val scrollWidth: Double? = null,
    val clientWidth: Double? = null,
    val scrollHeight: Double? = null,
    val clientHeight: Double? = null,
    /** `position: absolute | fixed` — such nodes are expected to overlap their siblings. */
    val positioned: Boolean = false,
    /** Carries a click handler, or is a link/button/`role=button`. */
    val interactive: Boolean = false,
    val style: SnapshotStyle = SnapshotStyle(),
)

data class SnapshotStyle(
    /** Resolved `rgb()`/`rgba()`/hex text colour, or null when inherited. */
    val color: String? = null,
    /** Resolved background colour of this node; null when transparent. */
    val backgroundColor: String? = null,
    val fontSizePx: Double? = null,
    /** `visible | hidden | clip | auto | scroll` for the inline axis. */
    val overflowX: String? = null,
    val overflowY: String? = null,
)

enum class SnapshotIssueKind {
    /** Content wider than its own box while the axis is not scrollable. */
    OVERFLOW_X,

    /** Content taller than its own box while the axis is not scrollable. */
    OVERFLOW_Y,

    /** Two sibling boxes in the same parent cover each other. */
    OVERLAP,

    /** Text/background contrast below WCAG AA. */
    LOW_CONTRAST,

    /** Clickable target below the minimum touch size. */
    SMALL_TAP_TARGET,

    /** A clickable node sits (partly) outside the viewport. */
    OFF_SCREEN,
}

data class SnapshotFinding(
    val kind: SnapshotIssueKind,
    val selector: String,
    /** One line, already formatted for a tool result; no markdown. */
    val message: String,
)

/**
 * Deterministic layout/style checks over a [UiSnapshot].
 *
 * These exist so the agent does not have to look at a picture to notice the bugs it produces
 * most: clipped content, boxes stacked on top of each other, unreadable text, and touch
 * targets that are too small to hit. Every rule is arithmetic on numbers the producer
 * measured, so the same snapshot always yields the same findings — which is what makes them
 * usable inside an iteration loop.
 */
object UiSnapshotChecks {

    /** Material guidance; expressed in dp, which equals CSS px under the device presets. */
    const val MIN_TAP_TARGET_DP = 48.0

    /** WCAG AA for body text and for large text (>= 24px). */
    private const val MIN_CONTRAST_NORMAL = 4.5
    private const val MIN_CONTRAST_LARGE = 3.0
    private const val LARGE_TEXT_PX = 24.0

    /** Tolerance for sub-pixel layout noise before a difference is called a bug. */
    private const val EPSILON = 1.0

    fun check(snapshot: UiSnapshot): List<SnapshotFinding> {
        val findings = mutableListOf<SnapshotFinding>()
        snapshot.nodes.forEach { node ->
            overflow(node, findings)
            contrast(snapshot, node, findings)
            tapTarget(snapshot, node, findings)
            offScreen(snapshot, node, findings)
        }
        overlap(snapshot, findings)
        return findings.sortedWith(compareBy({ it.kind.ordinal }, { it.selector }))
    }

    private fun overflow(node: SnapshotNode, out: MutableList<SnapshotFinding>) {
        val scrollWidth = node.scrollWidth
        val clientWidth = node.clientWidth
        if (scrollWidth != null && clientWidth != null &&
            isClippingAxis(node.style.overflowX) && scrollWidth > clientWidth + EPSILON
        ) {
            out += SnapshotFinding(
                SnapshotIssueKind.OVERFLOW_X,
                node.selector,
                "${node.selector}: content is ${scrollWidth.toInt()}px wide inside a " +
                    "${clientWidth.toInt()}px box and overflow-x is not scrollable",
            )
        }
        val scrollHeight = node.scrollHeight
        val clientHeight = node.clientHeight
        if (scrollHeight != null && clientHeight != null &&
            isClippingAxis(node.style.overflowY) && scrollHeight > clientHeight + EPSILON
        ) {
            out += SnapshotFinding(
                SnapshotIssueKind.OVERFLOW_Y,
                node.selector,
                "${node.selector}: content is ${scrollHeight.toInt()}px tall inside a " +
                    "${clientHeight.toInt()}px box and overflow-y is not scrollable",
            )
        }
    }

    /**
     * A non-scrollable axis that clips or overflows is a layout bug; `auto`/`scroll` means the
     * author asked for a scroll container, so a larger scroll size is the intended state.
     */
    private fun isClippingAxis(overflow: String?): Boolean =
        overflow != "auto" && overflow != "scroll"

    private fun overlap(snapshot: UiSnapshot, out: MutableList<SnapshotFinding>) {
        val byParent = snapshot.nodes
            .filter { !it.positioned && it.parentSelector != null }
            .groupBy { it.parentSelector }
        byParent.forEach { (_, siblings) ->
            for (i in siblings.indices) {
                for (j in i + 1 until siblings.size) {
                    val a = siblings[i]
                    val b = siblings[j]
                    val shared = a.rect.overlapArea(b.rect)
                    if (shared <= EPSILON) continue
                    // Containers legitimately enclose their children; only flag the case where
                    // neither box is the other's parent and the overlap is substantial.
                    val smaller = minOf(a.rect.area, b.rect.area)
                    if (smaller <= 0.0 || shared / smaller < 0.25) continue
                    if (a.rect.contains(b.rect) || b.rect.contains(a.rect)) continue
                    out += SnapshotFinding(
                        SnapshotIssueKind.OVERLAP,
                        a.selector,
                        "${a.selector} and ${b.selector} overlap by " +
                            "${(shared / smaller * 100).toInt()}% of the smaller box; " +
                            "neither is positioned",
                    )
                }
            }
        }
    }

    private fun contrast(
        snapshot: UiSnapshot,
        node: SnapshotNode,
        out: MutableList<SnapshotFinding>,
    ) {
        val text = node.text?.trim().orEmpty()
        if (text.isEmpty()) return
        val fg = parseColor(node.style.color) ?: return
        // A translucent text colour tells us nothing reliable about what ends up behind it.
        if (fg.alpha < 1.0) return
        // A transparent node background is not a background: fall through to the page. Reading
        // it as "black with alpha 0" would silently disable the check on every unstyled element,
        // which is exactly where low-contrast text is most common.
        val bg = parseColor(node.style.backgroundColor)?.takeIf { it.alpha == 1.0 }
            ?: parseColor(snapshot.backgroundColor)
            ?: return
        if (bg.alpha < 1.0) return
        val ratio = contrastRatio(fg, bg)
        val required = if ((node.style.fontSizePx ?: 0.0) >= LARGE_TEXT_PX) {
            MIN_CONTRAST_LARGE
        } else {
            MIN_CONTRAST_NORMAL
        }
        if (ratio < required) {
            out += SnapshotFinding(
                SnapshotIssueKind.LOW_CONTRAST,
                node.selector,
                "${node.selector}: contrast ${"%.2f".format(ratio)}:1 is below the " +
                    "$required:1 required for this text size",
            )
        }
    }

    private fun tapTarget(
        snapshot: UiSnapshot,
        node: SnapshotNode,
        out: MutableList<SnapshotFinding>,
    ) {
        if (!node.interactive) return
        val minSize = MIN_TAP_TARGET_DP * snapshot.viewport.density
        val w = node.rect.w
        val h = node.rect.h
        if (w <= 0.0 && h <= 0.0) return
        if (w + EPSILON < minSize || h + EPSILON < minSize) {
            out += SnapshotFinding(
                SnapshotIssueKind.SMALL_TAP_TARGET,
                node.selector,
                "${node.selector}: touch target ${w.toInt()}×${h.toInt()}px is under the " +
                    "${minSize.toInt()}px minimum",
            )
        }
    }

    private fun offScreen(
        snapshot: UiSnapshot,
        node: SnapshotNode,
        out: MutableList<SnapshotFinding>,
    ) {
        if (!node.interactive) return
        val r = node.rect
        val vp = snapshot.viewport
        val outsideHorizontally = r.right <= 0.0 || r.x >= vp.width
        val outsideVertically = r.bottom <= 0.0 || r.y >= vp.height
        if (outsideHorizontally || outsideVertically) {
            out += SnapshotFinding(
                SnapshotIssueKind.OFF_SCREEN,
                node.selector,
                "${node.selector}: clickable node sits outside the " +
                    "${vp.width.toInt()}×${vp.height.toInt()} viewport " +
                    "(rect ${r.x.toInt()},${r.y.toInt()} ${r.w.toInt()}×${r.h.toInt()})",
            )
        }
    }

    private fun SnapshotRect.contains(other: SnapshotRect): Boolean =
        x <= other.x + EPSILON && y <= other.y + EPSILON &&
            right >= other.right - EPSILON && bottom >= other.bottom - EPSILON

    // ---- colour helpers -------------------------------------------------------------

    data class Rgba(val r: Double, val g: Double, val b: Double, val alpha: Double)

    /** Accepts `#rgb`, `#rrggbb`, `#rrggbbaa`, `rgb(...)`, `rgba(...)`. Returns null otherwise. */
    fun parseColor(raw: String?): Rgba? {
        val text = raw?.trim()?.lowercase() ?: return null
        if (text.isEmpty() || text == "transparent" || text == "currentcolor") return null
        return when {
            text.startsWith("#") -> parseHex(text)
            text.startsWith("rgb") -> parseRgbFunction(text)
            else -> named(text)
        }
    }

    private fun parseHex(text: String): Rgba? {
        val hex = text.removePrefix("#")
        fun component(i: Int): Double =
            (hex.substring(i, i + 2).toIntOrNull(16) ?: return -1.0) / 255.0
        return when (hex.length) {
            3 -> {
                val r = hex[0].digitToIntOrNull(16)?.times(17) ?: return null
                val g = hex[1].digitToIntOrNull(16)?.times(17) ?: return null
                val b = hex[2].digitToIntOrNull(16)?.times(17) ?: return null
                Rgba(r / 255.0, g / 255.0, b / 255.0, 1.0)
            }
            6, 8 -> {
                val r = component(0)
                val g = component(2)
                val b = component(4)
                if (r < 0 || g < 0 || b < 0) return null
                val a = if (hex.length == 8) component(6).takeIf { it >= 0 } ?: 1.0 else 1.0
                Rgba(r, g, b, a)
            }
            else -> null
        }
    }

    private fun parseRgbFunction(text: String): Rgba? {
        val open = text.indexOf('(')
        val close = text.lastIndexOf(')')
        if (open < 0 || close <= open) return null
        val parts = text.substring(open + 1, close)
            .split(',', '/')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
        if (parts.size < 3) return null
        val channels = parts.take(3).map { part ->
            if (part.endsWith("%")) {
                val percent = part.dropLast(1).toDoubleOrNull() ?: return null
                percent / 100.0
            } else {
                val value = part.toDoubleOrNull() ?: return null
                value / 255.0
            }
        }
        val alpha = parts.getOrNull(3)?.let { part ->
            if (part.endsWith("%")) {
                (part.dropLast(1).toDoubleOrNull() ?: 1.0) / 100.0
            } else {
                part.toDoubleOrNull() ?: 1.0
            }
        } ?: 1.0
        return Rgba(channels[0], channels[1], channels[2], alpha)
    }

    private fun named(text: String): Rgba? = when (text) {
        "black" -> Rgba(0.0, 0.0, 0.0, 1.0)
        "white" -> Rgba(1.0, 1.0, 1.0, 1.0)
        "red" -> Rgba(1.0, 0.0, 0.0, 1.0)
        "grey", "gray" -> Rgba(0.5, 0.5, 0.5, 1.0)
        else -> null
    }

    /** WCAG 2.1 relative-luminance contrast ratio, 1.0 … 21.0. */
    fun contrastRatio(a: Rgba, b: Rgba): Double {
        val la = relativeLuminance(a)
        val lb = relativeLuminance(b)
        val lighter = maxOf(la, lb)
        val darker = minOf(la, lb)
        return (lighter + 0.05) / (darker + 0.05)
    }

    private fun relativeLuminance(color: Rgba): Double {
        fun channel(c: Double): Double =
            if (c <= 0.03928) c / 12.92 else Math.pow((c + 0.055) / 1.055, 2.4)
        return 0.2126 * channel(color.r) + 0.7152 * channel(color.g) + 0.0722 * channel(color.b)
    }
}
