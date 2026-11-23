package com.cordiact.app.experience

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.cordiact.app.experience.model.ToolPhase
import com.cordiact.core.action.ApprovalOwner
import com.cordiact.core.action.ApprovalRequest
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

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
                approvals > 0 && other > 0 -> "待审批 ${approvals + other} 项（本会话 $approvals · 其他 $other）"
                approvals > 0 -> "本会话待审批 $approvals 项"
                else -> "其他会话待审批 $other 项"
            }
            ApprovalDockPill(label = label, onClick = onApprovals)
        } else if (status != null) {
            TextButton(onClick = onTrace, modifier = Modifier.heightIn(min = 48.dp)) {
                AppGlyph(GlyphName.Trace, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(8.dp))
                Text(status)
            }
        }
    }
}

/** Slim hairline dock strip above composer for off-screen / cross-session pending approvals. */
@Composable
internal fun ApprovalDockPill(label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    Surface(
        onClick = onClick,
        shape = CircleShape,
        color = colors.surface,
        border = BorderStroke(1.dp, Lumen.Approve.copy(alpha = .5f)),
        modifier = modifier.heightIn(min = 38.dp).testTag("approval-dock"),
    ) {
        Row(
            Modifier.padding(horizontal = 14.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            AppGlyph(GlyphName.Shield, tint = Lumen.Approve, modifier = Modifier.size(15.dp))
            Text(label, style = MaterialTheme.typography.labelMedium, color = colors.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
            AppGlyph(GlyphName.ChevronRight, tint = colors.onSurfaceVariant, modifier = Modifier.size(13.dp))
        }
    }
}

/**
 * In-place restrained hairline approval card embedded directly in the active turn's stream.
 * Medium/Low risk: one-tap allow + deny. High/Critical risk: 0.8s hold-to-approve (no edge-back conflict).
 */
@Composable
internal fun InlineApprovalCard(
    request: ApprovalRequest,
    busy: Boolean,
    error: String?,
    onDecide: (Boolean) -> Unit,
    onExpandQueue: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    val high = request.riskLevel >= ApprovalRequest.RiskLevel.HIGH
    val riskAccent = if (high) colors.error else Lumen.Approve
    val riskText = when (request.riskLevel) {
        ApprovalRequest.RiskLevel.LOW -> "低风险"
        ApprovalRequest.RiskLevel.MEDIUM -> "中风险"
        ApprovalRequest.RiskLevel.HIGH -> "高风险"
        ApprovalRequest.RiskLevel.CRITICAL -> "严重风险"
    }
    var detailsOpen by remember(request.id) { mutableStateOf(false) }

    Surface(
        shape = RoundedCornerShape(14.dp),
        color = colors.surface,
        border = BorderStroke(1.dp, riskAccent.copy(alpha = .45f)),
        modifier = modifier.fillMaxWidth().testTag("inline-approval"),
    ) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.size(7.dp).clip(CircleShape).background(riskAccent))
                Text(
                    request.toolName,
                    style = MaterialTheme.typography.titleSmall.copy(fontFamily = FontFamily.Monospace),
                    color = colors.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    riskText,
                    style = MaterialTheme.typography.labelSmall,
                    color = riskAccent,
                    modifier = Modifier.border(1.dp, riskAccent.copy(alpha = .35f), CircleShape).padding(horizontal = 8.dp, vertical = 2.dp),
                )
            }
            SelectionContainer {
                Text(request.summary, style = MaterialTheme.typography.bodySmall, color = colors.onSurface)
            }
            if (request.details.isNotEmpty()) {
                val preview = remember(request.details, detailsOpen) {
                    val entries = request.details.entries.toList()
                    val slice = if (detailsOpen) entries else entries.take(3)
                    slice.joinToString("\n") { "${it.key}: ${it.value}" }
                }
                Column(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
                        .background(colors.surfaceContainerLow)
                        .border(0.5.dp, colors.outlineVariant, RoundedCornerShape(10.dp))
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    SelectionContainer {
                        Text(preview, style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace), color = colors.onSurfaceVariant)
                    }
                    if (request.details.size > 3) {
                        Text(
                            if (detailsOpen) "收起参数" else "展开其余 ${request.details.size - 3} 项参数",
                            style = MaterialTheme.typography.labelSmall,
                            color = colors.onSurfaceVariant,
                            modifier = Modifier.clickable { detailsOpen = !detailsOpen }.padding(top = 2.dp),
                        )
                    }
                }
            }
            error?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = colors.error) }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(
                    onClick = { onDecide(false) },
                    enabled = !busy,
                    shape = CircleShape,
                    border = BorderStroke(1.dp, colors.outlineVariant),
                    modifier = Modifier.height(42.dp),
                    contentPadding = PaddingValues(horizontal = 16.dp),
                ) {
                    Text("拒绝", style = MaterialTheme.typography.labelMedium, color = colors.error)
                }
                if (high) {
                    HoldToApproveButton(
                        busy = busy,
                        key = request.id,
                        onApprove = { onDecide(true) },
                        modifier = Modifier.weight(1f),
                    )
                } else {
                    Button(
                        onClick = { onDecide(true) },
                        enabled = !busy,
                        shape = CircleShape,
                        colors = ButtonDefaults.buttonColors(containerColor = colors.onSurface, contentColor = colors.surface),
                        modifier = Modifier.weight(1f).height(42.dp),
                    ) {
                        Text(if (busy) "正在提交" else "允许本次", style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (high) "高风险操作 · 仅对本次生效，长按确认" else "仅对本次请求生效",
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                if (onExpandQueue != null) {
                    Text(
                        "查看队列",
                        style = MaterialTheme.typography.labelSmall,
                        color = colors.onSurfaceVariant,
                        modifier = Modifier.clickable(onClick = onExpandQueue).padding(horizontal = 4.dp, vertical = 2.dp),
                    )
                }
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
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
        containerColor = colors.surface,
        modifier = Modifier.testTag("approval-sheet"),
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 24.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(34.dp).clip(CircleShape).background(colors.surfaceContainerLow).border(1.dp, colors.outlineVariant, CircleShape), contentAlignment = Alignment.Center) {
                    AppGlyph(GlyphName.Shield, tint = Lumen.Approve, modifier = Modifier.size(17.dp))
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text("待审批队列", Modifier.semantics { heading() }, style = MaterialTheme.typography.titleMedium)
                    Text(
                        if (requests.size > 1) "共 ${requests.size} 项 · 关闭仅稍后处理，不会拒绝" else "关闭本面板仅表示稍后处理，不会拒绝请求",
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.onSurfaceVariant,
                    )
                }
            }
            if (requests.size > 1) {
                Spacer(Modifier.height(12.dp))
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    requests.forEach { item ->
                        val on = item.id == selected
                        Surface(
                            onClick = { onSelect(item.id) },
                            shape = CircleShape,
                            color = if (on) colors.onSurface else colors.surface,
                            border = if (on) null else BorderStroke(1.dp, colors.outlineVariant),
                            modifier = Modifier.heightIn(min = 34.dp).semantics { this.selected = on },
                        ) {
                            Text(
                                item.toolName,
                                Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
                                style = MaterialTheme.typography.labelMedium.copy(fontFamily = FontFamily.Monospace),
                                color = if (on) colors.surface else colors.onSurface,
                                maxLines = 1,
                            )
                        }
                    }
                }
            }
            Spacer(Modifier.height(14.dp))
            Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (request == null) Text(errors[selected] ?: "该请求已处理或不再可用。", color = colors.onSurfaceVariant)
                if (request != null) {
                    val high = request.riskLevel >= ApprovalRequest.RiskLevel.HIGH
                    val risk = when (request.riskLevel) {
                        ApprovalRequest.RiskLevel.LOW -> "低风险"
                        ApprovalRequest.RiskLevel.MEDIUM -> "中风险"
                        ApprovalRequest.RiskLevel.HIGH -> "高风险"
                        ApprovalRequest.RiskLevel.CRITICAL -> "严重风险"
                    }
                    val tone = if (high) colors.error else if (request.riskLevel == ApprovalRequest.RiskLevel.MEDIUM) Lumen.Approve else colors.onSurfaceVariant
                    Surface(
                        shape = RoundedCornerShape(14.dp),
                        color = colors.surface,
                        border = BorderStroke(1.dp, colors.outlineVariant),
                    ) {
                        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(request.toolName, style = MaterialTheme.typography.titleSmall.copy(fontFamily = FontFamily.Monospace), modifier = Modifier.weight(1f))
                                Text(
                                    risk,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = tone,
                                    modifier = Modifier.border(1.dp, tone.copy(alpha = .4f), CircleShape).padding(horizontal = 9.dp, vertical = 3.dp),
                                )
                            }
                            Text(ownerLabel(request.owner), style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                            SelectionContainer { Text(request.summary, style = MaterialTheme.typography.bodyMedium) }
                        }
                    }
                    if (request.details.isNotEmpty()) Surface(
                        shape = RoundedCornerShape(14.dp),
                        color = colors.surfaceContainerLow,
                        border = BorderStroke(1.dp, colors.outlineVariant),
                    ) {
                        SelectionContainer {
                            Text(
                                request.details.entries.joinToString("\n") { "${it.key}: ${it.value}" },
                                Modifier.fillMaxWidth().padding(14.dp),
                                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                            )
                        }
                    }
                    Text("允许仅对本次请求生效", style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
                    errors[selected]?.let { Text(it, color = colors.error) }
                }
            }
            Spacer(Modifier.height(14.dp))
            if (request != null) {
                val busy = selected in deciding
                val dangerous = request.riskLevel >= ApprovalRequest.RiskLevel.HIGH
                if (dangerous) {
                    HoldToApproveButton(busy = busy, key = selected, onApprove = { onDecide(selected, true) }, modifier = Modifier.fillMaxWidth())
                } else {
                    Button(
                        onClick = { onDecide(selected, true) },
                        enabled = !busy,
                        shape = CircleShape,
                        colors = ButtonDefaults.buttonColors(containerColor = colors.onSurface, contentColor = colors.surface),
                        modifier = Modifier.fillMaxWidth().height(48.dp),
                    ) { Text(if (busy) "正在提交" else "允许本次", style = MaterialTheme.typography.labelLarge) }
                }
                Spacer(Modifier.height(8.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = { onDecide(selected, false) },
                        enabled = !busy,
                        shape = CircleShape,
                        border = BorderStroke(1.dp, colors.outlineVariant),
                        modifier = Modifier.weight(1f).height(44.dp),
                    ) { Text("拒绝", color = colors.error) }
                    OutlinedButton(
                        onClick = onDismiss,
                        shape = CircleShape,
                        border = BorderStroke(1.dp, colors.outlineVariant),
                        modifier = Modifier.weight(1f).height(44.dp),
                    ) { Text("稍后", color = colors.onSurface) }
                }
            } else {
                OutlinedButton(
                    onClick = onDismiss,
                    shape = CircleShape,
                    border = BorderStroke(1.dp, colors.outlineVariant),
                    modifier = Modifier.fillMaxWidth().height(44.dp),
                ) { Text("关闭", color = colors.onSurface) }
            }
        }
    }
}

