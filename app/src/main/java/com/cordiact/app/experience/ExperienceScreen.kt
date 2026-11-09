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
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
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

private data class WindowSpec(val id: String)
private val windows = listOf(WindowSpec("chat"), WindowSpec("trace"), WindowSpec("preview"))

@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
fun ExperienceScreen(viewModel: ExperienceViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val drawer = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val pager = rememberPagerState(pageCount = { windows.size })
    val saveable = rememberSaveableStateHolder()
    var inspected by remember { mutableStateOf<ToolActivity?>(null) }
    var promptToInsert by remember { mutableStateOf("") }
    var modelMenu by remember { mutableStateOf(false) }
    var providerForm by remember { mutableStateOf(false) }
    BackHandler(inspected != null || modelMenu || providerForm) { inspected = null; modelMenu = false; providerForm = false }
    ModalNavigationDrawer(
        drawerState = drawer,
        gesturesEnabled = drawer.isOpen || pager.currentPage == 0,
        drawerContent = {
            val drawerWidth = minOf(LocalConfiguration.current.screenWidthDp.dp * .86f, 360.dp)
            val context = LocalContext.current
            val closeAfterSelection: () -> Unit = { scope.launch { drawer.close() } }
            ModalDrawerSheet(modifier = Modifier.width(drawerWidth), drawerContainerColor = MaterialTheme.colorScheme.surfaceContainerLowest) {
                ConversationSidebar(
                    groups = state.sidebar, activeWorkspaceId = state.workspace?.id, activeSessionId = state.sessionId,
                    runningSessionIds = state.runningSessionIds, busy = state.loading, error = state.error,
                    onClose = { scope.launch { drawer.close() } },
                    onNewSession = { workspace -> viewModel.newSession(workspace ?: state.workspace?.id, closeAfterSelection) },
                    onSelectWorkspace = { viewModel.selectWorkspace(it, closeAfterSelection) },
                    onSelectSession = { viewModel.selectSession(it, closeAfterSelection) },
                    onCreateWorkspace = { name, onSuccess -> viewModel.createWorkspace(name) { closeAfterSelection(); onSuccess() } },
                    onRenameWorkspace = { id, name, onSuccess -> viewModel.renameWorkspace(id, name, onSuccess) },
                    onRetry = viewModel::refresh,
                    onSettings = { android.widget.Toast.makeText(context, "设置将在批二开放", android.widget.Toast.LENGTH_SHORT).show() },
                )
            }
        },
    ) {
        Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
            Box(Modifier.fillMaxSize()) {
                ExperienceFrame(
                    top = {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = { scope.launch { drawer.open() } }, modifier = Modifier.testTag("open-navigation")) { AppGlyph(GlyphName.Menu, "打开工作区和会话") }
                        Column(Modifier.weight(1f).padding(horizontal = 4.dp)) {
                            Text(state.workspace?.name ?: "CordiAct", style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(state.sessions.find { it.id == state.sessionId }?.title ?: "你的智能工作空间", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        Row(Modifier.padding(end = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(6.dp).background(if (state.running) MaterialTheme.colorScheme.primary else Color(0xFF5B927C), CircleShape))
                            Text(if (state.running) "运行中" else "就绪", style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(start = 6.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    WindowTabs(
                        selected = pager.settledPage,
                        position = pager.currentPage + pager.currentPageOffsetFraction,
                        tools = state.activeTools,
                        previews = com.cordiact.app.render.PreviewInbox.unseenCount(state.sessionId),
                        onSelect = { scope.launch { pager.animateScrollToPage(it) } },
                    )
                    },
                    content = { viewportModifier ->
                    Column(viewportModifier) {
                    if (state.error != null) Box(Modifier.padding(horizontal = 18.dp, vertical = 5.dp)) { ErrorStrip(state.error!!, viewModel::dismissError) }
                    HorizontalPager(state = pager, beyondViewportPageCount = 2, key = { windows[it].id }, modifier = Modifier.weight(1f).fillMaxWidth()) { page ->
                        when (windows[page].id) {
                            "chat" -> TranscriptView(state, onPrompt = { promptToInsert = it }, onInspect = { inspected = it })
                            "trace" -> TrajectoryView(state, onInspect = { inspected = it })
                            "preview" -> PreviewView(state, visible = pager.settledPage == 2)
                        }
                    }
                    }
                    },
                    bottom = {
                    state.approvals.firstOrNull()?.let { request ->
                        Surface(Modifier.padding(horizontal = 16.dp, vertical = 5.dp).fillMaxWidth(), shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.tertiaryContainer) {
                            Column(Modifier.padding(13.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    AppGlyph(GlyphName.Warning, modifier = Modifier.size(17.dp))
                                    Text("需要确认 · ${request.toolName}", Modifier.padding(start = 7.dp).weight(1f), style = MaterialTheme.typography.titleSmall)
                                    if (state.approvals.size > 1) Text("1 / ${state.approvals.size}", style = MaterialTheme.typography.labelSmall)
                                }
                                Text(request.summary, style = MaterialTheme.typography.bodySmall, maxLines = 5, overflow = TextOverflow.Ellipsis)
                                request.details.takeIf { it.isNotEmpty() }?.let { details ->
                                    var expanded by remember(request.id) { mutableStateOf(false) }
                                    TextButton(onClick = { expanded = !expanded }, contentPadding = PaddingValues(0.dp)) { Text(if (expanded) "收起参数" else "查看参数") }
                                    if (expanded) SelectionContainer { Text(details.entries.joinToString("\n") { "${it.key}: ${it.value}" }, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall) }
                                }
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                                    TextButton(onClick = { viewModel.decide(request.id, false) }) { Text("拒绝") }
                                    Spacer(Modifier.width(8.dp))
                                    FilledTonalButton(onClick = { viewModel.decide(request.id, true) }) { Text("仅本次允许") }
                                }
                            }
                        }
                    }
                    AnimatedVisibility(state.running, enter = fadeIn(spring()) + expandVertically(spring()), exit = fadeOut() + shrinkVertically(spring())) {
                        Row(Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 5.dp), horizontalArrangement = Arrangement.Center) {
                            Surface(onClick = { scope.launch { pager.animateScrollToPage(1) } }, shape = CircleShape, color = MaterialTheme.colorScheme.inverseSurface) {
                                Row(Modifier.padding(horizontal = 13.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
                                    CircularProgressIndicator(Modifier.size(10.dp), color = MaterialTheme.colorScheme.inverseOnSurface, strokeWidth = 1.5.dp)
                                    Text(state.turns.lastOrNull()?.tools?.lastOrNull { it.phase == ToolPhase.RUNNING }?.name ?: "Agent 正在响应", Modifier.padding(start = 8.dp).widthIn(max = 240.dp), color = MaterialTheme.colorScheme.inverseOnSurface, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                            }
                        }
                    }
                    saveable.SaveableStateProvider(state.sessionId ?: "loading") {
                        Composer(
                            state = state, insert = promptToInsert,
                            onInserted = { promptToInsert = "" },
                            onSend = viewModel::send, onStop = viewModel::stop,
                            onModelMenu = { modelMenu = true },
                        )
                    }
                    },
                )
                inspected?.let { step ->
                    CompactInspector(step = step, onClose = { inspected = null }, modifier = Modifier.align(Alignment.BottomCenter).windowInsetsPadding(WindowInsets.systemBars.union(WindowInsets.ime)))
                }
                if (providerForm) {
                    ProviderForm(onClose = { providerForm = false }, onSave = viewModel::addProvider, modifier = Modifier.align(Alignment.BottomCenter).windowInsetsPadding(WindowInsets.systemBars.union(WindowInsets.ime)))
                }
                if (modelMenu) {
                    CompactModelPicker(state, onClose = { modelMenu = false }, onSelect = { viewModel.selectModel(it.id); modelMenu = false }, onAdd = { modelMenu = false; providerForm = true }, modifier = Modifier.align(Alignment.BottomCenter).windowInsetsPadding(WindowInsets.systemBars.union(WindowInsets.ime)))
                }
            }
        }
    }
}

@Composable
internal fun Composer(state: ExperienceState, insert: String, onInserted: () -> Unit, onSend: (String) -> Boolean, onStop: () -> Unit, onModelMenu: () -> Unit) {
    var text by rememberSaveable { mutableStateOf("") }
    LaunchedEffect(insert) { if (insert.isNotEmpty()) { text = insert; onInserted() } }
    Surface(
        shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant), shadowElevation = 3.dp,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        Column(Modifier.padding(top = 12.dp, bottom = 3.dp, start = 15.dp, end = 5.dp)) {
            BasicTextField(
                value = text, onValueChange = { text = it },
                modifier = Modifier.fillMaxWidth().heightIn(min = 30.dp, max = 132.dp).padding(end = 10.dp).semantics { contentDescription = "消息输入" }.testTag("composer-input"),
                textStyle = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurface),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary), maxLines = 6,
                decorationBox = { field -> Box { if (text.isEmpty()) Text("发送消息 · / 指令 · @ 上下文 · # 私密", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium); field() } },
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onModelMenu, contentPadding = PaddingValues(end = 6.dp), modifier = Modifier.heightIn(min = 44.dp).weight(1f)) {
                    Text(state.models.find { it.id == state.model }?.displayName ?: "选择模型", Modifier.weight(1f), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    AppGlyph(GlyphName.ChevronDown, modifier = Modifier.size(13.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                FilledIconButton(
                    onClick = { if (state.running) onStop() else if (onSend(text)) text = "" },
                    enabled = !state.stopping && (state.running || (!state.loading && text.isNotBlank() && state.model != null)),
                    shape = CircleShape, modifier = Modifier.size(48.dp).testTag(if (state.running) "stop-generation" else "send-message"),
                ) { AppGlyph(if (state.running) GlyphName.Stop else GlyphName.Send, if (state.running) "停止当前回合" else "发送消息", modifier = Modifier.size(20.dp)) }
            }
        }
    }
}

@Composable
internal fun CompactInspector(step: ToolActivity, onClose: () -> Unit, modifier: Modifier = Modifier) {
    val clipboard = LocalClipboardManager.current
    BoxWithConstraints(modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
        val maxSheet = maxHeight * .45f
        Surface(Modifier.fillMaxWidth().heightIn(max = maxSheet).padding(horizontal = 8.dp, vertical = 4.dp), shape = RoundedCornerShape(23.dp), shadowElevation = 12.dp, color = MaterialTheme.colorScheme.surface, border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
            Column {
                PeekHandle(onClose)
                Row(Modifier.padding(start = 17.dp, end = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                    AppGlyph(GlyphName.Terminal, modifier = Modifier.size(17.dp), tint = MaterialTheme.colorScheme.primary)
                    Text(step.name, Modifier.weight(1f).padding(horizontal = 9.dp), style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    IconButton(onClick = { clipboard.setText(AnnotatedString(listOf(step.arguments, step.summary.orEmpty()).filter { it.isNotBlank() }.joinToString("\n\n"))) }) { AppGlyph(GlyphName.Copy, "复制工具详情", modifier = Modifier.size(17.dp)) }
                    IconButton(onClick = onClose) { AppGlyph(GlyphName.Close, "关闭工具详情", modifier = Modifier.size(17.dp)) }
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
internal fun CompactModelPicker(state: ExperienceState, onClose: () -> Unit, onSelect: (com.cordiact.core.conversation.ModelDescriptor) -> Unit, onAdd: () -> Unit = {}, modifier: Modifier = Modifier) {
    BoxWithConstraints(modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
        Surface(Modifier.fillMaxWidth().heightIn(max = maxHeight * .45f).padding(8.dp), color = MaterialTheme.colorScheme.surface, shape = RoundedCornerShape(23.dp), shadowElevation = 12.dp, border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
            Column {
                PeekHandle(onClose)
                Row(Modifier.padding(start = 18.dp, end = 5.dp), verticalAlignment = Alignment.CenterVertically) { Text("选择模型", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium); IconButton(onClick = onClose) { AppGlyph(GlyphName.Close, "关闭模型选择") } }
                TextButton(onClick = onAdd, modifier = Modifier.padding(horizontal = 10.dp)) { AppGlyph(GlyphName.Plus, modifier = Modifier.size(16.dp)); Text("添加模型来源", Modifier.padding(start = 8.dp)) }
                LazyColumn(Modifier.weight(1f, fill = false)) {
                    if (state.models.isEmpty()) item { Text("尚未配置可用模型。", Modifier.padding(20.dp), style = MaterialTheme.typography.bodyMedium) }
                    items(state.models, key = { "${it.id.providerId}:${it.id.modelId}" }) { model ->
                        ListItem(
                            headlineContent = { Text(model.displayName, style = MaterialTheme.typography.bodyMedium) },
                            supportingContent = { Text(model.id.providerId, style = MaterialTheme.typography.labelSmall) },
                            trailingContent = { if (model.id == state.model) AppGlyph(GlyphName.Check, "已选择", tint = MaterialTheme.colorScheme.primary) },
                            modifier = Modifier.clickable { onSelect(model) },
                        )
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


/** Compact local configuration: save never probes an endpoint or sends the user's key. */
@Composable
private fun ProviderForm(
    onClose: () -> Unit,
    onSave: (String, String, List<String>, ModelProtocol, String, (String?) -> Unit) -> Unit,
    modifier: Modifier = Modifier,
) {
    var name by remember { mutableStateOf("") }
    var url by remember { mutableStateOf("") }
    var models by remember { mutableStateOf("") }
    var key by remember { mutableStateOf("") } // Intentionally not Saveable: never bundle a secret.
    var protocol by remember { mutableStateOf(ModelProtocol.OPENAI_CHAT_COMPLETIONS) }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    BoxWithConstraints(modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
        Surface(Modifier.fillMaxWidth().heightIn(max = maxHeight * .45f).padding(8.dp), color = MaterialTheme.colorScheme.surface, shape = RoundedCornerShape(23.dp), shadowElevation = 12.dp, border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
            Column {
                PeekHandle { if (!saving) { key = ""; onClose() } }
                Row(Modifier.padding(start = 17.dp, end = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("添加模型来源", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                    IconButton(enabled = !saving, onClick = { key = ""; onClose() }) { AppGlyph(GlyphName.Close, "关闭来源设置") }
                }
                Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("只保存本地配置，不发起连接；密钥由 Android Keystore 加密。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    OutlinedTextField(name, { name = it }, label = { Text("来源名称") }, singleLine = true, enabled = !saving, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(url, { url = it }, label = { Text("HTTPS 完整请求地址") }, placeholder = { Text("https://api.example.com/v1/chat/completions") }, singleLine = true, enabled = !saving, modifier = Modifier.fillMaxWidth(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri))
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                        listOf(ModelProtocol.OPENAI_CHAT_COMPLETIONS to "Chat Completions", ModelProtocol.OPENAI_RESPONSES to "Responses", ModelProtocol.ANTHROPIC_MESSAGES to "Anthropic").forEach { (value, label) ->
                            FilterChip(selected = protocol == value, onClick = { protocol = value }, label = { Text(label) }, enabled = !saving)
                        }
                    }
                    OutlinedTextField(models, { models = it }, label = { Text("模型 ID（逗号分隔）") }, singleLine = true, enabled = !saving, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(key, { key = it }, label = { Text("API Key") }, visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), singleLine = true, enabled = !saving, modifier = Modifier.fillMaxWidth())
                    error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                    Spacer(Modifier.height(4.dp))
                }
                FilledTonalButton(
                    enabled = !saving && name.isNotBlank() && url.isNotBlank() && models.isNotBlank() && key.isNotBlank(),
                    onClick = {
                        saving = true; error = null
                        onSave(name, url, models.split(',', '，', '\n').map(String::trim).filter(String::isNotBlank), protocol, key) { result ->
                            saving = false
                            if (result == null) { key = ""; onClose() } else error = result
                        }
                    }, modifier = Modifier.fillMaxWidth().padding(12.dp),
                ) { Text(if (saving) "正在保存…" else "保存来源") }
            }
        }
    }
}
