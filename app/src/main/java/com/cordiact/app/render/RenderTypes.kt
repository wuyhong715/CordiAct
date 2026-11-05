package com.cordiact.app.render

import com.cordiact.core.render.SnapshotFinding
import com.cordiact.core.render.UiSnapshot

/**
 * Viewport presets for the preview stage.
 *
 * One CSS pixel stands for one dp at [scale] = 1, which is why 390 matches the design width
 * agents are asked to target. [scale] only shrinks the *display* — the page still lays out at
 * [widthDp] × [heightDp], so a preview never lies about a breakpoint.
 */
data class PreviewViewport(
    val widthDp: Int = 390,
    val heightDp: Int = 844,
    val scale: Float = 1f,
) {
    fun clamped(): PreviewViewport = PreviewViewport(
        widthDp = widthDp.coerceIn(200, 2000),
        heightDp = heightDp.coerceIn(200, 3000),
        scale = scale.coerceIn(0.2f, 2f),
    )

    companion object {
        val SmallPhone = PreviewViewport(widthDp = 360, heightDp = 800)
        val Phone = PreviewViewport(widthDp = 390, heightDp = 844)
        val Tablet = PreviewViewport(widthDp = 768, heightDp = 1024, scale = 0.55f)
    }
}

/**
 * What the surface reports back. Mirrors the `post(...)` calls in `assets/render/preview.js`;
 * adding a message there without adding a branch here degrades to [Unknown] rather than
 * throwing, so a newer page never crashes an older host.
 */
sealed interface RenderEvent {
    data class Ready(val userAgent: String) : RenderEvent

    data class Mounted(val viewport: PreviewViewport) : RenderEvent

    data object Patched : RenderEvent

    data class Seeked(val timeMs: Double) : RenderEvent

    data object Playing : RenderEvent

    data class Paused(val timeMs: Double) : RenderEvent

    /**
     * A structural snapshot plus the deterministic findings computed from it. The agent gets
     * both: the raw numbers when it needs to work out a fix, the findings when it just needs
     * to know something is wrong.
     */
    data class SnapshotReady(
        val snapshot: UiSnapshot,
        val findings: List<SnapshotFinding>,
    ) : RenderEvent

    data class Failed(
        val where: String,
        val message: String,
        val stack: String? = null,
    ) : RenderEvent

    data class Unknown(val type: String, val raw: String) : RenderEvent
}
