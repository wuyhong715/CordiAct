package com.cordiact.app.experience.settings

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.cordiact.app.CordiActApplication
import com.cordiact.app.statistics.AndroidModelUsageStore
import java.text.DateFormat
import java.time.LocalDate
import java.time.ZoneId
import java.util.Date
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
internal fun UsageSettingsPage(state: SettingsSnapshot) {
    val store = (LocalContext.current.applicationContext as CordiActApplication).modelUsageStore
    val health by store.health.collectAsState()
    var selected by rememberSaveable { mutableStateOf<String?>(null) }
    val workspace = selected?.let { id -> state.workspaces.find { it.id == id } }
        ?: state.workspaces.find { it.id == state.capabilityWorkspaceId }
    var days by rememberSaveable { mutableIntStateOf(7) }
    var size by rememberSaveable(workspace?.id, days) { mutableIntStateOf(20) }
    var expanded by remember { mutableStateOf(false) }
    var modelsOpen by rememberSaveable { mutableStateOf(true) }
    var refresh by remember { mutableIntStateOf(0) }
    val bounds = remember(days, health.revision, refresh) {
        val zone = ZoneId.systemDefault()
        val today = LocalDate.now(zone)
        today.minusDays(days.toLong() - 1).atStartOfDay(zone).toInstant().toEpochMilli() to
            today.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
    }
    val result by key(workspace?.id, days) {
        produceState<Result<AndroidModelUsageStore.Report>?>(null, health.revision, size, bounds, refresh) {
            val id = workspace?.id ?: return@produceState
            value = try {
                Result.success(withContext(Dispatchers.IO) { store.report(id, bounds.first, bounds.second, size) })
            } catch (cancel: CancellationException) { throw cancel }
            catch (failure: Exception) { Result.failure(failure) }
        }
    }
    Box {
        OutlinedButton(onClick = { expanded = true }, enabled = state.workspaces.isNotEmpty()) { Text(workspace?.name ?: "选择工作区") }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            state.workspaces.forEach { ws -> DropdownMenuItem(text = { Text(ws.name) }, onClick = { selected = ws.id; expanded = false }) }
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        listOf(1 to "今天", 7 to "7 天", 30 to "30 天").forEach { (value, label) ->
            FilterChip(selected = days == value, onClick = { days = value }, label = { Text(label) })
        }
    }
    Text("仅统计开始记录后的可观察模型调用，未上报的 Token 不视为 0。", style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant)
    if (health.writeFailed) Text("部分调用未能写入设备，统计可能不完整。", color = MaterialTheme.colorScheme.error)
    if (workspace == null) { Text("请选择要查看的工作区"); return }
    val current = result
    if (current == null) { LinearProgressIndicator(Modifier.fillMaxWidth()); return }
    val report = current.getOrElse {
        Text("暂时无法读取用量。", color = MaterialTheme.colorScheme.error)
        TextButton(onClick = { refresh++ }) { Text("重试") }
        return
    }
    if (report.total.calls == 0L) { Text("当前范围暂无调用记录"); return }
    UsageSection("概览") {
        val total = report.total
        Text("${total.calls} 次调用 · ${total.failed} 次失败 · ${total.cancelled} 次取消")
        Text("输入 ${total.input.display(total.calls)} · 输出 ${total.output.display(total.calls)} Token")
    }
    TextButton(onClick = { modelsOpen = !modelsOpen }) { Text(if (modelsOpen) "按模型收起" else "按模型展开") }
    if (modelsOpen) UsageSection("提供商与模型") {
        report.models.forEachIndexed { index, model ->
            if (index > 0) HorizontalDivider()
            Text("${model.provider} / ${model.model}", style = MaterialTheme.typography.titleSmall)
            Text("${model.totals.calls} 次 · 输入 ${model.totals.input.display(model.totals.calls)} · 输出 ${model.totals.output.display(model.totals.calls)}",
                style = MaterialTheme.typography.bodySmall)
        }
    }
    val time = remember { DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT) }
    UsageSection("最近调用") {
        report.calls.forEachIndexed { index, call ->
            if (index > 0) HorizontalDivider()
            val status = when (call.status) { "COMPLETED" -> "完成"; "FAILED" -> "失败"; else -> "取消" }
            Text("${call.provider} / ${call.model}", style = MaterialTheme.typography.titleSmall)
            Text("${time.format(Date(call.time))} · $status · 输入 ${call.input ?: "未知"} · 输出 ${call.output ?: "未知"}",
                style = MaterialTheme.typography.bodySmall)
        }
        if (report.more && size < 500) TextButton(onClick = { size += 20 }) { Text("加载更多") }
        else if (report.more) Text("仅显示最近 500 次调用；概览涵盖所选时间范围。", style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun UsageSection(title: String, body: @Composable ColumnScope.() -> Unit) {
    Text(title, style = MaterialTheme.typography.titleMedium)
    com.cordiact.app.experience.settings.components.SettingsPanel(body)
}
