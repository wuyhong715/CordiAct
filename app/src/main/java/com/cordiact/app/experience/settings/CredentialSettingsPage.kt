package com.cordiact.app.experience.settings

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.cordiact.core.settings.ManagedCredentialMeta
import kotlinx.coroutines.launch

@Composable
internal fun CredentialSettingsPage(vm: SettingsViewModel, onBusy: (Boolean) -> Unit) {
    val facade = vm.facade.credentialSettings
    if (facade == null) { Text("凭据管理不可用"); return }
    var rows by remember { mutableStateOf(emptyList<ManagedCredentialMeta>()) }
    var pending by remember { mutableStateOf<ManagedCredentialMeta?>(null) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    SideEffect { onBusy(busy) }
    DisposableEffect(Unit) { onDispose { onBusy(false) } }
    fun refresh() {
        if (busy) return
        busy = true; error = null
        scope.launch {
            try { rows = facade.list() }
            catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (_: Exception) { error = "无法读取凭据元数据，未读取任何明文" }
            finally { busy = false }
        }
    }
    LaunchedEffect(Unit) { refresh() }
    Text("已存凭据（全局）", style = MaterialTheme.typography.titleLarge)
    Text("列出模型、搜索、SSH 及 MCP 命名空间；不读取明文。页面不读取或显示明文。")
    Text("未知时间表示旧记录未采集，不代表从未使用。")
    TextButton(onClick = { refresh() }, enabled = !busy) { Text("刷新") }
    if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
    if (rows.isEmpty() && !busy && error == null) Text("尚无可管理的模型或搜索凭据")
    rows.forEach { item ->
        OutlinedCard(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("${if (item.namespace == "model") "模型" else "搜索"} · ${item.owner}")
                Text("更新时间：${credentialTime(item.updatedAtMillis)}")
                Text("最后使用：${credentialTime(item.lastUsedAtMillis)}")
                TextButton(onClick = { pending = item }, enabled = !busy) { Text("清除凭据") }
            }
        }
    }
    error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    notice?.let { Text(it) }
    pending?.let { item ->
        AlertDialog(onDismissRequest = { pending = null }, title = { Text("清除凭据？") },
            text = { Text("将清除 ${item.namespace} / ${item.owner} 的本机 Key，依赖它的请求会失败。提供商与模型配置保留；恢复需重新输入 Key，无法撤销。") },
            confirmButton = { TextButton(onClick = {
                pending = null; busy = true; error = null; notice = null
                scope.launch {
                    try {
                        val receipt = facade.clear(item)
                        notice = if (receipt.auditRecorded) "凭据已清除 · 已记录审计" else "凭据已清除，但完成审计失败"
                        rows = facade.list()
                        vm.reload()
                    } catch (e: kotlinx.coroutines.CancellationException) { throw e }
                    catch (e: Exception) { error = if (e.message == "CREDENTIAL_REVISION_CONFLICT")
                        "凭据已被其他操作更换；未清除新凭据，请刷新后重试" else "清除或刷新失败；请刷新检查实际状态" }
                    finally { busy = false }
                }
            }) { Text("确认清除") } },
            dismissButton = { TextButton(onClick = { pending = null }) { Text("取消") } })
    }
}
private fun credentialTime(value: Long?): String = value?.let {
    java.time.Instant.ofEpochMilli(it).atZone(java.time.ZoneId.systemDefault())
        .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
} ?: "未知（未采集）"
