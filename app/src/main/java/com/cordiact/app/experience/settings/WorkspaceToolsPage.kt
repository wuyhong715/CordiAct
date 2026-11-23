package com.cordiact.app.experience.settings

import androidx.compose.foundation.layout.Row
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import com.cordiact.app.CordiActApplication
import com.cordiact.core.search.SEARCH_PLUGIN_ID
import com.cordiact.core.settings.WorkspaceToolPreferences
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Restrictions from the file are separate from tool capability grants and can never add one. */
@Composable
internal fun WorkspaceToolsPage(state: SettingsSnapshot, onBusy: (Boolean) -> Unit) {
    val workspace = state.capabilityWorkspaceId
    if (workspace.isBlank()) { Text("未选择工作区"); return }
    val app = LocalContext.current.applicationContext as CordiActApplication
    val store = remember(app) { WorkspaceToolPreferences(app.workspaceFiles) }
    val scope = rememberCoroutineScope()
    key(workspace) {
        var view by remember { mutableStateOf<WorkspaceToolPreferences.Snapshot?>(null) }
        var available by remember { mutableStateOf(emptyList<com.cordiact.core.tool.ToolDescriptor>()) }
        var busy by remember { mutableStateOf(false) }
        var message by remember { mutableStateOf<String?>(null) }
        suspend fun perform(action: suspend () -> Unit) {
            busy = true; onBusy(true); message = null
            try { action() }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { message = "无法读取或保存 .cordiact/tools.toml。配置无效时不开放工具；请修复后刷新。" }
            finally { busy = false; onBusy(false) }
        }
        suspend fun reload() {
            view = withContext(Dispatchers.IO) { store.read(workspace) }
            available = withContext(Dispatchers.IO) { app.tools.resolveEnabled(workspace) }
        }
        LaunchedEffect(workspace) { perform { reload() } }
        Text(".cordiact/tools.toml", style = MaterialTheme.typography.titleMedium)
        Text("只限制当前工作区的搜索和工具，不替代权限档位或连接授权。Agent 与此页共用文件。")
        val current = view
        if (current != null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(checked = current.searchDisabled, enabled = !busy, onCheckedChange = { stop ->
                    scope.launch { perform {
                        withContext(Dispatchers.IO) { store.setSearchDisabled(workspace, current.revision, stop) }
                        reload()
                    } }
                })
                Text("关闭工作区搜索（包括模型内置搜索）")
            }
            val ids = (available.filter { it.ownerPluginId != SEARCH_PLUGIN_ID }.map { it.id } + current.disabled).distinct().sorted()
            if (ids.isNotEmpty()) Text("限制单个工具", style = MaterialTheme.typography.labelLarge)
            ids.forEach { id ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(id in current.disabled, enabled = !busy, onCheckedChange = { stop ->
                        scope.launch { perform {
                            withContext(Dispatchers.IO) { store.setDisabled(workspace, current.revision, id, stop) }
                            reload()
                        } }
                    })
                    Text(id)
                }
            }
        }
        message?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        TextButton(enabled = !busy, onClick = { scope.launch { perform { reload() } } }) { Text("刷新工作区配置") }
    }
}
