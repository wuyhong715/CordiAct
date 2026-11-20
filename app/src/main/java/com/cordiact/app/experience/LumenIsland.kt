package com.cordiact.app.experience

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.*
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.cordiact.app.experience.model.ToolPhase
import com.cordiact.app.experience.composer.motionDuration
import kotlinx.coroutines.delay

/** What the island is currently telling. Exactly one state is shown; it is the only status owner. */
internal sealed interface IslandMode {
    data object Idle : IslandMode
    data class Running(val step: String?, val done: Int, val startedAt: Long) : IslandMode
    data object Stopping : IslandMode
    data class Approval(val count: Int, val here: Boolean) : IslandMode
}

internal fun ExperienceState.islandMode(): IslandMode {
    val here = currentApprovals.size
    val other = approvals.size - here
    if (here > 0 || other > 0) return IslandMode.Approval(if (here > 0) here else other, here > 0)
    if (stopping) return IslandMode.Stopping
    val turn = turns.lastOrNull()
    if (running && turn != null) {
        val live = turn.tools.lastOrNull { it.phase == ToolPhase.RUNNING }?.name
        return IslandMode.Running(live, turn.tools.count { it.phase == ToolPhase.SUCCEEDED }, turn.startedAt)
    }
    return IslandMode.Idle
}

/**
 * Lumen top chrome: a menu orb, the floating island, and the workbench orb.
 * Pull the island down to open the spaces/conversations sheet.
 */
@Composable
internal fun LumenTopChrome(
    workspace: String,
    session: String,
    mode: IslandMode,
    menuLabel: String,
    onMenu: () -> Unit,
    onIsland: () -> Unit,
    onApprovals: () -> Unit,
    trailing: @Composable () -> Unit,
) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        FrostedSurface(radius = 24.dp) {
            IconButton(onClick = onMenu, modifier = Modifier.testTag("open-navigation")) { AppGlyph(GlyphName.Menu, menuLabel) }
        }
        Box(Modifier.weight(1f).padding(horizontal = 8.dp), contentAlignment = Alignment.Center) {
            LumenIsland(workspace, session, mode, onTap = { if (mode is IslandMode.Approval) onApprovals() else onIsland() }, onPull = onMenu)
        }
        FrostedSurface(radius = 24.dp) { trailing() }
    }
}

@Composable
private fun LumenIsland(workspace: String, session: String, mode: IslandMode, onTap: () -> Unit, onPull: () -> Unit) {
    val haptics = LocalHapticFeedback.current
    var pulled by remember { mutableFloatStateOf(0f) }
    val description = when (mode) {
        IslandMode.Idle -> "$workspace，$session"
        is IslandMode.Running -> "正在执行${mode.step?.let { "：$it" } ?: ""}"
        IslandMode.Stopping -> "正在停止"
        is IslandMode.Approval -> "待审批 ${mode.count} 项，轻触处理"
    }
    FrostedSurface(
        radius = 22.dp,
        modifier = Modifier.heightIn(min = 44.dp).widthIn(max = 320.dp).testTag("lumen-island")
            .semantics(mergeDescendants = true) { contentDescription = description; role = Role.Button }
            .clickable { haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove); onTap() }
            .pointerInput(Unit) {
                detectVerticalDragGestures(onDragEnd = { if (pulled > 48f) onPull(); pulled = 0f }) { _, dy -> pulled = (pulled + dy).coerceAtLeast(0f) }
            }
            .animateContentSize(spring(dampingRatio = .82f, stiffness = 380f)),
    ) {
        AnimatedContent(mode::class, transitionSpec = { fadeIn(tween(180)) togetherWith fadeOut(tween(120)) }, label = "island") { kind ->
            Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                when (kind) {
                    IslandMode.Running::class -> {
                        val running = mode as? IslandMode.Running
                        SignalDot()
                        Spacer(Modifier.width(10.dp))
                        Text(running?.step ?: "思考中", style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                        Spacer(Modifier.width(8.dp))
                        if (running != null) Elapsed(running.startedAt, running.done)
                    }
                    IslandMode.Stopping::class -> {
                        SignalDot(Lumen.Error)
                        Spacer(Modifier.width(10.dp))
                        Text("正在停止", style = MaterialTheme.typography.labelLarge)
                    }
                    IslandMode.Approval::class -> {
                        val approval = mode as? IslandMode.Approval
                        AppGlyph(GlyphName.Shield, tint = Lumen.Approve, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(if (approval?.here != false) "待审批 ${approval?.count ?: 0} 项" else "其他会话待审批 ${approval.count} 项",
                            style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurface)
                        Spacer(Modifier.width(6.dp))
                        AppGlyph(GlyphName.ChevronRight, tint = Lumen.Approve, modifier = Modifier.size(14.dp))
                    }
                    else -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(session, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(workspace, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}

/** Breathing aurora dot: the one place the brand signal moves. */
@Composable
internal fun SignalDot(tint: androidx.compose.ui.graphics.Color? = null, size: androidx.compose.ui.unit.Dp = 10.dp) {
    val pulse by rememberInfiniteTransition(label = "signal").animateFloat(.72f, 1.12f,
        infiniteRepeatable(tween(motionDuration(900).coerceAtLeast(1)), RepeatMode.Reverse), label = "pulse")
    val brush = if (tint != null) Brush.linearGradient(listOf(tint, tint)) else Brush.sweepGradient(Lumen.Aurora + Lumen.Aurora.first())
    Box(Modifier.size(size).scale(pulse).clip(CircleShape).background(brush))
}

@Composable
private fun Elapsed(startedAt: Long, done: Int) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(startedAt) { while (true) { now = System.currentTimeMillis(); delay(1000) } }
    val seconds = ((now - startedAt).coerceAtLeast(0) / 1000)
    val time = if (seconds >= 60) "${seconds / 60}:${"%02d".format(seconds % 60)}" else "${seconds}s"
    Text(if (done > 0) "$done 步 · $time" else time, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
}
