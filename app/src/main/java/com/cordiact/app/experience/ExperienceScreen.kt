package com.cordiact.app.experience

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import com.cordiact.model.providers.ModelProtocol
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cordiact.app.experience.model.ToolActivity
import com.cordiact.app.experience.model.ToolPhase
import kotlinx.coroutines.launch

internal enum class StudioPane(val label: String) { Conversation("对话"), Activity("执行轨迹"), Artifacts("产物") }

@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
fun ExperienceScreen(viewModel: ExperienceViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val drawer = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    var pane by rememberSaveable { mutableStateOf(StudioPane.Conversation) }
    val saveable = rememberSaveableStateHolder()
    val saveableKey = "${state.workspace?.id}:${state.sessionId}"
    var approvalId by rememberSaveable { mutableStateOf<String?>(null) }
    var inspected by remember { mutableStateOf<ToolActivity?>(null) }
    var promptToInsert by remember { mutableStateOf("") }
    var settingsLink by remember { mutableStateOf<String?>(null) }
    var manageModels by remember { mutableStateOf(false) }
    val composer by viewModel.composer.collectAsStateWithLifecycle()
    val settingsApp = LocalContext.current.applicationContext as com.cordiact.app.CordiActApplication
    val settingsVm: com.cordiact.app.experience.settings.SettingsViewModel = androidx.lifecycle.viewmodel.compose.viewModel(
        factory = com.cordiact.app.experience.settings.SettingsViewModel.factory(
            remember(settingsApp) { com.cordiact.app.experience.settings.AndroidSettingsFacade(settingsApp) }))
    fun openSettings(link: String? = null) { settingsLink = link; inspected = null; manageModels = true }
    if (manageModels) {
        com.cordiact.app.experience.settings.SettingsHost(settingsVm, initialLink = settingsLink) { manageModels = false; settingsLink = null; viewModel.refreshSettingsModels() }
        return
    }
    val wide = LocalConfiguration.current.screenWidthDp >= 840
    var showWideSidebar by rememberSaveable { mutableStateOf(true) }
    val closeSidebar: () -> Unit = { if (wide) showWideSidebar = false else scope.launch { drawer.close() } }
    val closeAfterSelection: () -> Unit = { pane = StudioPane.Conversation; inspected = null; if (!wide) closeSidebar() }
    BackHandler(wide && showWideSidebar && !manageModels) { showWideSidebar = false }
    BackHandler(pane != StudioPane.Conversation && !drawer.isOpen) { pane = StudioPane.Conversation }
    BackHandler(inspected != null && !drawer.isOpen) { inspected = null }
    val sidebar: @Composable () -> Unit = {
        saveable.SaveableStateProvider("navigation-sidebar") {
            androidx.compose.runtime.CompositionLocalProvider(LocalApprovalOwners provides state.approvalOwners) {
                ConversationSidebar(
                    groups = state.sidebar, activeWorkspaceId = state.workspace?.id, activeSessionId = state.sessionId,
                    runningSessionIds = state.runningSessionIds, busy = state.loading, error = state.error,
                    onClose = closeSidebar,
                    onNewSession = { workspace -> viewModel.newSession(workspace ?: state.workspace?.id, closeAfterSelection) },
                    onSelectWorkspace = { viewModel.selectWorkspace(it, closeAfterSelection) },
                    onSelectSession = { viewModel.selectSession(it, closeAfterSelection) },
                    onCreateWorkspace = { name, onSuccess -> viewModel.createWorkspace(name) { closeAfterSelection(); onSuccess() } },
                    onRenameWorkspace = { id, name, onSuccess -> viewModel.renameWorkspace(id, name, onSuccess) },
                    onRenameSession = { id, title, done -> viewModel.renameSession(id, title, done) },
                    onArchiveSession = viewModel::archiveSession,
                    onRestoreSession = { viewModel.restoreSession(it) },
                    onPinSession = viewModel::pinSession,
                    onRetry = viewModel::refresh,
                    onSettings = { closeAfterSelection(); settingsVm.reload(); openSettings() },
                    onAutomationTasks = {
                        closeAfterSelection()
                        settingsVm.reload()
                        openSettings("settings://tasks")
                    },
                )
            }
        }
    }
    val workbench: @Composable () -> Unit = {
        Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
            Box(Modifier.fillMaxSize()) {
                ExperienceFrame(
                    top = {
                    val openApprovals = {
                        viewModel.composerController.popover(null)
                        approvalId = (state.currentApprovals.firstOrNull() ?: state.approvals.firstOrNull())?.id
                    }
                    LumenTopChrome(
                        workspace = state.workspace?.name ?: "CordiAct",
                        session = state.sessions.find { it.id == state.sessionId }?.title?.ifBlank { "新对话" } ?: "新对话",
                        mode = state.islandMode(),
                        menuLabel = "打开会话",
                        onMenu = { if (wide) showWideSidebar = !showWideSidebar else scope.launch { drawer.open() } },
                        onIsland = { pane = if (pane == StudioPane.Conversation && state.turns.any { it.tools.isNotEmpty() }) StudioPane.Activity else StudioPane.Conversation },
                        onApprovals = openApprovals,
                    ) {
                        StudioPaneMenu(pane, state.activeTools,
                            com.cordiact.app.render.PreviewInbox.unseenCount(state.sessionId),
                            onSelect = { pane = it })
                    }
                    },
                    content = { viewportModifier ->
                    Column(viewportModifier) {
                    if (state.error != null) Box(Modifier.padding(horizontal = 18.dp, vertical = 5.dp)) { ErrorStrip(state.error!!, viewModel::dismissError, { openSettings() }) }
                    Box(Modifier.weight(1f).fillMaxWidth()) {
                        saveable.SaveableStateProvider("$saveableKey:${pane.name}") {
                            when (pane) {
                                StudioPane.Conversation -> TranscriptView(state, onPrompt = { promptToInsert = it }, onInspect = { inspected = it })
                                StudioPane.Activity -> TrajectoryView(state, onInspect = { inspected = it })
                                StudioPane.Artifacts -> PreviewView(state, modifier = Modifier.padding(LocalChromePadding.current))
                            }
                        }
                    }
                    }
                    },
                    bottom = {
                    if (state.undoArchivedSessionId != null) {
                        Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text("会话已归档，运行任务不受影响", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                            TextButton(onClick = viewModel::undoArchive, enabled = !state.loading) { Text("撤销") }
                            TextButton(onClick = viewModel::dismissArchiveUndo) { Text("关闭") }
                        }
                    }
                    if (state.archived) {
                        Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text("此会话已归档", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                            if (state.running) TextButton(onClick = viewModel::stop) { Text("停止") }
                            TextButton(onClick = { state.sessionId?.let { viewModel.restoreSession(it) } }, enabled = !state.loading) { Text("恢复并继续") }
                        }
                    } else com.cordiact.app.experience.composer.ComposerBar(
                        state, composer, viewModel.composerController, promptToInsert,
                        { promptToInsert = "" }, viewModel::send, viewModel::stop,
                        viewModel::selectModel, { openSettings("settings://models/providers") },
                        { openSettings("settings://security/permissions") },
                    )
                    },
                )
                approvalId?.let { id ->
                    val currentIds = state.currentApprovals.mapTo(hashSetOf()) { it.id }
                    val sortedRequests = state.approvals.sortedBy { it.id !in currentIds }
                    ApprovalDecisionDialog(sortedRequests, state.decidingApprovals, state.approvalErrors,
                        ownerLabel = { owner ->
                            val group = owner?.let { key -> state.sidebar.firstOrNull { it.id == key.workspaceId } }
                            val title = group?.sessions?.firstOrNull { it.id == owner.sessionId }?.title
                            when {
                                owner == null -> "全局请求"
                                group == null -> "工作区审批"
                                title != null -> "${group.name} / $title"
                                owner.sessionId != null -> "${group.name} / 其他会话"
                                else -> "${group.name} / 工作区请求"
                            }
                        },
                        selected = id, onSelect = { approvalId = it }, onDismiss = { approvalId = null }, onDecide = viewModel::decide)
                }
                inspected?.let { step ->
                    CompactInspector(step = step, onClose = { inspected = null }, onSettings = { link -> openSettings(link) }, modifier = Modifier.align(Alignment.BottomCenter).windowInsetsPadding(WindowInsets.systemBars.union(WindowInsets.ime)))
                }

            }
        }
    }
    if (wide) {
        Row(Modifier.fillMaxSize()) {
            if (showWideSidebar) {
                Box(Modifier.width(300.dp).fillMaxHeight().windowInsetsPadding(WindowInsets.systemBars)) { sidebar() }
                VerticalDivider()
            }
            Box(Modifier.weight(1f).fillMaxHeight()) { workbench() }
        }
    } else {
        ModalNavigationDrawer(drawerState = drawer, gesturesEnabled = drawer.isOpen || pane == StudioPane.Conversation,
            drawerContent = {
                ModalDrawerSheet(Modifier.width(minOf(LocalConfiguration.current.screenWidthDp.dp * .84f, 360.dp)),
                    drawerContainerColor = MaterialTheme.colorScheme.surfaceContainerLowest) { sidebar() }
            }, content = workbench)
    }

}

