package com.cordiact.app.experience.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.background
import android.content.res.Configuration
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import kotlinx.coroutines.launch
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeSource
import com.cordiact.app.experience.LocalBackdrop
import com.cordiact.app.experience.FrostedSurface
import com.cordiact.app.experience.ViewportFade
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cordiact.app.experience.AppGlyph
import com.cordiact.app.experience.settings.components.SettingsGroup
import com.cordiact.app.experience.settings.components.SettingsRow
import com.cordiact.app.experience.GlyphName
import com.cordiact.app.experience.settings.components.*
import com.cordiact.core.conversation.ModelId
import com.cordiact.model.providers.ModelProtocol
import java.util.UUID

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsHost(vm: SettingsViewModel, initialLink: String? = null, onBack: () -> Unit) {
    val state by vm.snapshot.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()
    val notice by vm.notice.collectAsStateWithLifecycle()
    val lifecycleOwner = LocalLifecycleOwner.current
    val latestBusy = rememberUpdatedState(busy)
    DisposableEffect(lifecycleOwner, vm) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME && !latestBusy.value) vm.reload()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    val startLink = remember(initialLink) { SettingsLink.parse(initialLink) }
    var stackNames by rememberSaveable { mutableStateOf(startLink?.navigation()?.entries?.map { it.name } ?: listOf(SettingsRoute.HOME.name)) }
    val navigation = SettingsNavigation.restore(stackNames)
    val route = navigation.current
    LaunchedEffect(stackNames) { if (SettingsNavigation.restore(stackNames) != navigation) stackNames = navigation.entries.map { it.name } }
    var providerId by rememberSaveable { mutableStateOf(startLink?.providerId.orEmpty()) }
    var connectionId by rememberSaveable { mutableStateOf(startLink?.connectionId) }
    var dirty by remember { mutableStateOf(false) }
    var securityBusy by remember { mutableStateOf(false) }
    var discard by remember { mutableStateOf(false) }
    var discardAction by remember { mutableStateOf<(suspend () -> Unit)?>(null) }
    val navigationScope = rememberCoroutineScope()
    var discardBusy by remember { mutableStateOf(false) }
    var discardError by remember { mutableStateOf<String?>(null) }
    var pendingNavigation by remember { mutableStateOf<SettingsNavigation?>(null) }
    fun applyNavigation(target: SettingsNavigation?) {
        dirty = false
        securityBusy = false
        if (target == null) onBack() else stackNames = target.entries.map { it.name }
    }
    fun requestNavigation(target: SettingsNavigation?) {
        if (busy || securityBusy || discardBusy) return
        if (dirty) { pendingNavigation = target; discard = true }
        else applyNavigation(target)
    }
    fun navigate(target: SettingsRoute) = requestNavigation(navigation.push(target))
    val directoryRoute = settingsDirectoryRoute(route)
    fun openDirectory(target: SettingsRoute) {
        if (route != target) requestNavigation(SettingsNavigation().push(target))
    }
    val presetContext = androidx.compose.ui.platform.LocalContext.current.applicationContext
    val agentPresets = remember(presetContext) { AgentPresetStore(presetContext) }
    var presetId by rememberSaveable { mutableStateOf("") }
    var requestSearchFocus by remember { mutableStateOf(false) }
    var searchActive by rememberSaveable { mutableStateOf(false) }
    var settingsQuery by rememberSaveable { mutableStateOf("") }
    var fieldFromSearch by rememberSaveable { mutableStateOf<String?>(null) }
    var sectionFromSearch by rememberSaveable { mutableStateOf<String?>(null) }
    val searchFocus = remember { androidx.compose.ui.focus.FocusRequester() }
    val keyboard = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
    val focusManager = androidx.compose.ui.platform.LocalFocusManager.current
    fun leaveSearch() {
        focusManager.clearFocus()
        keyboard?.hide()
        requestSearchFocus = false
        searchActive = false
        settingsQuery = ""
    }
    fun back() {
        if (route == SettingsRoute.HOME && searchActive) leaveSearch()
        else requestNavigation(navigation.pop())
    }
    fun openResult(hit: SettingsSearchHit) {
        if (busy || securityBusy || dirty || discardBusy) return
        focusManager.clearFocus()
        keyboard?.hide()
        fieldFromSearch = hit.field
        sectionFromSearch = hit.section
        navigate(hit.entry.route)
    }
    val context = androidx.compose.ui.platform.LocalContext.current
    val termuxPermission = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission()) { vm.reload() }
    fun openLink(raw: String?) {
        val link = SettingsLink.parse(raw) ?: return
        if (busy || securityBusy || dirty || discardBusy) return
        if (raw == "settings://connections/screen") {
            val component = android.content.ComponentName(context, com.cordiact.app.screen.CordiActAccessibilityService::class.java)
            val intent = android.content.Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS)
                .putExtra(":settings:fragment_args_key", component.flattenToString())
                .putExtra(":settings:show_fragment_args", android.os.Bundle().apply {
                    putString(":settings:fragment_args_key", component.flattenToString())
                })
            if (intent.resolveActivity(context.packageManager) != null) {
                context.startActivity(intent)
                return
            }
        }
        if (raw == "settings://connections/termux" &&
            androidx.core.content.ContextCompat.checkSelfPermission(context, "com.termux.permission.RUN_COMMAND") != android.content.pm.PackageManager.PERMISSION_GRANTED &&
            runCatching { context.packageManager.getPermissionInfo("com.termux.permission.RUN_COMMAND", 0) }.isSuccess) {
            termuxPermission.launch("com.termux.permission.RUN_COMMAND")
            return
        }
        providerId = link.providerId
        connectionId = link.connectionId
        applyNavigation(link.navigation())
    }
    BackHandler { back() }
    if (discard) AlertDialog(onDismissRequest = { if (!discardBusy) discard = false }, title = { Text("放弃修改？") },
        text = { Text(discardError ?: "尚未保存的修改将丢失。") }, dismissButton = { TextButton(enabled = !discardBusy, onClick = { discard = false }) { Text("继续编辑") } },
        confirmButton = { TextButton(enabled = !discardBusy, onClick = {
            discardBusy = true
            navigationScope.launch {
                try { discardAction?.invoke(); discard = false; discardError = null; applyNavigation(pendingNavigation) }
                catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                catch (_: Exception) { discardError = "清理未保存的临时凭据失败，请重试；修改尚未丢弃。" }
                finally { discardBusy = false }
            }
        }) { Text(if (discardBusy) "正在清理…" else "放弃") } })
    val snackbar = remember { SnackbarHostState() }
    val backdrop = remember { HazeState() }
    var toolbarHeight by remember { mutableIntStateOf(0) }
    val toolbarDp = with(LocalDensity.current) { toolbarHeight.toDp() }
    val sections = settingsSections(route)
    val showSearch = route == SettingsRoute.HOME
    val showResults = showSearch && searchActive
    val homeScroll = rememberScrollState()
    val resultsScroll = androidx.compose.runtime.saveable.rememberSaveable(saver = androidx.compose.foundation.ScrollState.Saver) { androidx.compose.foundation.ScrollState(0) }

    var selectedSection by rememberSaveable(route.name) { mutableStateOf(sections.firstOrNull()?.id.orEmpty()) }
    LaunchedEffect(route, sectionFromSearch) {
        val target = sectionFromSearch
        if (target != null && sections.any { it.id == target }) selectedSection = target
        if (route != SettingsRoute.HOME) sectionFromSearch = null
        if (route == SettingsRoute.HOME) fieldFromSearch = null
    }
    val editor = remember(route) { SettingsEditorState() }
    val keyboardVisible = WindowInsets.ime.getBottom(LocalDensity.current) > 0
    val showSections = sections.isNotEmpty() && !editor.active && !keyboardVisible
    var sectionBarHeight by remember { mutableIntStateOf(0) }
    val config = LocalConfiguration.current
    val wide = config.screenWidthDp >= (if (config.fontScale > 1.3f) 760 else 600)
    val dockAtEnd = wide || config.orientation == Configuration.ORIENTATION_LANDSCAPE
    val railWidth = if (config.screenWidthDp < 760) 200.dp else 232.dp
    val sectionBottom = with(LocalDensity.current) { if (showSections || showSearch) sectionBarHeight.toDp() else 0.dp }
    // Keep a separate scroll position per section without discarding the page's draft state.
    val firstScroll = rememberSaveable(route.name, saver = androidx.compose.foundation.ScrollState.Saver) { androidx.compose.foundation.ScrollState(0) }
    val secondScroll = rememberSaveable(route.name, saver = androidx.compose.foundation.ScrollState.Saver) { androidx.compose.foundation.ScrollState(0) }
    val thirdScroll = rememberSaveable(route.name, saver = androidx.compose.foundation.ScrollState.Saver) { androidx.compose.foundation.ScrollState(0) }
    val sectionScroll = when (sections.indexOfFirst { it.id == selectedSection }) { 1 -> secondScroll; 2 -> thirdScroll; else -> firstScroll }

    Scaffold(containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets.systemBars.union(WindowInsets.ime),
        snackbarHost = { SnackbarHost(snackbar) }) { inset ->
      CompositionLocalProvider(LocalBackdrop provides backdrop, LocalSettingsSection provides selectedSection, LocalSettingsEditor provides editor) {
        Box(Modifier.padding(inset).consumeWindowInsets(inset).fillMaxSize()) {
        Row(Modifier.fillMaxSize().hazeSource(backdrop)) {
            if (wide) {
                Column(Modifier.width(railWidth).fillMaxHeight().background(MaterialTheme.colorScheme.surfaceContainerLowest)
                    .verticalScroll(homeScroll).padding(start = 16.dp, end = 12.dp, top = toolbarDp + 13.dp, bottom = if (showSearch) sectionBottom + 24.dp else 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    WorkspaceScopePicker(state, busy || dirty || securityBusy, vm::selectWorkspace)
                    SettingsMenu(state, ::openDirectory, ::openLink, selectedRoute = directoryRoute, compact = true)
                }
            }
            Box(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.TopCenter) {
            key(route, providerId, if (route == SettingsRoute.AGENT_PRESET_EDITOR) presetId else "") {
            Column(Modifier.widthIn(max = 640.dp).fillMaxWidth().fillMaxHeight().verticalScroll(when { showResults && settingsQuery.isNotBlank() -> resultsScroll; showSearch && (!wide || showResults) -> homeScroll; else -> sectionScroll }).padding(start = 16.dp, end = 16.dp, top = toolbarDp + 20.dp, bottom = sectionBottom + 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                notice?.let { Text(it, color = MaterialTheme.colorScheme.secondary) }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                when(route) {
                    SettingsRoute.MODELS, SettingsRoute.PLUGINS, SettingsRoute.SECURITY, SettingsRoute.APP_PREFERENCES -> SettingsBranch(route, ::navigate)
                    SettingsRoute.PROVIDER_DRAFTS, SettingsRoute.MCP_DRAFTS -> {
                        val provider = route == SettingsRoute.PROVIDER_DRAFTS
                        WorkspaceDeclarationsPage(state,
                            if (provider) com.cordiact.core.settings.WorkspaceDeclarations.Kind.PROVIDER else com.cordiact.core.settings.WorkspaceDeclarations.Kind.MCP,
                            onDirty = { dirty = it }, onBusy = { securityBusy = it }) {
                            navigate(if (provider) SettingsRoute.PROVIDERS else SettingsRoute.MCP)
                        }
                    }
                    SettingsRoute.PROJECT_INSTRUCTIONS -> ProjectInstructionsPage(state, onDirty = { dirty = it }, onBusy = { securityBusy = it })
                    SettingsRoute.WORKSPACE_DEFAULTS -> {
                        WorkspaceDefaultsPage(state, agentPresets, busy, vm::selectWorkspace)
                        SettingsGroup {
                            SettingsRow("项目指令", "AGENTS.md · 不授予权限", GlyphName.File) { navigate(SettingsRoute.PROJECT_INSTRUCTIONS) }
                            SettingsRow("Agent 预设库", "此客户端 · 系统提示词与默认模型", GlyphName.AgentPreset) { navigate(SettingsRoute.AGENT_PRESETS) }
                        }
                    }
                    SettingsRoute.HOME -> if (showResults && settingsQuery.isNotBlank()) {
                        SettingsSearchResults(settingsQuery, ::openResult)

                    } else if (wide) {
                        Text("工作区设置", style = MaterialTheme.typography.headlineSmall)
                        Text(state.workspaces.firstOrNull { it.id == state.capabilityWorkspaceId }?.name ?: "未选择工作区")
                        Text("从左侧选择工作区默认、模型与提供商、插件与连接或权限与安全。", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    } else {
                        WorkspaceScopePicker(state, busy || dirty || securityBusy, vm::selectWorkspace)
                        SettingsMenu(state, ::navigate, ::openLink)
                    }
                    SettingsRoute.AGENT_PRESETS -> AgentPresetLibrary(agentPresets, state, wide) {
                        presetId = it
                        navigate(SettingsRoute.AGENT_PRESET_EDITOR)
                    }
                    SettingsRoute.AGENT_PRESET_EDITOR -> AgentPresetEditor(agentPresets, presetId, state.models, selectedSection,
                        onDirty = { dirty = it }, onSaved = { applyNavigation(navigation.pop()) },
                        onCopy = { presetId = "copy:$it" }, onPermissions = { openDirectory(SettingsRoute.PERMISSIONS) })
                    SettingsRoute.PROVIDERS -> ProviderList(state) { providerId = it; navigate(SettingsRoute.DETAIL) }
                    SettingsRoute.ADD -> ProviderEditor(vm, busy, { dirty = it }) { applyNavigation(navigation.providerList()) }
                    SettingsRoute.DETAIL -> state.providers.firstOrNull { it.id == providerId }?.let {
                        key(it.id) { ProviderDetail(it, state, vm, busy, { dirty = it }) { applyNavigation(navigation.providerList()) } }
                    } ?: Text("提供商已删除或无法读取")
                    SettingsRoute.PERMISSIONS, SettingsRoute.PRIVILEGE -> SecuritySettingsPage(vm,
                        privilege = route == SettingsRoute.PRIVILEGE,
                        onState = { changed, working -> dirty = changed; securityBusy = working },
                        onBack = { back() })
                    SettingsRoute.INPUT_VARIABLES -> InputVariablesPage()
                    SettingsRoute.ABOUT -> AboutSettingsPage(onOpenProviders = { navigate(SettingsRoute.PROVIDERS) })
                    SettingsRoute.ROLES -> ModelRoles(state, vm, busy)
                    SettingsRoute.CREDENTIALS -> CredentialSettingsPage(vm) { securityBusy = it }
                    SettingsRoute.ATTACHMENTS -> AttachmentSettingsPage(state.attachmentLimits, busy, { dirty = it }) { value -> vm.saveAttachments(value) { dirty = false } }
                    SettingsRoute.TASKS -> {
                        WorkspaceScopePicker(state, busy || dirty, vm::selectWorkspace)
                        key(state.capabilityWorkspaceId) { AutomationTasksPage(state.capabilityWorkspaceId) }
                    }
                    SettingsRoute.SEARCH -> {
                        WorkspaceScopePicker(state, busy || dirty, vm::selectWorkspace)
                        key(state.capabilityWorkspaceId) { SearchSettingsPage(vm.facade.searchSettings, state.capabilityWorkspaceId, { dirty = it }, { securityBusy = it }) }
                    }
                    SettingsRoute.MCP -> McpSettingsPage(vm.facade.mcpSettings, { dirty = it }, { securityBusy = it })
                    SettingsRoute.SSH -> SshSettingsPage(vm.facade.sshSettings, { dirty = it }, { securityBusy = it }, { discardAction = it })
                    SettingsRoute.CONNECTIONS -> ConnectionSettingsPage(state, vm, busy, connectionId) { workspaceId, capability, next ->
                        val previous = capability.enabled
                        vm.setCapability(workspaceId, capability.id, next)
                        navigationScope.launch {
                            val result = snackbar.showSnackbar("${capability.label}已${if (next) "启用" else "关闭"}", actionLabel = "撤销")
                            if (result == SnackbarResult.ActionPerformed) vm.setCapability(workspaceId, capability.id, previous)
                        }
                    }
                    SettingsRoute.USAGE -> UsageSettingsPage(state, fieldFromSearch)
                    SettingsRoute.AUDIT -> SecurityAuditPage(androidx.compose.ui.platform.LocalContext.current.applicationContext as com.cordiact.app.CordiActApplication)
                }
            }
            }
            }
        }
        ViewportFade(backdrop, true, Modifier.align(Alignment.TopCenter).fillMaxWidth().height(toolbarDp + 16.dp))
        ViewportFade(backdrop, false, Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(sectionBottom + 16.dp))
        if (showSections) SettingsSectionBar(sections, selectedSection, { selectedSection = it },
            Modifier.align(if (dockAtEnd) Alignment.BottomEnd else Alignment.BottomCenter).onSizeChanged { sectionBarHeight = it.height }.padding(horizontal = 20.dp, vertical = 12.dp),
            maxBarWidth = if (dockAtEnd) 324.dp else 440.dp)
        if (showSearch) SettingsSearchBar(searchActive, settingsQuery, searchFocus, requestSearchFocus,
            onFocused = { requestSearchFocus = false }, activate = { requestSearchFocus = true; searchActive = true }, edit = {
                settingsQuery = it
                navigationScope.launch { resultsScroll.scrollTo(0) }
            }, dismiss = ::leaveSearch,
            modifier = Modifier.align(Alignment.BottomCenter).widthIn(max = 640.dp).fillMaxWidth()
                .onSizeChanged { sectionBarHeight = it.height }.padding(horizontal = 20.dp, vertical = 10.dp))
        Row(Modifier.align(Alignment.TopCenter).fillMaxWidth().onSizeChanged { toolbarHeight = it.height }
            .padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            FrostedSurface(radius = 24.dp) {
                IconButton(onClick = { back() }, enabled = !busy && !securityBusy) { AppGlyph(GlyphName.Back, "返回") }
            }
            Text(when (route) {
                SettingsRoute.AGENT_PRESETS -> if (wide) "设置" else "Agent 预设"
                SettingsRoute.AGENT_PRESET_EDITOR -> "Agent 预设"
                else -> route.title
            }, Modifier.weight(1f).padding(horizontal = 12.dp), style = MaterialTheme.typography.titleLarge)
            if (route == SettingsRoute.PROVIDERS) FrostedSurface(radius = 24.dp) {
                TextButton({ navigate(SettingsRoute.ADD) }, enabled = !busy) { Text("添加") }
            }
        }
        }
      }
    }
}
@Composable
private fun SettingsMenu(state: SettingsSnapshot, navigate: (SettingsRoute) -> Unit, openLink: (String?) -> Unit,
                         selectedRoute: SettingsRoute? = null, compact: Boolean = false) {
    SettingsOverview(state, navigate, openLink, selectedRoute, compact)
}
@Composable
private fun ProviderList(state: SettingsSnapshot, select: (String) -> Unit) {
    if (state.providers.isEmpty()) Text("尚无提供商。点右上角「添加」，填写 URL 与 Key 后选择要启用的模型。")
    state.providers.groupBy { it.protocol }.forEach { (protocol, providers) ->
        Text(protocol.label(), style = MaterialTheme.typography.titleMedium)
        SettingsGroup { providers.forEach { p ->
            SettingsRow(p.name, "${java.net.URI(p.endpoint).host} · ${p.enabled.size}/${p.discovered.size} 启用 · ${if (p.keyIsSet) "凭据已设置" else "需要设置凭据"}", GlyphName.Link) { select(p.id) }
        } }
    }
}
@Composable
private fun ProviderEditor(vm: SettingsViewModel, busy: Boolean, dirty: (Boolean) -> Unit, saved: () -> Unit) {
    var step by remember { mutableIntStateOf(0) }
    var name by rememberSaveable { mutableStateOf("") }
    var url by rememberSaveable { mutableStateOf("") }
    // Secrets deliberately never enter rememberSaveable or saved state.
    var secret by remember { mutableStateOf("") }
    val id = rememberSaveable { "custom-${UUID.randomUUID()}" }
    var protocol by rememberSaveable { mutableStateOf(ModelProtocol.OPENAI_CHAT_COMPLETIONS.name) }
    var discovered by remember { mutableStateOf(emptyList<String>()) }
    var selected by remember { mutableStateOf(emptySet<String>()) }
    var query by remember { mutableStateOf("") }
    var manualImageModel by rememberSaveable { mutableStateOf("gpt-image-1.5") }
    DisposableEffect(Unit) { onDispose { secret = "" } }
    Text("第 ${step + 1} 步 / 3", style = MaterialTheme.typography.titleMedium)
    if (protocol == ModelProtocol.OPENAI_IMAGES.name) Text("GPT 生图模型通过 generate_image 工具使用，不进入聊天模型选择器；生成可能计费。", style = MaterialTheme.typography.bodySmall)
    when(step) {
        0 -> {
            ModelProtocol.entries.forEach { p ->
                Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(!busy) { protocol = p.name; dirty(true) }, verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(protocol == p.name, { protocol = p.name; dirty(true) }, enabled = !busy)
                    Column { Text(p.label()); Text(p.example(), style = MaterialTheme.typography.bodySmall) }
                }
            }
            Button({ step = 1 }, enabled = !busy) { Text("下一步") }
        }
        1 -> {
            OutlinedTextField(name, { name = it; dirty(true) }, label = { Text("名称（可选）") }, enabled = !busy, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(url, { url = it; dirty(true) }, label = { Text("Base URL（HTTPS）") }, enabled = !busy, modifier = Modifier.fillMaxWidth())
            SecretField(secret, { secret = it; dirty(true) }, !busy)
            Text("Key 仅发送到你填写的端点，加密保存在本机凭据库。拉取模型不保存任何配置。", style = MaterialTheme.typography.bodySmall)
            Button({ vm.perform { discovered = vm.facade.discover(ProviderDraft(id, name, ModelProtocol.valueOf(protocol), url), secret); selected = discovered.toSet(); step = 2 } }, enabled = !busy && url.isNotBlank() && secret.isNotBlank()) { Text("拉取模型") }
            if (protocol == ModelProtocol.OPENAI_IMAGES.name) {
                OutlinedTextField(manualImageModel, { manualImageModel = it; dirty(true) }, label = { Text("生图模型 ID（手动填写）") }, enabled = !busy, modifier = Modifier.fillMaxWidth())
                Text("部分代理不支持模型发现，可填写服务商实际支持的 gpt-image-* ID。", style = MaterialTheme.typography.bodySmall)
                Button({ discovered = listOf(manualImageModel.trim()); selected = discovered.toSet(); step = 2 },
                    enabled = !busy && url.isNotBlank() && secret.isNotBlank() && manualImageModel.trim().matches(Regex("gpt-image-[a-zA-Z0-9._-]+"))) { Text("使用此生图模型") }
            }
            TextButton({ step = 0 }, enabled = !busy) { Text("上一步") }
        }
        2 -> {
            Text("选择启用模型 · ${selected.size}/${discovered.size}")
            OutlinedTextField(query, { query = it }, label = { Text("搜索模型") }, modifier = Modifier.fillMaxWidth())
            Row { TextButton({ selected = discovered.toSet() }, enabled = !busy) { Text("全选") }; TextButton({ selected = emptySet() }, enabled = !busy) { Text("全不选") } }
            discovered.filter { it.contains(query, true) }.forEach { model ->
                Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(model in selected, { checked -> selected = if (checked) selected + model else selected - model }, enabled = !busy); Text(model, Modifier.weight(1f))
                }
            }
            Button({ vm.perform(success = { secret = ""; saved() }) { vm.facade.commit(ProviderDraft(id, name, ModelProtocol.valueOf(protocol), url), secret, discovered, selected) } }, enabled = !busy && selected.isNotEmpty()) { Text("完成并保存") }
            TextButton({ step = 1 }, enabled = !busy) { Text("修改连接") }
        }
    }
}
@Composable
private fun ProviderDetail(p: ProviderSummary, state: SettingsSnapshot, vm: SettingsViewModel, busy: Boolean, dirty: (Boolean) -> Unit, deleted: () -> Unit) {
    var name by remember(p.name) { mutableStateOf(p.name) }
    var enabled by remember(p.enabled, p.discovered) { mutableStateOf(p.enabled) }
    var secret by remember { mutableStateOf("") }
    var changingKey by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var clearKey by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var diff by remember { mutableStateOf<ModelsDiff?>(null) }
    val affected = state.roles.filterValues { it is ModelRoleSelection.Explicit && it.model.providerId == p.id }
    val changed = name != p.name || enabled != p.enabled || secret.isNotEmpty()
    LaunchedEffect(changed) { dirty(changed) }
    var showModels by rememberSaveable(p.id) { mutableStateOf(true) }
    var editingModel by rememberSaveable(p.id) { mutableStateOf<String?>(null) }
    var addModel by rememberSaveable(p.id) { mutableStateOf(false) }
    if (editingModel != null && editingModel in p.discovered) {
        ProviderModelEditor(p, requireNotNull(editingModel), vm, busy, dirty) { editingModel = null }
        return
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(selected = showModels, onClick = { showModels = true }, label = { Text("模型") })
        FilterChip(selected = !showModels, onClick = { showModels = false }, label = { Text("连接配置") })
    }
    if (!showModels) {
        SettingsGroup {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(p.protocol.label()); Text(p.endpoint)
                Text("换地址或协议需新建提供商，现有 Key 不会迁移。", style = MaterialTheme.typography.bodySmall)
            }
        }
        OutlinedTextField(name, { name = it }, label = { Text("名称") }, enabled = !busy, modifier = Modifier.fillMaxWidth())
        StatusChip(if (p.keyIsSet) SettingsStatus.READY else SettingsStatus.NEEDS_ACTION, if (p.keyIsSet) "API Key 已设置（不回显）" else "API Key 未设置")
        Row { TextButton({ changingKey = !changingKey }, enabled = !busy) { Text("更换 Key") }; if (p.keyIsSet) TextButton({ clearKey = true }, enabled = !busy) { Text("清除 Key") } }
        if (changingKey) SecretField(secret, { secret = it }, !busy)
    } else {
        TextButton({ addModel = true }, enabled = !busy && !changed) { Text("按模型 ID 手动添加") }
        if (addModel) {
            var manual by rememberSaveable { mutableStateOf("") }
            AlertDialog(onDismissRequest = { if (!busy) addModel = false }, title = { Text("手动添加模型") },
                text = { OutlinedTextField(manual, { manual = it.take(256) }, label = { Text("请求模型 ID") }, singleLine = true) },
                dismissButton = { TextButton({ addModel = false }, enabled = !busy) { Text("取消") } },
                confirmButton = { TextButton({ vm.perform(success = { addModel = false }) {
                    vm.facade.addProviderModel(p.id, manual); vm.notice.value = "已添加，勾选并保存后启用"
                } }, enabled = !busy && manual.isNotBlank() && manual.trim() !in p.discovered) { Text("添加") } })
        }
        TextButton({ vm.perform { val started = System.nanoTime(); diff = vm.facade.refreshProvider(p.id); vm.notice.value = "模型目录已刷新（未测试模型能力） · ${(System.nanoTime() - started) / 1_000_000}ms" } }, enabled = !busy && !changed && p.keyIsSet) { Text("获取模型") }
        diff?.let { Text("新增 ${it.added.size} / 下架 ${it.removed.size}；新增模型不会自动启用。") }
        affected.forEach { (role, value) ->
            val model = (value as ModelRoleSelection.Explicit).model
            if (model.modelId !in enabled) Text("${role.title}将失效：${model.modelId}", color = MaterialTheme.colorScheme.error)
        }
        Text("启用模型 ${enabled.size}/${p.discovered.size}", style = MaterialTheme.typography.titleMedium)
        OutlinedTextField(query, { query = it }, label = { Text("搜索模型") }, modifier = Modifier.fillMaxWidth())
        p.discovered.filter { it.contains(query, true) }.forEach { model ->
            Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
                Checkbox(model in enabled, { checked -> enabled = if (checked) enabled + model else enabled - model }, enabled = !busy); Text(model, Modifier.weight(1f))
            }
            SettingsRow("模型声明与参数", model) { if (!busy && !changed) editingModel = model }
        }
    }
    Button({ vm.perform(success = { secret = ""; changingKey = false; dirty(false) }) {
        if (name != p.name) vm.facade.updateProvider(p.id, name, p.endpoint)
        if (secret.isNotEmpty()) vm.facade.setKey(p.id, secret)
        if (enabled != p.enabled) vm.facade.setEnabledModels(p.id, enabled)
        vm.notice.value = "已保存"
    } }, enabled = changed && !busy && enabled.isNotEmpty()) { Text("保存") }
    if (!showModels) TextButton({ confirmDelete = true }, enabled = !busy) { Text("删除提供商") }
    if (confirmDelete) DangerConfirmDialog("删除 ${p.name}？", "删除全部模型与 Key。以下角色保留引用并显示失效：${affected.keys.joinToString { it.title }.ifBlank { "无" }}。", busy, { confirmDelete = false }, onConfirm = {
        vm.perform(success = { confirmDelete = false; deleted() }) { vm.facade.removeProvider(p.id) }
    })
    if (clearKey) AlertDialog(onDismissRequest = { clearKey = false }, title = { Text("清除 Key？") }, text = { Text("此提供商将无法调用，直到重新填写。") },
        dismissButton = { TextButton({ clearKey = false }) { Text("取消") } }, confirmButton = { TextButton({ vm.perform(success = { clearKey = false }) { vm.facade.setKey(p.id, null) } }, enabled = !busy) { Text("清除") } })
}
@Composable
private fun ModelRoles(state: SettingsSnapshot, vm: SettingsViewModel, busy: Boolean) {
    var picking by remember { mutableStateOf<SettingsModelRole?>(null) }
    var query by remember { mutableStateOf("") }
    SettingsModelRole.entries.forEach { role ->
        val selection = if (role == SettingsModelRole.CONVERSATION) state.conversationDefault?.let { ModelRoleSelection.Explicit(it) } else state.roles[role]
        val label = when(selection) {
            is ModelRoleSelection.Explicit -> if (state.models.any { it.id == selection.model }) "${selection.model.modelId} · ${selection.model.providerId}" else "⚠ 已失效：${selection.model.modelId}"
            ModelRoleSelection.FollowConversation -> "跟随对话模型"
            else -> if (role == SettingsModelRole.SUMMARY) "规则摘要（未启用模型）" else "未设置"
        }
        SettingsRow(role.title, label) { if (!busy) { picking = role; query = "" } }
    }
    Text("自动审批审核人将在安全批次提供；本页不会写入旧 endpoint 快照配置。", style = MaterialTheme.typography.bodySmall)
    picking?.let { role ->
        AlertDialog(onDismissRequest = { if (!busy) picking = null }, title = { Text(role.title) }, text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(query, { query = it }, label = { Text("搜索模型") })
                fun choose(selection: ModelRoleSelection) { vm.perform(success = { picking = null }) { vm.facade.setRole(role, selection) } }
                if (role != SettingsModelRole.CONVERSATION) {
                    TextButton({ choose(ModelRoleSelection.FollowConversation) }, enabled = !busy) { Text("跟随对话模型") }
                    TextButton({ choose(ModelRoleSelection.Disabled) }, enabled = !busy) { Text(if (role == SettingsModelRole.SUMMARY) "使用规则摘要" else "关闭记忆抽取") }
                }
                state.models.filter { it.displayName.contains(query, true) || it.id.modelId.contains(query, true) }.groupBy { it.id.providerId }.forEach { (provider, models) ->
                    Text(provider, style = MaterialTheme.typography.labelLarge)
                    models.forEach { model -> TextButton({ choose(ModelRoleSelection.Explicit(model.id)) }, enabled = !busy) { Text(model.id.modelId) } }
                }
            }
        }, confirmButton = { TextButton({ picking = null }, enabled = !busy) { Text("关闭") } })
    }
}
private fun ModelProtocol.label() = when(this) {
    ModelProtocol.OPENAI_CHAT_COMPLETIONS -> "OpenAI 兼容 · Chat Completions"
    ModelProtocol.OPENAI_RESPONSES -> "OpenAI Responses"
    ModelProtocol.OPENAI_IMAGES -> "GPT 图像生成"
    ModelProtocol.ANTHROPIC_MESSAGES -> "Anthropic Messages"
    ModelProtocol.GEMINI_GENERATE_CONTENT -> "Gemini"
}
private fun ModelProtocol.example() = when(this) {
    ModelProtocol.ANTHROPIC_MESSAGES -> "https://api.anthropic.com"
    ModelProtocol.GEMINI_GENERATE_CONTENT -> "https://generativelanguage.googleapis.com"
    else -> "https://api.openai.com/v1"
}

@Composable
private fun ParameterMenu(label: String, selected: String, options: List<String>, busy: Boolean, choose: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        TextButton({ expanded = true }, enabled = !busy) { Text("$label：${selected.lowercase()}") }
        DropdownMenu(expanded, { expanded = false }) {
            options.forEach { option -> DropdownMenuItem(text = { Text(option.lowercase()) }, onClick = { choose(option); expanded = false }) }
        }
    }
}
