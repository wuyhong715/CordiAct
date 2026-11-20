package com.cordiact.app.experience

import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.background
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.sp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import com.cordiact.core.action.ApprovalOwner
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

/**
 * Standalone drawer content, without a ViewModel dependency. The owner controls navigation.
 *
 * Conversation management (rename / pin / archive / restore) is expressed only through optional
 * callbacks so existing call sites and tests stay source-compatible. [archiveMode] is an in-drawer
 * view rather than a separate screen: the same header, search, list and edge fades are reused.
 */
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
    onAutomationTasks: () -> Unit = {},
    onRenameSession: (String, String, () -> Unit) -> Unit = { _, _, _ -> },
    onArchiveSession: (String) -> Unit = {},
    onRestoreSession: (String) -> Unit = {},
    onPinSession: (String, Boolean) -> Unit = { _, _ -> },
) {
    var query by rememberSaveable { mutableStateOf("") }
    var searchOpen by rememberSaveable { mutableStateOf(false) }
    var includeArchived by rememberSaveable { mutableStateOf(false) }
    var archiveMode by rememberSaveable { mutableStateOf(false) }
    var expanded by rememberSaveable { mutableStateOf(emptyList<String>()) }
    var collapsed by rememberSaveable { mutableStateOf(emptyList<String>()) }
    var menuId by remember { mutableStateOf<String?>(null) }
    var topMenuOpen by remember { mutableStateOf(false) }
    var sessionMenuId by remember { mutableStateOf<String?>(null) }
    var editingId by rememberSaveable { mutableStateOf<String?>(null) }
    var editorOpen by rememberSaveable { mutableStateOf(false) }
    var name by rememberSaveable { mutableStateOf("") }
    var sessionEditingId by rememberSaveable { mutableStateOf<String?>(null) }
    var sessionEditorOpen by rememberSaveable { mutableStateOf(false) }
    var sessionName by rememberSaveable { mutableStateOf("") }
    val approvalOwners = LocalApprovalOwners.current
    var filter by rememberSaveable { mutableStateOf("all") }
    var filteredCollapsed by remember(filter) { mutableStateOf(emptySet<String>()) }
    val visible = remember(groups, query, includeArchived, archiveMode, filter, runningSessionIds, approvalOwners) {
        if (archiveMode) sidebarGroups(groups, query, archivedOnly = true)
        else sidebarGroups(groups, query, includeArchived = includeArchived).let { result ->
            if (filter == "all") result else result.mapNotNull { group ->
                val sessions = group.sessions.filter { session ->
                    when (filter) {
                        "approval" -> ApprovalOwner(group.id, session.id) in approvalOwners
                        else -> session.id in runningSessionIds
                    }
                }
                if (sessions.isEmpty()) null else group.copy(sessions = sessions)
            }
        }
    }
    val colors = MaterialTheme.colorScheme
    val backdrop = remember { HazeState() }
    var headerHeight by remember { mutableIntStateOf(0) }
    var footerHeight by remember { mutableIntStateOf(0) }
    val density = LocalDensity.current
    val headerDp = with(density) { headerHeight.toDp() }
    val footerDp = with(density) { footerHeight.toDp() }
    CompositionLocalProvider(LocalBackdrop provides backdrop) {
    Box(modifier.fillMaxHeight().fillMaxWidth().background(colors.surfaceContainerLowest).testTag("sidebar")) {
      Column(Modifier.fillMaxSize().hazeSource(backdrop)) {
        LazyColumn(Modifier.weight(1f).fillMaxWidth().testTag("sidebar-list"), contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = headerDp + 12.dp, bottom = footerDp + 24.dp)) {
        if (busy) item { LinearProgressIndicator(Modifier.fillMaxWidth().height(2.dp)) }
        if (error != null) item {
            Surface(color = colors.errorContainer, shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                Column(Modifier.padding(12.dp)) {
                    Text(error, color = colors.onErrorContainer, style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                    TextButton(onClick = onRetry, enabled = !busy) { Text("重试") }
                }
            }
        }
            if (visible.isEmpty()) item {
                val emptyText = when {
                    archiveMode && query.isBlank() -> "暂无归档会话"
                    archiveMode -> "没有匹配的归档会话"
                    filter == "running" -> "没有匹配的运行中会话"
                    filter == "approval" -> "没有匹配的待确认会话"
                    query.isBlank() -> "暂无工作区"
                    else -> "没有匹配的对话或工作区"
                }
                Text(emptyText, Modifier.padding(16.dp),
                    style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
            }
            visible.forEach { workspace ->
                val isExpanded = when {
                    archiveMode -> workspace.id !in collapsed
                    filter != "all" -> workspace.id !in filteredCollapsed
                    query.isNotBlank() -> true
                    else -> workspace.id in expanded ||
                        (workspace.id == activeWorkspaceId && workspace.id !in collapsed)
                }
                item(key = "workspace:${workspace.id}") {
                    Row(Modifier.fillMaxWidth().padding(top = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = {
                            if (filter != "all" && !archiveMode) {
                                filteredCollapsed = if (isExpanded) filteredCollapsed + workspace.id else filteredCollapsed - workspace.id
                            } else if (isExpanded) { expanded = expanded - workspace.id; collapsed = collapsed + workspace.id }
                            else { expanded = expanded + workspace.id; collapsed = collapsed - workspace.id }
                        }, modifier = Modifier.size(48.dp)) {
                            AppGlyph(GlyphName.ChevronDown, modifier = Modifier.rotate(if (isExpanded) 0f else -90f),
                                description = "${if (isExpanded) "收起" else "展开"}工作区 ${workspace.name}")
                        }
                        Surface(
                            onClick = { onSelectWorkspace(workspace.id) }, enabled = !busy,
                            color = Color.Transparent, shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.weight(1f).semantics { selected = workspace.id == activeWorkspaceId },
                        ) {
                            Row(Modifier.heightIn(min = 48.dp).padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                                AppGlyph(GlyphName.Folder, modifier = Modifier.size(21.dp), tint = if (workspace.id == activeWorkspaceId) colors.primary else colors.onSurfaceVariant)
                                Text(workspace.name, Modifier.weight(1f).padding(start = 8.dp), style = MaterialTheme.typography.titleSmall,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                        if (!archiveMode) {
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
                }
                if (isExpanded) {
                    if (workspace.sessions.isEmpty()) item(key = "empty:${workspace.id}") {
                        Text(if (archiveMode) "暂无归档会话" else "还没有对话",
                            Modifier.padding(start = 32.dp, top = 8.dp, bottom = 12.dp),
                            style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                    }
                    itemsIndexed(workspace.sessions, key = { _, session -> "session:${session.id}" }) { index, session ->
                        val current = activeSessionId == session.id
                        Row(
                            Modifier.fillMaxWidth().drawBehind {
                                val x = 19.dp.toPx()
                                val stroke = 1.dp.toPx()
                                val color = colors.outlineVariant
                                if (index > 0) drawLine(color, Offset(x, 0f), Offset(x, center.y), stroke)
                                if (index < workspace.sessions.lastIndex) drawLine(color, Offset(x, center.y), Offset(x, size.height), stroke)
                                drawLine(color, Offset(x, center.y), Offset(27.dp.toPx(), center.y), stroke)
                            }.padding(start = 32.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Surface(
                                onClick = { onSelectSession(session.id) }, enabled = !busy,
                                shape = RoundedCornerShape(10.dp),
                                color = if (current) colors.primaryContainer else Color.Transparent,
                                modifier = Modifier.weight(1f)
                                    .semantics { selected = current; if (session.id in runningSessionIds) stateDescription = "执行中" },
                            ) {
                                Row(Modifier.heightIn(min = 48.dp).padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                                    if (session.pinnedAtMillis != null) {
                                        AppGlyph(GlyphName.Pin, description = "已置顶", modifier = Modifier.size(14.dp), tint = colors.primary)
                                    }
                                    Text(session.title.ifBlank { "新对话" },
                                        Modifier.weight(1f).padding(start = if (session.pinnedAtMillis != null) 6.dp else 0.dp),
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = if (current) FontWeight.Medium else FontWeight.Normal, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    if (session.archivedAtMillis != null) Text(
                                        "已归档", Modifier.padding(start = 6.dp), style = MaterialTheme.typography.labelSmall,
                                        color = colors.tertiary, maxLines = 1)
                                    if (session.updatedAtMillis > 0) Text(
                                        remember(session.updatedAtMillis) { java.text.SimpleDateFormat("MM-dd", java.util.Locale.getDefault()).format(java.util.Date(session.updatedAtMillis)) },
                                        Modifier.padding(start = 8.dp), style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant,
                                        maxLines = 1)
                                    if (ApprovalOwner(workspace.id, session.id) in approvalOwners) {
                                        Text("待确认", Modifier.padding(start = 8.dp), color = colors.primary, style = MaterialTheme.typography.labelSmall)
                                    } else if (session.id in runningSessionIds) {
                                        Text("运行中", Modifier.padding(start = 8.dp), color = colors.primary, style = MaterialTheme.typography.labelSmall)
                                    }
                                }
                            }
                            Box {
                                IconButton(
                                    onClick = { sessionMenuId = session.id }, enabled = !busy,
                                    modifier = Modifier.size(48.dp),
                                ) {
                                    AppGlyph(GlyphName.More,
                                        description = "会话 ${session.title.ifBlank { "新对话" }} 的更多操作",
                                        modifier = Modifier.size(20.dp))
                                }
                                DropdownMenu(expanded = sessionMenuId == session.id, onDismissRequest = { sessionMenuId = null }) {
                                    if (archiveMode && session.archivedAtMillis != null) {
                                        DropdownMenuItem(text = { Text("恢复") }, enabled = !busy, onClick = {
                                            sessionMenuId = null; onRestoreSession(session.id)
                                        })
                                        DropdownMenuItem(text = { Text("重命名") }, enabled = !busy, onClick = {
                                            sessionMenuId = null; sessionEditingId = session.id
                                            sessionName = session.title; sessionEditorOpen = true
                                        })
                                    } else {
                                        DropdownMenuItem(text = { Text("重命名") }, enabled = !busy, onClick = {
                                            sessionMenuId = null; sessionEditingId = session.id
                                            sessionName = session.title; sessionEditorOpen = true
                                        })
                                        DropdownMenuItem(
                                            text = { Text(if (session.pinnedAtMillis == null) "置顶" else "取消置顶") },
                                            enabled = !busy,
                                            onClick = { sessionMenuId = null; onPinSession(session.id, session.pinnedAtMillis == null) },
                                        )
                                        if (session.id in runningSessionIds) {
                                            Text("归档后任务仍继续",
                                                Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                                                style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
                                        }
                                        DropdownMenuItem(text = { Text("归档") }, enabled = !busy, onClick = {
                                            sessionMenuId = null; onArchiveSession(session.id)
                                        })
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
      }
      ViewportFade(backdrop, true, Modifier.align(Alignment.TopCenter).fillMaxWidth().height(headerDp + 12.dp))
      ViewportFade(backdrop, false, Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(footerDp + 16.dp))
      Column(Modifier.align(Alignment.TopCenter).fillMaxWidth().onSizeChanged { headerHeight = it.height }.padding(horizontal = 12.dp)) {
        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
            if (archiveMode) {
                IconButton(onClick = { archiveMode = false; query = ""; searchOpen = false }, modifier = Modifier.size(48.dp)) {
                    AppGlyph(GlyphName.Back, "返回会话列表", Modifier.size(20.dp))
                }
                Text("归档会话", Modifier.weight(1f).padding(start = 4.dp), fontSize = 20.sp, fontWeight = FontWeight.Medium)
            } else {
                AppGlyph(GlyphName.Chat, modifier = Modifier.size(24.dp), tint = colors.primary)
                Text("CordiAct", Modifier.weight(1f).padding(start = 10.dp), fontSize = 20.sp, fontWeight = FontWeight.Medium)
                Box {
                    IconButton(onClick = { topMenuOpen = true }, enabled = !busy) {
                        AppGlyph(GlyphName.More, "更多操作")
                    }
                    DropdownMenu(expanded = topMenuOpen, onDismissRequest = { topMenuOpen = false }) {
                        DropdownMenuItem(text = { Text("归档会话") }, enabled = !busy, onClick = {
                            topMenuOpen = false; archiveMode = true; query = ""; searchOpen = false
                        })
                    }
                }
            }
            IconButton(onClick = onClose) { AppGlyph(GlyphName.Close, "关闭侧边栏", Modifier.size(20.dp)) }
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (searchOpen) {
                Surface(color = colors.surfaceContainerLow, shape = RoundedCornerShape(10.dp), modifier = Modifier.weight(1f)) {
                    Row(Modifier.heightIn(min = 48.dp).padding(start = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        BasicTextField(query, { query = it }, Modifier.weight(1f).testTag("sidebar-search"), singleLine = true,
                            textStyle = MaterialTheme.typography.bodyMedium.copy(color = colors.onSurface),
                            decorationBox = { field -> Box { if (query.isEmpty()) Text(if (archiveMode) "搜索归档…" else "搜索对话与工作区…", color = colors.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium); field() } })
                        IconButton(onClick = { query = ""; searchOpen = false }) { AppGlyph(GlyphName.Close, "收起搜索", Modifier.size(18.dp)) }
                    }
                }
            } else Surface(onClick = { searchOpen = true }, color = colors.surfaceContainerLow, shape = RoundedCornerShape(10.dp), modifier = Modifier.weight(1f).testTag("sidebar-search-open")) {
                Row(Modifier.heightIn(min = 48.dp).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    AppGlyph(GlyphName.Search, "搜索对话与工作区", Modifier.size(18.dp), tint = colors.onSurfaceVariant)
                    Text(if (archiveMode) "搜索归档…" else "搜索对话与工作区…", Modifier.padding(start = 8.dp), style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
                }
            }
            if (!archiveMode) {
                val compactAction = LocalDensity.current.fontScale > 1.3f || androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp < 360
                FilledTonalButton(onClick = { onNewSession(null) }, enabled = !busy,
                    shape = RoundedCornerShape(10.dp), contentPadding = PaddingValues(horizontal = 12.dp),
                    modifier = Modifier.heightIn(min = 48.dp).testTag("sidebar-new-session"),
                    colors = ButtonDefaults.filledTonalButtonColors(containerColor = colors.primaryContainer, contentColor = colors.primary)) {
                    AppGlyph(GlyphName.Plus, "新对话", Modifier.size(18.dp))
                    if (!compactAction) Text("新对话", Modifier.padding(start = 6.dp), style = MaterialTheme.typography.labelLarge)
                }
            }
        }
        if (!archiveMode) {
            Surface(
                onClick = onAutomationTasks,
                color = Color.Transparent,
                modifier = Modifier.fillMaxWidth().testTag("sidebar-automation-tasks"),
            ) {
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    AppGlyph(GlyphName.Bolt, modifier = Modifier.size(20.dp))
                    Text("自动化任务", style = MaterialTheme.typography.bodyLarge)
                }
            }
        }
        if (searchOpen && !archiveMode) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Checkbox(
                    checked = includeArchived, onCheckedChange = { includeArchived = it }, enabled = !busy,
                    modifier = Modifier.testTag("sidebar-include-archived"),
                )
                Text("包含归档会话", style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant)
            }
        }
        // Single row: the three filters fit any supported width and avoid the newer FlowRow overload.
        if (!archiveMode) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = filter == "all", onClick = { filter = "all" }, label = { Text("全部") })
            FilterChip(selected = filter == "running", onClick = { filter = "running" }, label = { Text("运行中") })
            FilterChip(selected = filter == "approval", onClick = { filter = "approval" }, label = { Text("待确认") })
        }
        if (!archiveMode) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("工作区", Modifier.weight(1f).padding(start = 4.dp), style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant)
                IconButton(onClick = { editingId = null; name = ""; editorOpen = true }, enabled = !busy) { AppGlyph(GlyphName.Plus, "新建工作区", Modifier.size(18.dp)) }
            }
        }

      }
      Row(Modifier.align(Alignment.BottomCenter).fillMaxWidth().onSizeChanged { footerHeight = it.height }
          .padding(horizontal = 12.dp, vertical = 4.dp).heightIn(min = 56.dp).testTag("sidebar-footer"), verticalAlignment = Alignment.CenterVertically) {
          // Reserved for a future avatar + account name. No fake account or dead target.
          Spacer(Modifier.weight(1f))
          FrostedSurface(radius = 24.dp) {
              IconButton(onClick = onSettings, modifier = Modifier.testTag("sidebar-settings")) {
                  AppGlyph(GlyphName.Settings, "设置", Modifier.size(21.dp), tint = colors.onSurfaceVariant)
              }
          }
      }
    }
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
    if (sessionEditorOpen) {
        val id = sessionEditingId
        Dialog(onDismissRequest = { if (!busy) sessionEditorOpen = false }) {
            Surface(shape = RoundedCornerShape(24.dp), color = colors.surfaceContainerLow, tonalElevation = 6.dp) {
                Column(Modifier.widthIn(max = 380.dp).padding(20.dp)) {
                    Text("重命名对话", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(12.dp))
                    SessionNameEditor(
                        name = sessionName, busy = busy, error = error,
                        onNameChange = { sessionName = it },
                        onConfirm = { trimmed ->
                            if (id == null) sessionEditorOpen = false
                            else onRenameSession(id, trimmed) { sessionEditorOpen = false }
                        },
                        onDismiss = { if (!busy) sessionEditorOpen = false },
                    )
                }
            }
        }
    }
}

/**
 * Self-contained editor panel. Production shows it inside [Dialog]; component tests render it
 * inline so validation and trimming are exercised without a second window. The dialog only closes
 * through the caller's success callback, so a failed rename keeps the text for correction.
 */
@Composable
fun SessionNameEditor(
    name: String,
    busy: Boolean,
    error: String?,
    onNameChange: (String) -> Unit,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val validation = sidebarSessionNameError(name)
    var submitting by remember { mutableStateOf(false) }
    // A backend turn flips busy or publishes an error; either one releases the local latch.
    LaunchedEffect(busy, error) { if (!busy) submitting = false }
    Column(Modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = name, onValueChange = onNameChange, singleLine = true, enabled = !busy && !submitting,
            label = { Text("对话名称") }, isError = name.isNotEmpty() && validation != null,
            supportingText = { Text(validation ?: "1–120 个字符，仅更改显示名称") },
            modifier = Modifier.fillMaxWidth().testTag("sidebar-session-name"),
        )
        if (!busy && !submitting && validation == null && error != null) {
            Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 2.dp))
        }
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = onDismiss, enabled = !busy && !submitting) { Text("取消") }
            TextButton(onClick = { submitting = true; onConfirm(name.trim()) }, enabled = !busy && !submitting && validation == null) {
                Text("保存")
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
                modifier = Modifier.padding(top = 2.dp))
        }
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = onDismiss, enabled = !busy) { Text("取消") }
            TextButton(onClick = { onConfirm(name.trim()) }, enabled = !busy && validation == null) {
                Text(if (existing) "保存" else "创建")
            }
        }
    }
}
