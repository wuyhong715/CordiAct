package com.cordiact.app.experience.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.cordiact.core.conversation.ModelId
import com.cordiact.core.security.PermissionPreset
import kotlinx.coroutines.launch

@Composable
internal fun SecuritySettingsPage(vm: SettingsViewModel, privilege: Boolean, onState: (Boolean, Boolean) -> Unit, onBack: () -> Unit) {
    val facade = vm.facade.security
    if (facade == null) { Text("安全设置不可用"); return }
    val models by vm.snapshot.collectAsState()
    var state by remember { mutableStateOf<SecuritySettingsSnapshot?>(null) }
    var preset by remember { mutableStateOf(PermissionPreset.WORKSPACE_ONLY) }
    var reviewer by remember { mutableStateOf<ModelId?>(null) }
    var root by remember { mutableStateOf(false) }
    var advanced by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var failure by remember { mutableStateOf<String?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }
    var confirm by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    fun applySnapshot(value: SecuritySettingsSnapshot) {
        state = value; preset = value.configuredPreset; reviewer = value.reviewer
        root = value.root; advanced = value.advanced
    }
    fun reload(workspace: String? = state?.workspaceId) {
        if (busy) return
        busy = true; failure = null
        scope.launch {
            try { applySnapshot(facade.snapshot(workspace)) }
            catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (e: Exception) { failure = settingsError(e) }
            finally { busy = false }
        }
    }
    LaunchedEffect(Unit) { reload() }
    val dirty = state?.let { if (privilege) root != it.root || advanced != it.advanced
        else preset != it.configuredPreset || (preset == PermissionPreset.AUTO_APPROVAL && reviewer != it.reviewer) } ?: false
    SideEffect { onState(dirty, busy) }
    DisposableEffect(Unit) { onDispose { onState(false, false) } }
    fun save() {
        val current = state ?: return
        if (busy) return
        busy = true; failure = null; notice = null
        scope.launch {
            try {
                val receipt = if (privilege) facade.setPrivilege(current.privilegeRevision, root, advanced)
                    else facade.setPermission(current.workspaceId, current.revision, preset, reviewer)
                applySnapshot(facade.snapshot(current.workspaceId))
                notice = if (receipt.auditRecorded) "已生效 · 旧执行上下文将失效，需重新提交"
                    else "设置已生效，但审计记录失败；请检查审计存储"
            } catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (e: Exception) { failure = settingsError(e) }
            finally { busy = false }
        }
    }
    Column(Modifier.fillMaxWidth().padding(8.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        TextButton(onClick = onBack, enabled = !busy) { Text("← 返回设置") }
        Text(if (privilege) "特权执行" else "权限档位", style = MaterialTheme.typography.headlineSmall)
        state?.let { current ->
            if (current.pendingMigrationAudit > 0) Text("旧审核人迁移已保存，但 ${current.pendingMigrationAudit} 项迁移审计待补写；刷新会重试。", color = MaterialTheme.colorScheme.error)
            Text(if (privilege) "全局开关 · 仍受调用工作区权限约束" else "按工作区生效")
            current.workspaces.forEach { workspace ->
                TextButton(enabled = !busy && !dirty, onClick = { reload(workspace.id) }) {
                    Text((if (workspace.id == current.workspaceId) "✓ " else "") + workspace.name)
                }
            }
            if (dirty) Text("请先保存或放弃修改，再切换工作区")
            if (privilege) {
                Text("仅完全权限工作区可执行 Root / 高级命令；自动审批档不允许。")
                if (current.effectivePreset != PermissionPreset.FULL_ACCESS) Text("在本工作区不生效", color = MaterialTheme.colorScheme.error)
                SecurityToggle("允许 Root 通道", root, !busy) { root = it }
                SecurityToggle("允许高级命令（pm/am/settings 写）", advanced, !busy) { advanced = it }
                models.capabilities.find { it.id == "privilege" }?.readiness?.let { readiness ->
                    Text("通道状态：${readiness.summary}")
                    readiness.steps.forEach { step -> Text("${if (step.complete) "✓" else "○"} ${step.id} · ${step.summary}", style = MaterialTheme.typography.bodySmall) }
                }
                Text("通道状态与授权操作请到连接与能力 → 特权命令；策略开关不代表通道已授权。")
                Text("当前修订 r${current.privilegeRevision}")
            } else {
                PermissionPreset.entries.forEach { option ->
                    OutlinedButton(onClick = { preset = option }, enabled = !busy, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
                        Text((if (preset == option) "◉ " else "○ ") + permissionTitle(option))
                    }
                }
                Text(permissionDescription(preset))
                if (current.reviewerInvalid) Text("审核人引用失效或需重选；当前实际按仅工作区执行", color = MaterialTheme.colorScheme.error)
                if (preset == PermissionPreset.AUTO_APPROVAL) {
                    Text("独立审核人（不能与对话默认模型相同）")
                    Text("当前：${reviewer?.let { "${it.modelId} · ${it.providerId}" } ?: "未选择"}")
                    models.models.filter { it.id != models.conversationDefault }.forEach { model ->
                        TextButton(enabled = !busy, onClick = { reviewer = model.id }) { Text((if (reviewer == model.id) "✓ " else "") + model.id.modelId + " · " + model.id.providerId) }
                    }
                    Text("逐会话仍会检查：审核人与实际对话模型相同则退回逐次审批。")
                }
                Text("凭据读写始终逐次审批（自动审批档可交独立审核模型）；默认不跨工作区。")
                Text("当前修订 r${current.revision} · 实际生效：${permissionTitle(current.effectivePreset)}")
            }
            val reviewerValid = reviewer != null && reviewer != models.conversationDefault && models.models.any { it.id == reviewer }
            Button(enabled = !busy && dirty && (privilege || preset != PermissionPreset.AUTO_APPROVAL || reviewerValid),
                onClick = {
                    val dangerous = if (privilege) (root && !current.root) || (advanced && !current.advanced)
                        else preset == PermissionPreset.FULL_ACCESS && current.configuredPreset != preset
                    if (dangerous) confirm = true else save()
                }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(if (busy) "保存中…" else "保存") }
            TextButton(onClick = { applySnapshot(current); reload(current.workspaceId) }, enabled = !busy) { Text("放弃修改并刷新") }
        }
        if (state == null && !busy) TextButton(onClick = { reload() }) { Text("重新加载") }
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        failure?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        notice?.let { Text(it) }
    }
    if (confirm) SecurityDangerConfirmation(
        if (privilege) "将开启所选全局特权开关。仅完全权限工作区可生效；可回本页关闭。" else "完全权限允许当前工作区和设备存储读写、任意 Shell；无法证明 Shell 隔离。可切回仅工作区撤销。",
        onDismiss = { confirm = false }, onConfirm = { confirm = false; save() })
}

@Composable
private fun SecurityToggle(title: String, checked: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(title, Modifier.weight(1f).padding(end = 12.dp))
        Switch(checked, onChange, enabled = enabled)
    }
}

