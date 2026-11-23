package com.cordiact.app.experience

import android.content.ActivityNotFoundException
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.cordiact.app.CordiActApplication
import com.cordiact.core.plugin.AuthoredPluginInstallationEntry
import com.cordiact.core.plugin.AuthoredPluginInstallationStatus
import com.cordiact.core.plugin.AuthoredPluginInstalledRecord
import com.cordiact.core.plugin.ExtensionPackageCapability
import com.cordiact.core.plugin.ExtensionPackageChange
import com.cordiact.core.plugin.ExtensionPackageManifest
import com.cordiact.core.plugin.ExtensionPackageRecord
import com.cordiact.core.plugin.ExtensionPackageStoreException
import com.cordiact.core.plugin.WorkspaceExtensionPackages
import com.cordiact.core.plugin.WorkspacePackageRuntime.Status
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible

@Composable
internal fun WorkspacePluginsRoute(app: CordiActApplication, workspaceId: String, workspaceName: String, onBack: () -> Unit) {
    val page = remember(app, workspaceId) { app.workspaceExtensionPackages.openPage(workspaceId) }
    DisposableEffect(page) { onDispose(page::close) }
    val scope = rememberCoroutineScope()
    var current by remember(page) { mutableStateOf<List<ExtensionPackageRecord>?>(null) }
    var pending by remember(page) { mutableStateOf<PackagePrompt?>(null) }
    var trials by remember(page) { mutableStateOf<List<AuthoredPluginInstallationEntry>?>(null) }
    var busy by remember(page) { mutableStateOf(false) }
    var status by remember(page) { mutableStateOf<String?>(null) }

    suspend fun load() {
        current = runInterruptible(Dispatchers.IO) { page.list() }
    }

    fun start(task: suspend () -> Unit) {
        if (busy) return
        busy = true
        status = null
        scope.launch {
            try { task() }
            catch (cancel: CancellationException) { throw cancel }
            catch (failure: Exception) { status = importError(failure) }
            finally { busy = false }
        }
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) start {
            pending = PackagePrompt.Import(runInterruptible(Dispatchers.IO) {
                require(uri.scheme == "content") { "PACKAGE_SOURCE_UNAVAILABLE" }
                requireNotNull(app.contentResolver.openInputStream(uri)) { "PACKAGE_SOURCE_UNAVAILABLE" }.use(page::preview)
            })
        }
    }
    fun previewTrial(record: AuthoredPluginInstalledRecord) {
        trials = null
        start {
            val read = { app.readSavedTrialPackage(workspaceId, record) }
            val preview = runInterruptible(Dispatchers.IO) { page.previewSavedTrial(read) }
            pending = PackagePrompt.Import(preview, read)
        }
    }
    LaunchedEffect(page) { start { load() } }
    val leave: () -> Unit = { page.close(); onBack() }
    BackHandler(onBack = leave)

    fun apply(prompt: PackagePrompt) {
        if (busy) return
        val change = try { prompt.approve(page) }
        catch (failure: Exception) {
            pending = null
            status = importError(failure)
            return
        }
        pending = null
        start {
            val result = runInterruptible(Dispatchers.IO) { change.commit() }
            // Re-read even when a commit outcome is unknown; never replay the write.
            val message = if ((prompt is PackagePrompt.Toggle || prompt is PackagePrompt.RetryStart) && result !is ExtensionPackageChange.Rejected) {
                val record = when (result) {
                    is ExtensionPackageChange.Applied -> result.record
                    is ExtensionPackageChange.Unchanged -> result.record
                    else -> error("Unexpected toggle result")
                }
                "${record.manifest.title}：${runtimeLabel(record, page.status(record))}。"
            } else changeMessage(result)
            current = null
            try { load(); status = message }
            catch (cancel: CancellationException) { throw cancel }
            catch (failure: Exception) { status = "$message ${importError(failure)}" }
        }
    }

    Surface(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.systemBars).padding(horizontal = 16.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = leave) { Text("返回") }
                Column(Modifier.weight(1f).padding(vertical = 12.dp)) {
                    Text("工作区插件", style = MaterialTheme.typography.titleLarge)
                    Text(workspaceName, style = MaterialTheme.typography.bodyMedium)
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(enabled = !busy, onClick = {
                    try { picker.launch("*/*") }
                    catch (_: ActivityNotFoundException) { status = "此设备没有可用的系统文件选择器。" }
                }) { Text("导入 JSON") }
                TextButton(enabled = !busy, onClick = { start { load() } }) { Text("刷新") }
            }
            TextButton(enabled = !busy, onClick = {
                start { trials = runInterruptible(Dispatchers.IO) { app.listSavedTrialsForPackage(workspaceId) } }
            }) { Text("从已保存试用版本转包") }
            Text("导入或转包只保存批准版本，不会运行插件。", style = MaterialTheme.typography.bodySmall)
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 8.dp))
            status?.let { Text(it, Modifier.padding(vertical = 8.dp), style = MaterialTheme.typography.bodyMedium) }
            val records = current
            if (records != null && records.isEmpty()) Text("这个工作区还没有已安装的声明包。", Modifier.padding(vertical = 16.dp))
            LazyColumn(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(records.orEmpty(), key = { it.manifest.id }) { record ->
                    val manifest = record.manifest
                    Card(Modifier.fillMaxWidth().padding(top = 8.dp)) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(manifest.title, style = MaterialTheme.typography.titleMedium)
                            val running = page.status(record)
                            val mayChangeVersion = !record.enableIntent && running == Status.INACTIVE
                            Text("${manifest.id} · ${manifest.version} · ${runtimeLabel(record, running)}")
                            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                TextButton(enabled = !busy, onClick = { pending = PackagePrompt.Details(record) }) { Text("详情") }
                                val turnOff = record.enableIntent || running == Status.CLEANUP_PENDING
                                TextButton(enabled = !busy, onClick = { pending = PackagePrompt.Toggle(record, !turnOff) }) {
                                    Text(if (running == Status.CLEANUP_PENDING) "重试清理" else if (turnOff) "停用" else "启用")
                                }
                                if (record.previous != null) TextButton(enabled = !busy && mayChangeVersion, onClick = { pending = PackagePrompt.Rollback(record) }) { Text("回滚") }
                                TextButton(enabled = !busy && mayChangeVersion, onClick = { pending = PackagePrompt.Remove(record) }) { Text("移除") }
                            }
                            if (running == Status.FAILED) {
                                TextButton(enabled = !busy, onClick = { pending = PackagePrompt.RetryStart(record) }) { Text("重试启动") }
                            }
                            if (!mayChangeVersion) Text("更换、回滚或移除版本前请先停用。", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }
    }
    trials?.let { entries -> SavedTrialPicker(entries, onDismiss = { trials = null }, onSelect = ::previewTrial) }
    pending?.let { prompt ->
        WorkspacePackagePrompt(prompt, onDismiss = { pending = null }, onConfirm = { apply(prompt) })
    }
}

