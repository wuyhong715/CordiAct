package com.cordiact.app.experience.settings

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.cordiact.core.mcp.McpToolDescriptor
import com.cordiact.app.experience.settings.components.SecretField
import com.cordiact.app.experience.settings.components.DangerConfirmDialog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
internal fun McpSettingsPage(facade: McpSettings?, dirty: (Boolean) -> Unit, onBusy: (Boolean) -> Unit) {
    if (facade == null) { Text("MCP 设置不可用"); return }
    var items by remember { mutableStateOf(emptyList<McpSummary>()) }
    var busy by remember { mutableStateOf(false) }
    var adding by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf("") }
    var url by remember { mutableStateOf("") }
    var header by remember { mutableStateOf("Authorization") }
    var secret by remember { mutableStateOf("") }
    var tools by remember { mutableStateOf<List<McpToolDescriptor>?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    val details = remember { mutableStateMapOf<String, List<McpToolDescriptor>>() }
    val statuses = remember { mutableStateMapOf<String, McpReconnectOutcome>() }
    var deleting by remember { mutableStateOf<McpSummary?>(null) }
    var editingAuth by remember { mutableStateOf<McpSummary?>(null) }
    var authHeader by remember { mutableStateOf("Authorization") }
    var authSecret by remember { mutableStateOf("") }
    var removeAuth by remember { mutableStateOf(false) }
    var confirmClear by remember { mutableStateOf(false) }
    var confirmCancel by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    SideEffect { onBusy(busy) }
    DisposableEffect(Unit) { onDispose { onBusy(false) } }
    fun work(action: suspend () -> Unit) {
        if (busy) return
        busy = true; message = null
        scope.launch {
            try { action(); items = facade.list() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { message = "MCP 操作未确认成功：检查地址、鉴权及连通性后重试；输入已保留。" }
            finally { busy = false }
        }
    }
    fun finishForm() {
        adding = false; editingAuth = null; secret = ""; authSecret = ""; tools = null
        removeAuth = false; confirmClear = false; dirty(false)
    }
    fun saveAuth() {
        val item = editingAuth ?: return
        work {
            val result = facade.updateAuthentication(item.name, if (removeAuth) null else authHeader, if (removeAuth) null else authSecret)
            details[item.name] = result; statuses.remove(item.name)
            finishForm(); message = "鉴权已更新 · 连接验证成功"
        }
    }
    LaunchedEffect(Unit) { work {} }
    Text("全局 · 仅 HTTPS 或 localhost HTTP；秘密仅进入凭据库。", style = MaterialTheme.typography.bodySmall)
    if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
    message?.let { Text(it) }
    val authItem = editingAuth
    when {
        authItem != null -> {
            Text("更新 ${authItem.name} 的鉴权", style = MaterialTheme.typography.titleMedium)
            Text("${authItem.url}\n${if (authItem.keySet) "凭据已设置，不回显" else "尚未设置凭据"}")
            Text("保存前将使用新鉴权测试连接。失败时保留原凭据；已关闭的服务器保持关闭。", style = MaterialTheme.typography.bodySmall)
            Row { Checkbox(removeAuth, { removeAuth = it; authSecret = ""; dirty(true) }, enabled = !busy); Text("改为无鉴权") }
            if (!removeAuth) {
                OutlinedTextField(authHeader, { authHeader = it; dirty(true) }, enabled = !busy, label = { Text("Header 名称") }, modifier = Modifier.fillMaxWidth())
                SecretField(authSecret, { authSecret = it; dirty(true) }, !busy, "Header 值（不回显已存凭据）")
            }
            Button(enabled = !busy && (removeAuth || (authHeader.isNotBlank() && authSecret.isNotBlank())), onClick = {
                if (removeAuth && authItem.keySet) confirmClear = true else saveAuth()
            }) { Text("验证并保存鉴权") }
            TextButton(enabled = !busy, onClick = { confirmCancel = true }) { Text("取消修改") }
        }
        adding -> {
            Text("新增 MCP 服务器", style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(name, { name = it; tools = null; dirty(true) }, label = { Text("名称") }, enabled = !busy, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(url, { url = it; tools = null; dirty(true) }, label = { Text("URL") }, enabled = !busy, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(header, { header = it; tools = null; dirty(true) }, label = { Text("Header 名称（留空为无鉴权）") }, enabled = !busy, modifier = Modifier.fillMaxWidth())
            SecretField(secret, { secret = it; tools = null; dirty(true) }, !busy, "Header 值（无鉴权时留空）")
            OutlinedButton(enabled = !busy && name.isNotBlank() && url.isNotBlank(), onClick = { work { tools = facade.probe(name, url, header, secret); message = "连接成功 · ${tools!!.size} 个工具" } }) { Text("连接并列出工具") }
            tools?.forEach { Text("${it.name} · ${it.description}") }
            Button(enabled = !busy && tools != null, onClick = { work { facade.install(name, url, header, secret); finishForm(); message = "服务器已安装" } }) { Text("确认安装") }
            TextButton(enabled = !busy, onClick = { confirmCancel = true }) { Text("取消新增") }
        }
        else -> {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(enabled = !busy, onClick = { adding = true; name = ""; url = ""; header = "Authorization"; secret = ""; tools = null }) { Text("添加服务器") }
                OutlinedButton(enabled = !busy, onClick = { work {} }) { Text("刷新") }
            }
            if (items.isEmpty() && !busy) Text("尚无 MCP 服务器。添加后可连接并预览工具，再确认安装。")
            items.forEach { item ->
                ElevatedCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(item.name, style = MaterialTheme.typography.titleMedium)
                        Text(item.url)
                        Text("${item.state} · ${item.toolCount} 个工具 · ${if (item.keySet) "鉴权已设置" else "无鉴权凭据"}")
                        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                            Switch(item.enabled, enabled = !busy, onCheckedChange = { enabled -> work { facade.setEnabled(item.name, enabled); statuses.remove(item.name); details.remove(item.name) } })
                            Text(if (item.enabled) "已启用" else "已关闭")
                        }
                        OutlinedButton(enabled = !busy, onClick = { work {
                            val outcome = facade.reconnect(item.name)
                            statuses[item.name] = outcome
                            if (outcome is McpReconnectOutcome.Connected) details[item.name] = outcome.tools else details.remove(item.name)
                        } }) { Text(if (item.enabled) "重新连接并查看工具" else "测试连接并查看工具（不启用）") }
                        when (val status = statuses[item.name]) {
                            is McpReconnectOutcome.Connected -> Text("连接成功 · ${status.tools.size} 个工具${if (!item.enabled) " · 未启用" else ""}")
                            is McpReconnectOutcome.Failed -> Text("连接失败 · ${status.reason.name}；检查地址、鉴权或网络后重试。", color = MaterialTheme.colorScheme.error)
                            null -> Unit
                        }
                        details[item.name]?.forEach { tool ->
                            Text(tool.name, style = MaterialTheme.typography.titleSmall)
                            if (tool.description.isNotBlank()) Text(tool.description, style = MaterialTheme.typography.bodySmall)
                        }
                        TextButton(enabled = !busy, onClick = { editingAuth = item; authHeader = "Authorization"; authSecret = ""; removeAuth = false; message = null }) { Text("更换 / 清除鉴权") }
                        TextButton(enabled = !busy, onClick = { deleting = item }) { Text("删除服务器") }
                    }
                }
            }
        }
    }
    if (confirmCancel) AlertDialog(onDismissRequest = { confirmCancel = false }, title = { Text("放弃修改？") }, text = { Text("未保存的输入将清除，已存配置不变。") },
        confirmButton = { TextButton({ confirmCancel = false; finishForm() }) { Text("放弃") } }, dismissButton = { TextButton({ confirmCancel = false }) { Text("继续编辑") } })
    if (confirmClear) DangerConfirmDialog("清除 MCP 鉴权？", "将验证服务器可在无鉴权下访问后，清除 ${authItem?.name.orEmpty()} 的凭据。", busy, { confirmClear = false }, { confirmClear = false; saveAuth() }, confirmLabel = "确认清除")
    deleting?.let { item -> DangerConfirmDialog("删除 MCP 服务器？", "将卸载 ${item.name} 并清除凭据，无法撤销。", busy, { deleting = null }, onConfirm = {
        work { facade.remove(item.name); details.remove(item.name); statuses.remove(item.name); deleting = null; message = "服务器及凭据已删除" }
    }) }
}
