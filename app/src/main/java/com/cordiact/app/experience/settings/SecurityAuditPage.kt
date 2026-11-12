package com.cordiact.app.experience.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.clickable
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.cordiact.app.CordiActApplication
import com.cordiact.app.security.CredentialVaultRuntime
import com.cordiact.core.security.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch

/** Read-only secret-free projection; no credential executor or plaintext getter reaches UI. */
@Composable
fun SecurityAuditPage(app: CordiActApplication) {
    val scope = rememberCoroutineScope()
    var exportPending by rememberSaveable { mutableStateOf(false) }
    var exportNotice by remember { mutableStateOf<String?>(null) }
    var exportSnapshot by remember { mutableStateOf<List<AuditEvent>?>(null) }
    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri == null) {
            exportPending = false
            exportSnapshot = null
        } else if (exportSnapshot == null) {
            // Activity/process recreation lost the in-memory window: never silently export an empty replacement.
            exportPending = false
            exportNotice = "页面已重建，待导出结果未保留；请重新筛选导出。目标文件尚未写入。"
        } else {
            val captured = exportSnapshot.orEmpty().toList()
            scope.launch {
                try {
                    val receipt = withContext(Dispatchers.IO) {
                        com.cordiact.core.settings.AuditExport(app.credentialVaultRuntime.database).write(captured) {
                            checkNotNull(app.contentResolver.openOutputStream(uri, "wt")) { "AUDIT_EXPORT_DESTINATION_UNAVAILABLE" }
                        }
                    }
                    exportNotice = "已导出 ${receipt.count} 条脱敏记录" +
                        if (receipt.completionRecorded) " · 导出操作已记审计" else " · 文件已写入，但完成审计失败"
                } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                catch (_: Exception) { exportNotice = "导出失败；目标文件可能为空或不完整，未确认成功" }
                finally { exportPending = false; exportSnapshot = null }
            }
        }
    }
    LaunchedEffect(Unit) {
        if (exportPending && exportSnapshot == null) {
            exportPending = false
            exportNotice = "页面已重建；未保留待导出数据，请重新导出。"
        }
    }
    var pendingMigrationAudit by remember { mutableIntStateOf(0) }
    var rows by remember { mutableStateOf(emptyList<AuditEvent>()) }
    var failure by remember { mutableStateOf<String?>(null) }
    var category by remember { mutableStateOf<AuditCategory?>(null) }
    var workspace by remember { mutableStateOf("") }
    var workspaceFilter by remember { mutableStateOf<String?>(null) }
    var outcome by remember { mutableStateOf<AuditOutcome?>(null) }
    var days by remember { mutableStateOf<Int?>(null) }
    var selected by remember { mutableStateOf<AuditEvent?>(null) }
    var refresh by remember { mutableIntStateOf(0) }
    var loading by remember { mutableStateOf(false) }
    LaunchedEffect(category, workspaceFilter, outcome, days, refresh) {
        loading = true; failure = null
        try {
            val result = withContext(Dispatchers.IO) {
                val profiles = com.cordiact.app.security.SecurityProfileStore(app)
                runCatching { profiles.flushMigrationAudit(app.credentialVaultRuntime.database) }
                val pending = profiles.pendingMigrationAuditCount()
                pending to app.credentialVaultRuntime.database.query(AuditFilter(category = category, workspaceId = workspaceFilter, limit = 200, outcome = outcome, since = days?.let { java.time.Instant.now().minusSeconds(it.toLong() * 86400) }, newestFirst = true))
            }
            pendingMigrationAudit = result.first
            rows = result.second
        } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
        catch (_: Exception) { failure = "审计读取失败，未显示旧数据"; rows = emptyList() }
        finally { loading = false }
    }
    Text("审计日志（只读，匹配条件的最近 200 条）", style = MaterialTheme.typography.titleMedium)
    AuditSelect("类型", category?.name ?: "全部", listOf(null) + AuditCategory.entries, !loading) { category = it }
    AuditSelect("结果", outcome?.name ?: "全部", listOf(null) + AuditOutcome.entries, !loading) { outcome = it }
    AuditSelect("时间", days?.let { "最近${it}天" } ?: "全部", listOf(null, 1, 7, 30), !loading) { days = it }
    OutlinedTextField(workspace, { workspace = it }, label = { Text("工作区 ID（留空为全部）") }, modifier = Modifier.fillMaxWidth(), enabled = !loading)
    TextButton({ workspaceFilter = workspace.trim().ifBlank { null }; refresh++ }, enabled = !loading) { Text("应用工作区筛选") }
    Text("按筛选条件查询历史，最多显示最近 200 条匹配记录。", style = MaterialTheme.typography.bodySmall)
    TextButton({ refresh++ }, enabled = !loading) { Text("刷新") }
    if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
    failure?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    if (pendingMigrationAudit > 0) Text("有 $pendingMigrationAudit 项旧审核人迁移审计尚未补写；刷新会重试。", color = MaterialTheme.colorScheme.error)
    if (!loading && failure == null && rows.isEmpty()) Text("暂无审计记录")
    val cutoff = days?.let { java.time.Instant.now().minusSeconds(it.toLong() * 86400) }
    val visible = com.cordiact.core.settings.filterAuditWindow(rows, outcome, cutoff)
    if (!loading && rows.isNotEmpty() && visible.isEmpty()) Text("最近 200 条中无匹配记录")
    Text("导出仅包含当前显示的最多 200 条脱敏记录，不是完整历史；在系统保存窗口选择 Documents。")
    Button(enabled = !loading && !exportPending && failure == null && visible.isNotEmpty(), onClick = {
        exportSnapshot = visible.toList()
        exportPending = true
        exportNotice = null
        try { exporter.launch("CordiAct-audit-${System.currentTimeMillis()}.json") }
        catch (_: Exception) { exportPending = false; exportSnapshot = null; exportNotice = "无法打开系统保存窗口" }
    }) { Text(if (exportPending) "导出中…" else "导出当前脱敏结果") }
    exportNotice?.let { Text(it) }
    visible.forEach { event -> Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainerLowest) {
        Text("${event.occurredAt} · ${event.draft.category}\n${event.draft.outcome} · ${event.draft.reasonCode}", Modifier.fillMaxWidth().clickable { selected = event }.padding(16.dp), style = MaterialTheme.typography.bodySmall)
    } }
    selected?.let { event ->
        val json = remember(event) { org.json.JSONObject().apply {
            com.cordiact.core.settings.auditDetailFields(event).forEach { (key, value) -> put(key, value ?: org.json.JSONObject.NULL) }
        }.toString(2) }
        AlertDialog(onDismissRequest = { selected = null }, title = { Text("脱敏事件详情") }, text = { Text(json, style = MaterialTheme.typography.bodySmall) }, confirmButton = { TextButton({ selected = null }) { Text("关闭") } })
    }
}
@Composable
private fun <T> AuditSelect(label: String, selected: String, values: List<T?>, enabled: Boolean, choose: (T?) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        TextButton({ open = true }, enabled = enabled) { Text("$label：$selected") }
        DropdownMenu(open, { open = false }) { values.forEach { value ->
            DropdownMenuItem(text = { Text(value?.toString() ?: "全部") }, onClick = { choose(value); open = false })
        } }
    }
}
