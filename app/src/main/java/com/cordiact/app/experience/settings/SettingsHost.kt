package com.cordiact.app.experience.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import kotlinx.coroutines.launch
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cordiact.app.experience.AppGlyph
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
    fun back() = requestNavigation(navigation.pop())
    fun openLink(raw: String?) {
        val link = SettingsLink.parse(raw) ?: return
        if (busy || securityBusy || dirty || discardBusy) return
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
    Scaffold(snackbarHost = { SnackbarHost(snackbar) }, topBar = { TopAppBar(title = { Text(route.title) }, navigationIcon = {
        IconButton({ back() }, enabled = !busy && !securityBusy) { AppGlyph(GlyphName.Back, "返回") }
    }, actions = { if (route == SettingsRoute.PROVIDERS) TextButton({ navigate(SettingsRoute.ADD) }, enabled = !busy) { Text("添加") } }) }) { inset ->
        Row(Modifier.padding(inset).fillMaxSize()) {
            val wide = LocalConfiguration.current.screenWidthDp >= 600
            if (wide) {
                Column(Modifier.width(360.dp).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    SettingsMenu(state, ::navigate, ::openLink)
                }
                VerticalDivider()
            }
            Box(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.TopCenter) {
            key(route, providerId) {
            Column(Modifier.widthIn(max = 640.dp).fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(when (route) {
                    SettingsRoute.HOME -> "全局与工作区设置"
                    SettingsRoute.PERMISSIONS -> "按工作区设置"
                    SettingsRoute.CONNECTIONS -> "全局连接状态 · 工作区能力开关"
                    SettingsRoute.SEARCH -> "工作区模式与排序 · 全局服务配置"
                    SettingsRoute.PRIVILEGE -> "全局开关 · 按工作区权限生效"
                    SettingsRoute.AUDIT -> "按工作区筛选"
                    else -> "全局设置"
                }, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                notice?.let { Text(it, color = MaterialTheme.colorScheme.secondary) }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                when(route) {
                    SettingsRoute.HOME -> if (wide) {
                        Text("选择左侧设置项", style = MaterialTheme.typography.titleMedium)
                        Text("模型、凭据和连接为全局设置；权限、搜索策略和能力开关作用于选定工作区。")
                    } else SettingsMenu(state, ::navigate, ::openLink)
                    SettingsRoute.PROVIDERS -> ProviderList(state) { providerId = it; navigate(SettingsRoute.DETAIL) }
                    SettingsRoute.ADD -> ProviderEditor(vm, busy, { dirty = it }) { applyNavigation(navigation.providerList()) }
                    SettingsRoute.DETAIL -> state.providers.firstOrNull { it.id == providerId }?.let {
                        key(it.id) { ProviderDetail(it, state, vm, busy, { dirty = it }) { applyNavigation(navigation.providerList()) } }
                    } ?: Text("提供商已删除或无法读取")
                    SettingsRoute.PERMISSIONS, SettingsRoute.PRIVILEGE -> SecuritySettingsPage(vm,
                        privilege = route == SettingsRoute.PRIVILEGE,
                        onState = { changed, working -> dirty = changed; securityBusy = working },
                        onBack = { back() })
                    SettingsRoute.ABOUT -> AboutSettingsPage()
                    SettingsRoute.ROLES -> ModelRoles(state, vm, busy)
                    SettingsRoute.CREDENTIALS -> CredentialSettingsPage(vm) { securityBusy = it }
                    SettingsRoute.ATTACHMENTS -> AttachmentSettingsPage(state.attachmentLimits, busy, { dirty = it }) { value -> vm.saveAttachments(value) { dirty = false } }
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
                    SettingsRoute.AUDIT -> SecurityAuditPage(androidx.compose.ui.platform.LocalContext.current.applicationContext as com.cordiact.app.CordiActApplication)
                }
            }
            }
            }
        }
    }
}
@Composable
private fun SettingsMenu(state: SettingsSnapshot, navigate: (SettingsRoute) -> Unit, openLink: (String?) -> Unit) {
                        SettingsHome(state, { navigate(SettingsRoute.PROVIDERS) }, { navigate(SettingsRoute.ROLES) }, openLink)
                        SettingsRow("SSH 主机", "全局 · 凭据库与指纹固定") { navigate(SettingsRoute.SSH) }
                        SettingsRow("连接与能力", "当前工作区 · 就绪向导与能力开关") { navigate(SettingsRoute.CONNECTIONS) }
                        SettingsRow("权限档位", "按工作区 · 四档权限与独立审核人") { navigate(SettingsRoute.PERMISSIONS) }
                        SettingsRow("特权执行", "全局开关 · 仅完全权限工作区生效") { navigate(SettingsRoute.PRIVILEGE) }
                        SettingsRow("已存凭据", "模型 / 搜索 / SSH / MCP · 元数据与清除") { navigate(SettingsRoute.CREDENTIALS) }
                        SettingsRow("搜索服务", "工作区模式与排序 · 全局 Key 和地址") { navigate(SettingsRoute.SEARCH) }
                        SettingsRow("MCP 服务器", "全局 · 连接探测、鉴权与工具预览") { navigate(SettingsRoute.MCP) }
                        SettingsRow("附件限制", "全局 · 仅允许下调安全上限") { navigate(SettingsRoute.ATTACHMENTS) }
                        SettingsRow("关于", "版本 / 构建号 / 开源许可") { navigate(SettingsRoute.ABOUT) }
                        SettingsRow("审计日志", "安全 / 凭据 / 审批 · 只读") { navigate(SettingsRoute.AUDIT) }
}
@Composable
private fun SettingsHome(state: SettingsSnapshot, providers: () -> Unit, roles: () -> Unit, openLink: (String?) -> Unit) {
    val warnings = state.readiness.filter { it.state == SettingsStatus.ERROR || it.state == SettingsStatus.NEEDS_ACTION }
    if (warnings.isNotEmpty()) {
        Text("${warnings.size} 项需要处理", style = MaterialTheme.typography.titleMedium)
        warnings.forEach { item ->
            val link = item.route ?: if (item.id.startsWith("role:")) "settings://models/roles" else null
            SettingsRow(item.title, item.summary, enabled = SettingsLink.parse(link) != null) { openLink(link) }
        }
    }
    Text("模型", style = MaterialTheme.typography.titleMedium)
    SettingsRow("模型提供商", "${state.providers.size} 个 · ${state.providers.sumOf { it.enabled.size }} 个启用模型") { providers() }
    SettingsRow("模型角色", "对话默认 / 上下文摘要 / 记忆抽取") { roles() }
}
@Composable
private fun ProviderList(state: SettingsSnapshot, select: (String) -> Unit) {
    if (state.providers.isEmpty()) Text("尚无提供商。点右上角「添加」，填写 URL 与 Key 后选择要启用的模型。")
    state.providers.groupBy { it.protocol }.forEach { (protocol, providers) ->
        Text(protocol.label(), style = MaterialTheme.typography.titleMedium)
        providers.forEach { p ->
            SettingsRow(p.name, "${java.net.URI(p.endpoint).host} · ${p.enabled.size}/${p.discovered.size} 启用 · ${if (p.keyIsSet) "Key 已设置" else "需要设置 Key"}") { select(p.id) }
        }
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
    DisposableEffect(Unit) { onDispose { secret = "" } }
    Text("第 ${step + 1} 步 / 3", style = MaterialTheme.typography.titleMedium)
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
    var defaults by remember(p.modelDefaults) { mutableStateOf(p.modelDefaults) }
    var secret by remember { mutableStateOf("") }
    var changingKey by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var clearKey by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var diff by remember { mutableStateOf<ModelsDiff?>(null) }
    val changed = name != p.name || enabled != p.enabled || secret.isNotEmpty() || defaults != p.modelDefaults
    LaunchedEffect(changed) { dirty(changed) }
    Text(p.protocol.label()); Text(p.endpoint)
    OutlinedTextField(name, { name = it }, label = { Text("名称") }, enabled = !busy, modifier = Modifier.fillMaxWidth())
    StatusChip(if (p.keyIsSet) SettingsStatus.READY else SettingsStatus.NEEDS_ACTION, if (p.keyIsSet) "API Key 已设置（不回显）" else "API Key 未设置")
    Row { TextButton({ changingKey = !changingKey }, enabled = !busy) { Text("更换 Key") }; if (p.keyIsSet) TextButton({ clearKey = true }, enabled = !busy) { Text("清除 Key") } }
    if (changingKey) SecretField(secret, { secret = it }, !busy)
    TextButton({ vm.perform { val started = System.nanoTime(); diff = vm.facade.refreshProvider(p.id); vm.notice.value = "模型目录检测成功 · ${(System.nanoTime() - started) / 1_000_000}ms" } }, enabled = !busy && !changed && p.keyIsSet) { Text("测试连接并重新拉取") }
    diff?.let { Text("新增 ${it.added.size} / 下架 ${it.removed.size}；新增模型不会自动启用。") }
    val affected = state.roles.filterValues { it is ModelRoleSelection.Explicit && it.model.providerId == p.id }
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
        p.capabilities[model]?.let { capability ->
            val value = defaults[model] ?: com.cordiact.core.conversation.GenerationParameters.NONE
            if (capability.reasoningEffortValues.isNotEmpty()) ParameterMenu("推理", value.reasoningEffort?.name ?: "AUTO",
                listOf("AUTO") + capability.reasoningEffortValues.keys.sortedBy { it.ordinal }.map { it.name }, busy) { selected ->
                defaults = defaults + (model to value.copy(reasoningEffort = com.cordiact.core.conversation.ReasoningEffort.valueOf(selected)))
            }
            if (capability.responseSpeedValues.isNotEmpty()) ParameterMenu("速度", value.responseSpeed?.name ?: "AUTO",
                listOf("AUTO") + capability.responseSpeedValues.keys.sortedBy { it.ordinal }.map { it.name }, busy) { selected ->
                defaults = defaults + (model to value.copy(responseSpeed = com.cordiact.core.conversation.ResponseSpeed.valueOf(selected)))
            }
        }
    }
    Button({ vm.perform(success = { secret = ""; changingKey = false; dirty(false) }) {
        if (name != p.name) vm.facade.updateProvider(p.id, name, p.endpoint)
        if (secret.isNotEmpty()) vm.facade.setKey(p.id, secret)
        if (enabled != p.enabled) vm.facade.setEnabledModels(p.id, enabled)
        if (defaults != p.modelDefaults) vm.facade.setModelDefaults(p.id, defaults)
        vm.notice.value = "已保存"
    } }, enabled = changed && !busy) { Text("保存") }
    TextButton({ confirmDelete = true }, enabled = !busy) { Text("删除提供商") }
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
