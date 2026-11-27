package com.cordiact.app.experience

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.withResumed
import com.cordiact.app.CordiActApplication
import com.cordiact.app.experience.settings.components.SettingsCard
import com.cordiact.app.plugin.AndroidUserCatalogs
import com.cordiact.core.plugin.WorkspaceExtensionPackages
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible

@Composable
internal fun UserSignedCatalogControls(
    app: CordiActApplication,
    page: WorkspaceExtensionPackages.Page,
    store: AndroidUserCatalogs,
    directories: List<AndroidUserCatalogs.Directory>,
    blocked: Boolean,
    canImport: Boolean,
    onBusy: (Boolean) -> Unit,
    onAdded: (String) -> Unit,
) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val scope = rememberCoroutineScope()
    val busyChanged by rememberUpdatedState(onBusy)
    var choosing by remember(page) { mutableStateOf(false) }
    var working by remember(page) { mutableStateOf(false) }
    var epoch by remember(page) { mutableLongStateOf(0L) }
    var preview by remember(page) { mutableStateOf<AndroidUserCatalogs.Candidate?>(null) }
    var removing by remember(page) { mutableStateOf<AndroidUserCatalogs.Directory?>(null) }
    var error by remember(page) { mutableStateOf<String?>(null) }
    DisposableEffect(page, lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE) {
                epoch++
                preview = null
                removing = null
            }
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            if (working || choosing) busyChanged(false)
        }
    }

    fun run(failure: String, action: suspend () -> Unit) {
        if (working) return
        working = true
        error = null
        busyChanged(true)
        scope.launch {
            try { action() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { error = failure }
            finally { working = false; busyChanged(false) }
        }
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (choosing) {
            choosing = false
            if (uri == null) busyChanged(false)
            else run("无法读取签名目录策略。") {
                val observed = lifecycle.withResumed { epoch }
                val candidate = runInterruptible(Dispatchers.IO) {
                    require(uri.scheme == "content")
                    requireNotNull(app.contentResolver.openInputStream(uri)).use { AndroidUserCatalogs.preview(it) }
                }
                if (observed == epoch && lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) preview = candidate
            }
        }
    }

    fun commit(failure: String, approved: () -> WorkspaceExtensionPackages.CatalogConfigurationChange, after: () -> Unit = {}) {
        if (blocked || working || !lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) return
        val change = try { approved() } catch (_: Exception) { error = failure; return }
        run(failure) { runInterruptible(Dispatchers.IO) { change.commit() }; after() }
    }

    SettingsCard {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("自管签名目录", style = MaterialTheme.typography.titleMedium)
            TextButton(enabled = canImport && !blocked && !working && !choosing && !store.uncertain, onClick = {
                choosing = true
                busyChanged(true)
                try { picker.launch("*/*") }
                catch (_: Exception) { choosing = false; busyChanged(false); error = "没有可用的文件选择器。" }
            }) { Text("导入公共信任策略") }
            directories.forEach { directory ->
                Text(directory.access.trustSourceId, style = MaterialTheme.typography.titleSmall)
                Text("根公钥 SHA-256 ${directory.rootFingerprint}", style = MaterialTheme.typography.bodySmall)
                TextButton(enabled = !blocked && !working && !choosing, onClick = { removing = directory }) { Text("停用此目录") }
            }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    }

    preview?.let { candidate ->
        AlertDialog(
            onDismissRequest = { preview = null },
            title = { Text("在此设备信任签名目录") },
            text = { SelectionContainer {
                Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("根公钥 SHA-256 ${candidate.rootFingerprint}")
                    Text("最低根代数 ${candidate.rootRevision} · 根来源 ${candidate.rootSource}")
                    Text("该代根清单 SHA-256 ${candidate.rootDigest}")
                    candidate.sources.forEach { source -> Text("${source.id} · ${source.base} · ${source.packagePrefix}") }
                    Text("请先核对发布者给出的根公钥指纹。该配置适用于此设备上的所有工作区。")
                }
            } },
            confirmButton = {
                TextButton(enabled = !blocked && !working, onClick = {
                    preview = null
                    commit("目录未添加。请检查包名前缀、来源 ID 和已安装包是否冲突。",
                        { app.approveUserCatalogImport(page, candidate) }, { onAdded(candidate.rootSource) })
                }) { Text("信任此根") }
            },
            dismissButton = { TextButton(onClick = { preview = null }) { Text("取消") } },
        )
    }
    removing?.let { directory ->
        AlertDialog(
            onDismissRequest = { removing = null },
            title = { Text("停用目录 ${directory.access.trustSourceId}") },
            text = { Text("该目录的签名插件会停止运行。已安装记录、保留前缀和防回退检查点将继续保留。") },
            confirmButton = {
                TextButton(enabled = !blocked && !working, onClick = {
                    removing = null
                    commit("目录停用未确认。", { app.approveUserCatalogRemoval(page, directory) })
                }) { Text("停用目录") }
            },
            dismissButton = { TextButton(onClick = { removing = null }) { Text("取消") } },
        )
    }
}
