package com.cordiact.app.experience

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.cordiact.app.experience.model.OverviewSession
import com.cordiact.app.experience.model.OverviewStatus
import com.cordiact.app.experience.model.SidebarWorkspace
import com.cordiact.app.experience.model.overviewSessions
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeSource
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Owns the body only. The overview still owns system bars, header, settings and footer. */
@Composable
internal fun AllSessionsPane(
    state: ExperienceState,
    viewModel: ExperienceViewModel,
    onOpenSession: (String) -> Unit,
    onWorkspaceOpened: () -> Unit,
    onAutomationTasks: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var query by rememberSaveable { mutableStateOf("") }
    var archiveQuery by rememberSaveable { mutableStateOf("") }
    var workspaceId by rememberSaveable { mutableStateOf<String?>(null) }
    var status by rememberSaveable { mutableStateOf(OverviewStatus.ALL) }
    var archived by rememberSaveable { mutableStateOf(false) }
    var manageWorkspaces by rememberSaveable { mutableStateOf(false) }
    var editingType by rememberSaveable { mutableStateOf<String?>(null) }
    var editingId by rememberSaveable { mutableStateOf<String?>(null) }
    var name by rememberSaveable { mutableStateOf("") }
    var confirmArchive by rememberSaveable { mutableStateOf<String?>(null) }
    val selectedWorkspace = state.sidebar.firstOrNull { it.id == workspaceId }
    val running = if (state.running) state.runningSessionIds + listOfNotNull(state.sessionId) else state.runningSessionIds
    val owners = remember(state.approvals) { state.approvalOwners.toSet() }
    val search = if (archived) archiveQuery else query
    val matches = remember(state.sidebar, search, selectedWorkspace, status, archived, running, owners) {
        overviewSessions(state.sidebar, search, selectedWorkspace?.id.takeUnless { archived },
            if (archived) OverviewStatus.ALL else status, archived, running, owners)
    }
    val activeScroll = rememberLazyListState()
    val archiveScroll = rememberLazyListState()
    val listState = if (archived) archiveScroll else activeScroll
    fun changeSearch(value: String) {
        listState.requestScrollToItem(0)
        if (archived) archiveQuery = value else query = value
    }
    fun changeWorkspace(id: String?) {
        if (workspaceId != id) activeScroll.requestScrollToItem(0)
        workspaceId = id
    }
    fun changeStatus(value: OverviewStatus) {
        if (status != value) activeScroll.requestScrollToItem(0)
        status = value
    }
    BackHandler(archived) { archived = false }

    fun edit(type: String, id: String?, initial: String) {
        viewModel.dismissError()
        editingType = type
        editingId = id
        name = initial
    }
    val searchBox: @Composable () -> Unit = {
        OutlinedTextField(
            value = search, onValueChange = ::changeSearch,
            modifier = Modifier.fillMaxWidth().testTag("all-sessions-search"),
            placeholder = { Text(if (archived) "搜索已归档会话" else "搜索会话或工作区", maxLines = 1, overflow = TextOverflow.Ellipsis) },
            leadingIcon = { AppGlyph(GlyphName.Search) },
            trailingIcon = if (search.isNotEmpty()) ({
                IconButton(onClick = { changeSearch("") }) { AppGlyph(GlyphName.Close, "清除搜索") }
            }) else null,
            singleLine = true, shape = RoundedCornerShape(12.dp),
            colors = OutlinedTextFieldDefaults.colors(
                unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .6f),
            ),
        )
    }
    val manage: @Composable () -> Unit = {
        SessionManagementMenu(archived, onArchive = { archived = !archived },
            onWorkspaces = { manageWorkspaces = true }, onAutomationTasks = onAutomationTasks)
    }
    val resultList: @Composable (Modifier) -> Unit = { resultModifier ->
        SessionResults(matches, listState, resultModifier, archived, state.loading, state.sessionId,
            canReset = search.isNotBlank() || (!archived && (selectedWorkspace != null || status != OverviewStatus.ALL)),
            onReset = { changeSearch(""); if (!archived) { changeWorkspace(null); changeStatus(OverviewStatus.ALL) } },
            onOpen = onOpenSession,
            onRename = { edit("session", it.session.id, it.session.title) },
            onPin = { viewModel.pinSession(it.session.id, !it.session.isPinned) },
            onArchive = { if (it.running || it.pending) confirmArchive = it.session.id else viewModel.archiveSession(it.session.id) },
            onRestore = { viewModel.restoreSession(it.session.id) })
    }
    val configuration = LocalConfiguration.current
    BoxWithConstraints(modifier.padding(horizontal = 16.dp).testTag("all-sessions-pane")) {
        val sideBySide = maxWidth >= 640.dp && configuration.screenWidthDp > configuration.screenHeightDp
        val railWidth = (maxWidth * .26f).coerceIn(176.dp, 220.dp)
        Column(Modifier.fillMaxSize()) {
            if (state.error != null && editingType == null) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(state.error, Modifier.weight(1f), color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    TextButton(onClick = viewModel::refresh, enabled = !state.loading) { Text("刷新") }
                    IconButton(onClick = viewModel::dismissError) { AppGlyph(GlyphName.Close, "关闭错误提示") }
                }
            }
            if (state.undoArchivedSessionId != null) {
                Row(Modifier.fillMaxWidth().padding(start = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("会话已归档", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = viewModel::undoArchive, enabled = !state.loading) { Text("撤销") }
                    IconButton(onClick = viewModel::dismissArchiveUndo) { AppGlyph(GlyphName.Close, "关闭归档提示") }
                }
            }
            if (sideBySide) {
                Row(Modifier.weight(1f).fillMaxWidth().testTag("all-sessions-landscape")) {
                    Column(Modifier.width(railWidth)
                        .fillMaxHeight().verticalScroll(rememberScrollState()).padding(end = 10.dp)) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text(if (archived) "已归档" else "工作区", Modifier.weight(1f).semantics { heading() },
                                style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            manage()
                        }
                        if (!archived) {
                            WorkspaceScopeRow("所有工作区", selectedWorkspace == null) { changeWorkspace(null) }
                            state.sidebar.forEach { group ->
                                WorkspaceScopeRow(group.name, selectedWorkspace?.id == group.id) { changeWorkspace(group.id) }
                            }
                            Text("会话状态", Modifier.padding(top = 10.dp, bottom = 2.dp).semantics { heading() },
                                style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            // Use stable Row/Column APIs: the packaged Compose lacks the FlowRow overflow overload.
                            val compactLabels = LocalConfiguration.current.fontScale <= 1.15f
                            val statusChips: @Composable () -> Unit = {
                                OverviewStatus.entries.forEach { item ->
                                    FilterChip(status == item, { changeStatus(item) }, { Text(item.label) })
                                }
                            }
                            if (compactLabels && railWidth >= 204.dp) {
                                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) { statusChips() }
                            } else Column { statusChips() }
                        }
                    }
                    VerticalDivider()
                    Column(Modifier.weight(1f).padding(start = 12.dp)) {
                        searchBox()
                        resultList(Modifier.weight(1f))
                    }
                }
            } else {
                searchBox()
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    if (!archived) Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        ScopeMenu(selectedWorkspace?.name ?: "所有工作区", selectedWorkspace != null,
                            modifier = Modifier.weight(1f, fill = false)) { dismiss ->
                            DropdownMenuItem(text = { Text("所有工作区") }, onClick = { changeWorkspace(null); dismiss() },
                                trailingIcon = if (selectedWorkspace == null) ({ AppGlyph(GlyphName.Check) }) else null)
                            state.sidebar.forEach { group ->
                                DropdownMenuItem(text = { Text(group.name) }, onClick = { changeWorkspace(group.id); dismiss() },
                                    trailingIcon = if (selectedWorkspace?.id == group.id) ({ AppGlyph(GlyphName.Check) }) else null)
                            }
                        }
                        ScopeMenu(if (status == OverviewStatus.ALL) "全部状态" else status.label, status != OverviewStatus.ALL,
                            modifier = Modifier.weight(1f, fill = false)) { dismiss ->
                            OverviewStatus.entries.forEach { item ->
                                DropdownMenuItem(text = { Text(item.label) }, onClick = { changeStatus(item); dismiss() },
                                    trailingIcon = if (status == item) ({ AppGlyph(GlyphName.Check) }) else null)
                            }
                        }
                    } else Spacer(Modifier.weight(1f))
                    manage()
                }
                resultList(Modifier.weight(1f))
            }
        }
    }

    if (manageWorkspaces && editingType == null) {
        AlertDialog(onDismissRequest = { manageWorkspaces = false }, title = { Text("管理工作区") },
            text = {
                Column(Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState())) {
                    state.sidebar.forEach { group ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            TextButton(onClick = { viewModel.selectWorkspace(group.id, onWorkspaceOpened) },
                                enabled = !state.loading, modifier = Modifier.weight(1f)) {
                                Text(group.name, Modifier.fillMaxWidth(), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            TextButton(onClick = { edit("workspace", group.id, group.name) }, enabled = !state.loading) { Text("重命名") }
                        }
                    }
                    TextButton(onClick = { edit("workspace", null, "") }, enabled = !state.loading) { Text("＋ 新建工作区") }
                }
            }, confirmButton = { TextButton(onClick = { manageWorkspaces = false }) { Text("完成") } })
    }
    editingType?.let { type ->
        val id = editingId
        val dismiss = { editingType = null; editingId = null; viewModel.dismissError() }
        AlertDialog(onDismissRequest = { if (!state.loading) dismiss() },
            title = { Text(if (id == null) "新建工作区" else if (type == "session") "重命名会话" else "重命名工作区") },
            text = {
                if (type == "session") SessionNameEditor(name, state.loading, state.error, { name = it },
                    { value -> id?.let { viewModel.renameSession(it, value, dismiss) } }, dismiss)
                else WorkspaceNameEditor(name, id != null, state.loading, state.error, { name = it }, { value ->
                    if (id == null) viewModel.createWorkspace(value) { dismiss(); onWorkspaceOpened() }
                    else viewModel.renameWorkspace(id, value, dismiss)
                }, dismiss)
            }, confirmButton = {})
    }
    confirmArchive?.let { id ->
        AlertDialog(onDismissRequest = { confirmArchive = null }, title = { Text("归档会话？") },
            text = { Text("归档不会停止正在运行的任务，也不会处理待确认请求。") },
            confirmButton = { TextButton(onClick = { confirmArchive = null; viewModel.archiveSession(id) }) { Text("归档") } },
            dismissButton = { TextButton(onClick = { confirmArchive = null }) { Text("取消") } })
    }
}

