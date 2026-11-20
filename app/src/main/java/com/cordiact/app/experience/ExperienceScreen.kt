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
    val scope = rememberCoroutineScope()
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
    val tabContext = LocalContext.current.applicationContext
    val tabs = remember { com.cordiact.app.experience.tabs.registerBuiltinTabs(); com.cordiact.app.experience.tabs.TabsController(com.cordiact.app.experience.tabs.PrefsTabStore(tabContext)) }
    var overview by rememberSaveable { mutableStateOf(false) }
    val activeTab = tabs.active
    LaunchedEffect(state.sessionId) { tabs.followSession(state.sessionId) }
    LaunchedEffect(state.sidebar) {
        val live = state.sidebar.flatMap { it.sessions }.filter { !it.isArchived }.map { it.id }.toSet()
        if (live.isNotEmpty()) tabs.prune(live + setOfNotNull(state.sessionId), com.cordiact.app.experience.tabs.TabRegistry.contributors.map { it.id }.toSet())
    }
    // A session tab is the selection: activating one moves the view model onto that session.
    LaunchedEffect(activeTab?.key) {
        val tab = activeTab as? com.cordiact.app.experience.tabs.WorkTab.Session ?: return@LaunchedEffect
        if (tab.sessionId != state.sessionId) viewModel.selectSession(tab.sessionId)
    }
    val tabHost = remember {
        object : com.cordiact.app.experience.tabs.TabHost {
            override fun inspect(step: ToolActivity) { inspected = step }
            override fun openSession(sessionId: String) { tabs.open(com.cordiact.app.experience.tabs.WorkTab.Session(sessionId)) }
        }
    }
    val ownerLabel: (com.cordiact.core.action.ApprovalOwner?) -> String = { owner ->
        val group = owner?.let { key -> state.sidebar.firstOrNull { it.id == key.workspaceId } }
        val title = group?.sessions?.firstOrNull { it.id == owner.sessionId }?.title
        when {
            owner == null -> "全局请求"
            group == null -> "工作区审批"
            title != null -> "${group.name} / $title"
            owner.sessionId != null -> "${group.name} / 其他会话"
            else -> "${group.name} / 工作区请求"
        }
    }
    val configuration = LocalConfiguration.current
    // Phone held sideways: the transcript becomes a three-pane command deck.
    val deckMode = configuration.screenWidthDp > configuration.screenHeightDp && configuration.screenWidthDp >= 720
    val graph by viewModel.graph.collectAsStateWithLifecycle()
    // Hoisted above the per-tab saveable scope: focusing a node in another session switches tabs.
    var deckFocus by rememberSaveable { mutableStateOf<String?>(null) }
    val closeAfterSelection: () -> Unit = { overview = false; inspected = null }
    BackHandler(overview) { overview = false }
    BackHandler(inspected != null && !overview) { inspected = null }
    // Landscape deck: the command line belongs to the centre column, so the side panes keep full height.
    val deckComposer: @Composable () -> Unit = {
        ArchiveRows(
            undoVisible = state.undoArchivedSessionId != null,
            archived = state.archived,
            running = state.running,
            loading = state.loading,
            horizontal = 16.dp,
            onUndo = viewModel::undoArchive,
            onDismiss = viewModel::dismissArchiveUndo,
            onStop = viewModel::stop,
            onRestore = { state.sessionId?.let { viewModel.restoreSession(it) } },
        )
        if (!state.archived) com.cordiact.app.experience.composer.ComposerBar(
            state, composer, viewModel.composerController, promptToInsert,
            { promptToInsert = "" }, viewModel::send, viewModel::stop,
            viewModel::selectModel, { openSettings("settings://models/providers") },
            { openSettings("settings://security/permissions") },
            compact = true,
        )
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
                    if (deckMode) DeckTopBar(
                        workspace = state.workspace?.name ?: "CordiAct",
                        approvalCount = state.currentApprovals.size,
                        onOverview = { overview = true },
                        onApprovals = openApprovals,
                        onNew = { viewModel.newSession(state.workspace?.id) },
                        enabledNew = !state.loading,
                        tabs = { stripModifier -> com.cordiact.app.experience.tabs.TabStrip(state, tabs, onClose = { tabs.close(it) }, modifier = stripModifier) },
                    ) else {
                    LumenTopChrome(
                        workspace = state.workspace?.name ?: "CordiAct",
                        session = state.sessions.find { it.id == state.sessionId }?.title?.ifBlank { "新对话" } ?: "新对话",
                        mode = state.islandMode(),
                        tabCount = tabs.tabs.size,
                        onMenu = { overview = true },
                        onIsland = {
                            if (state.turns.any { it.tools.isNotEmpty() }) tabs.open(com.cordiact.app.experience.tabs.WorkTab.Plugin(com.cordiact.app.experience.tabs.TraceTab.id))
                            else overview = true
                        },
                        onApprovals = openApprovals,
                    ) {
                        IconButton(onClick = { viewModel.newSession(state.workspace?.id) }, enabled = !state.loading,
                            modifier = Modifier.testTag("new-tab")) { AppGlyph(GlyphName.Plus, "新对话标签") }
                    }
                    com.cordiact.app.experience.tabs.TabStrip(state, tabs, onClose = { tabs.close(it) }, modifier = Modifier.padding(top = 4.dp, bottom = 2.dp))
                    }
                    },
                    content = { viewportModifier ->
                    Column(viewportModifier) {
                    if (state.error != null) Box(Modifier.padding(horizontal = 18.dp, vertical = 5.dp)) { ErrorStrip(state.error!!, viewModel::dismissError, { openSettings() }) }
                    Box(Modifier.weight(1f).fillMaxWidth()) {
                        // A tab click precedes the asynchronous session load. Key the transcript by
                        // the loaded session, so old text doesn't remount under the destination tab.
                        val contentTabKey = (activeTab as? com.cordiact.app.experience.tabs.WorkTab.Plugin)?.key
                            ?: state.sessionId?.let { "s:$it" }
                        saveable.SaveableStateProvider("$saveableKey:$contentTabKey") {
                            when (val tab = activeTab) {
                                is com.cordiact.app.experience.tabs.WorkTab.Plugin -> {
                                    val contributor = com.cordiact.app.experience.tabs.TabRegistry.find(tab.pluginId)
                                    if (contributor != null) contributor.Content(state, tabHost)
                                    else Text("该插件已卸载", Modifier.padding(LocalChromePadding.current).padding(24.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                else -> if (deckMode) {
                                    val openIds = tabs.tabs.filterIsInstance<com.cordiact.app.experience.tabs.WorkTab.Session>().map { it.sessionId }
                                        .ifEmpty { listOfNotNull(state.sessionId) }
                                    LaunchedEffect(openIds, state.sessionId, state.turns.size, state.running, state.activeTools) { viewModel.refreshGraph(openIds) }
                                    CommandDeck(state, graph, deckFocus, { deckFocus = it },
                                        onFocusSession = { id -> tabs.open(com.cordiact.app.experience.tabs.WorkTab.Session(id)) },
                                        onInspect = { inspected = it }, onDecide = viewModel::decide,
                                        onOpenApproval = { approvalId = it }, ownerLabel = ownerLabel,
                                        composer = deckComposer)
                                } else TranscriptView(state, onPrompt = { promptToInsert = it }, onInspect = { inspected = it })
                            }
                        }
                    }
                    }
                    },
                    bottom = {
                    if (!deckMode) {
                        ArchiveRows(
                            undoVisible = state.undoArchivedSessionId != null,
                            archived = state.archived,
                            running = state.running,
                            loading = state.loading,
                            onUndo = viewModel::undoArchive,
                            onDismiss = viewModel::dismissArchiveUndo,
                            onStop = viewModel::stop,
                            onRestore = { state.sessionId?.let { viewModel.restoreSession(it) } },
                        )
                        if (!state.archived) com.cordiact.app.experience.composer.ComposerBar(
                            state, composer, viewModel.composerController, promptToInsert,
                            { promptToInsert = "" }, viewModel::send, viewModel::stop,
                            viewModel::selectModel, { openSettings("settings://models/providers") },
                            { openSettings("settings://security/permissions") },
                        )
                    }
                    },
                )
                approvalId?.let { id ->
                    val currentIds = state.currentApprovals.mapTo(hashSetOf()) { it.id }
                    val sortedRequests = state.approvals.sortedBy { it.id !in currentIds }
                    ApprovalDecisionDialog(sortedRequests, state.decidingApprovals, state.approvalErrors,
                        ownerLabel = ownerLabel,
                        selected = id, onSelect = { approvalId = it }, onDismiss = { approvalId = null }, onDecide = viewModel::decide)
                }
                inspected?.let { step ->
                    CompactInspector(step = step, onClose = { inspected = null }, onSettings = { link -> openSettings(link) }, modifier = Modifier.align(Alignment.BottomCenter).windowInsetsPadding(WindowInsets.systemBars.union(WindowInsets.ime)))
                }

            }
        }
    }
    Box(Modifier.fillMaxSize()) {
        workbench()
        androidx.compose.animation.AnimatedVisibility(overview,
            enter = androidx.compose.animation.fadeIn(tween(com.cordiact.app.experience.composer.motionDuration(220))) + androidx.compose.animation.scaleIn(tween(com.cordiact.app.experience.composer.motionDuration(220)), initialScale = .96f),
            exit = androidx.compose.animation.fadeOut(tween(com.cordiact.app.experience.composer.motionDuration(160))) + androidx.compose.animation.scaleOut(tween(com.cordiact.app.experience.composer.motionDuration(160)), targetScale = .96f)) {
            com.cordiact.app.experience.tabs.TabOverview(
                state, tabs,
                onClose = { tabs.close(it) },
                onDismiss = { overview = false },
                onNewSession = { viewModel.newSession(state.workspace?.id, closeAfterSelection) },
                onSettings = { closeAfterSelection(); settingsVm.reload(); openSettings() },
            ) { m ->
                AllSessionsPane(
                    state, viewModel,
                    onOpenSession = { id ->
                        if (state.sessionId == id) {
                            tabs.open(com.cordiact.app.experience.tabs.WorkTab.Session(id))
                            closeAfterSelection()
                        } else viewModel.selectSession(id) {
                            tabs.open(com.cordiact.app.experience.tabs.WorkTab.Session(id))
                            closeAfterSelection()
                        }
                    },
                    onWorkspaceOpened = closeAfterSelection,
                    onAutomationTasks = { closeAfterSelection(); settingsVm.reload(); openSettings("settings://tasks") },
                    modifier = m,
                )
            }
        }
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

/** Archive banners shared by the portrait bottom bar and the landscape centre column. */
@Composable
private fun ArchiveRows(
    undoVisible: Boolean,
    archived: Boolean,
    running: Boolean,
    loading: Boolean,
    horizontal: androidx.compose.ui.unit.Dp = 18.dp,
    onUndo: () -> Unit,
    onDismiss: () -> Unit,
    onStop: () -> Unit,
    onRestore: () -> Unit,
) {
    if (undoVisible) {
        Row(Modifier.fillMaxWidth().padding(horizontal = horizontal), verticalAlignment = Alignment.CenterVertically) {
            Text("会话已归档，运行任务不受影响", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = onUndo, enabled = !loading) { Text("撤销") }
            TextButton(onClick = onDismiss) { Text("关闭") }
        }
    }
    if (archived) {
        Row(Modifier.fillMaxWidth().padding(horizontal = horizontal, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("此会话已归档", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            if (running) TextButton(onClick = onStop) { Text("停止") }
            TextButton(onClick = onRestore, enabled = !loading) { Text("恢复并继续") }
        }
    }
}
