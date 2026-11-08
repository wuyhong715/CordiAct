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
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
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

private data class WindowSpec(val id: String, val title: String, val glyph: GlyphName)
private val windows = listOf(WindowSpec("chat", "对话", GlyphName.Chat), WindowSpec("trace", "轨迹", GlyphName.Trace), WindowSpec("preview", "预览", GlyphName.Preview))

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
    BackHandler(inspected != null || modelMenu) { inspected = null; modelMenu = false }
    ModalNavigationDrawer(
        drawerState = drawer,
        gesturesEnabled = drawer.isOpen || pager.currentPage == 0,
        drawerContent = {
            ModalDrawerSheet(modifier = Modifier.fillMaxWidth(.87f), drawerContainerColor = MaterialTheme.colorScheme.background) {
                Column(Modifier.fillMaxSize().statusBarsPadding().padding(horizontal = 16.dp)) {
                    Row(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 18.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("CordiAct", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                        IconButton(onClick = { scope.launch { drawer.close() } }) { AppGlyph(GlyphName.Close, "关闭导航") }
                    }
                    FilledTonalButton(onClick = { viewModel.newSession(); scope.launch { drawer.close() } }, modifier = Modifier.fillMaxWidth(), contentPadding = PaddingValues(13.dp)) {
                        AppGlyph(GlyphName.Plus, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(9.dp)); Text("新对话")
                    }
                    Text("工作区", Modifier.padding(top = 26.dp, bottom = 10.dp), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    state.workspaces.forEach { workspace ->
                        NavigationDrawerItem(
                            label = { Text(workspace.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            selected = state.workspace?.id == workspace.id,
                            onClick = { viewModel.selectWorkspace(workspace.id) },
                            icon = { AppGlyph(GlyphName.Terminal, modifier = Modifier.size(18.dp)) },
                            modifier = Modifier.padding(vertical = 2.dp),
                            colors = NavigationDrawerItemDefaults.colors(selectedContainerColor = MaterialTheme.colorScheme.primaryContainer),
                        )
                    }
                    Text("会话", Modifier.padding(top = 24.dp, bottom = 10.dp), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        items(state.sessions, key = { it.id }) { session ->
                            NavigationDrawerItem(
                                label = { Text(session.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                                selected = session.id == state.sessionId,
                                onClick = { viewModel.selectSession(session.id); scope.launch { drawer.close() } },
                                icon = { AppGlyph(GlyphName.Chat, modifier = Modifier.size(17.dp)) },
                                colors = NavigationDrawerItemDefaults.colors(selectedContainerColor = MaterialTheme.colorScheme.surfaceContainer),
                            )
                        }
                    }
                    Text("工作区隔离 · 会话持久保存", Modifier.padding(vertical = 19.dp).navigationBarsPadding(), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelSmall)
                }
            }
        },
    ) {
        Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
            Box(Modifier.fillMaxSize()) {
                Column(Modifier.fillMaxSize().statusBarsPadding().imePadding().navigationBarsPadding()) {
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
                    Row(Modifier.padding(horizontal = 18.dp, vertical = 10.dp).fillMaxWidth().height(38.dp).clip(RoundedCornerShape(13.dp)).background(MaterialTheme.colorScheme.surfaceContainer), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                        windows.forEachIndexed { index, spec ->
                            val selected = pager.currentPage == index
                            val background by animateColorAsState(if (selected) MaterialTheme.colorScheme.surface else Color.Transparent, spring(), label = "tab-surface")
                            Surface(
                                onClick = { scope.launch { pager.animateScrollToPage(index) } },
                                color = background, shape = RoundedCornerShape(10.dp),
                                modifier = Modifier.weight(1f).padding(3.dp).fillMaxHeight().semantics { this.selected = selected; role = Role.Tab }.testTag("window-${spec.id}"),
                            ) {
                                Row(horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                                    AppGlyph(spec.glyph, modifier = Modifier.size(15.dp), tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                                    Text(spec.title, Modifier.padding(start = 6.dp), style = MaterialTheme.typography.labelLarge, color = if (selected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant)
                                    if (index == 1 && state.activeTools > 0) Text(" ${state.activeTools}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                                }
                            }
                        }
                    }
                    if (state.error != null) Box(Modifier.padding(horizontal = 18.dp, vertical = 5.dp)) { ErrorStrip(state.error!!, viewModel::dismissError) }
                    HorizontalPager(state = pager, beyondViewportPageCount = 2, key = { windows[it].id }, modifier = Modifier.weight(1f).fillMaxWidth()) { page ->
                        when (windows[page].id) {
                            "chat" -> TranscriptView(state, onPrompt = { promptToInsert = it }, onInspect = { inspected = it })
                            "trace" -> TrajectoryView(state, onInspect = { inspected = it })
                            "preview" -> PreviewView(state, onInspect = { inspected = it })
                        }
                    }
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
                }
                inspected?.let { step ->
                    CompactInspector(step = step, onClose = { inspected = null }, modifier = Modifier.align(Alignment.BottomCenter).imePadding().navigationBarsPadding())
                }
                if (modelMenu) {
                    CompactModelPicker(state, onClose = { modelMenu = false }, onSelect = { viewModel.selectModel(it.id); modelMenu = false }, modifier = Modifier.align(Alignment.BottomCenter).imePadding().navigationBarsPadding())
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
        modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 7.dp).animateContentSize(spring(stiffness = Spring.StiffnessMedium)),
    ) {
        Column(Modifier.padding(top = 12.dp, bottom = 6.dp, start = 15.dp, end = 6.dp)) {
            BasicTextField(
                value = text, onValueChange = { text = it },
                modifier = Modifier.fillMaxWidth().heightIn(min = 36.dp, max = 144.dp).padding(end = 10.dp).semantics { contentDescription = "消息输入" }.testTag("composer-input"),
                textStyle = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurface),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary), maxLines = 6,
                decorationBox = { field -> Box { if (text.isEmpty()) Text("告诉我，你想完成什么？", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium); field() } },
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onModelMenu, contentPadding = PaddingValues(end = 6.dp), modifier = Modifier.heightIn(min = 48.dp).weight(1f)) {
                    Text(state.models.find { it.id == state.model }?.displayName ?: "选择模型", Modifier.weight(1f), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    AppGlyph(GlyphName.ChevronDown, modifier = Modifier.size(13.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                listOf("@", "#", "/").forEach { token ->
                    TextButton(onClick = { text += if (text.isNotEmpty() && !text.endsWith(" ")) " $token" else token }, modifier = Modifier.size(44.dp,48.dp), contentPadding = PaddingValues(0.dp)) {
                        Text(token, fontFamily = FontFamily.Monospace, fontSize = 17.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
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
internal fun CompactModelPicker(state: ExperienceState, onClose: () -> Unit, onSelect: (com.cordiact.core.conversation.ModelDescriptor) -> Unit, modifier: Modifier = Modifier) {
    BoxWithConstraints(modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
        Surface(Modifier.fillMaxWidth().heightIn(max = maxHeight * .45f).padding(8.dp), color = MaterialTheme.colorScheme.surface, shape = RoundedCornerShape(23.dp), shadowElevation = 12.dp, border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
            Column {
                PeekHandle(onClose)
                Row(Modifier.padding(start = 18.dp, end = 5.dp), verticalAlignment = Alignment.CenterVertically) { Text("选择模型", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium); IconButton(onClick = onClose) { AppGlyph(GlyphName.Close, "关闭模型选择") } }
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