@Composable
private fun SecurityDangerConfirmation(impact: String, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    var agreed by remember { mutableStateOf(false) }
    var seconds by remember { mutableIntStateOf(3) }
    LaunchedEffect(Unit) { while (seconds > 0) { kotlinx.coroutines.delay(1000); seconds-- } }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("确认危险权限变更") },
        text = { Column {
            Text(impact)
            Row { Checkbox(agreed, { agreed = it }); Text("我了解风险", Modifier.padding(top = 12.dp)) }
        } },
        confirmButton = { TextButton(onClick = onConfirm, enabled = agreed && seconds == 0) { Text(if (seconds > 0) "确认（${seconds}s）" else "确认并保存") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } })
}

private fun permissionTitle(preset: PermissionPreset): String = when (preset) {
    PermissionPreset.READ_ONLY -> "只读"
    PermissionPreset.WORKSPACE_ONLY -> "仅工作区"
    PermissionPreset.FULL_ACCESS -> "完全权限"
    PermissionPreset.AUTO_APPROVAL -> "自动审批"
}
private fun permissionDescription(preset: PermissionPreset): String = when (preset) {
    PermissionPreset.READ_ONLY -> "只允许读取工作区文件，拒绝写入和执行。"
    PermissionPreset.WORKSPACE_ONLY -> "工作区内读写；越界与敏感操作逐次审批。"
    PermissionPreset.FULL_ACCESS -> "当前工作区 + 设备存储；开放任意 Shell。"
    PermissionPreset.AUTO_APPROVAL -> "独立审核模型代为审批；不开放 Root / 高级命令。"
}