@Composable
internal fun CompactInspector(step: ToolActivity, onClose: () -> Unit, modifier: Modifier = Modifier, onSettings: ((String?) -> Unit)? = null) {
    val clipboard = LocalClipboardManager.current
    BoxWithConstraints(modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
        // Landscape: a side panel beside the transcript instead of a sheet squeezed into 360dp of height.
        val side = maxWidth > maxHeight && maxWidth >= 600.dp
        val maxSheet = if (side) maxHeight else maxHeight * .45f
        Surface((if (side) Modifier.align(Alignment.CenterEnd).width(minOf(420.dp, maxWidth * .45f)).fillMaxHeight()
                else Modifier.fillMaxWidth()).heightIn(max = maxSheet).padding(horizontal = 8.dp, vertical = 4.dp).testTag("tool-inspector"), shape = RoundedCornerShape(23.dp), shadowElevation = 12.dp, color = MaterialTheme.colorScheme.surface, border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
            Column {
                PeekHandle(onClose)
                Row(Modifier.padding(start = 17.dp, end = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                    AppGlyph(GlyphName.Terminal, modifier = Modifier.size(17.dp), tint = MaterialTheme.colorScheme.primary)
                    Text(step.name, Modifier.weight(1f).padding(horizontal = 9.dp), style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    IconButton(onClick = { clipboard.setText(AnnotatedString(listOf(step.arguments, step.summary.orEmpty()).filter { it.isNotBlank() }.joinToString("\n\n"))) }) { AppGlyph(GlyphName.Copy, "复制工具详情", modifier = Modifier.size(17.dp)) }
                    IconButton(onClick = onClose) { AppGlyph(GlyphName.Close, "关闭工具详情", modifier = Modifier.size(17.dp)) }
                }
                if (step.phase == ToolPhase.FAILED && onSettings != null) {
                    val link = when {
                        step.name.startsWith("ssh_") -> "settings://ssh"
                        step.name.startsWith("termux_") -> "settings://connections/termux"
                        step.name.startsWith("browser_") -> "settings://connections/browser"
                        step.name.startsWith("mcp") || step.name.startsWith("mcp_") -> "settings://mcp"
                        step.name in setOf("root_exec", "shizuku_exec") -> "settings://connections/privilege"
                        step.name in setOf("take_screenshot", "read_window_tree", "click_node", "tap", "swipe", "set_text", "scroll", "global_action") -> "settings://connections/screen"
                        step.name.startsWith("web_") || step.name == "search_web" -> "settings://search"
                        else -> null
                    }
                    TextButton(onClick = { onSettings(link) }, modifier = Modifier.padding(horizontal = 16.dp)) { Text("前往设置检查连接与权限") }
                }
                SelectionContainer(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()).padding(horizontal = 17.dp, vertical = 12.dp)) {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        if (step.arguments.isNotBlank()) { Text("输入参数", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant); Text(step.arguments, fontFamily = FontFamily.Monospace, fontSize = 12.sp, lineHeight = 18.sp) }
                        Text("返回内容", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(step.summary ?: "工具尚未返回内容", fontFamily = FontFamily.Monospace, fontSize = 12.sp, lineHeight = 18.sp)
                    }
                }
            }
        }
    }
}

@Composable
private fun PeekHandle(onDismiss: () -> Unit) {
    var distance by remember { mutableFloatStateOf(0f) }
    Box(
        Modifier.fillMaxWidth().height(28.dp)
            .pointerInput(onDismiss) {
                detectVerticalDragGestures(
                    onDragStart = { distance = 0f },
                    onDragEnd = { if (distance > 24.dp.toPx()) onDismiss() },
                    onVerticalDrag = { change, amount -> distance += amount; change.consume() },
                )
            }
            .semantics { contentDescription = "下滑关闭面板" },
        contentAlignment = Alignment.Center,
    ) { Box(Modifier.width(28.dp).height(3.dp).background(MaterialTheme.colorScheme.outlineVariant, CircleShape)) }
}
