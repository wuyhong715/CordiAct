package com.cordiact.app.experience

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.cordiact.app.CordiActApplication
import com.cordiact.core.plugin.AuthoredPluginInstallationEntry
import com.cordiact.core.plugin.AuthoredPluginInstallationStatus
import com.cordiact.core.plugin.AuthoredPluginInstalledRecord
import com.cordiact.interaction.InteractionContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
internal fun WorkspacePluginVersionsRoute(app: CordiActApplication, owner: InteractionContext, onBack: () -> Unit) {
    var revision by remember(owner) { mutableStateOf(0) }
    var entries by remember(owner, revision) { mutableStateOf<List<AuthoredPluginInstallationEntry>?>(null) }
    var error by remember(owner, revision) { mutableStateOf(false) }
    var selected by remember(owner) { mutableStateOf<AuthoredPluginInstalledRecord?>(null) }
    var statusMessage by remember(owner) { mutableStateOf<String?>(null) }
    LaunchedEffect(owner, revision) {
        try {
            entries = withContext(Dispatchers.IO) { app.listWorkspacePluginVersions(owner) }
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (_: Exception) {
            error = true
        }
    }
    val version = selected
    if (version == null) {
        WorkspacePluginVersionsDialog(entries, error, onRefresh = { statusMessage = null; revision++ }, onBack = onBack,
            onPreview = { selected = it }, statusMessage = statusMessage)
    } else {
        PluginVersionPreviewRoute(app, owner, version.id, saved = version,
            onSavedVersionChanged = {
                statusMessage = "保存清单已更新；会话运行状态未改变。"
                selected = null
                revision++
            }, onBack = {
                statusMessage = null
                selected = null
                revision++
            })
    }
}

@Composable
internal fun WorkspacePluginVersionsDialog(
    entries: List<AuthoredPluginInstallationEntry>?, error: Boolean, onRefresh: () -> Unit, onBack: () -> Unit,
    onPreview: ((AuthoredPluginInstalledRecord) -> Unit)? = null,
    statusMessage: String? = null,
) {
    AlertDialog(
        onDismissRequest = onBack,
        title = { Text("工作区已保存版本") },
        text = {
            Column(Modifier.fillMaxWidth().heightIn(max = 440.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("这些是当前工作区保存的批准版本。替换或删除快照不会改变会话运行状态；重启不会恢复会话试用。")
                if (statusMessage != null) Text(statusMessage, color = MaterialTheme.colorScheme.primary)
                when {
                    error -> Text("无法读取或校验已保存版本。请刷新；这不表示没有已保存记录。", color = MaterialTheme.colorScheme.error)
                    entries == null -> CircularProgressIndicator()
                    entries.isEmpty() -> Text("当前工作区尚无已保存版本。")
                    else -> entries.forEach { entry ->
                        Card(Modifier.fillMaxWidth().testTag("workspace-plugin-version-${entry.id}")) {
                            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                                Text(entry.id, style = MaterialTheme.typography.titleSmall)
                                when (entry.status) {
                                    AuthoredPluginInstallationStatus.CORRUPT -> Text("内容或摘要校验失败", color = MaterialTheme.colorScheme.error)
                                    AuthoredPluginInstallationStatus.UNSUPPORTED -> Text("此应用版本不支持该记录格式", color = MaterialTheme.colorScheme.error)
                                    AuthoredPluginInstallationStatus.READY -> entry.record?.let { record ->
                                        Text("已校验 · 源码格式 ${record.schemaVersion} · ${record.sizeBytes} 字节")
                                        Text(record.title)
                                        Text("已保存版本 SHA-256", style = MaterialTheme.typography.labelSmall)
                                        SelectionContainer { Text(record.sha256, fontFamily = FontFamily.Monospace) }
                                        record.toolIds.forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
                                        if (onPreview != null) {
                                            TextButton(onClick = { onPreview(record) },
                                                modifier = Modifier.testTag("workspace-plugin-preview-${entry.id}")) { Text("预览版本") }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onBack) { Text("返回会话插件") } },
        dismissButton = { TextButton(onClick = onRefresh, enabled = entries != null || error) { Text("刷新") } },
    )
}
