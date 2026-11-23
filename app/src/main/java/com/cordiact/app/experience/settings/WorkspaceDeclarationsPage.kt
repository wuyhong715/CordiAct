package com.cordiact.app.experience.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.cordiact.app.CordiActApplication
import com.cordiact.app.experience.settings.components.SettingsGroup
import com.cordiact.core.settings.WorkspaceDeclarations
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

@Composable
internal fun WorkspaceDeclarationsPage(
    state: SettingsSnapshot,
    kind: WorkspaceDeclarations.Kind,
    onDirty: (Boolean) -> Unit,
    onBusy: (Boolean) -> Unit,
    onConnectionChanged: () -> Unit,
    openClient: () -> Unit,
) {
    val workspace = state.capabilityWorkspaceId
    if (workspace.isBlank()) { Text("正在读取工作区…"); return }
    val app = LocalContext.current.applicationContext as CordiActApplication
    val store = remember(app) { WorkspaceDeclarations(app.conversationCatalog.fileManager) }
    val scope = rememberCoroutineScope()
    val protocols = WorkspaceDeclarations.protocols(kind)
    key(workspace, kind) {
        var snapshot by remember { mutableStateOf<WorkspaceDeclarations.Snapshot?>(null) }
        var baseRevision by rememberSaveable { mutableStateOf<String?>(null) }
        var name by rememberSaveable { mutableStateOf("") }
        var endpoint by remember { mutableStateOf("") }
        var protocol by rememberSaveable { mutableStateOf(protocols.first()) }
        var dirty by rememberSaveable { mutableStateOf(false) }
        var busy by remember { mutableStateOf(false) }
        var message by remember { mutableStateOf<String?>(null) }
        var expanded by remember { mutableStateOf(false) }
        var reviewing by rememberSaveable { mutableStateOf<String?>(null) }
        var selected by remember { mutableStateOf(setOf<String>()) }
        var secret by remember { mutableStateOf("") }
        var progress by remember { mutableIntStateOf(0) }
        var pending by remember { mutableStateOf<com.cordiact.core.action.ApprovalRequest?>(null) }
        var responding by remember { mutableStateOf(false) }
        LaunchedEffect(workspace, busy) {
            if (busy) while (true) {
                pending = withContext(Dispatchers.IO) { app.approvalGateway.pendingRequests().firstOrNull {
                    it.toolName == "workspace_connection" && it.owner?.workspaceId == workspace
                } }
                kotlinx.coroutines.delay(180)
            } else pending = null
        }
        pending?.let { request ->
            AlertDialog(onDismissRequest = {}, title = { Text("需要独立审批") }, text = {
                Column {
                    Text(request.summary)
                    request.details["origin"]?.let { Text(it + request.details["endpointPath"].orEmpty()) }
                    request.details["selection"]?.let {
                        Text(it, modifier = Modifier.heightIn(max = 220.dp).verticalScroll(rememberScrollState()))
                    }
                    Text("仅作用于当前工作区，不修改客户端默认。")
                }
            }, confirmButton = {
                TextButton(enabled = !responding, onClick = {
                    responding = true
                    scope.launch { try {
                        withContext(Dispatchers.IO) { app.approvalGateway.respond(request.id, com.cordiact.core.action.ApprovalDecision(true)) }
                    } finally { responding = false } }
                }) { Text("批准这一项") }
            }, dismissButton = {
                TextButton(enabled = !responding, onClick = {
                    responding = true
                    scope.launch { try {
                        withContext(Dispatchers.IO) { app.approvalGateway.respond(request.id, com.cordiact.core.action.ApprovalDecision(false)) }
                    } finally { responding = false } }
                }) { Text("拒绝") }
            })
        }
        suspend fun run(task: suspend () -> Unit) {
            busy = true; onBusy(true); message = null
            try { task() } catch (e: CancellationException) { throw e }
            catch (_: Exception) { message = "操作未完成。可能未获批准、配置已改动或目标不可达；不会自动启用。" }
            finally { busy = false; onBusy(false) }
        }
        fun edited() {
            if (!dirty) baseRevision = snapshot?.revision
            dirty = true; onDirty(true)
        }
        LaunchedEffect(workspace, kind) {
            onDirty(dirty)
            run { snapshot = withContext(Dispatchers.IO) { store.read(workspace, kind) } }
        }
        Text("${state.workspaces.firstOrNull { it.id == workspace }?.name ?: "当前工作区"} · ${kind.path}", style = MaterialTheme.typography.labelLarge)
        Text("保存声明不联网。需要接入时，先独立审批出站与凭据，再探测、选择并授权当前工作区。进程重启后需要重新接入。")
        snapshot?.let { view ->
            SettingsGroup {
                if (view.drafts.isEmpty()) DeclarationSummary("还没有接入声明", "可由 Agent 编辑文件，也可在下方添加")
                view.drafts.forEach { draft ->
                    // Do not echo untrusted endpoint paths that may themselves contain a secret.
                    val host = java.net.URI(draft.endpoint).host
                    com.cordiact.app.experience.settings.components.SettingsRow(draft.name, "$host · ${draft.protocol}", onClick = {
                        if (!busy) { reviewing = draft.id; selected = emptySet(); secret = "" }
                    })
                }
            }
        }
        val draft = snapshot?.drafts?.firstOrNull { it.id == reviewing }
        if (draft != null) {
            val key = WorkspaceConnections.Key(workspace, kind, draft.id)
            val status = remember(key, progress, snapshot?.revision) { app.workspaceConnections.status(key) }
            Text("接入审查 · ${draft.name}", style = MaterialTheme.typography.titleMedium)
            if (status == null) {
                OutlinedTextField(secret, { secret = it }, enabled = !busy, singleLine = true,
                    label = { Text(if (kind == WorkspaceDeclarations.Kind.MCP) "Bearer 凭据（可选，仅本次）" else "API 密钥（仅本次）") },
                    visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
                Button(enabled = !busy && (kind == WorkspaceDeclarations.Kind.MCP || secret.isNotBlank()), onClick = {
                    val oneTime = secret.takeIf(String::isNotBlank); secret = ""
                    val revision = snapshot?.revision
                    scope.launch { run {
                        withContext(Dispatchers.IO) { app.workspaceConnections.probe(key, revision, draft, oneTime) }
                        progress++; selected = emptySet()
                        message = "探测成功。尚未授权任何工具或模型。"
                    } }
                }) { Text("申请审批并探测") }
            } else if (status.granted.isEmpty()) {
                Text(if (kind == WorkspaceDeclarations.Kind.MCP) "已探测 · 工具尚未授权" else "已探测 · 路由尚未启用")
                status.choices.forEach { c ->
                    androidx.compose.foundation.layout.Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        Checkbox(c.id in selected, { yes -> selected = if (yes) selected + c.id else selected - c.id }, enabled = !busy)
                        Text(c.label)
                    }
                }
                Button(enabled = !busy && selected.isNotEmpty(), onClick = {
                    val choice = selected
                    scope.launch { run {
                        withContext(Dispatchers.IO) { app.workspaceConnections.grant(key, choice) }
                        progress++; onConnectionChanged()
                        message = "已批准，仅对当前工作区生效。"
                    } }
                }) { Text(if (kind == WorkspaceDeclarations.Kind.MCP) "申请授权所选工具" else "申请启用所选模型") }
            } else {
                Text("当前工作区已授权 · ${status.granted.size} 项")
                status.granted.sorted().forEach { Text(it) }
            }
            if (status != null) TextButton(enabled = !busy, onClick = {
                scope.launch { run {
                    withContext(Dispatchers.IO) { app.workspaceConnections.revoke(key) }
                    progress++; onConnectionChanged(); selected = emptySet(); message = "已撤销。后续调用会被阻止。"
                } }
            }) { Text("撤销连接与授权") }
        }
        message?.let { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        TextButton(enabled = !busy, onClick = { scope.launch { run { snapshot = withContext(Dispatchers.IO) { store.read(workspace, kind) } } } }) { Text("刷新工作区文件") }
        if (snapshot != null) {
            OutlinedTextField(name, { name = it; edited() }, enabled = !busy, label = { Text("声明名称") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(endpoint, { endpoint = it; edited() }, enabled = !busy, label = { Text("HTTPS 地址（不含凭据）") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedButton(enabled = !busy, onClick = { expanded = true }) { Text(protocol) }
            DropdownMenu(expanded, onDismissRequest = { expanded = false }) {
                protocols.forEach { option -> DropdownMenuItem(text = { Text(option) }, onClick = { protocol = option; expanded = false; edited() }) }
            }
            Button(enabled = dirty && !busy && name.isNotBlank() && endpoint.isNotBlank(), onClick = {
                scope.launch { run {
                    withContext(Dispatchers.IO) { store.add(workspace, kind, baseRevision, "draft-${UUID.randomUUID()}", name.trim(), endpoint.trim(), protocol) }
                    dirty = false; onDirty(false); name = ""; endpoint = ""
                    snapshot = withContext(Dispatchers.IO) { store.read(workspace, kind) }
                    message = "声明已保存，尚未接入。"
                } }
            }) { Text("保存离线声明") }
            if (dirty) TextButton(enabled = !busy, onClick = { dirty = false; onDirty(false); name = ""; endpoint = "" }) { Text("放弃草稿") }
        }
        TextButton(enabled = !busy, onClick = openClient) { Text("打开客户端连接管理") }
    }
}

@Composable
private fun DeclarationSummary(title: String, subtitle: String) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp)) {
        Text(title, style = MaterialTheme.typography.bodyLarge)
        Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
