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
import com.cordiact.app.experience.tabs.WorkTab
import com.cordiact.app.experience.tabs.PublishedTab
import com.cordiact.app.experience.tabs.TabHost
import com.cordiact.app.experience.tabs.TraceTab
import com.cordiact.app.experience.tabs.WorkbenchLayoutEditor
import com.cordiact.app.experience.tabs.WorkbenchProfiles
import com.cordiact.app.experience.tabs.WorkbenchProfileStore
import com.cordiact.core.workbench.PanelPlacement
import com.cordiact.core.workbench.WorkbenchTheme
import com.cordiact.interaction.InteractionContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal enum class StudioPane(val label: String) { Conversation("对话"), Activity("执行轨迹"), Artifacts("产物") }

@Composable
fun ExperienceScreen(viewModel: ExperienceViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val workspaceId = state.workspace?.id
    val context = LocalContext.current.applicationContext
    val store = remember(context) { WorkbenchProfileStore(context) }
    var profiles by remember(workspaceId) { mutableStateOf<WorkbenchProfiles?>(null) }
    var layoutReadFailed by remember(workspaceId) { mutableStateOf(false) }
    LaunchedEffect(workspaceId) {
        if (workspaceId == null) return@LaunchedEffect
        try {
            profiles = withContext(Dispatchers.IO) { store.load(workspaceId) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            layoutReadFailed = true
        }
    }
    val profile = profiles?.selected ?: WorkbenchProfiles.presets.getValue(WorkbenchProfiles.CLASSIC)
    val dark = when (profile.theme) {
        WorkbenchTheme.LIGHT -> false
        WorkbenchTheme.DARK -> true
        WorkbenchTheme.SYSTEM -> isSystemInDarkTheme()
    }
    ExperienceTheme(darkTheme = dark, accent = profile.accent) {
        ExperienceContent(
            viewModel, state, profiles, layoutReadFailed,
            onSaveLayout = { next ->
                val owner = checkNotNull(workspaceId)
                val previous = checkNotNull(profiles)
                check(viewModel.state.value.workspace?.id == owner)
                withContext(Dispatchers.IO) { store.save(owner, previous, next) }
                profiles = next
            },
            onRestoreLayouts = {
                val owner = checkNotNull(workspaceId)
                check(viewModel.state.value.workspace?.id == owner)
                withContext(Dispatchers.IO) { store.restoreDefault(owner) }
                profiles = WorkbenchProfiles()
                layoutReadFailed = false
            },
        )
    }
}

@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
private fun ExperienceContent(
    viewModel: ExperienceViewModel,
    state: ExperienceState,
    profiles: WorkbenchProfiles?,
    layoutReadFailed: Boolean,
    onSaveLayout: suspend (WorkbenchProfiles) -> Unit,
    onRestoreLayouts: suspend () -> Unit,
) {
    val saveable = rememberSaveableStateHolder()
    val saveableKey = "${state.workspace?.id}:${state.sessionId}"
    var approvalId by rememberSaveable { mutableStateOf<String?>(null) }
    var inspected by remember(saveableKey) { mutableStateOf<ToolActivity?>(null) }
    var promptToInsert by remember { mutableStateOf("") }
    var settingsLink by remember { mutableStateOf<String?>(null) }
    var manageModels by remember { mutableStateOf(false) }
    var editingLayoutFor by rememberSaveable { mutableStateOf<String?>(null) }
    var sessionPluginsOwner by remember(saveableKey, state.archived) { mutableStateOf<InteractionContext?>(null) }
    var openedPublication by remember { mutableStateOf<PublishedTab?>(null) }
    val composer by viewModel.composer.collectAsStateWithLifecycle()
    val settingsApp = LocalContext.current.applicationContext as com.cordiact.app.CordiActApplication
    val prepareLocal = remember(settingsApp) { settingsApp.interactionService::prepareLocalCommand }
    val settingsVm: com.cordiact.app.experience.settings.SettingsViewModel = androidx.lifecycle.viewmodel.compose.viewModel(
        factory = com.cordiact.app.experience.settings.SettingsViewModel.factory(
            remember(settingsApp) { com.cordiact.app.experience.settings.AndroidSettingsFacade(settingsApp) }))
    fun openSettings(link: String? = null) { settingsLink = link; inspected = null; sessionPluginsOwner = null; manageModels = true }
    if (manageModels) {
        com.cordiact.app.experience.settings.SettingsHost(settingsVm, initialLink = settingsLink) { manageModels = false; settingsLink = null; viewModel.refreshSettingsModels() }
        return
    }
    val tabContext = LocalContext.current.applicationContext
    val tabs = remember(tabContext) { com.cordiact.app.experience.tabs.TabsController(com.cordiact.app.experience.tabs.PrefsTabStore(tabContext)) }
    val resultDirectory = settingsApp.conversationCatalog.toolResultCards
    val resultCards by resultDirectory.snapshot.collectAsStateWithLifecycle()
    val clientCatalog = settingsApp.conversationCatalog.workspaceClientContributions
    val clientSnapshot by clientCatalog.snapshot.collectAsStateWithLifecycle()
    val resultCardFor: (ToolActivity) -> com.cordiact.interaction.ToolResultCard? = { tool ->
        val key = tool.forToolInspector().provenance
        val packageCard = state.workspace?.id?.let { workspaceId -> key?.let { clientSnapshot.toolResultCard(workspaceId, it) } }
        packageCard ?: resultCards[key]?.takeIf { resultDirectory.resolve(key) === it }
    }
    val tabDirectory = settingsApp.conversationCatalog.workbenchTabs
    val tabSnapshot by tabDirectory.snapshot.collectAsStateWithLifecycle()
    val tabWorkspace = state.workspace?.id
    val tabSession = state.sessionId
    val messageNodes: (com.cordiact.core.plugin.ExtensionPackageMessageEvent) -> List<com.cordiact.interaction.WorkspaceClientContributions.MessageNode> = { event ->
        tabWorkspace?.let { clientSnapshot.messageNodes(it, event) }.orEmpty()
    }
    val availableTabs = remember(tabSnapshot, tabWorkspace, tabSession) { tabSnapshot.visible(tabWorkspace, tabSession) }
    val layout = profiles?.selected ?: WorkbenchProfiles.presets.getValue(WorkbenchProfiles.CLASSIC)
    val configuration = LocalConfiguration.current
    val landscape = configuration.screenWidthDp > configuration.screenHeightDp && configuration.screenWidthDp >= 600
    val canDock = landscape && configuration.screenWidthDp >= LANDSCAPE_DOCK_MIN_WIDTH
    val placedPanels = remember(layout, availableTabs, canDock) { layout.visiblePanels(availableTabs.map { it.id }, canDock) }
    val byId = remember(availableTabs) { availableTabs.associateBy { it.id } }
    val displayedTabs = remember(placedPanels, byId) {
        placedPanels.filter { it.placement == PanelPlacement.TAB }.mapNotNull { byId[it.id] }
    }
    val dockedTabs = remember(placedPanels, byId) {
        placedPanels.filter { it.placement == PanelPlacement.DOCK }.mapNotNull { byId[it.id] }
    }
    val currentDisplayedTabs by rememberUpdatedState(displayedTabs)
    var selectedDockId by rememberSaveable(tabWorkspace) { mutableStateOf<String?>(null) }
    var overview by rememberSaveable { mutableStateOf(false) }
    var overviewEpoch by remember { mutableLongStateOf(0L) }
    fun showOverview(show: Boolean) {
        if (overview != show) overviewEpoch++
        overview = show
    }
    val activePublication = (tabs.active as? WorkTab.Plugin)?.takeIf { tabWorkspace != null && tabSession != null }?.let { tab ->
        displayedTabs.firstOrNull { it.id == tab.pluginId &&
            (openedPublication == null || openedPublication?.id != tab.pluginId || openedPublication === it) }
            ?.takeIf { tabDirectory.isCurrent(it, tabWorkspace, tabSession) }
    }
    val activeTab = if (tabs.active is WorkTab.Plugin && activePublication == null) tabSession?.let { WorkTab.Session(it) } else tabs.active
    LaunchedEffect(state.sessionId, activePublication) {
        inspected = null
        tabs.followSession(state.sessionId)
    }
    LaunchedEffect(state.sidebar, state.loading, tabSession, displayedTabs, tabWorkspace, activePublication) {
        if (state.loading || tabSession == null) return@LaunchedEffect
        val live = state.sidebar.flatMap { it.sessions }.filter { !it.isArchived }.map { it.id }.toSet()
        val livePages = displayedTabs.mapTo(mutableSetOf()) { it.id }
        val previousPage = tabs.active as? WorkTab.Plugin
        if (previousPage != null && activePublication == null) livePages.remove(previousPage.pluginId)
        tabs.prune(live, livePages, tabSession)
        openedPublication = activePublication
    }
    LaunchedEffect(layout.panels, displayedTabs, tabSession, state.loading) {
        if (layout.panels.isNotEmpty() && tabSession != null && !state.loading) {
            tabs.orderPluginTabs(displayedTabs.map { it.id }, layout.panels.mapTo(hashSetOf()) { it.id })
        }
    }
    // A session tab is the selection: activating one moves the view model onto that session.
    LaunchedEffect(activeTab?.key) {
        val tab = activeTab as? WorkTab.Session ?: return@LaunchedEffect
        if (tab.sessionId != state.sessionId) viewModel.selectSession(tab.sessionId)
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
    var requestedTab by remember { mutableStateOf<WorkTab.Session?>(null) }
    var loadingTab by remember { mutableStateOf<WorkTab.Session?>(null) }
    val committedKey = if (activeTab is WorkTab.Plugin) activeTab.key else state.sessionId?.let { "s:$it" }
    val pendingSessionId = requestedTab?.sessionId ?: loadingTab?.sessionId
    val switchPending = requestedTab != null || loadingTab != null
    LaunchedEffect(requestedTab, loadingTab, state.loading) {
        val target = requestedTab ?: return@LaunchedEffect
        if (loadingTab != null || state.loading) return@LaunchedEffect
        if (target.sessionId == state.sessionId) {
            if (tabs.tabs.any { it.key == target.key }) tabs.select(target.key)
            requestedTab = null
        } else {
            loadingTab = target
            viewModel.selectWorkbenchSession(target.sessionId,
                shouldCommit = { requestedTab == target && tabs.tabs.any { it.key == target.key } }) {
                if (requestedTab == target) {
                    if (viewModel.state.value.sessionId == target.sessionId) tabs.select(target.key)
                    requestedTab = null
                }
                loadingTab = null
            }
        }
    }
    val closeSessionPluginDetail: () -> Unit = { sessionPluginsOwner = null }
    val closeAfterSelection: () -> Unit = { showOverview(false); inspected = null; closeSessionPluginDetail() }
    val ownerPending = switchPending || state.loading ||
        (activeTab is WorkTab.Session && activeTab.sessionId != state.sessionId)
    fun sameLoadedOwner(): Boolean {
        val current = viewModel.state.value
        return !current.loading && requestedTab == null && loadingTab == null &&
            current.workspace?.id == tabWorkspace && current.sessionId == tabSession && tabWorkspace != null && tabSession != null
    }
    fun openPublication(publication: PublishedTab): Boolean {
        if (!sameLoadedOwner() || publication !in currentDisplayedTabs ||
            !tabDirectory.isCurrent(publication, tabWorkspace, tabSession)) return false
        inspected = null
        closeSessionPluginDetail()
        openedPublication = publication
        tabs.open(WorkTab.Plugin(publication.id))
        if (layout.panels.isNotEmpty()) tabs.orderPluginTabs(currentDisplayedTabs.map { it.id })
        return true
    }
    fun openTrace(): Boolean = displayedTabs.firstOrNull { it.id == TraceTab.id }?.let(::openPublication) == true
    val sessionActionScope = rememberCoroutineScope()
    val viewLifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    var viewEpoch by remember(viewLifecycle) { mutableLongStateOf(0L) }
    DisposableEffect(viewLifecycle) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_PAUSE) viewEpoch++
        }
        viewLifecycle.addObserver(observer)
        onDispose { viewLifecycle.removeObserver(observer) }
    }
    val canInsertSessionAction = !state.running && !state.stopping && composer.text.isEmpty() && composer.attachments.isEmpty()
    fun insertPluginDraft(
        offer: com.cordiact.interaction.WorkspaceClientContributions.SessionActionOffer,
        stillVisible: () -> Boolean,
        onInserted: () -> Unit,
    ) {
        val shownEpoch = viewEpoch
        fun currentHost() = sameLoadedOwner() && !overview && viewEpoch == shownEpoch && stillVisible() &&
            viewLifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)
        if (!currentHost()) return
        sessionActionScope.launch {
            try {
                if (viewModel.insertSessionAction(offer, ::currentHost)) {
                    promptToInsert = ""
                    tabs.open(WorkTab.Session(offer.context.sessionId))
                    onInserted()
                }
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                if (currentHost()) viewModel.composerController.banner("无法验证会话动作，草稿未修改。")
            }
        }
    }
    fun pluginHost(publication: PublishedTab, isLandscape: Boolean,
        onInspect: (ToolActivity) -> Unit, onArtifact: (String) -> Unit = {},
        isPresented: () -> Boolean = { tabs.active?.key == "p:${publication.id}" && publication in currentDisplayedTabs },
    ): TabHost = object : TabHost {
        override val landscape = isLandscape
        private fun current() = sameLoadedOwner() && isPresented() &&
            tabDirectory.isCurrent(publication, tabWorkspace, tabSession)
        override fun inspect(step: ToolActivity) { if (current()) onInspect(step) }
        override fun inspectArtifact(id: String) { if (current()) onArtifact(id) }
        override fun openSession(sessionId: String) {
            if (!current() || state.sidebar.none { ws -> ws.id == tabWorkspace && ws.sessions.any { it.id == sessionId && !it.isArchived } }) return
            viewModel.selectSession(sessionId) { tabs.open(WorkTab.Session(sessionId)) }
        }
        override val canInsertPluginAction get() = canInsertSessionAction && !state.archived
        override fun insertPluginAction(
            offer: com.cordiact.interaction.WorkspaceClientContributions.SessionActionOffer,
            isStillVisible: () -> Boolean,
        ) {
            insertPluginDraft(offer, {
                current() && offer.context.workspaceId == publication.owner.workspaceId && isStillVisible()
            }) {}
        }
    }
    val pluginWorkspace = state.workspace?.takeUnless { ownerPending || activeTab is WorkTab.Plugin }
    val pluginOwner = pluginWorkspace?.let { workspace ->
        state.sessionId?.takeUnless { state.archived }?.let { InteractionContext(it, workspace.id) }
    }
    LaunchedEffect(pluginOwner) {
        if (sessionPluginsOwner != pluginOwner) sessionPluginsOwner = null
    }
    val pluginDetailOwner = sessionPluginsOwner?.takeIf { it == pluginOwner }
    val sessionActions = remember(clientSnapshot, pluginOwner) { pluginOwner?.let(clientSnapshot::sessionActions).orEmpty() }
    val focusManager = androidx.compose.ui.platform.LocalFocusManager.current
    val keyboard = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
    if (editingLayoutFor != null && editingLayoutFor == tabWorkspace && !state.loading) {
        val workspace = checkNotNull(state.workspace)
        if (profiles != null) {
            key(workspace.id) {
                WorkbenchLayoutEditor(workspace.name, profiles, availableTabs, onSaveLayout) { editingLayoutFor = null }
            }
            return
        }
        if (layoutReadFailed) {
            RestoreWorkbenchLayoutDialog(onRestoreLayouts) { editingLayoutFor = null }
        }
    }
    BackHandler(!landscape && pluginDetailOwner != null && !overview, onBack = closeSessionPluginDetail)
    BackHandler(inspected != null && !overview) { inspected = null }
    val clientSlots = settingsApp.conversationCatalog.clientComponentSlots
    val toolbarComponents: @Composable () -> Unit = {
        val create: (() -> Unit)? = if (state.loading || switchPending || overview) null else ({
            val current = viewModel.state.value
            if (!current.loading && !overview && requestedTab == null && loadingTab == null &&
                current.workspace?.id == tabWorkspace && current.sessionId == tabSession
            ) viewModel.newSession(tabWorkspace)
        })
        Row(verticalAlignment = Alignment.CenterVertically) {
            clientSlots.Mount(ClientComponentSlot.SESSION_TOOLBAR, tabWorkspace, tabSession, landscape,
                canInsertAction = canInsertSessionAction && !state.archived, onInsertAction = ::insertPluginDraft, onCreateSession = create)
        }
    }
    val composerAccessories: @Composable () -> Unit = {
        if (!ownerPending && tabSession != null && tabWorkspace != null) {
            clientSlots.Mount(ClientComponentSlot.COMPOSER_ACCESSORIES, tabWorkspace, tabSession, landscape,
                canInsertAction = canInsertSessionAction && !state.archived, onInsertAction = ::insertPluginDraft)
        }
    }
    val workbench: @Composable () -> Unit = {
        if (landscape) {
            val activity = rememberWorkbenchActivity(settingsApp, state.workspace?.id, state.sessionId, state.runningSessionIds)
            LandscapeWorkbench(state, pendingSessionId, committedKey.orEmpty(), activeTab is WorkTab.Plugin, activity,
                onOverview = { showOverview(true); inspected = null },
                onNew = { viewModel.newSession(state.workspace?.id) },
                onOpenApproval = { approvalId = it }, ownerLabel = ownerLabel,
                tabs = { modifier ->
                    com.cordiact.app.experience.tabs.TabStrip(state, tabs, displayedTabs, ::openPublication,
                        onClose = { if (!switchPending && !state.loading) tabs.close(it) }, modifier = modifier,
                        onSelect = { requestedTab = it; closeSessionPluginDetail() }, displayedKey = committedKey,
                        pendingKey = pendingSessionId?.let { "s:$it" }, compact = true)
                },
                body = { openStep, openArtifact ->
                    Column(Modifier.fillMaxSize()) {
                        state.error?.let { message ->
                            Box(Modifier.padding(horizontal = 12.dp, vertical = 3.dp)) {
                                ErrorStrip(message, viewModel::dismissError, { openSettings() })
                            }
                        }
                        Box(Modifier.weight(1f).fillMaxWidth()) {
                            if (activePublication == null) TranscriptView(
                                state,
                                onPrompt = { promptToInsert = it },
                                onInspect = openStep,
                                onDecideApproval = viewModel::decide,
                                onOpenApprovalQueue = {
                                    approvalId = (state.currentApprovals.firstOrNull() ?: state.approvals.firstOrNull())?.id
                                },
                                onOpenTrace = if (displayedTabs.any { it.id == TraceTab.id }) ({ openTrace(); Unit }) else null,
                                messageNodes = messageNodes,
                            )
                            else key(activePublication) {
                                activePublication.content.Content(state, pluginHost(activePublication, true, openStep, openArtifact))
                            }
                        }
                    }
                },
                composer = { lines ->
                    state.undoArchivedSessionId?.let {
                        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text("会话已归档", Modifier.weight(1f), style = MaterialTheme.typography.labelSmall)
                            TextButton(onClick = { viewModel.restoreSession(it) }) { Text("撤销") }
                        }
                    }
                    if (state.archived) TextButton(onClick = { state.sessionId?.let { viewModel.restoreSession(it) } },
                        enabled = !state.loading && !switchPending) { Text("恢复并继续") }
                    else com.cordiact.app.experience.composer.ComposerBar(
                        if (switchPending) state.copy(loading = true) else state, composer, viewModel.composerController, promptToInsert,
                        { promptToInsert = "" }, { text -> if (requestedTab != null || loadingTab != null) false else viewModel.send(text) }, { if (requestedTab == null && loadingTab == null) viewModel.stop() },
                        viewModel::selectModel, compact = true, maxEditorLines = lines,
                        onSendLocal = { if (requestedTab != null || loadingTab != null) false else viewModel.sendLocal(it) },
                        prepareLocal = prepareLocal,
                        commandRevision = clientSnapshot,
                        accessories = composerAccessories,
                    )
                },
                sessionPluginDetail = if (pluginDetailOwner != null) {
                    { SessionPluginsRoute(settingsApp, pluginDetailOwner, onDismiss = closeSessionPluginDetail) }
                } else null,
                onCloseSessionPluginDetail = closeSessionPluginDetail,
                tabPublication = activePublication,
                resultCardFor = resultCardFor,
                toolbarComponents = toolbarComponents,
                docked = if (dockedTabs.isEmpty()) null else { modifier, inspect, artifact ->
                    DockedWorkbenchPanels(dockedTabs, selectedDockId, { selectedDockId = it }, modifier) { publication, isPresented ->
                        publication.content.Content(state, pluginHost(publication, true, inspect, artifact, isPresented))
                    }
                },
            )
        } else Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
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
                        tabCount = tabs.tabs.size,
                        onMenu = { showOverview(true) },
                        onIsland = {
                            if (state.turns.none { it.tools.isNotEmpty() } || !openTrace()) showOverview(true)
                        },
                        onApprovals = openApprovals,
                        trailing = toolbarComponents,
                    )
                    com.cordiact.app.experience.tabs.TabStrip(state, tabs, displayedTabs, ::openPublication,
                        onClose = { if (!switchPending && !state.loading) tabs.close(it) }, modifier = Modifier.padding(top = 4.dp, bottom = 2.dp),
                        onSelect = { requestedTab = it; closeSessionPluginDetail() }, displayedKey = committedKey,
                        pendingKey = pendingSessionId?.let { "s:$it" })
                    },
                    content = { viewportModifier ->
                    Column(viewportModifier) {
                    if (state.error != null) Box(Modifier.padding(horizontal = 18.dp, vertical = 5.dp)) { ErrorStrip(state.error!!, viewModel::dismissError, { openSettings() }) }
                    Box(Modifier.weight(1f).fillMaxWidth()) {
                        // A tab click precedes the asynchronous session load. Key the transcript by
                        // the loaded session, so old text doesn't remount under the destination tab.
                        val contentTabKey = (activeTab as? com.cordiact.app.experience.tabs.WorkTab.Plugin)?.key
                            ?: state.sessionId?.let { "s:$it" }
                        if (pluginDetailOwner != null) {
                            SessionPluginsRoute(settingsApp, pluginDetailOwner,
                                modifier = Modifier.padding(LocalChromePadding.current).padding(horizontal = 12.dp),
                                onDismiss = closeSessionPluginDetail)
                        } else saveable.SaveableStateProvider("$saveableKey:$contentTabKey") {
                            if (activePublication != null) key(activePublication) {
                                activePublication.content.Content(state, pluginHost(activePublication, false, { inspected = it }))
                            } else TranscriptView(
                                    state,
                                    onPrompt = { promptToInsert = it },
                                    onInspect = { inspected = it },
                                    onDecideApproval = viewModel::decide,
                                    onOpenApprovalQueue = {
                                        viewModel.composerController.popover(null)
                                        approvalId = (state.currentApprovals.firstOrNull() ?: state.approvals.firstOrNull())?.id
                                    },
                                    onOpenTrace = if (displayedTabs.any { it.id == TraceTab.id }) ({ openTrace(); Unit }) else null,
                                    messageNodes = messageNodes,
                                )
                        }
                    }
                    }
                    },
                    bottom = {
                        if (activeTab !is WorkTab.Plugin) {
                            val otherApprovals = state.approvals.size - state.currentApprovals.size
                            if (otherApprovals > 0) {
                                Box(Modifier.fillMaxWidth().padding(bottom = 6.dp), contentAlignment = Alignment.Center) {
                                    ApprovalDockPill(
                                        label = "其他会话待审批 $otherApprovals 项 · 查看队列",
                                        onClick = {
                                            viewModel.composerController.popover(null)
                                            approvalId = (state.approvals.firstOrNull { it !in state.currentApprovals } ?: state.approvals.firstOrNull())?.id
                                        },
                                    )
                                }
                            }
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
                                if (ownerPending) state.copy(loading = true) else state, composer, viewModel.composerController, promptToInsert,
                                { promptToInsert = "" }, { if (requestedTab == null && loadingTab == null && tabs.active?.key == "s:${viewModel.state.value.sessionId}") viewModel.send(it) else false }, viewModel::stop,
                                viewModel::selectModel,
                                onSendLocal = { prepared ->
                                    val selected = tabs.active as? WorkTab.Session
                                    if (requestedTab != null || loadingTab != null || selected?.sessionId != viewModel.state.value.sessionId) false
                                    else viewModel.sendLocal(prepared)
                                },
                                prepareLocal = prepareLocal.takeUnless { ownerPending },
                                commandRevision = clientSnapshot,
                                accessories = composerAccessories,
                            )
                        }
                    },
                )
                inspected?.let { step ->
                    val currentStep = state.turns.asSequence().flatMap { it.tools.asSequence() }.firstOrNull { it.id == step.id } ?: step
                    CompactInspector(step = currentStep, card = resultCardFor(currentStep), onClose = { inspected = null },
                        onSettings = { link -> openSettings(link) },
                        modifier = Modifier.align(Alignment.BottomCenter).windowInsetsPadding(WindowInsets.systemBars.union(WindowInsets.ime)))
                }
            }
        }
    }
    Box(Modifier.fillMaxSize()) {
        workbench()
        BackHandler(overview) { showOverview(false) }
        approvalId?.let { id ->
            val currentIds = state.currentApprovals.mapTo(hashSetOf()) { it.id }
            val sortedRequests = state.approvals.sortedBy { it.id !in currentIds }
            ApprovalDecisionDialog(sortedRequests, state.decidingApprovals, state.approvalErrors,
                ownerLabel = ownerLabel,
                selected = id, onSelect = { approvalId = it }, onDismiss = { approvalId = null }, onDecide = viewModel::decide)
        }
        androidx.compose.animation.AnimatedVisibility(overview,
            enter = androidx.compose.animation.fadeIn(tween(com.cordiact.app.experience.composer.motionDuration(220))) + androidx.compose.animation.scaleIn(tween(com.cordiact.app.experience.composer.motionDuration(220)), initialScale = .96f),
            exit = androidx.compose.animation.fadeOut(tween(com.cordiact.app.experience.composer.motionDuration(160))) + androidx.compose.animation.scaleOut(tween(com.cordiact.app.experience.composer.motionDuration(160)), targetScale = .96f)) {
            com.cordiact.app.experience.tabs.TabOverview(
                state, tabs, displayedTabs, ::openPublication,
                onClose = { if (!switchPending && !state.loading) tabs.close(it) },
                onDismiss = { showOverview(false) },
                onNewSession = { viewModel.newSession(state.workspace?.id, closeAfterSelection) },
                onSettings = { closeAfterSelection(); settingsVm.reload(); openSettings() },
                onLayout = if (tabWorkspace != null && !state.loading && (profiles != null || layoutReadFailed)) {
                    { closeAfterSelection(); editingLayoutFor = tabWorkspace }
                } else null,
                onSelectTab = { requestedTab = it; closeSessionPluginDetail() },
                onSegmentChange = { overviewEpoch++ },
            ) { m, overviewSegments ->
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
                    currentPluginOwner = pluginOwner,
                    sessionActions = sessionActions,
                    canInsertSessionAction = canInsertSessionAction,
                    onSessionPlugins = { owner ->
                        if (overview && owner == pluginOwner && (tabs.active as? WorkTab.Session)?.sessionId == owner.sessionId) {
                            closeAfterSelection()
                            viewModel.composerController.popover(null)
                            focusManager.clearFocus()
                            keyboard?.hide()
                            sessionPluginsOwner = owner
                        }
                    },
                    onSessionAction = { offer ->
                        val shownEpoch = viewEpoch
                        val openedOverview = overviewEpoch
                        fun currentHost() = overview && overviewEpoch == openedOverview && viewEpoch == shownEpoch &&
                            requestedTab == null && loadingTab == null &&
                            (tabs.active as? WorkTab.Session)?.sessionId == offer.context.sessionId &&
                            viewLifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)
                        sessionActionScope.launch {
                            try {
                                if (viewModel.insertSessionAction(offer, ::currentHost)) {
                                    promptToInsert = ""
                                    closeAfterSelection()
                                }
                            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                                throw cancelled
                            } catch (_: Exception) {
                                if (currentHost()) viewModel.composerController.banner("无法验证会话动作，草稿未修改。")
                            }
                        }
                    },
                    overviewSegments = overviewSegments,
                )
            }
        }
    }

}

