package com.cordiact.app.experience

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
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
    val label = when {
        approvals > 0 -> "待确认 $approvals 项"
        state.stopping -> "正在停止"
        state.running -> if (progress > 0) "正在运行 · 已完成 $progress 步" else "正在运行"
        else -> null
    }
    if (label == null && other == 0) return
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.Center) {
        if (label != null) TextButton(onClick = if (approvals > 0) onApprovals else onTrace, modifier = Modifier.heightIn(min = 48.dp)) {
            if (approvals > 0) AppGlyph(GlyphName.Warning, modifier = Modifier.size(16.dp))
            else AppGlyph(GlyphName.Trace, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(8.dp))
            Text(label)
        }
        if (other > 0 && approvals == 0) TextButton(onClick = onApprovals, modifier = Modifier.heightIn(min = 48.dp)) {
            Text("其他审批 $other 项")
        }
    }
}

@Composable
internal fun ApprovalDecisionDialog(
    requests: List<ApprovalRequest>, deciding: Set<String>, errors: Map<String, String>, selected: String,
    ownerLabel: (ApprovalOwner?) -> String,
    onSelect: (String) -> Unit, onDismiss: () -> Unit, onDecide: (String, Boolean) -> Unit,
) {
    val request = requests.firstOrNull { it.id == selected }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(dismissOnClickOutside = false)) {
        Surface(shape = MaterialTheme.shapes.large) {
            Column(Modifier.fillMaxWidth().padding(20.dp)) {
                Text("待处理审批", Modifier.semantics { heading() }, style = MaterialTheme.typography.titleLarge)
                Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (requests.size > 1) requests.forEach { item ->
                        Text("${item.toolName} · ${ownerLabel(item.owner)}", Modifier.fillMaxWidth().heightIn(min = 48.dp)
                            .selectable(selected = item.id == selected, onClick = { onSelect(item.id) }).padding(vertical = 10.dp),
                            color = if (item.id == selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
                    }
                    if (request == null) Text(errors[selected] ?: "该请求已处理或不再可用。")
                    if (request != null) {
                        Text(request.toolName, style = MaterialTheme.typography.titleMedium)
                        Text(ownerLabel(request.owner), style = MaterialTheme.typography.bodySmall)
                        val risk = when (request.riskLevel) {
                            ApprovalRequest.RiskLevel.LOW -> "低"
                            ApprovalRequest.RiskLevel.MEDIUM -> "中"
                            ApprovalRequest.RiskLevel.HIGH -> "高"
                            ApprovalRequest.RiskLevel.CRITICAL -> "严重"
                        }
                        Text("风险：$risk · 允许仅对本次请求生效", style = MaterialTheme.typography.labelMedium)
                        SelectionContainer { Text(request.summary) }
                        if (request.details.isNotEmpty()) SelectionContainer {
                            Text(request.details.entries.joinToString("\n") { "${it.key}: ${it.value}" }, style = MaterialTheme.typography.bodySmall)
                        }
                        errors[selected]?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    }
                }
                if (request != null) {
                    val busy = selected in deciding
                    FilledTonalButton(onClick = { onDecide(selected, true) }, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                        Text(if (busy) "正在提交" else "仅本次允许")
                    }
                    TextButton(onClick = { onDecide(selected, false) }, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("拒绝") }
                }
                TextButton(onClick = onDismiss, modifier = Modifier.align(Alignment.End)) { Text(if (request == null) "关闭" else "稍后") }
            }
        }
    }
}
