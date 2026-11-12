package com.cordiact.app.experience.settings

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.cordiact.app.experience.settings.components.*
import com.cordiact.core.search.SearchMode
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException

@Composable
internal fun SearchSettingsPage(facade: SearchSettings?, workspaceId: String, dirty: (Boolean) -> Unit, onBusy: (Boolean) -> Unit) {
    if (facade == null || workspaceId.isBlank()) { Text("搜索设置不可用，请先选择工作区"); return }
    var state by remember(workspaceId) { mutableStateOf<SearchSettingsSnapshot?>(null) }
    var mode by remember(workspaceId) { mutableStateOf(SearchMode.AUTO) }
    var order by remember(workspaceId) { mutableStateOf(emptyList<String>()) }
    var enabled by remember(workspaceId) { mutableStateOf(emptySet<String>()) }
    var editing by remember { mutableStateOf<SearchProviderSummary?>(null) }
    var endpoint by remember { mutableStateOf("") }; var key by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }; var error by remember { mutableStateOf<String?>(null) }
    var result by remember { mutableStateOf(emptyList<String>()) }
    val scope = rememberCoroutineScope()
    SideEffect { onBusy(busy) }
    DisposableEffect(Unit) { onDispose { onBusy(false) } }
    fun work(action: suspend () -> Unit) {
        if (busy) return
        busy = true; error = null
        scope.launch {
            try { action(); state = facade.snapshot(workspaceId) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { error = "搜索设置操作未确认成功，请刷新并检查配置。输入保留；错误不包含 Key。" }
            finally { busy = false }
        }
    }
    LaunchedEffect(workspaceId) { work {
        val initial = facade.snapshot(workspaceId); state = initial; mode = initial.policy.mode
        val ids = initial.providers.filter { !it.native }.map { it.id }
        order = initial.policy.providerOrder + ids.filter { it !in initial.policy.providerOrder }
        enabled = initial.policy.enabledProviders ?: ids.toSet()
    } }
    Text("模式和优先顺序作用于工作区：$workspaceId。Key 和地址作用于所有工作区。", style = MaterialTheme.typography.bodySmall)
    if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
    error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    state?.let { snapshot ->
        Text("本月 ${snapshot.monthlyCalls} 次计费调用 · 本地抓取${if (snapshot.localFetch) "已启用（SSRF 防护）" else "已关闭"}")
        if (editing == null) {
            listOf(SearchMode.AUTO to "自动：原生优先，受限回落第三方", SearchMode.TOOL to "仅第三方", SearchMode.OFF to "关闭").forEach { (value, label) ->
                Row { RadioButton(mode == value, enabled = !busy, onClick = { mode = value; dirty(true) }); Text(label) }
            }
            if (mode == SearchMode.NATIVE) Text("当前为仅原生模式，可改为自动、仅第三方或关闭。")
            Text("第三方服务（上方优先；修改排序后请先保存再配置服务）", style = MaterialTheme.typography.titleMedium)
            order.forEachIndexed { index, id ->
                val provider = snapshot.providers.firstOrNull { it.id == id } ?: return@forEachIndexed
                ElevatedCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp)) {
                        Row {
                            Checkbox(id in enabled, enabled = !busy, onCheckedChange = { yes -> enabled = if (yes) enabled + id else enabled - id; dirty(true) })
                            Column(Modifier.weight(1f)) { Text(id); Text(if (provider.keySet) "Key 已配置" else if (id == "searxng") "实例地址服务" else "未配置 Key", style = MaterialTheme.typography.bodySmall) }
                            TextButton(enabled = !busy && mode == snapshot.policy.mode && order == (snapshot.policy.providerOrder + snapshot.providers.map { it.id }.filter { it !in snapshot.policy.providerOrder }) && enabled == (snapshot.policy.enabledProviders ?: snapshot.providers.map { it.id }.toSet()), onClick = { editing = provider; endpoint = provider.endpoint; key = ""; result = emptyList() }) { Text("配置") }
                        }
                        Row {
                            TextButton(enabled = !busy && index > 0, onClick = { order = order.toMutableList().apply { add(index - 1, removeAt(index)) }; dirty(true) }) { Text("上移") }
                            TextButton(enabled = !busy && index < order.lastIndex, onClick = { order = order.toMutableList().apply { add(index + 1, removeAt(index)) }; dirty(true) }) { Text("下移") }
                        }
                    }
                }
            }
            Button(enabled = !busy, onClick = { work {
                facade.savePolicy(workspaceId, snapshot.policy.copy(mode = mode, providerOrder = order, enabledProviders = enabled, searchProviderId = null))
                dirty(false)
            } }) { Text("保存模式与排序") }
        } else {
            val provider = editing!!
            Text("${provider.id} · 全局配置", style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(endpoint, { endpoint = it; dirty(true) }, label = { Text("服务地址") }, enabled = !busy, modifier = Modifier.fillMaxWidth())
            Text(if (provider.keySet) "Key 已设置，不回显；输入新 Key 可更换。" else "Key 未设置（SearXNG 可留空）")
            SecretField(key, { key = it; dirty(true) }, !busy)
            Button(enabled = !busy && endpoint.isNotBlank(), onClick = { work {
                facade.saveProvider(provider.id, endpoint, key.takeIf { it.isNotBlank() }); key = ""; dirty(false)
                editing = facade.snapshot(workspaceId).providers.first { it.id == provider.id }
            } }) { Text("保存服务配置") }
            TextButton(enabled = !busy, onClick = { work { facade.clearKey(provider.id); editing = facade.snapshot(workspaceId).providers.first { it.id == provider.id } } }) { Text("清除 Key") }
            TextButton(enabled = !busy, onClick = { work { result = facade.test(workspaceId, provider.id) } }) { Text("测试搜索（会消耗服务额度）") }
            result.forEach { Text(it) }
            TextButton(enabled = !busy && key.isBlank() && endpoint == snapshot.providers.first { it.id == provider.id }.endpoint, onClick = { editing = null; dirty(false) }) { Text("返回服务列表") }
        }
    }
}