/**
 * Ergonomic one-handed confirmation for HIGH/CRITICAL approvals: press and hold 800ms to approve,
 * releasing early cancels safely without conflicting with Android edge-back gestures.
 */
@Composable
internal fun HoldToApproveButton(
    busy: Boolean,
    key: String,
    onApprove: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    val haptics = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    var progress by remember(key) { mutableFloatStateOf(0f) }
    var holding by remember(key) { mutableStateOf(false) }
    var fired by remember(key) { mutableStateOf(false) }

    Box(
        modifier
            .height(46.dp)
            .clip(CircleShape)
            .background(colors.errorContainer.copy(alpha = .55f))
            .border(1.dp, colors.error.copy(alpha = .45f), CircleShape)
            .drawBehind {
                val fillW = size.width * (if (fired) 1f else progress)
                if (fillW > 0f) drawRect(colors.error, size = Size(fillW, size.height))
            }
            .testTag("hold-to-approve")
            .semantics(mergeDescendants = true) {
                contentDescription = "长按以允许高风险操作"
                customActions = listOf(CustomAccessibilityAction("允许本次") {
                    if (!busy && !fired) {
                        fired = true
                        onApprove()
                    }
                    true
                })
            }
            .pointerInput(key, busy) {
                if (busy) return@pointerInput
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    if (fired) return@awaitEachGesture
                    holding = true
                    val job = scope.launch {
                        val started = System.currentTimeMillis()
                        val holdMillis = 800L
                        while (holding && !fired) {
                            val elapsed = System.currentTimeMillis() - started
                            progress = (elapsed.toFloat() / holdMillis).coerceIn(0f, 1f)
                            if (elapsed >= holdMillis) {
                                fired = true
                                holding = false
                                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                onApprove()
                                break
                            }
                            delay(16)
                        }
                    }
                    waitForUpOrCancellation()
                    holding = false
                    job.cancel()
                    if (!fired) progress = 0f
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        val overFilled = fired || progress > 0.45f
        Text(
            text = when {
                busy -> "正在提交"
                holding -> "继续按住确认…"
                else -> "长按确认允许"
            },
            style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
            color = if (overFilled) Color.White else colors.error,
        )
    }
}
