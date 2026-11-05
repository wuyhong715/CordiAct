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
 * What the stage shows: one frame, or several side by side.
 *
 * The compare layout is a *viewing* aid — three frames of the same document at 360/390/768, so
 * a breakpoint mistake is visible at a glance. Snapshots and frame series still target
 * [primary] only, because "which element moved" has to have one answer.
 */
data class PreviewLayout(
    val frames: List<PreviewViewport>,
    val primary: Int = 0,
) {
    fun clamped(): PreviewLayout {
        val safeFrames = frames.take(MAX_FRAMES).map { it.clamped() }.ifEmpty {
            listOf(PreviewViewport.Phone)
        }
        return PreviewLayout(safeFrames, primary.coerceIn(0, safeFrames.lastIndex))
    }

    companion object {
        private const val MAX_FRAMES = 3

        fun single(viewport: PreviewViewport) = PreviewLayout(listOf(viewport))

        /** The three widths worth comparing before shipping a screen. */
        val Compare3 = PreviewLayout(
            frames = listOf(
                PreviewViewport(widthDp = 360, heightDp = 800),
                PreviewViewport(widthDp = 390, heightDp = 844),
                PreviewViewport(widthDp = 768, heightDp = 1024),
            ),
            primary = 1,
        )
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

    /** Emitted after the stage has rearranged itself; [frames] is the live frame count. */
    data class LayoutApplied(val frames: Int) : RenderEvent

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

    /**
     * Environment facts reported from inside the frame. Used by the instrumented tests to
     * answer the questions that cannot be settled by reading Chromium docs: does the shim run
     * before the page's own scripts, and are CSS animations reachable from the parent document.
     */
    data class Probed(
        val hasClock: Boolean,
        val frozen: Boolean,
        val pageSawClock: Boolean,
        val animationsApi: Boolean,
        val animationCount: Int,
        val animationTimes: List<Double?>,
        val nodeCount: Int,
        val frameCount: Int = 0,
        val frameWidths: List<Int> = emptyList(),
    ) : RenderEvent

    /** A/B of the live document against a candidate one; both sides are structural snapshots. */
    data class Compared(
        val previous: UiSnapshot,
        val current: UiSnapshot,
    ) : RenderEvent

    data class Failed(
        val where: String,
        val message: String,
        val stack: String? = null,
    ) : RenderEvent

    data class Unknown(val type: String, val raw: String) : RenderEvent
}