@Composable
internal fun CompactInspector(
    step: ToolActivity, onClose: () -> Unit, modifier: Modifier = Modifier,
    onSettings: ((String?) -> Unit)? = null, card: com.cordiact.interaction.ToolResultCard? = null,
) {
    val preview = step.forToolInspector()
    val clipboard = LocalClipboardManager.current
    val colors = MaterialTheme.colorScheme
    var tab by remember(step.id) { mutableIntStateOf(0) } // 0: 概览, 1: 输入参数, 2: 返回内容
    val statusText = when (step.phase) {
        ToolPhase.SUCCEEDED -> "完成"
        ToolPhase.FAILED -> "失败"
        ToolPhase.RUNNING -> "执行中"
        ToolPhase.INTERRUPTED -> "中断"
    }
    val statusColor = when (step.phase) {
        ToolPhase.FAILED -> colors.error
        ToolPhase.RUNNING -> colors.primary
        else -> colors.onSurfaceVariant
    }
    BoxWithConstraints(modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
        // Landscape: a side panel beside the transcript instead of a sheet squeezed into 360dp of height.
        val side = maxWidth > maxHeight && maxWidth >= 600.dp
        val maxSheet = if (side) maxHeight else maxHeight * .46f
        Surface(
            (if (side) Modifier.align(Alignment.CenterEnd).width(minOf(420.dp, maxWidth * .45f)).fillMaxHeight()
            else Modifier.fillMaxWidth()).heightIn(max = maxSheet).padding(horizontal = 8.dp, vertical = 4.dp).testTag("tool-inspector"),
            shape = RoundedCornerShape(20.dp),
            shadowElevation = 2.dp,
            color = colors.surface,
            border = BorderStroke(1.dp, colors.outlineVariant),
        ) {
            Column {
                PeekHandle(onClose)
                Row(Modifier.padding(start = 16.dp, end = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(7.dp).clip(CircleShape).background(statusColor))
                    Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
                        Text(step.name, style = MaterialTheme.typography.titleSmall.copy(fontFamily = FontFamily.Monospace), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            listOfNotNull(statusText, step.durationMillis?.let { com.cordiact.app.experience.model.durationLabel(it) }).joinToString(" · "),
                            style = MaterialTheme.typography.labelSmall,
                            color = statusColor,
                        )
                    }
                    IconButton(onClick = { clipboard.setText(AnnotatedString(listOf(preview.arguments, preview.summary.orEmpty()).filter { it.isNotBlank() }.joinToString("\n\n"))) }) {
                        AppGlyph(GlyphName.Copy, "复制工具详情", modifier = Modifier.size(17.dp))
                    }
                    IconButton(onClick = onClose) { AppGlyph(GlyphName.Close, "关闭工具详情", modifier = Modifier.size(17.dp)) }
                }
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    listOf("概览", "输入参数", "返回内容").forEachIndexed { idx, label ->
                        val active = tab == idx
                        Surface(
                            onClick = { tab = idx },
                            shape = CircleShape,
                            color = if (active) colors.onSurface else colors.surfaceContainerLow,
                        ) {
                            Text(
                                label,
                                style = MaterialTheme.typography.labelSmall,
                                color = if (active) colors.surface else colors.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 11.dp, vertical = 5.dp),
                            )
                        }
                    }
                }
                HorizontalDivider(thickness = 0.5.dp, color = colors.outlineVariant)
                if (step.phase == ToolPhase.FAILED && onSettings != null) {
                    val link = when {
                        step.name.startsWith("ssh_") -> "settings://ssh"
                        step.name.startsWith("termux_") -> "settings://connections/termux"
                        step.name.startsWith("browser_") -> "settings://connections/browser"
                        step.name.startsWith("mcp") || step.name.startsWith("mcp_") -> "settings://mcp"
                        step.name in setOf("root_exec", "shizuku_exec", "priv_exec", "priv_shell", "priv_root") -> "settings://connections/privilege"
                        step.name in setOf("take_screenshot", "read_window_tree", "click_node", "tap", "swipe", "set_text", "scroll", "global_action") -> "settings://connections/screen"
                        step.name.startsWith("web_") || step.name == "search_web" -> "settings://search"
                        else -> null
                    }
                    TextButton(onClick = { onSettings(link) }, modifier = Modifier.padding(horizontal = 12.dp)) { Text("前往设置检查连接与权限") }
                }
                SelectionContainer(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 12.dp)) {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        when (tab) {
                            0 -> {
                                if (card != null) ToolResultCardView(card)
                                if (card == null || preview.provenance?.packageSha256 != null) Text(
                                    preview.summary?.takeIf { it.isNotBlank() } ?: "工具尚未返回可显示摘要",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = colors.onSurface,
                                )
                                if (preview.arguments.isNotBlank()) {
                                    Text("调用参数预览", style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
                                    Text(
                                        preview.arguments.take(360),
                                        fontFamily = FontFamily.Monospace,
                                        fontSize = 12.sp,
                                        lineHeight = 18.sp,
                                        color = colors.onSurfaceVariant,
                                    )
                                }
                            }
                            1 -> {
                                Text(
                                    preview.arguments.ifBlank { "无输入参数" },
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 12.sp,
                                    lineHeight = 18.sp,
                                )
                            }
                            else -> {
                                Text(
                                    preview.summary?.ifBlank { "工具尚未返回内容" } ?: "工具尚未返回内容",
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 12.sp,
                                    lineHeight = 18.sp,
                                )
                            }
                        }
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
