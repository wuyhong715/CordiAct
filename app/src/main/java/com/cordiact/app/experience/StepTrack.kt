package com.cordiact.app.experience

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.*
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.cordiact.app.experience.model.ToolActivity
import com.cordiact.app.experience.model.ToolPhase

/**
 * Lumen signature: a turn's process is a string of beads. Drag across it to scrub
 * through steps; the glance above follows the finger. Tap a bead (or the glance) to inspect.
 */
@Composable
internal fun StepTrack(tools: List<ToolActivity>, running: Boolean, onInspect: (ToolActivity) -> Unit) {
    if (tools.isEmpty()) return
    val haptics = LocalHapticFeedback.current
    var focused by rememberSaveable(tools.firstOrNull()?.id) { mutableIntStateOf(-1) }
    var scrubbing by remember { mutableStateOf(false) }
    val failed = tools.count { it.phase == ToolPhase.FAILED }
    val done = tools.count { it.phase == ToolPhase.SUCCEEDED }
    val shown = tools.getOrNull(focused) ?: tools.lastOrNull { it.phase == ToolPhase.RUNNING }
        ?: tools.firstOrNull { it.phase == ToolPhase.FAILED }
    val colors = MaterialTheme.colorScheme
    val summary = when {
        failed > 0 -> "${tools.size} 步 · $failed 步失败"
        running -> "第 ${done + 1} 步 · 进行中"
        else -> "${tools.size} 步完成"
    }

    Column(Modifier.fillMaxWidth().testTag("step-track")) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (running) SignalDot(size = 7.dp) else AppGlyph(GlyphName.Trace, tint = colors.onSurfaceVariant, modifier = Modifier.size(13.dp))
            Spacer(Modifier.width(7.dp))
            Text(summary, style = MaterialTheme.typography.labelMedium, color = if (failed > 0) colors.error else colors.onSurfaceVariant)
            Spacer(Modifier.weight(1f))
            Text("拖动查看每一步", style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant.copy(alpha = .7f))
        }
        Spacer(Modifier.height(8.dp))
        Beads(
            tools = tools, running = running, focused = focused,
            modifier = Modifier.fillMaxWidth().height(36.dp)
                .semantics {
                    contentDescription = "执行轨道，$summary"
                    customActions = tools.mapIndexed { i, t -> CustomAccessibilityAction("查看第${i + 1}步 ${t.name}") { onInspect(t); true } }.take(12)
                }
                .pointerInput(tools.size) {
                    fun indexAt(x: Float) = ((x / size.width) * tools.size).toInt().coerceIn(0, tools.lastIndex)
                    detectTapGestures { offset -> onInspect(tools[indexAt(offset.x)]) }
                }
                .pointerInput(tools.size) {
                    fun indexAt(x: Float) = ((x / size.width) * tools.size).toInt().coerceIn(0, tools.lastIndex)
                    detectHorizontalDragGestures(
                        onDragStart = { scrubbing = true; focused = indexAt(it.x) },
                        onDragEnd = { scrubbing = false },
                        onDragCancel = { scrubbing = false },
                    ) { change, _ ->
                        change.consume()
                        val next = indexAt(change.position.x)
                        if (next != focused) { focused = next; haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove) }
                    }
                },
        )
        AnimatedVisibility(shown != null, enter = fadeIn(tween(160)), exit = fadeOut(tween(120))) {
            shown?.let { step ->
                StepGlance(step, tools.indexOf(step) + 1, emphasised = scrubbing, onClick = { onInspect(step) })
            }
        }
    }
}

@Composable
private fun Beads(tools: List<ToolActivity>, running: Boolean, focused: Int, modifier: Modifier) {
    val colors = MaterialTheme.colorScheme
    val breath by rememberInfiniteTransition(label = "bead").animateFloat(.35f, 1f,
        infiniteRepeatable(tween(800), RepeatMode.Reverse), label = "breath")
    val aurora = Lumen.Aurora
    Canvas(modifier) {
        val n = tools.size
        val slot = size.width / n
        val gap = (slot * .18f).coerceIn(1.5.dp.toPx(), 5.dp.toPx())
        val baseH = 10.dp.toPx()
        val cy = size.height / 2
        tools.forEachIndexed { i, step ->
            val isFocus = i == focused
            val h = if (isFocus) 22.dp.toPx() else baseH
            val w = (slot - gap).coerceAtLeast(2.dp.toPx())
            val left = i * slot + gap / 2
            val color: Brush = when (step.phase) {
                ToolPhase.SUCCEEDED -> Brush.horizontalGradient(aurora, startX = 0f, endX = size.width)
                ToolPhase.FAILED -> Brush.linearGradient(listOf(Lumen.Error, Lumen.Error))
                ToolPhase.RUNNING -> Brush.linearGradient(listOf(aurora[0].copy(alpha = breath), aurora[1].copy(alpha = breath)))
                ToolPhase.INTERRUPTED -> Brush.linearGradient(listOf(colors.outline, colors.outline))
            }
            drawRoundRect(color, Offset(left, cy - h / 2), Size(w, h), CornerRadius(minOf(w, h) / 2))
        }
        if (running && tools.none { it.phase == ToolPhase.RUNNING }) {
            drawCircle(aurora[1].copy(alpha = breath), radius = 4.dp.toPx(), center = Offset(size.width - 4.dp.toPx(), cy))
        }
    }
}

@Composable
private fun StepGlance(step: ToolActivity, ordinal: Int, emphasised: Boolean, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val tone = when (step.phase) { ToolPhase.FAILED -> colors.error; ToolPhase.RUNNING -> colors.onPrimaryContainer; else -> colors.onSurfaceVariant }
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(14.dp),
        color = if (emphasised) colors.surface else colors.surfaceContainerLow,
        shadowElevation = if (emphasised) 6.dp else 0.dp,
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp).heightIn(min = 48.dp).testTag("step-glance"),
    ) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("$ordinal", style = MaterialTheme.typography.labelMedium.copy(fontFamily = FontFamily.Monospace), color = tone, modifier = Modifier.width(22.dp))
            Column(Modifier.weight(1f)) {
                Text(step.name, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val detail = step.summary ?: step.arguments.take(120).replace('\n', ' ')
                if (detail.isNotBlank()) Text(detail, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Text(
                when (step.phase) {
                    ToolPhase.SUCCEEDED -> step.durationMillis?.let { if (it >= 1000) "%.1fs".format(it / 1000f) else "${it}ms" } ?: "完成"
                    ToolPhase.FAILED -> "失败"; ToolPhase.RUNNING -> "执行中"; ToolPhase.INTERRUPTED -> "中断"
                },
                style = MaterialTheme.typography.labelSmall, color = tone, modifier = Modifier.padding(horizontal = 6.dp),
            )
            AppGlyph(GlyphName.ChevronRight, tint = colors.onSurfaceVariant, modifier = Modifier.size(12.dp))
        }
    }
}
