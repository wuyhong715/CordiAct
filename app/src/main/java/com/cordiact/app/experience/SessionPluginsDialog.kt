package com.cordiact.app.experience

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.referentialEqualityPolicy
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.cordiact.app.CordiActApplication
import com.cordiact.core.plugin.AuthoredPluginException
import com.cordiact.core.plugin.AuthoredPluginReceipt
import com.cordiact.interaction.InteractionContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** In-body inventory; no model required. The viewport host owns Back for the main panel. */
@Composable
internal fun SessionPluginsRoute(
    app: CordiActApplication,
    owner: InteractionContext,
    modifier: Modifier = Modifier,
    onDismiss: () -> Unit,
): Unit = key(owner) {
    val scope = rememberCoroutineScope()
    // A reactivation of the exact same bytes yields equal data but a different revoke identity.
    var inventory by remember(owner) { mutableStateOf(SessionPluginInventorySnapshot.EMPTY) }
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
            inventory = SessionPluginInventorySnapshot(withContext(Dispatchers.IO) { app.listSessionPlugins(owner) })
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (_: Exception) {
            inventory = SessionPluginInventorySnapshot.EMPTY
            failure = "无法读取本会话插件。请确认会话仍然可用后刷新。"
        } finally {
            reading = false
        }
    }
    SessionPluginsPanel(
        modifier = modifier,
        inventory = inventory, reading = reading, busy = busy, note = note, failure = failure, onDismiss = onDismiss,
        onRefresh = { failure = null; note = null; reading = true; refresh++ },
        onOpenDrafts = { if (!showSaved && !busy) showDrafts = true },
        onOpenSaved = { if (!showDrafts && !busy) showSaved = true },
        onRevoke = { receipt ->
            if (!busy && !reading) {
                val confirmation = app.authoredPluginForeground.openConfirmation(owner.workspaceId, owner.sessionId)
                if (confirmation == null) {
                    failure = "会话或前台状态已变化，请重新确认。"
                } else {
                    busy = true
                    failure = null
                    note = null
                    scope.launch {
                        try {
                            withContext(Dispatchers.IO) { app.revokeSessionPlugin(owner, confirmation, receipt) }
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
                            confirmation.close()
                            reading = true
                            refresh++
                            busy = false
                        }
                    }
                }
            }
        },
    )
    val returnToInventory = {
        failure = null
        note = null
        reading = true
        refresh++
    }
    if (showSaved) WorkspacePluginVersionsRoute(app, owner) { showSaved = false; returnToInventory() }
    if (showDrafts) PluginDraftsRoute(app, owner) { showDrafts = false; returnToInventory() }
}

/** Identity survives Compose's value-based parameter and lambda comparisons. */
internal class SessionPluginInventorySnapshot(receipts: List<AuthoredPluginReceipt>) {
    internal val rows = receipts.map(::SessionPluginActivation)
    internal fun contains(activation: SessionPluginActivation): Boolean = rows.any { it == activation }

    companion object { val EMPTY = SessionPluginInventorySnapshot(emptyList()) }
}

internal class SessionPluginActivation(val receipt: AuthoredPluginReceipt) {
    override fun equals(other: Any?): Boolean = other is SessionPluginActivation && receipt === other.receipt
    override fun hashCode(): Int = System.identityHashCode(receipt)
}

@Composable
internal fun SessionPluginsPanel(
    inventory: SessionPluginInventorySnapshot,
    reading: Boolean,
    busy: Boolean,
    note: String?,
    failure: String?,
    onDismiss: () -> Unit,
    onRefresh: () -> Unit,
    onRevoke: (AuthoredPluginReceipt) -> Unit,
    onOpenDrafts: (() -> Unit)? = null,
    onOpenSaved: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    var confirming by remember { mutableStateOf<SessionPluginActivation?>(null, referentialEqualityPolicy()) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE) confirming = null
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    val scroll = rememberScrollState()
    Surface(
        modifier.fillMaxSize().testTag("session-plugins-panel").semantics { paneTitle = "会话插件" },
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = RoundedCornerShape(19.dp),
    ) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val compactHeight = maxHeight < 120.dp
            // In a very short body, include the header in the scroll instead of giving
            // almost the entire viewport to a fixed toolbar. At normal height close stays pinned.
            Column(if (compactHeight) Modifier.fillMaxSize().verticalScroll(scroll) else Modifier.fillMaxSize()) {
                Row(
                    Modifier.fillMaxWidth().padding(start = 14.dp, end = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "会话插件", Modifier.weight(1f).semantics { heading() },
                        style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                    IconButton(onClick = onDismiss, enabled = !busy, modifier = Modifier.testTag("session-plugins-close")) {
                        AppGlyph(GlyphName.Close, "关闭会话插件")
                    }
                }
                val inventoryModifier = if (compactHeight) Modifier else Modifier.weight(1f).verticalScroll(scroll)
                SessionPluginInventory(
                    inventory, reading, busy, note, failure, onOpenDrafts, onOpenSaved, onRevoke = { confirming = it },
                    modifier = inventoryModifier.fillMaxWidth().padding(start = 13.dp, end = 13.dp, bottom = 12.dp),
                    onRefresh = onRefresh,
                )
            }
        }
    }
    confirming?.let { activation ->
        SessionPluginRevokeConfirmation(activation, inventory, reading, busy, onDismiss = { confirming = null }, onRevoke = onRevoke)
    }
}

