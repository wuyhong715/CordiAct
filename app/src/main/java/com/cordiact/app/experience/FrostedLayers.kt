package com.cordiact.app.experience

import android.graphics.RenderEffect
import android.graphics.RuntimeShader
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.*
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.*

/** One source per viewport; never capture the controls that consume it. */
internal val LocalBackdrop = staticCompositionLocalOf<HazeState?> { null }
internal val LocalChromePadding = staticCompositionLocalOf { PaddingValues() }
// Actual floating chrome bounds, without the extra fade clearance used by full-bleed viewports.
internal val LocalChromeBoundsPadding = staticCompositionLocalOf { PaddingValues() }

/** The background alone is filtered. Input text, cursor and icons stay sharp. */
@Composable
internal fun FrostedSurface(
    modifier: Modifier = Modifier,
    radius: Dp = 24.dp,
    content: @Composable BoxScope.() -> Unit,
) {
    val source = LocalBackdrop.current
    val colors = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(radius)
    Box(modifier.clip(shape)) {
        val refraction = if (Build.VERSION.SDK_INT >= 33) Modifier.edgeRefraction() else Modifier
        Box(Modifier.matchParentSize().then(refraction).hazeEffect(source) {
            backgroundColor = colors.background
            blurRadius = 16.dp
            inputScale = HazeInputScale.Auto
            noiseFactor = .018f
            tints = listOf(HazeTint(colors.surface.copy(alpha = .62f)))
            fallbackTint = HazeTint(colors.surface.copy(alpha = .94f))
        })
        Box(Modifier.matchParentSize().border(.7.dp, Brush.linearGradient(listOf(
            colors.surface.copy(alpha = .85f), colors.outlineVariant.copy(alpha = .24f),
            colors.surface.copy(alpha = .4f))), shape))
        content()
    }
}

/** A viewport overlay, not a touch target. Real progressive blur plus a gentle fade. */
@Composable
internal fun ViewportFade(source: HazeState, top: Boolean, modifier: Modifier = Modifier) {
    val background = MaterialTheme.colorScheme.background
    // Fade the sampled image itself: an unmasked near-zero blur otherwise overlays
    // a second, slightly filtered copy on the sharp source at the join.
    val stops = listOf(0f to 1f, .25f to .96f, .5f to .68f, .75f to .22f, 1f to 0f)
    val fade = Brush.verticalGradient(*stops.map { (position, strength) ->
        (if (top) position else 1f - position) to Color.Black.copy(alpha = strength)
    }.sortedBy { it.first }.toTypedArray())
    val tint = Brush.verticalGradient(*stops.map { (position, strength) ->
        (if (top) position else 1f - position) to background.copy(alpha = strength * .88f)
    }.sortedBy { it.first }.toTypedArray())
    Box(modifier.hazeEffect(source) {
        backgroundColor = background
        blurRadius = 14.dp
        noiseFactor = 0f
        mask = fade
        progressive = HazeProgressive.verticalGradient(
            startIntensity = if (top) 1f else 0f,
            endIntensity = if (top) 0f else 1f,
            preferPerformance = false,
        )
        tints = emptyList()
        fallbackTint = HazeTint(background.copy(alpha = .85f))
    }.background(tint))
}

/** AGSL is only applied to the sampled backing, never to foreground children.
 * A subpixel-to-1px edge displacement, not a watery lens. API 31/32 retain real blur.
 */
@RequiresApi(33)
@Composable
private fun Modifier.edgeRefraction(): Modifier {
    val shader = remember { RuntimeShader("""
        uniform shader content;
        uniform float2 bounds;
        half4 main(float2 p) {
            float2 c = bounds * 0.5;
            float2 q = abs(p - c) - max(c - float2(24.0), float2(0.0));
            float d = length(max(q, float2(0.0))) + min(max(q.x, q.y), 0.0) - 24.0;
            float edge = 1.0 - smoothstep(0.0, 7.0, abs(d));
            float2 direction = (p - c) / max(length(p - c), 1.0);
            float2 sampleAt = clamp(p - direction * edge * 1.0, float2(0.0), bounds - 1.0);
            return content.eval(sampleAt);
        }
    """) }
    val effect = remember(shader) { RenderEffect.createRuntimeShaderEffect(shader, "content").asComposeRenderEffect() }
    return this.onSizeChanged { shader.setFloatUniform("bounds", it.width.toFloat(), it.height.toFloat()) }
        .graphicsLayer { renderEffect = effect }
}