@Composable
private fun SavedTrialPicker(entries: List<AuthoredPluginInstallationEntry>, onDismiss: () -> Unit, onSelect: (AuthoredPluginInstalledRecord) -> Unit) {
    AlertDialog(onDismissRequest = onDismiss, title = { Text("选择已保存试用版本") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState())) {
            if (entries.isEmpty()) Text("此工作区没有已保存试用版本。可先在会话插件中保存批准版本。")
            entries.forEach { entry ->
                val record = entry.record
                if (entry.status == AuthoredPluginInstallationStatus.READY && record != null) {
                    TextButton(onClick = { onSelect(record) }) { Text("预览 ${record.title}") }
                } else Text("${entry.id}：无法校验，不可转包")
            }
        }
    }, confirmButton = { TextButton(onClick = onDismiss) { Text("取消") } })
}

internal sealed interface PackagePrompt {
    val title: String
    val action: String? get() = null
    val allowed: Boolean get() = action != null
    fun approve(page: WorkspaceExtensionPackages.Page): WorkspaceExtensionPackages.Change = error("Not a mutation")

    data class Import(
        val preview: WorkspaceExtensionPackages.Preview,
        val readSavedTrial: (() -> com.cordiact.core.plugin.ExtensionPackageArtifact)? = null,
    ) : PackagePrompt {
        override val title = when {
            preview.fromSavedTrial -> "预览试用转包"
            preview.current == null -> "预览待安装版本"
            else -> "预览已安装包的候选版本"
        }
        override val action = if (preview.current == null) "确认安装" else "确认更换"
        override val allowed get() = preview.canInstall && preview.current?.enableIntent != true
        override fun approve(page: WorkspaceExtensionPackages.Page) =
            if (readSavedTrial == null) page.approveInstall(preview) else page.approveSavedTrial(preview, readSavedTrial)
    }
    data class Details(val record: ExtensionPackageRecord) : PackagePrompt { override val title = "已安装版本" }
    data class Toggle(val record: ExtensionPackageRecord, val enable: Boolean) : PackagePrompt {
        override val title = if (enable) "在此工作区启用" else "停用工作区插件"
        override val action = if (enable) "确认启用" else "确认停用"
        override fun approve(page: WorkspaceExtensionPackages.Page) = page.approveEnable(record, enable)
    }
    data class RetryStart(val record: ExtensionPackageRecord) : PackagePrompt {
        override val title = "重试启动此版本"
        override val action = "确认重试启动"
        override fun approve(page: WorkspaceExtensionPackages.Page) = page.approveRetryStart(record)
    }
    data class Rollback(val record: ExtensionPackageRecord) : PackagePrompt {
        override val title = "回滚安装版本"
        override val action = "确认回滚"
        override fun approve(page: WorkspaceExtensionPackages.Page) = page.approveRollback(record)
    }
    data class Remove(val record: ExtensionPackageRecord) : PackagePrompt {
        override val title = "移除安装记录"
        override val action = "确认移除"
        override fun approve(page: WorkspaceExtensionPackages.Page) = page.approveRemoval(record)
    }
}

