package com.cordiact.app.experience

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.selection.SelectionContainer
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
import androidx.compose.runtime.referentialEqualityPolicy
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.cordiact.app.CordiActApplication
import com.cordiact.core.plugin.AuthoredPluginException
import com.cordiact.core.plugin.AuthoredPluginReceipt
import com.cordiact.interaction.InteractionContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Reads and revokes exact runtime generations, even if no model is configured. */
@Composable
internal fun SessionPluginsRoute(app: CordiActApplication, owner: InteractionContext, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    // A reactivation of the exact same bytes yields equal data but a different revoke identity.
    var receipts by remember(owner) { mutableStateOf<List<AuthoredPluginReceipt>>(emptyList(), referentialEqualityPolicy()) }
    var reading by remember(owner) { mutableStateOf(true) }
    var busy by remember(owner) { mutableStateOf(false) }
    var failure by remember(owner) { mutableStateOf<String?>(null) }
    var note by remember(owner) { mutableStateOf<String?>(null) }
    var refresh by remember(owner) { mutableStateOf(0) }
    var showDrafts by remember(owner) { mutableStateOf(false) }
    var showSaved by remember(owner) { mutableStateOf(false) }
    LaunchedEffect(owner, refresh) {
        reading = true
        try {
            receipts = withContext(Dispatchers.IO) { app.listSessionPlugins(owner) }
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (_: Exception) {
            receipts = emptyList()
            failure = "无法读取本会话插件。请确认会话仍然可用后刷新。"
        } finally {
            reading = false
        }
    }
    if (showSaved) {
        WorkspacePluginVersionsRoute(app, owner) { showSaved = false }
        return
    }
    if (showDrafts) {
        PluginDraftsRoute(app, owner) {
            showDrafts = false
            failure = null
            note = null
            reading = true
            refresh++
        }
        return
    }
    SessionPluginsDialog(
        receipts = receipts, reading = reading, busy = busy, note = note, failure = failure, onDismiss = onDismiss,
        onRefresh = { failure = null; note = null; reading = true; refresh++ },
        onOpenDrafts = { showDrafts = true },
        onOpenSaved = { showSaved = true },
        onRevoke = { receipt ->
            if (!busy && !reading) {
                busy = true
                failure = null
                note = null
                scope.launch {
                    try {
                        withContext(Dispatchers.IO) { app.revokeSessionPlugin(owner, receipt) }
                        note = "已撤回 ${receipt.id}，草稿仍保留。"
                    } catch (cancel: CancellationException) {
                        throw cancel
                    } catch (error: AuthoredPluginException) {
                        failure = when (error.code) {
                            AuthoredPluginException.Code.NOT_ACTIVE,
                            AuthoredPluginException.Code.VERSION_CHANGED -> "启用状态已变化，请查看刷新结果后再决定。"
                            else -> "未确认撤回完成，请查看当前状态。"
                        }
                    } catch (_: Exception) {
                        failure = "未确认撤回完成，请确认会话仍然可用。"
                    } finally {
                        reading = true
                        refresh++
                        busy = false
                    }
                }
            }
        },
    )
}

@Composable
internal fun SessionPluginsDialog(
    receipts: List<AuthoredPluginReceipt>,
    reading: Boolean,
    busy: Boolean,
    note: String?,
    failure: String?,
    onDismiss: () -> Unit,
    onRefresh: () -> Unit,
    onRevoke: (AuthoredPluginReceipt) -> Unit,
    onOpenDrafts: (() -> Unit)? = null,
    onOpenSaved: (() -> Unit)? = null,
) {
    var confirming by remember { mutableStateOf<AuthoredPluginReceipt?>(null) }
    val selected = confirming
    if (selected != null) {
        AlertDialog(
            onDismissRequest = { confirming = null },
            title = { Text("撤回 ${selected.id}？") },
            text = { Text("只撤回本会话显示的启用版本。草稿与已有对话记录不受影响。") },
            confirmButton = {
                TextButton(
                    enabled = !reading && !busy && receipts.any { it === selected },
                    onClick = { confirming = null; onRevoke(selected) },
                    modifier = Modifier.testTag("session-plugin-confirm-revoke"),
                ) { Text("确认撤回") }
            },
            dismissButton = { TextButton(onClick = { confirming = null }) { Text("返回") } },
        )
        return
    }
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text("会话插件") },
        text = {
            Column(Modifier.fillMaxWidth().heightIn(max = 440.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("这里列出本会话的试用记录。工作区草稿与已启用版本分开；重启应用不会恢复激活。")
                if (onOpenDrafts != null) {
                    TextButton(onClick = onOpenDrafts, enabled = !busy, modifier = Modifier.testTag("session-plugin-drafts")) {
                        Text("查看工作区草稿")
                    }
                }
                if (onOpenSaved != null) {
                    TextButton(onClick = onOpenSaved, enabled = !busy, modifier = Modifier.testTag("session-plugin-saved-versions")) {
                        Text("查看工作区已保存版本")
                    }
                }
                if (reading && receipts.isEmpty()) CircularProgressIndicator()
                if (!reading && failure == null && receipts.isEmpty()) Text("本会话没有已启用的插件。")
                if (failure != null) Text(failure, color = MaterialTheme.colorScheme.error)
                if (note != null) Text(note, color = MaterialTheme.colorScheme.primary)
                receipts.forEach { receipt ->
                    Card(Modifier.fillMaxWidth().testTag("session-plugin-${receipt.id}")) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                            Text(receipt.id, style = MaterialTheme.typography.titleSmall)
                            Text("SHA-256", style = MaterialTheme.typography.labelSmall)
                            SelectionContainer {
                                Text(receipt.sha256, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                            }
                            receipt.toolIds.forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
                            TextButton(
                                onClick = { confirming = receipt },
                                enabled = !reading && !busy,
                                modifier = Modifier.testTag("session-plugin-revoke-${receipt.id}"),
                            ) { Text("撤回") }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(enabled = !busy, onClick = onDismiss) { Text("关闭") } },
        dismissButton = { TextButton(enabled = !busy && !reading, onClick = onRefresh) { Text("刷新") } },
    )
}
