package com.cordiact.app.experience

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.cordiact.app.experience.tabs.PublishedTab
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
internal fun DockedWorkbenchPanels(
    panels: List<PublishedTab>,
    selectedId: String?,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable (PublishedTab, () -> Boolean) -> Unit,
) {
    val selected = panels.firstOrNull { it.id == selectedId } ?: panels.firstOrNull() ?: return
    val current by rememberUpdatedState(selected)
    Surface(modifier.testTag("docked-workbench-panels"), color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.fillMaxSize()) {
            LazyRow(Modifier.fillMaxWidth().padding(horizontal = 6.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                items(panels, key = { it.id }) { panel ->
                    FilterChip(
                        selected = panel === selected,
                        onClick = { onSelect(panel.id) },
                        label = { Text(panel.content.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    )
                }
            }
            key(selected) {
                var attached by remember { mutableStateOf(false) }
                DisposableEffect(selected) {
                    attached = true
                    onDispose { attached = false }
                }
                Box(Modifier.fillMaxSize()) { content(selected) { attached && current === selected } }
            }
        }
    }
}

@Composable
internal fun RestoreWorkbenchLayoutDialog(onRestore: suspend () -> Unit, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    var restoring by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = { if (!restoring) onDismiss() },
        title = { Text("无法读取已保存的布局") },
        text = { Text(if (failed) "未能恢复布局，请检查存储后重试。" else "当前使用经典布局。恢复默认将清除这个工作区已保存的布局方案。") },
        confirmButton = {
            TextButton(enabled = !restoring, onClick = {
                restoring = true
                scope.launch {
                    try {
                        onRestore()
                        onDismiss()
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        failed = true
                    } finally {
                        restoring = false
                    }
                }
            }) { Text("恢复默认") }
        },
        dismissButton = { TextButton(enabled = !restoring, onClick = onDismiss) { Text("取消") } },
    )
}