@Composable
internal fun WorkspacePackagePrompt(prompt: PackagePrompt, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(prompt.title) },
        text = {
            SelectionContainer {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    when (prompt) {
                        is PackagePrompt.Import -> {
                            prompt.preview.current?.let { Text("当前保存版本：${it.manifest.version}（${it.manifest.sha256}）") }
                            ManifestDetails(prompt.preview.manifest)
                            if (prompt.preview.fromSavedTrial) {
                                Text("转包保留原试用版本，不会更改会话运行状态。之后启用新包时使用新的工具 ID。")
                                prompt.preview.manifest.tools.forEach { tool ->
                                    val id = prompt.preview.manifest.id
                                    Text("extension.authored.$id.${tool.name} → extension.pkg.$id.${tool.name}")
                                }
                            }
                            Text(when {
                                !prompt.preview.canInstall -> "工作区为只读，不能安装。"
                                !prompt.allowed -> "请先停用当前版本，再预览新版本。"
                                else -> "仅保存此版本，不运行插件。"
                            })
                        }
                        is PackagePrompt.Details -> ManifestDetails(prompt.record.manifest)
                        is PackagePrompt.Toggle -> {
                            ManifestDetails(prompt.record.manifest)
                            Text(if (prompt.enable) "仅在此工作区发布贡献，之后首次进入此工作区时会恢复。" else "撤回此工作区的工具和命令，不删除安装记录或历史。")
                        }
                        is PackagePrompt.RetryStart -> {
                            ManifestDetails(prompt.record.manifest)
                            Text("仅重试当前已选择启用的版本，不更改安装记录或回滚版本。")
                        }
                        is PackagePrompt.Rollback -> {
                            Text("当前：${prompt.record.manifest.version}（${prompt.record.manifest.sha256}）。回滚后不再保留本次回滚槽。")
                            ManifestDetails(requireNotNull(prompt.record.previous).manifest)
                        }
                        is PackagePrompt.Remove -> {
                            Text("移除 ${prompt.record.manifest.title} ${prompt.record.manifest.version} 及其回滚版本。不删除来源文件和历史对话。")
                            Text(prompt.record.manifest.sha256)
                        }
                    }
                }
            }
        },
        confirmButton = {
            if (prompt.action == null) TextButton(onClick = onDismiss) { Text("关闭") }
            else TextButton(onClick = onConfirm, enabled = prompt.allowed) { Text(requireNotNull(prompt.action)) }
        },
        dismissButton = { if (prompt.action != null) TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@Composable
private fun ManifestDetails(manifest: ExtensionPackageManifest) {
    Text("${manifest.title} · ${manifest.version}", style = MaterialTheme.typography.titleSmall)
    Text(manifest.id)
    Text(manifest.description)
    Text("SHA-256：${manifest.sha256}")
    Text("请求能力：${manifest.capabilities.joinToString("、") {
        when (it) {
            ExtensionPackageCapability.TOOL_PUBLISH -> "提供模型工具"
            ExtensionPackageCapability.COMMAND_PUBLISH -> "提供斜杠命令"
        }
    }}")
    manifest.tools.forEach { Text("工具 ${it.name}：${it.operation?.name ?: it.text}") }
    manifest.commands.forEach { Text("命令 /${it.name}：${it.text}") }
    if (manifest.tools.isNotEmpty()) Text("启用并调用后，工具结果可能随对话发送给所选模型。")
}

