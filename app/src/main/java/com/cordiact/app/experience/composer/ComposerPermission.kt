package com.cordiact.app.experience.composer

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.cordiact.app.CordiActApplication
import com.cordiact.app.experience.settings.SecuritySettingsFacade
import com.cordiact.app.experience.settings.SecuritySettingsSnapshot
import com.cordiact.app.experience.settings.settingsError
import com.cordiact.core.conversation.ModelId
import com.cordiact.core.security.PermissionPreset
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
internal fun ComposerPermission(workspaceId: String?, model: ModelId?, canEdit: Boolean, onSecurity: () -> Unit) = key(workspaceId) {
    val app = LocalContext.current.applicationContext as? CordiActApplication
    val facade = remember(app) { app?.let(::SecuritySettingsFacade) }
    val scope = rememberCoroutineScope()
    val canEditNow by rememberUpdatedState(canEdit)
    val modelNow by rememberUpdatedState(model)
    var snapshot by remember { mutableStateOf<SecuritySettingsSnapshot?>(null) }
    var expanded by remember { mutableStateOf(false) }
    var confirm by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var feedback by remember { mutableStateOf<String?>(null) }
    var rememberedReviewer by remember { mutableStateOf<ModelId?>(null) }
    suspend fun refresh(id: String) {
        val fresh = facade?.snapshot(id)?.takeIf { it.workspaceId == id }
        if (fresh?.reviewer != null) rememberedReviewer = fresh.reviewer
        snapshot = fresh
    }
    LaunchedEffect(workspaceId, expanded) {
        if (workspaceId != null && !busy) try { refresh(workspaceId) }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) { snapshot = null; feedback = settingsError(e) }
    }
    val current = snapshot
    val active = current?.effectivePreset
    val auto = current != null && active == PermissionPreset.AUTO_APPROVAL && model != null && current.reviewer != model
    val autoSelected = current?.configuredPreset == PermissionPreset.AUTO_APPROVAL
    val full = active == PermissionPreset.FULL_ACCESS
    val label = when (active) {
        PermissionPreset.READ_ONLY -> "只读"
        PermissionPreset.FULL_ACCESS -> "完全权限"
        PermissionPreset.WORKSPACE_ONLY, PermissionPreset.AUTO_APPROVAL -> "仅工作区"
        null -> "未读取"
    }
    val description = "权限：$label；自动审批${if (auto) "开启" else "关闭"}"
    val tint = when { full -> MaterialTheme.colorScheme.error; auto -> MaterialTheme.colorScheme.primary; else -> MaterialTheme.colorScheme.onSurfaceVariant }

    fun save(preset: PermissionPreset) {
        val id = workspaceId ?: return
        val security = facade ?: return
        val previous = snapshot ?: return
        if (!canEditNow || busy || previous.workspaceId != id) return
        val reviewer = previous.reviewer ?: rememberedReviewer
        if (preset == PermissionPreset.AUTO_APPROVAL && (previous.reviewerInvalid || modelNow == null || reviewer == null || reviewer == modelNow)) return
        busy = true; feedback = null
        scope.launch {
            try {
                val receipt = security.setPermission(id, previous.revision, preset, reviewer)
                refresh(id)
                feedback = when {
                    !receipt.auditRecorded -> "已生效，但审计记录失败，请检查安全设置。"
                    preset != PermissionPreset.AUTO_APPROVAL && previous.reviewer != null -> "已生效。审核模型绑定已按现有规则清除，重新开启会沿用上次选择。"
                    else -> "已生效"
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                feedback = settingsError(e)
                try { refresh(id) } catch (cancel: CancellationException) { throw cancel }
                catch (_: Exception) { snapshot = null }
            } finally { busy = false }
        }
    }

    Box {
        IconButton(onClick = { feedback = null; expanded = true }, enabled = workspaceId != null && facade != null,
            modifier = Modifier.size(48.dp).testTag("composer-permission").semantics { contentDescription = description }) {
            Canvas(Modifier.size(20.dp)) {
                val x = size.width / 24f; val y = size.height / 24f
                val shield = Path().apply {
                    moveTo(12*x, 3*y); lineTo(19*x, 6*y); lineTo(19*x, 11*y)
                    quadraticTo(19*x, 17*y, 12*x, 21*y); quadraticTo(5*x, 17*y, 5*x, 11*y)
                    lineTo(5*x, 6*y); close()
                }
                drawPath(shield, tint, style = Stroke(width = 1.5.dp.toPx()))
                drawLine(tint, Offset(9*x, 11.5f*y), Offset(11*x, 13.5f*y), 1.4.dp.toPx())
                drawLine(tint, Offset(11*x, 13.5f*y), Offset(15*x, 9.5f*y), 1.4.dp.toPx())
                if (auto || full) drawCircle(tint, 2.dp.toPx(), Offset(20*x, 4*y))
            }
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { if (!busy) expanded = false }, modifier = Modifier.widthIn(min = 200.dp, max = 260.dp)) {
            Text("权限与审批", Modifier.padding(horizontal = 14.dp, vertical = 6.dp), style = MaterialTheme.typography.labelLarge)
            val editable = current != null && canEdit && !busy
            val options = listOf(PermissionPreset.READ_ONLY to "只读", PermissionPreset.WORKSPACE_ONLY to "仅工作区", PermissionPreset.FULL_ACCESS to "完全权限")
            options.forEach { (preset, name) ->
                val selected = preset == active || (preset == PermissionPreset.WORKSPACE_ONLY && active == PermissionPreset.AUTO_APPROVAL)
                DropdownMenuItem(text = { Text(name, color = if (selected) MaterialTheme.colorScheme.primary else LocalContentColor.current) },
                    enabled = editable && !selected, onClick = { if (preset == PermissionPreset.FULL_ACCESS) { expanded = false; confirm = true } else save(preset) })
            }
            HorizontalDivider(Modifier.padding(horizontal = 12.dp))
            val reason = when {
                current == null -> "尚未读取权限。"
                !canEdit -> "请在当前回合结束后修改。"
                active != PermissionPreset.WORKSPACE_ONLY && active != PermissionPreset.AUTO_APPROVAL -> "仅工作区档位可开启。"
                current.reviewerInvalid || (current.reviewer == null && rememberedReviewer == null) -> "请先在安全设置中配置可用的审核模型。"
                model == null || model == (current.reviewer ?: rememberedReviewer) -> "审核模型须与当前对话模型不同。"
                else -> null
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("自动审批", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                Switch(checked = auto, onCheckedChange = { save(if (it) PermissionPreset.AUTO_APPROVAL else PermissionPreset.WORKSPACE_ONLY) },
                    enabled = editable && (reason == null || auto))
            }
            if (autoSelected && !auto) DropdownMenuItem(text = { Text("停用已配置的自动审批") }, enabled = editable, onClick = { save(PermissionPreset.WORKSPACE_ONLY) })
            Text(feedback ?: reason ?: "独立审核模型逐项判断，不扩大权限。",
                Modifier.padding(horizontal = 14.dp, vertical = 4.dp), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            DropdownMenuItem(text = { Text("安全设置") }, enabled = !busy, onClick = { expanded = false; onSecurity() })
        }
    }
    if (confirm) AlertDialog(onDismissRequest = { confirm = false }, title = { Text("切换到完全权限？") },
        text = { Text("权限范围将扩展到当前工作区与设备存储，并允许任意 Shell。切回「仅工作区」可撤销。") },
        confirmButton = { TextButton(enabled = canEdit && !busy, onClick = { confirm = false; expanded = true; save(PermissionPreset.FULL_ACCESS) }) { Text("确认切换") } },
        dismissButton = { TextButton(onClick = { confirm = false; expanded = true }) { Text("取消") } })
}