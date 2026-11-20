package com.cordiact.app.experience

import androidx.compose.foundation.layout.*
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.IntOffset
import kotlin.math.roundToInt
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.cordiact.app.experience.model.ToolPhase
import com.cordiact.core.action.ApprovalOwner
import com.cordiact.core.action.ApprovalRequest

@Composable
internal fun WorkbenchDock(state: ExperienceState, onApprovals: () -> Unit, onTrace: () -> Unit) {
    val approvals = state.currentApprovals.size
    val other = state.approvals.size - approvals
    val progress = state.turns.lastOrNull()?.tools?.count { it.phase == ToolPhase.SUCCEEDED } ?: 0
    val status = when {
        state.stopping -> "正在停止"
        state.running -> if (progress > 0) "执行中 · 已完成 $progress 步" else "执行中"
        else -> null
    }
    if (status == null && approvals == 0 && other == 0) return
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.Center) {
        if (approvals > 0 || other > 0) {
            val label = when {
                approvals > 0 -> "本对话待审批 $approvals 项"
                else -> "其他待审批 $other 项"
            }
            FilledTonalButton(onClick = onApprovals, modifier = Modifier.heightIn(min = 48.dp)) {
                AppGlyph(GlyphName.Shield, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(8.dp))
                Text(label)
            }
        } else if (status != null) {
            TextButton(onClick = onTrace, modifier = Modifier.heightIn(min = 48.dp)) {
                AppGlyph(GlyphName.Trace, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(8.dp))
                Text(status)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ApprovalDecisionDialog(
    requests: List<ApprovalRequest>, deciding: Set<String>, errors: Map<String, String>, selected: String,
    ownerLabel: (ApprovalOwner?) -> String,
    onSelect: (String) -> Unit, onDismiss: () -> Unit, onDecide: (String, Boolean) -> Unit,
) {
    val request = requests.firstOrNull { it.id == selected }
    val colors = MaterialTheme.colorScheme
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        containerColor = colors.surface,
        modifier = Modifier.testTag("approval-sheet"),
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 24.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(36.dp).clip(CircleShape).background(Lumen.Approve.copy(alpha = .14f)), contentAlignment = Alignment.Center) {
                    AppGlyph(GlyphName.Shield, tint = Lumen.Approve, modifier = Modifier.size(18.dp))
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text("需要你批准", Modifier.semantics { heading() }, style = MaterialTheme.typography.titleLarge)
                    Text(if (requests.size > 1) "共 ${requests.size} 项待处理" else "Agent 已暂停，等待你的决定", style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                }
            }
            if (requests.size > 1) {
                Spacer(Modifier.height(14.dp))
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    requests.forEach { item ->
                        val on = item.id == selected
                        Surface(onClick = { onSelect(item.id) }, shape = CircleShape,
                            color = if (on) colors.onSurface else colors.surfaceContainer,
                            modifier = Modifier.heightIn(min = 36.dp).semantics { this.selected = on }) {
                            Text(item.toolName, Modifier.padding(horizontal = 14.dp, vertical = 8.dp), style = MaterialTheme.typography.labelLarge,
                                color = if (on) colors.surface else colors.onSurface, maxLines = 1)
                        }
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
            Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (request == null) Text(errors[selected] ?: "该请求已处理或不再可用。", color = colors.onSurfaceVariant)
                if (request != null) {
                    val (risk, tone) = when (request.riskLevel) {
                        ApprovalRequest.RiskLevel.LOW -> "低风险" to Lumen.Ok
                        ApprovalRequest.RiskLevel.MEDIUM -> "中风险" to Lumen.Approve
                        ApprovalRequest.RiskLevel.HIGH -> "高风险" to Lumen.Error
                        ApprovalRequest.RiskLevel.CRITICAL -> "严重风险" to Lumen.Error
                    }
                    Surface(shape = RoundedCornerShape(14.dp), color = colors.surfaceContainerLow) {
                        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(request.toolName, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                                Text(risk, style = MaterialTheme.typography.labelMedium, color = tone,
                                    modifier = Modifier.clip(CircleShape).background(tone.copy(alpha = .12f)).padding(horizontal = 10.dp, vertical = 4.dp))
                            }
                            Text(ownerLabel(request.owner), style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                            SelectionContainer { Text(request.summary, style = MaterialTheme.typography.bodyMedium) }
                        }
                    }
                    if (request.details.isNotEmpty()) Surface(shape = RoundedCornerShape(14.dp), color = colors.surfaceContainerLow) {
                        SelectionContainer {
                            Text(request.details.entries.joinToString("\n") { "${it.key}: ${it.value}" },
                                Modifier.fillMaxWidth().padding(14.dp),
                                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace))
                        }
                    }
                    Text("允许仅对本次请求生效", style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
                    errors[selected]?.let { Text(it, color = colors.error) }
                }
            }
            Spacer(Modifier.height(16.dp))
            if (request != null) {
                val busy = selected in deciding
                val dangerous = request.riskLevel >= ApprovalRequest.RiskLevel.HIGH
                if (dangerous) {
                    SlideToApprove(busy = busy, key = selected, onApprove = { onDecide(selected, true) })
                } else {
                    Button(onClick = { onDecide(selected, true) }, enabled = !busy, shape = CircleShape,
                        colors = ButtonDefaults.buttonColors(containerColor = colors.onSurface, contentColor = colors.surface),
                        modifier = Modifier.fillMaxWidth().height(52.dp)) { Text(if (busy) "正在提交" else "允许本次", style = MaterialTheme.typography.labelLarge) }
                }
                Spacer(Modifier.height(8.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { onDecide(selected, false) }, enabled = !busy, shape = CircleShape,
                        modifier = Modifier.weight(1f).height(48.dp)) { Text("拒绝", color = colors.error) }
                    OutlinedButton(onClick = onDismiss, shape = CircleShape, modifier = Modifier.weight(1f).height(48.dp)) { Text("稍后") }
                }
            } else {
                OutlinedButton(onClick = onDismiss, shape = CircleShape, modifier = Modifier.fillMaxWidth().height(48.dp)) { Text("关闭") }
            }
        }
    }
}

/** iOS power-off semantics for risky actions: a deliberate drag, never a stray tap. */
@Composable
private fun SlideToApprove(busy: Boolean, key: String, onApprove: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val haptics = LocalHapticFeedback.current
    val density = LocalDensity.current
    var trackWidth by remember { mutableIntStateOf(0) }
    val knob = 44.dp
    val knobPx = with(density) { knob.toPx() }
    val max = (trackWidth - knobPx - with(density) { 8.dp.toPx() }).coerceAtLeast(1f)
    var drag by remember(key) { mutableFloatStateOf(0f) }
    var fired by remember(key) { mutableStateOf(false) }
    val shown by animateFloatAsState(if (fired) max else drag, spring(dampingRatio = .8f, stiffness = 500f), label = "slide")
    Box(
        Modifier.fillMaxWidth().height(56.dp).clip(CircleShape).background(colors.surfaceContainer)
            .onSizeChanged { trackWidth = it.width }.testTag("slide-to-approve")
            .semantics(mergeDescendants = true) {
                contentDescription = "滑动以允许高风险操作"
                customActions = listOf(CustomAccessibilityAction("允许本次") { if (!busy) { fired = true; onApprove() }; true })
            },
        contentAlignment = Alignment.CenterStart,
    ) {
        Box(Modifier.matchParentSize().drawBehind {
            drawRect(Brush.horizontalGradient(listOf(Lumen.Error.copy(alpha = .18f), Lumen.Approve.copy(alpha = .10f))),
                size = androidx.compose.ui.geometry.Size(shown + knobPx + 4.dp.toPx(), size.height))
        })
        Text(if (busy) "正在提交" else "滑动以允许", Modifier.align(Alignment.Center).graphicsLayer { alpha = 1f - (shown / max).coerceIn(0f, 1f) },
            style = MaterialTheme.typography.labelLarge, color = colors.onSurfaceVariant)
        Box(
            Modifier.padding(4.dp).offset { IntOffset(shown.roundToInt(), 0) }.size(knob).clip(CircleShape).background(Lumen.Error)
                .pointerInput(key, busy) {
                    if (busy) return@pointerInput
                    detectHorizontalDragGestures(
                        onDragEnd = {
                            if (drag >= max * .92f) { fired = true; haptics.performHapticFeedback(HapticFeedbackType.LongPress); onApprove() } else drag = 0f
                        },
                        onDragCancel = { drag = 0f },
                    ) { change, delta -> change.consume(); drag = (drag + delta).coerceIn(0f, max) }
                },
            contentAlignment = Alignment.Center,
        ) { AppGlyph(GlyphName.ChevronRight, tint = Color.White, modifier = Modifier.size(18.dp)) }
    }
}