/** Legacy modal wrapper for component-test callers; production uses [SessionPluginsPanel]. */
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
    var confirming by remember { mutableStateOf<SessionPluginActivation?>(null, referentialEqualityPolicy()) }
    val inventory = SessionPluginInventorySnapshot(receipts)
    val selected = confirming
    if (selected != null) {
        SessionPluginRevokeConfirmation(selected, inventory, reading, busy, onDismiss = { confirming = null }, onRevoke = onRevoke)
    } else {
        AlertDialog(
            onDismissRequest = { if (!busy) onDismiss() },
            title = { Text("会话插件") },
            text = {
                SessionPluginInventory(
                    inventory, reading, busy, note, failure, onOpenDrafts, onOpenSaved, onRevoke = { confirming = it },
                    modifier = Modifier.fillMaxWidth().heightIn(max = 440.dp).verticalScroll(rememberScrollState()),
                )
            },
            confirmButton = { TextButton(enabled = !busy, onClick = onDismiss) { Text("关闭") } },
            dismissButton = { TextButton(enabled = !busy && !reading, onClick = onRefresh) { Text("刷新") } },
        )
    }
}

@Composable
private fun SessionPluginRevokeConfirmation(
    activation: SessionPluginActivation,
    inventory: SessionPluginInventorySnapshot,
    reading: Boolean,
    busy: Boolean,
    onDismiss: () -> Unit,
    onRevoke: (AuthoredPluginReceipt) -> Unit,
) {
    val currentInventory by androidx.compose.runtime.rememberUpdatedState(inventory)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("撤回会话试用？", maxLines = 2, overflow = TextOverflow.Ellipsis) },
        text = {
            Column(
                Modifier.fillMaxWidth().heightIn(max = 440.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(activation.receipt.id, style = MaterialTheme.typography.titleSmall)
                Text("只撤回本会话显示的启用版本。草稿与已有对话记录不受影响。")
            }
        },
        confirmButton = {
            TextButton(
                enabled = !reading && !busy && inventory.contains(activation),
                onClick = {
                    if (!reading && !busy && currentInventory.contains(activation)) {
                        onDismiss()
                        onRevoke(activation.receipt)
                    }
                },
                modifier = Modifier.testTag("session-plugin-confirm-revoke"),
            ) { Text("确认撤回") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("返回") } },
    )
}

@Composable
private fun SessionPluginInventory(
    inventory: SessionPluginInventorySnapshot,
    reading: Boolean,
    busy: Boolean,
    note: String?,
    failure: String?,
    onOpenDrafts: (() -> Unit)?,
    onOpenSaved: (() -> Unit)?,
    onRevoke: (SessionPluginActivation) -> Unit,
    modifier: Modifier,
    onRefresh: (() -> Unit)? = null,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            "这里列出本会话的试用记录。工作区草稿与已启用版本分开；重启应用不会恢复激活。",
            style = MaterialTheme.typography.bodySmall,
        )
        if (onRefresh != null) {
            TextButton(onClick = onRefresh, enabled = !busy && !reading, modifier = Modifier.testTag("session-plugin-refresh")) {
                Text("刷新")
            }
        }
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
        if (reading && inventory.rows.isEmpty()) CircularProgressIndicator()
        if (!reading && failure == null && inventory.rows.isEmpty()) Text("本会话没有已启用的插件。")
        if (failure != null) Text(failure, color = MaterialTheme.colorScheme.error)
        if (note != null) Text(note, color = MaterialTheme.colorScheme.primary)
        inventory.rows.forEach { activation ->
            val receipt = activation.receipt
            key(activation) {
                Card(Modifier.fillMaxWidth().testTag("session-plugin-${receipt.id}")) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        Text(receipt.id, style = MaterialTheme.typography.titleSmall)
                        Text("SHA-256", style = MaterialTheme.typography.labelSmall)
                        SelectionContainer {
                            Text(receipt.sha256, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                        }
                        receipt.toolIds.forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
                        TextButton(
                            onClick = { onRevoke(activation) },
                            enabled = !reading && !busy,
                            modifier = Modifier.testTag("session-plugin-revoke-${receipt.id}"),
                        ) { Text("撤回") }
                    }
                }
            }
        }
    }
}
