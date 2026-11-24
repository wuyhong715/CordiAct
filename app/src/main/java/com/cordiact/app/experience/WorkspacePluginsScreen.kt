package com.cordiact.app.experience

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.withResumed
import com.cordiact.app.CordiActApplication
import com.cordiact.app.experience.settings.components.SettingsCard
import com.cordiact.core.plugin.AuthoredPluginInstallationEntry
import com.cordiact.core.plugin.AuthoredPluginInstallationStatus
import com.cordiact.core.plugin.AuthoredPluginInstalledRecord
import com.cordiact.core.plugin.ExtensionPackageArtifact
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

/** Management content only: Settings owns navigation, scope selection, insets and scrolling. */
@Composable
internal fun WorkspacePackageManagement(
    app: CordiActApplication,
    page: WorkspaceExtensionPackages.Page,
    blocked: Boolean,
    onBusy: (Boolean) -> Unit,
    onExportWriting: (Boolean) -> Unit = {},
) {
    val workspaceId = page.workspaceId
    val scope = rememberCoroutineScope()
    var current by remember(page) { mutableStateOf<List<ExtensionPackageRecord>?>(null) }
    var pending by remember(page) { mutableStateOf<PackagePrompt?>(null) }
    var trials by remember(page) { mutableStateOf<List<AuthoredPluginInstallationEntry>?>(null) }
    var busy by remember(page) { mutableStateOf(false) }
    var signedBusy by remember(page) { mutableStateOf(false) }
    var status by remember(page) { mutableStateOf<String?>(null) }
    var pickerRequested by remember(page) { mutableStateOf(false) }
    // Not saveable: a recreated screen cannot inherit an export destination or candidate.
    var exportRequest by remember(page) { mutableStateOf<ExtensionPackageRecord?>(null) }
    var query by rememberSaveable(workspaceId) { mutableStateOf("") }
    val controlsBlocked = blocked || busy || signedBusy || pickerRequested || exportRequest != null
    fun publishBusy() = onBusy(busy || signedBusy || pickerRequested || exportRequest != null)
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var pauseEpoch by remember(page, lifecycle) { mutableStateOf(0L) }
    DisposableEffect(lifecycle, page) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE) {
                pauseEpoch++
                pending = null
                trials = null
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }

    suspend fun load() {
        current = runInterruptible(Dispatchers.IO) { page.list() }
    }

    fun start(task: suspend () -> Unit) {
        if (busy) return
        busy = true
        publishBusy()
        status = null
        scope.launch {
            try { task() }
            catch (cancel: CancellationException) { throw cancel }
            catch (failure: Exception) { status = importError(failure) }
            finally { busy = false; publishBusy() }
        }
    }

    val picker = key(page) {
        rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
            if (pickerRequested) {
                pickerRequested = false
                if (uri == null) publishBusy()
                else start {
                    // A picker can return before Activity.onResume. The old foreground epoch is not reused.
                    val resumedEpoch = lifecycle.withResumed { pauseEpoch }
                    val preview = runInterruptible(Dispatchers.IO) {
                        require(uri.scheme == "content") { "PACKAGE_SOURCE_UNAVAILABLE" }
                        requireNotNull(app.contentResolver.openInputStream(uri)) { "PACKAGE_SOURCE_UNAVAILABLE" }.use(page::preview)
                    }
                    if (pauseEpoch == resumedEpoch && lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) pending = PackagePrompt.Import(preview)
                }
            }
        }
    }
    val saveJson = key(page) {
        rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val record = exportRequest
            exportRequest = null
            val uri = result.data?.data
            if (record == null || result.resultCode != Activity.RESULT_OK || uri == null) {
                publishBusy()
            } else start {
                val flags = result.data?.flags ?: 0
                require((flags and Intent.FLAG_GRANT_WRITE_URI_PERMISSION) != 0 && uri.scheme == "content" &&
                    DocumentsContract.isDocumentUri(app, uri)) { "PACKAGE_EXPORT_DESTINATION_UNAVAILABLE" }
                val resumedEpoch = lifecycle.withResumed { pauseEpoch }
                val preview = runInterruptible(Dispatchers.IO) { page.previewExport(record) }
                if (pauseEpoch == resumedEpoch && lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
                    pending = PackagePrompt.Export(preview, uri)
                }
            }
        }
    }

    fun chooseExportDestination(record: ExtensionPackageRecord) {
        if (controlsBlocked || busy || pickerRequested || exportRequest != null || pending != null) return
        try { page.requireExportDestinationSelection(record) } catch (failure: Exception) {
            status = importError(failure)
            return
        }
        exportRequest = record
        publishBusy()
        try {
            saveJson.launch(Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                putExtra(Intent.EXTRA_LOCAL_ONLY, true)
                type = "application/json"
                putExtra(Intent.EXTRA_TITLE, "${record.manifest.id}-${record.manifest.version}.json")
                addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_READ_URI_PERMISSION)
            })
        } catch (_: ActivityNotFoundException) {
            exportRequest = null
            publishBusy()
            status = "此设备没有可用的系统文件保存器。"
        } catch (_: SecurityException) {
            exportRequest = null
            publishBusy()
            status = "当前无法打开系统文件保存器。"
        }
    }

    fun previewTrial(record: AuthoredPluginInstalledRecord) {
        trials = null
        start {
            val read = { app.readSavedTrialPackage(workspaceId, record) }
            val preview = runInterruptible(Dispatchers.IO) { page.previewSavedTrial(read) }
            if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) pending = PackagePrompt.Import(preview, read)
        }
    }
    LaunchedEffect(page) { start { load() } }

    fun apply(prompt: PackagePrompt) {
        if (controlsBlocked || busy || pending !== prompt || !lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) return
        if (prompt is PackagePrompt.Export) {
            val operation = try { page.approveExport(prompt.preview) } catch (failure: Exception) {
                pending = null
                status = importError(failure)
                return
            }
            pending = null
            onExportWriting(true)
            start {
                try {
                    status = when (runInterruptible(Dispatchers.IO) {
                        operation.write {
                            checkNotNull(app.contentResolver.openOutputStream(prompt.destination, "wt")) {
                                "PACKAGE_EXPORT_DESTINATION_UNAVAILABLE"
                            }
                        }
                    }) {
                        WorkspaceExtensionPackages.ExportResult.WRITTEN -> "已写入原始 JSON，未读回校验。接收方仍需预览并批准安装。"
                        WorkspaceExtensionPackages.ExportResult.DESTINATION_UNCONFIRMED -> PACKAGE_EXPORT_UNCONFIRMED
                    }
                } finally { onExportWriting(false) }
            }
            return
        }
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

    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(enabled = !controlsBlocked, onClick = {
            if (!busy && !pickerRequested) {
                pickerRequested = true
                publishBusy()
                try { picker.launch("*/*") }
                catch (_: ActivityNotFoundException) {
                    pickerRequested = false
                    publishBusy()
                    status = "此设备没有可用的系统文件选择器。"
                }
            }
        }) { Text("导入 JSON") }
        TextButton(enabled = !controlsBlocked, onClick = { start { load() } }) { Text("刷新") }
    }
    TextButton(enabled = !controlsBlocked, onClick = {
        start {
            val entries = runInterruptible(Dispatchers.IO) { app.listSavedTrialsForPackage(workspaceId) }
            if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) trials = entries
        }
    }) { Text("从已保存试用版本转包") }
    Text("导入或转包只保存批准版本，不会运行插件。", style = MaterialTheme.typography.bodySmall)
    if (app.signedCatalogPolicyInvalid) {
        Text("此构建的签名目录配置无效；无法导入、启动或导出可能占用受控包名的声明包。",
            color = MaterialTheme.colorScheme.error)
    }
    if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
    status?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
    current?.let { records ->
        if (records.isEmpty()) Text("这个工作区还没有已安装的声明包。")
        else {
            OutlinedTextField(query, { query = it }, label = { Text("搜索已安装插件包") },
                modifier = Modifier.fillMaxWidth(), singleLine = true, enabled = !controlsBlocked)
            val matches = records.filter { record ->
                val term = query.trim()
                val manifest = record.manifest
                manifest.title.contains(term, true) || manifest.id.contains(term, true) || manifest.description.contains(term, true)
            }
            if (matches.isEmpty()) Text("没有匹配的插件包。")
            matches.forEach { record -> key(record.manifest.id) {
                val manifest = record.manifest
                SettingsCard {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(manifest.title, style = MaterialTheme.typography.titleMedium)
                        val running = page.status(record)
                        val mayChangeVersion = !record.enableIntent && running == Status.INACTIVE
                        Text("${manifest.id} · ${manifest.version} · ${runtimeLabel(record, running)}")
                        val turnOff = record.enableIntent || running == Status.CLEANUP_PENDING
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            TextButton(enabled = !controlsBlocked, onClick = { pending = PackagePrompt.Details(record) }) { Text("详情") }
                            TextButton(enabled = !controlsBlocked, onClick = { pending = PackagePrompt.Toggle(record, !turnOff) }) {
                                Text(if (running == Status.CLEANUP_PENDING) "重试清理" else if (turnOff) "停用" else "启用")
                            }
                            if (running == Status.FAILED || running == Status.TRUST_WITHDRAWN ||
                                (record.sourceReceipt != null && record.enableIntent && running == Status.INACTIVE)) {
                                TextButton(enabled = !controlsBlocked, onClick = { pending = PackagePrompt.RetryStart(record) }) { Text("重试启动") }
                            }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            if (record.artifact.format == ExtensionPackageArtifact.Format.DECLARATIVE && record.sourceReceipt == null) {
                                TextButton(enabled = !controlsBlocked, onClick = { chooseExportDestination(record) }) { Text("导出 JSON") }
                            }
                            if (record.previous != null) TextButton(enabled = !controlsBlocked && mayChangeVersion, onClick = { pending = PackagePrompt.Rollback(record) }) { Text("回滚") }
                            TextButton(enabled = !controlsBlocked && mayChangeVersion, onClick = { pending = PackagePrompt.Remove(record) }) { Text("移除") }
                        }
                        if (!mayChangeVersion) Text("更换、回滚或移除版本前请先停用。", style = MaterialTheme.typography.bodySmall)
                    }
                }
            } }
        }
    }
    app.signedCatalogAccess?.let { access ->
        SignedPackageDirectoryPanel(
            trustSourceId = access.trustSourceId, page = page,
            blocked = blocked || busy || pickerRequested || exportRequest != null || pending != null || trials != null,
            onBusy = { signedBusy = it; publishBusy() },
            onPreview = { preview ->
                if (preview.sourceReceipt != null && pending == null && !busy && !pickerRequested &&
                    exportRequest == null && lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
                    pending = PackagePrompt.Import(preview)
                }
            },
            prepareTrust = { access.prepareTrust(page) },
        )
    }
    trials?.let { entries -> SavedTrialPicker(entries, onDismiss = { trials = null }, onSelect = ::previewTrial) }
    pending?.let { prompt ->
        WorkspacePackagePrompt(prompt, onDismiss = { pending = null }, onConfirm = { apply(prompt) }, canConfirm = !controlsBlocked)
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
            when {
                readSavedTrial != null -> page.approveSavedTrial(preview, readSavedTrial)
                preview.sourceReceipt != null -> page.approveSignedInstall(preview)
                else -> page.approveInstall(preview)
            }
    }
    data class Export(val preview: WorkspaceExtensionPackages.ExportPreview, val destination: Uri) : PackagePrompt {
        override val title = "导出此版本的原始 JSON"
        override val action = "确认写入文件"
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
internal fun WorkspacePackagePrompt(prompt: PackagePrompt, onDismiss: () -> Unit, onConfirm: () -> Unit, canConfirm: Boolean = true) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(prompt.title) },
        text = {
            SelectionContainer {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    when (prompt) {
                        is PackagePrompt.Import -> {
                            prompt.preview.current?.let { Text("当前保存版本：${it.manifest.version}（${it.manifest.sha256}）") }
                            prompt.preview.sourceReceipt?.let { Text("签发来源：${it.sourceId} · 发布者：${it.publisherId}") }
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
                        is PackagePrompt.Export -> {
                            ManifestDetails(prompt.preview.manifest)
                            Text("写入刚才选定的文件。只导出上面版本的原始 JSON，不包含安装批准、启用状态、回滚版本或聊天记录。")
                            Text("这里显示摘要；文件会包含原始 JSON 的全部字段和固定文案。请先确认源文件不含私密内容。所选文件服务仍可能同步文件；取消时保存器可能已创建空文件。")
                        }
                        is PackagePrompt.Details -> {
                            ManifestDetails(prompt.record.manifest)
                            prompt.record.sourceReceipt?.let { Text("安装时的签发来源：${it.sourceId} · 发布者：${it.publisherId}（非当前信任状态）") }
                        }
                        is PackagePrompt.Toggle -> {
                            ManifestDetails(prompt.record.manifest)
                            Text(if (prompt.enable) "仅在此工作区发布贡献，进程重建后首次进入时按权限恢复。" else "撤回此工作区的工具、命令及客户端贡献，不删除安装记录或历史。")
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
            else TextButton(onClick = onConfirm, enabled = prompt.allowed && canConfirm) { Text(requireNotNull(prompt.action)) }
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
            ExtensionPackageCapability.UI_SETTINGS_PUBLISH -> "在工作区设置中显示说明页"
            ExtensionPackageCapability.UI_SESSION_ACTION_PUBLISH -> "在会话菜单中提供命令填入动作"
        }
    }}")
    manifest.tools.forEach { Text("工具 ${it.name}：${it.operation?.name ?: it.text}") }
    manifest.commands.forEach { Text("命令 /${it.name}：${it.text}") }
    manifest.client?.settingsPage?.let { page ->
        Text("说明页：${page.title}")
        Text(page.description)
        page.rows.forEach { Text("${it.label}：${it.value}") }
    }
    manifest.client?.sessionActions?.forEach { Text("会话动作 ${it.label}：仅填入 /${it.command}，不发送。") }
    if (manifest.tools.isNotEmpty()) Text("启用并调用后，工具结果可能随对话发送给所选模型。")
}

private fun runtimeLabel(record: ExtensionPackageRecord, status: Status): String = when (status) {
    Status.STARTING -> "正在启动"
    Status.ACTIVE -> "运行中"
    Status.FAILED -> "已选择启用，启动失败"
    Status.CLEANUP_PENDING -> "已撤回，待清理"
    Status.TRUST_WITHDRAWN -> "签名依据已失效，已撤回"
    Status.INACTIVE -> if (record.sourceReceipt != null && record.enableIntent) "未运行，须手动启动" else if (record.enableIntent) "已选择启用，未运行" else "已安装，未启用"
}

internal const val PACKAGE_EXPORT_UNCONFIRMED = "未确认完整导出；目标文件可能为空或不完整。请检查文件，不会自动重试。"

private fun importError(failure: Exception): String = when (failure.message) {
    "PACKAGE_CONFIRMATION_EXPIRED" -> "确认已失效，请重新预览后确认。"
    "PACKAGE_WORKSPACE_UNAVAILABLE" -> "此工作区已不可用。"
    "PACKAGE_WORKSPACE_READ_ONLY" -> "工作区为只读，不能修改已安装版本。"
    "PACKAGE_ID_RESERVED" -> "该包 ID 由内置插件保留。"
    "PACKAGE_STOP_BEFORE_CHANGE" -> "请先停用并完成清理，再更换、回滚或移除版本。"
    "PACKAGE_RETRY_UNAVAILABLE" -> "当前版本已不是可重试状态，请刷新清单。"
    "PACKAGE_EXPORT_READ_ONLY" -> "工作区为只读，不能导出文件。"
    "PACKAGE_EXPORT_FORMAT_UNSUPPORTED" -> "仅支持导出可以重新导入的声明包 JSON。"
    "PACKAGE_EXPORT_SIGNED_SOURCE" -> "受控来源的包不能导出为不带来源的 JSON。"
    "PACKAGE_SIGNED_SOURCE_REQUIRED" -> "此包名由受控来源保留，不能通过本地 JSON 或试用转包安装。"
    "CATALOG_UNTRUSTED", "CATALOG_EXPIRED", "CATALOG_STATE_UNAVAILABLE" -> "当前无法验证签发来源。"
    "PACKAGE_EXPORT_DESTINATION_UNAVAILABLE" -> "未取得可写的系统文件位置；未写入包内容。"
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
        ExtensionPackageChange.Code.SOURCE_CONFLICT -> "已安装包属于其他来源或发布者，不能覆盖。"
        ExtensionPackageChange.Code.SIGNED_SOURCE_REQUIRED -> "此包名必须通过已验证的签名来源安装。"
        ExtensionPackageChange.Code.TRUST_UNAVAILABLE -> "当前索引未验证此版本，未执行所请求的变更。"
    }
}
