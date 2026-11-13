package com.cordiact.app.experience

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.cordiact.app.experience.model.*

/** Standalone drawer content, without a ViewModel dependency. The owner controls navigation. */
@Composable
fun ConversationSidebar(
    groups: List<SidebarWorkspace>,
    activeWorkspaceId: String?,
    activeSessionId: String?,
    runningSessionIds: Set<String>,
    busy: Boolean,
    error: String?,
    onClose: () -> Unit,
    onNewSession: (String?) -> Unit,
    onSelectWorkspace: (String) -> Unit,
    onSelectSession: (String) -> Unit,
    onCreateWorkspace: (String, () -> Unit) -> Unit,
    onRenameWorkspace: (String, String, () -> Unit) -> Unit,
    onRetry: () -> Unit,
    onSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var query by rememberSaveable { mutableStateOf("") }
    var searchOpen by rememberSaveable { mutableStateOf(false) }
    var expanded by rememberSaveable { mutableStateOf(emptyList<String>()) }
    var collapsed by rememberSaveable { mutableStateOf(emptyList<String>()) }
    var menuId by remember { mutableStateOf<String?>(null) }
    var editingId by rememberSaveable { mutableStateOf<String?>(null) }
    var editorOpen by rememberSaveable { mutableStateOf(false) }
    var name by rememberSaveable { mutableStateOf("") }
    val visible = remember(groups, query) { sidebarGroups(groups, query) }
    val colors = MaterialTheme.colorScheme
    Column(modifier.fillMaxHeight().background(colors.surfaceContainerLowest).padding(horizontal = 14.dp)) {
        Row(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Surface(color = colors.primaryContainer, shape = RoundedCornerShape(12.dp)) {
                AppGlyph(GlyphName.Chat, modifier = Modifier.padding(10.dp), tint = colors.primary)
            }
            Column(Modifier.weight(1f).padding(start = 12.dp)) {
                Text("CordiAct", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text("对话与工作区", style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant)
            }
            IconButton(onClick = onClose) { AppGlyph(GlyphName.Close, description = "关闭侧边栏") }
        }
        OutlinedButton(
            onClick = { onNewSession(null) }, enabled = !busy,
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = colors.onSurface),
            border = androidx.compose.foundation.BorderStroke(1.dp, colors.outlineVariant),
        ) {
            AppGlyph(GlyphName.Plus)
            Spacer(Modifier.width(8.dp))
            Text("新对话", style = MaterialTheme.typography.titleSmall)
        }
        Row(Modifier.fillMaxWidth().padding(top = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("工作区", Modifier.weight(1f).padding(start = 8.dp), style = MaterialTheme.typography.labelLarge, color = colors.onSurfaceVariant)
            IconButton(onClick = { searchOpen = !searchOpen; if (!searchOpen) query = "" }) {
                AppGlyph(if (searchOpen) GlyphName.Close else GlyphName.Search, description = if (searchOpen) "收起搜索" else "搜索对话与工作区")
            }
            IconButton(onClick = { editingId = null; name = ""; editorOpen = true }, enabled = !busy) {
                AppGlyph(GlyphName.Plus, description = "新建工作区")
            }
        }
        if (searchOpen) {
            OutlinedTextField(
                value = query, onValueChange = { query = it }, singleLine = true,
                placeholder = { Text("搜索对话或工作区", style = MaterialTheme.typography.bodyMedium) },
                leadingIcon = { AppGlyph(GlyphName.Search) },
                modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp), shape = RoundedCornerShape(14.dp),
            )
        }
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth().height(2.dp))
        if (error != null) {
            Surface(color = colors.errorContainer, shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                Column(Modifier.padding(12.dp)) {
                    Text(error, color = colors.onErrorContainer, style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                    TextButton(onClick = onRetry, enabled = !busy) { Text("重试") }
                }
            }
        }
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(bottom = 16.dp)) {
            if (visible.isEmpty()) item {
                Text(if (query.isBlank()) "暂无工作区" else "没有匹配的对话或工作区", Modifier.padding(16.dp),
                    style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
            }
            visible.forEach { workspace ->
                val isExpanded = query.isNotBlank() || workspace.id in expanded ||
                    (workspace.id == activeWorkspaceId && workspace.id !in collapsed)
                item(key = "workspace:${workspace.id}") {
                    Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = {
                            if (isExpanded) { expanded = expanded - workspace.id; collapsed = collapsed + workspace.id }
                            else { expanded = expanded + workspace.id; collapsed = collapsed - workspace.id }
                        }, modifier = Modifier.size(48.dp)) {
                            AppGlyph(GlyphName.ChevronDown, modifier = Modifier.rotate(if (isExpanded) 0f else -90f),
                                description = "${if (isExpanded) "收起" else "展开"}工作区 ${workspace.name}")
                        }
                        Surface(
                            onClick = { onSelectWorkspace(workspace.id) }, enabled = !busy,
                            color = colors.surfaceContainerLowest, shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.weight(1f).semantics { selected = workspace.id == activeWorkspaceId },
                        ) {
                            Row(Modifier.heightIn(min = 52.dp).padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                                AppGlyph(GlyphName.Folder, modifier = Modifier.size(21.dp), tint = if (workspace.id == activeWorkspaceId) colors.primary else colors.onSurfaceVariant)
                                Text(workspace.name, Modifier.weight(1f).padding(start = 8.dp), style = MaterialTheme.typography.titleSmall,
                                    maxLines = 2, overflow = TextOverflow.Ellipsis)
                            }
                        }
                        Box {
                            IconButton(onClick = { menuId = workspace.id }, enabled = !busy) {
                                AppGlyph(GlyphName.More, description = "工作区 ${workspace.name} 的更多操作")
                            }
                            DropdownMenu(expanded = menuId == workspace.id, onDismissRequest = { menuId = null }) {
                                DropdownMenuItem(text = { Text("在此新建对话") }, onClick = { menuId = null; onNewSession(workspace.id) })
                                DropdownMenuItem(text = { Text("重命名工作区") }, onClick = {
                                    menuId = null; editingId = workspace.id; name = workspace.name; editorOpen = true
                                })
                            }
                        }
                    }
                }
                if (isExpanded) {
                    if (workspace.sessions.isEmpty()) item(key = "empty:${workspace.id}") {
                        Text("还没有对话", Modifier.padding(start = 56.dp, top = 8.dp, bottom = 12.dp),
                            style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                    }
                    items(workspace.sessions, key = { "session:${it.id}" }) { session ->
                        val current = activeSessionId == session.id
                        Surface(
                            onClick = { onSelectSession(session.id) }, enabled = !busy,
                            shape = RoundedCornerShape(12.dp),
                            color = if (current) colors.surfaceContainer else colors.surfaceContainerLowest,
                            modifier = Modifier.fillMaxWidth().padding(start = 38.dp, top = 2.dp, bottom = 2.dp)
                                .semantics { selected = current; if (session.id in runningSessionIds) stateDescription = "执行中" },
                        ) {
                            Row(Modifier.heightIn(min = 48.dp).padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text(session.title.ifBlank { "新对话" }, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = if (current) FontWeight.Medium else FontWeight.Normal, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                if (session.id in runningSessionIds) {
                                    Text("●", Modifier.padding(start = 8.dp), color = colors.primary, style = MaterialTheme.typography.labelSmall)
                                }
                            }
                        }
                    }
                }
            }
        }
        HorizontalDivider(color = colors.outlineVariant)
        Surface(onClick = onSettings, color = colors.surfaceContainerLowest, shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()) {
            Row(Modifier.heightIn(min = 56.dp).padding(horizontal = 12.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                AppGlyph(GlyphName.Settings)
                Text("设置", Modifier.padding(start = 12.dp).weight(1f), style = MaterialTheme.typography.bodyMedium)
                AppGlyph(GlyphName.ChevronRight, tint = colors.onSurfaceVariant, modifier = Modifier.size(16.dp))
            }
        }
        Text("各工作区的对话与上下文相互隔离", Modifier.padding(start = 12.dp, bottom = 12.dp), style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
    }
    if (editorOpen) {
        val id = editingId
        Dialog(onDismissRequest = { if (!busy) editorOpen = false }) {
            Surface(shape = RoundedCornerShape(24.dp), color = colors.surfaceContainerLow, tonalElevation = 6.dp) {
                Column(Modifier.widthIn(max = 380.dp).padding(20.dp)) {
                    Text(if (id == null) "新建工作区" else "重命名工作区", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(12.dp))
                    WorkspaceNameEditor(
                        name = name, existing = id != null, busy = busy, error = error,
                        onNameChange = { name = it },
                        onConfirm = { trimmed ->
                            val onSuccess = { editorOpen = false }
                            if (id == null) onCreateWorkspace(trimmed, onSuccess) else onRenameWorkspace(id, trimmed, onSuccess)
                        },
                        onDismiss = { editorOpen = false },
                    )
                }
            }
        }
    }
}

/**
 * Self-contained editor panel. Production shows it inside [Dialog]; component tests render it
 * inline so validation and trimming are exercised without a second window.
 */
@Composable
fun WorkspaceNameEditor(
    name: String,
    existing: Boolean,
    busy: Boolean,
    error: String?,
    onNameChange: (String) -> Unit,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val validation = sidebarNameError(name)
    Column(Modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = name, onValueChange = onNameChange, singleLine = true, enabled = !busy,
            label = { Text("工作区名称") }, isError = name.isNotEmpty() && validation != null,
            supportingText = { Text(validation ?: "仅更改显示名称，不移动工作区文件") },
            modifier = Modifier.fillMaxWidth(),
        )
        if (!busy && validation == null && error != null) {
            Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 4.dp))
        }
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = onDismiss, enabled = !busy) { Text("取消") }
            TextButton(onClick = { onConfirm(name.trim()) }, enabled = !busy && validation == null) {
                Text(if (existing) "保存" else "创建")
            }
        }
    }
}