@Composable
private fun ScopeMenu(label: String, active: Boolean, modifier: Modifier = Modifier,
    content: @Composable ColumnScope.(() -> Unit) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Box(modifier) {
        FilterChip(active, { open = true }, { Text(label, Modifier.widthIn(max = 120.dp), maxLines = 1, overflow = TextOverflow.Ellipsis) },
            trailingIcon = { AppGlyph(GlyphName.ChevronDown, modifier = Modifier.size(14.dp)) })
        DropdownMenu(open, { open = false }) { content { open = false } }
    }
}

@Composable
private fun WorkspaceScopeRow(label: String, active: Boolean, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Surface(selected = active, onClick = onClick, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(8.dp),
        color = if (active) colors.primaryContainer else colors.background,
        contentColor = if (active) colors.onPrimaryContainer else colors.onSurface) {
        Row(Modifier.heightIn(min = 48.dp).padding(horizontal = 7.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            AppGlyph(GlyphName.Folder, modifier = Modifier.size(15.dp))
            Text(label, Modifier.weight(1f), style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (active) AppGlyph(GlyphName.Check, modifier = Modifier.size(14.dp))
        }
    }
}

@Composable
private fun SessionManagementMenu(archived: Boolean, onArchive: () -> Unit, onWorkspaces: () -> Unit, onAutomationTasks: () -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        TextButton(onClick = { if (archived) onArchive() else expanded = true }) {
            Text(if (archived) "返回全部" else "管理", maxLines = 1)
            if (!archived) AppGlyph(GlyphName.More, modifier = Modifier.padding(start = 3.dp).size(15.dp))
        }
        DropdownMenu(expanded, { expanded = false }) {
            DropdownMenuItem(text = { Text("已归档") }, onClick = { expanded = false; onArchive() })
            DropdownMenuItem(text = { Text("管理工作区") }, onClick = { expanded = false; onWorkspaces() })
            DropdownMenuItem(text = { Text("自动化任务") }, onClick = { expanded = false; onAutomationTasks() })
        }
    }
}

@Composable
private fun SessionResults(
    rows: List<OverviewSession>, listState: LazyListState, modifier: Modifier,
    archived: Boolean, busy: Boolean, current: String?, canReset: Boolean, onReset: () -> Unit,
    onOpen: (String) -> Unit, onRename: (OverviewSession) -> Unit, onPin: (OverviewSession) -> Unit,
    onArchive: (OverviewSession) -> Unit, onRestore: (OverviewSession) -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val haze = remember { HazeState() }
    val groups = remember(rows, archived) {
        if (archived) listOf("已归档" to rows) else {
            val (pinned, recent) = rows.partition { it.session.isPinned }
            listOf("置顶" to pinned, "最近" to recent)
        }
    }
    Box(modifier.fillMaxWidth().testTag("all-sessions-results")) {
        if (rows.isEmpty()) {
            Column(Modifier.align(Alignment.Center).padding(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                when {
                    busy -> CircularProgressIndicator(Modifier.size(26.dp), strokeWidth = 2.dp)
                    canReset -> {
                        Text("没有匹配的会话", style = MaterialTheme.typography.titleSmall)
                        TextButton(onClick = onReset) { Text("清除筛选") }
                    }
                    else -> Text(if (archived) "暂无归档会话" else "还没有会话", color = colors.onSurfaceVariant)
                }
            }
        } else {
            val timezone = remember { ZoneId.systemDefault() }
            val today = LocalDate.now(timezone)
            LazyColumn(Modifier.fillMaxSize().hazeSource(haze), state = listState, contentPadding = PaddingValues(bottom = 8.dp)) {
                groups.filter { it.second.isNotEmpty() }.forEach { (title, items) ->
                    item("group:$title") {
                        Row(Modifier.fillMaxWidth().padding(start = 3.dp, end = 3.dp, top = 7.dp, bottom = 5.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            Text(title, Modifier.weight(1f).semantics { heading() }, style = MaterialTheme.typography.labelMedium)
                            Text("${items.size} 条", style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
                        }
                    }
                    items(items.size, key = { "session:${items[it].session.id}" }) { index ->
                        val row = items[index]
                        val top = if (index == 0) 14.dp else 0.dp
                        val bottom = if (index == items.lastIndex) 14.dp else 0.dp
                        Surface(color = if (!archived && row.session.id == current) colors.primaryContainer.copy(alpha = .40f) else colors.surface,
                            shape = RoundedCornerShape(top, top, bottom, bottom), border = BorderStroke(.65.dp, colors.outlineVariant)) {
                            OverviewSessionRow(row, row.session.id == current, archived, busy,
                                sessionTime(if (archived) row.session.archivedAtMillis ?: row.session.updatedAtMillis else row.session.updatedAtMillis, timezone, today),
                                onOpen = { onOpen(row.session.id) }, onRename = { onRename(row) }, onPin = { onPin(row) },
                                onArchive = { onArchive(row) }, onRestore = { onRestore(row) })
                        }
                    }
                }
            }
            if (listState.canScrollBackward) ViewportFade(haze, true, Modifier.align(Alignment.TopCenter).fillMaxWidth().height(9.dp))
            if (listState.canScrollForward) ViewportFade(haze, false, Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(9.dp))
        }
    }
}

@Composable
private fun OverviewSessionRow(
    row: OverviewSession, current: Boolean, archived: Boolean, busy: Boolean, time: String,
    onOpen: () -> Unit, onRename: () -> Unit, onPin: () -> Unit, onArchive: () -> Unit, onRestore: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    var open by remember { mutableStateOf(false) }
    val title = row.session.title.ifBlank { "新对话" }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Surface(onClick = onOpen, enabled = !busy && !archived, color = androidx.compose.ui.graphics.Color.Transparent,
            modifier = Modifier.weight(1f).testTag("all-session-${row.session.id}")) {
            Row(Modifier.heightIn(min = 54.dp).padding(start = 10.dp, top = 4.dp, bottom = 4.dp, end = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(row.workspace.name + if (current && !archived) " · 当前" else "", style = MaterialTheme.typography.bodySmall,
                        color = colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                val hasState = !archived && (row.pending || row.running)
                if (hasState) {
                    SignalDot(tint = if (row.pending) Lumen.Approve else colors.primary, size = 5.dp)
                    Text(if (row.pending) "待确认" else "运行中", style = MaterialTheme.typography.labelSmall,
                        color = if (row.pending) Lumen.Approve else colors.primary, maxLines = 1)
                } else Text(time, style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant, maxLines = 1)
            }
        }
        if (archived) {
            TextButton(onClick = onRestore, enabled = !busy) { Text("恢复", maxLines = 1) }
        } else Box {
            IconButton(onClick = { open = true }, enabled = !busy) {
                AppGlyph(GlyphName.More, "会话操作：$title", modifier = Modifier.size(17.dp))
            }
            DropdownMenu(open, { open = false }) {
                DropdownMenuItem(text = { Text("重命名") }, onClick = { open = false; onRename() })
                DropdownMenuItem(text = { Text(if (row.session.isPinned) "取消置顶" else "置顶") }, onClick = { open = false; onPin() })
                DropdownMenuItem(text = { Text("归档") }, onClick = { open = false; onArchive() })
            }
        }
    }
}

private val shortTime = DateTimeFormatter.ofPattern("HH:mm")
private val shortDate = DateTimeFormatter.ofPattern("M月d日")
private val fullDate = DateTimeFormatter.ofPattern("yyyy/M/d")
private fun sessionTime(millis: Long, zone: ZoneId, today: LocalDate): String {
    val timestamp = Instant.ofEpochMilli(millis).atZone(zone)
    val day = timestamp.toLocalDate()
    return when {
        day == today -> timestamp.format(shortTime)
        day == today.minusDays(1) -> "昨天"
        day.year == today.year -> timestamp.format(shortDate)
        else -> timestamp.format(fullDate)
    }
}
