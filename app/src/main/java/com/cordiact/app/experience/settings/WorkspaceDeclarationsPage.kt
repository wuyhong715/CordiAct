package com.cordiact.app.experience.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
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
        suspend fun run(task: suspend () -> Unit) {
            busy = true; onBusy(true); message = null
            try { task() } catch (e: CancellationException) { throw e }
            catch (_: Exception) { message = "操作未完成。请检查文件修订、作用域和 HTTPS 地址（不允许认证信息、查询参数或片段）。" }
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
        Text("这些是离线接入声明。用户和 Agent 共用文件；保存不联网、不读取凭据、不注册或授权资源。")
        snapshot?.let { view ->
            SettingsGroup {
                if (view.drafts.isEmpty()) DeclarationSummary("还没有接入声明", "可由 Agent 编辑文件，也可在下方添加")
                view.drafts.forEach { draft ->
                    // Do not echo untrusted endpoint paths that may themselves contain a secret.
                    val host = java.net.URI(draft.endpoint).host
                    DeclarationSummary(draft.name, "$host · ${draft.protocol} · 未启用")
                }
            }
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