private fun runtimeLabel(record: ExtensionPackageRecord, status: Status): String = when (status) {
    Status.STARTING -> "正在启动"
    Status.ACTIVE -> "运行中"
    Status.FAILED -> "已选择启用，启动失败"
    Status.CLEANUP_PENDING -> "已撤回，清理待重试"
    Status.INACTIVE -> if (record.enableIntent) "已选择启用，未运行" else "已安装，未启用"
}

private fun importError(failure: Exception): String = when (failure.message) {
    "PACKAGE_CONFIRMATION_EXPIRED" -> "确认已失效，请重新预览后确认。"
    "PACKAGE_WORKSPACE_UNAVAILABLE" -> "此工作区已不可用。"
    "PACKAGE_WORKSPACE_READ_ONLY" -> "工作区为只读，不能修改已安装版本。"
    "PACKAGE_ID_RESERVED" -> "该包 ID 由内置插件保留。"
    "PACKAGE_STOP_BEFORE_CHANGE" -> "请先停用并完成清理，再更换、回滚或移除版本。"
    "PACKAGE_RETRY_UNAVAILABLE" -> "当前版本已不是可重试状态，请刷新清单。"
    else -> when (failure) {
        is ExtensionPackageStoreException -> "无法读取已安装版本，未清空记录。"
        is com.cordiact.core.plugin.AuthoredPluginInstallationException,
        is com.cordiact.core.plugin.AuthoredPluginInstallationStoreException -> "已保存试用版本无法校验或已变化，请重新选择。"
        is IllegalArgumentException -> "不是受支持的声明包 JSON，或文件超出 32 KiB。"
        else -> "无法读取文件或处理插件请求。"
    }
}

private fun changeMessage(result: ExtensionPackageChange): String = when (result) {
    is ExtensionPackageChange.Applied -> "已保存 ${result.record.manifest.version}，未运行。"
    is ExtensionPackageChange.Unchanged -> "相同版本已经保存，无需更改。"
    is ExtensionPackageChange.ConsentRequired -> "未获得完整的贡献同意，未安装。"
    ExtensionPackageChange.Removed -> "已移除安装记录。"
    is ExtensionPackageChange.Rejected -> when (result.code) {
        ExtensionPackageChange.Code.STALE, ExtensionPackageChange.Code.NOT_FOUND -> "安装记录已变化，请重新预览。"
        ExtensionPackageChange.Code.CONFLICT -> "同一版本已存在，但文件内容不同。"
        ExtensionPackageChange.Code.VERSION_NOT_NEWER -> "候选版本比已安装版本旧。"
        ExtensionPackageChange.Code.NO_PREVIOUS -> "没有可回滚的版本。"
        ExtensionPackageChange.Code.QUOTA_EXCEEDED -> "此工作区的声明包数量已达上限。"
        ExtensionPackageChange.Code.COMMIT_UNKNOWN -> "提交结果未知，请对照当前清单，不要重试原确认。"
        ExtensionPackageChange.Code.INVALID_MANIFEST -> "该版本与当前应用不兼容。"
        ExtensionPackageChange.Code.STORE_FAILURE -> "无法访问插件存储。"
    }
}
