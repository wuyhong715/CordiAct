package com.cordiact.app.experience

import androidx.compose.ui.draw.drawBehind
import androidx.compose.animation.core.tween
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cordiact.app.experience.composer.Motion
import com.cordiact.app.experience.composer.motionDuration
import com.cordiact.app.experience.model.*
import com.cordiact.core.plugin.ExtensionPackageMessageEvent
import com.cordiact.interaction.WorkspaceClientContributions.MessageNode
import kotlinx.coroutines.launch

@Composable
fun TranscriptView(
    state: ExperienceState,
    onPrompt: (String) -> Unit,
    onInspect: (ToolActivity) -> Unit,
    modifier: Modifier = Modifier,
    onDecideApproval: ((String, Boolean) -> Unit)? = null,
    onOpenApprovalQueue: (() -> Unit)? = null,
    onOpenTrace: (() -> Unit)? = null,
    messageNodes: (ExtensionPackageMessageEvent) -> List<MessageNode> = { emptyList() },
) {
    val list = rememberLazyListState()
    val scope = rememberCoroutineScope()
    var pinned by rememberSaveable(state.sessionId) { mutableStateOf(true) }
    var newContent by rememberSaveable { mutableStateOf(false) }
    val pinPolicy = remember { PinnedScrollPolicy() }
    val latestPinned by rememberUpdatedState(pinned)
    val latestCount by rememberUpdatedState(state.turns.size)
    val connection = remember(list) {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (source == NestedScrollSource.UserInput && pinned && list.canScrollBackward && !pinPolicy.userDrag(available.y)) {
                    pinned = false
                    newContent = false
                }
                return Offset.Zero
            }
        }
    }
    var readingKey by rememberSaveable { mutableStateOf<String?>(null) }
    var readingOffset by rememberSaveable { mutableIntStateOf(0) }
    val restoredKey = remember { readingKey }
    var anchorReady by remember { mutableStateOf(restoredKey == null) }
    LaunchedEffect(state.loading, state.turns.isNotEmpty()) {
        if (!anchorReady && state.turns.isNotEmpty()) {
            val index = state.turns.indexOfFirst { it.id == restoredKey }
            list.scrollToItem(if (index >= 0) index else list.firstVisibleItemIndex.coerceAtMost(state.turns.lastIndex),
                if (index >= 0) readingOffset else 0)
            anchorReady = true
        }
    }
    LaunchedEffect(list, anchorReady) {
        if (!anchorReady) return@LaunchedEffect
        snapshotFlow {
            val index = list.firstVisibleItemIndex
            val key = list.layoutInfo.visibleItemsInfo.firstOrNull { it.index == index }?.key as? String
            Triple(latestPinned, key, list.firstVisibleItemScrollOffset)
        }.collect { (following, key, offset) ->
            if (following) readingKey = null
            else if (key != null && key != "generated-images" && key != "transcript-end") {
                readingKey = key
                readingOffset = offset
            }
        }
    }
    val tail = state.turns.lastOrNull()
    val tailMarker = listOf(tail?.id, tail?.answer?.length, tail?.reasoning?.length, tail?.tools, tail?.isRunning)
    var observedTail by remember { mutableStateOf<List<Any?>?>(null) }
    LaunchedEffect(tailMarker) {
        if (observedTail != null && tail != null && !latestPinned) newContent = true
        if (tail != null) observedTail = tailMarker
    }
    // Measured layout, not token activity, triggers a single correction when the tail is clipped.
    LaunchedEffect(list) {
        snapshotFlow { latestPinned && latestCount > 0 && list.canScrollForward && !list.isScrollInProgress }
            .collect { follow ->
                if (follow && latestPinned) list.scrollToItem((list.layoutInfo.totalItemsCount - 1).coerceAtLeast(0))
            }
    }
    Box(modifier.fillMaxSize()) {
        if (state.loading && state.turns.isEmpty()) {
            CircularProgressIndicator(Modifier.align(Alignment.Center).size(25.dp), strokeWidth = 2.dp)
        } else if (state.turns.isEmpty()) {
            BoxWithConstraints(
                Modifier.fillMaxSize().padding(LocalChromeBoundsPadding.current)
                    .padding(horizontal = 27.dp, vertical = 16.dp),
                contentAlignment = Alignment.TopCenter,
            ) {
                val viewportHeight = maxHeight
                EmptyConversation(
                    onPrompt = onPrompt,
                    compact = viewportHeight < 320.dp,
                    modifier = Modifier.widthIn(max = 640.dp).fillMaxWidth().verticalScroll(rememberScrollState())
                        .heightIn(min = viewportHeight),
                )
            }
        } else {
            // Reading measure: ~720dp column, centred in landscape/tablet; phones keep 16dp gutters.
            val gutter = with(androidx.compose.ui.platform.LocalDensity.current) { ((androidx.compose.ui.platform.LocalWindowInfo.current.containerSize.width.toDp() - 720.dp) / 2).coerceAtLeast(16.dp) }
            LazyColumn(
                state = list, contentPadding = PaddingValues(start = gutter, end = gutter, top = LocalChromePadding.current.calculateTopPadding(), bottom = LocalChromePadding.current.calculateBottomPadding()),
                verticalArrangement = Arrangement.spacedBy(20.dp),
                modifier = Modifier.fillMaxSize().nestedScroll(connection).testTag("transcript"),
            ) {
                items(state.turns, key = { it.id }, contentType = { "turn" }) { turn ->
                    TurnView(turn, onInspect, onOpenTrace = onOpenTrace, messageNodes = messageNodes)
                }
                if (state.currentApprovals.isNotEmpty() && onDecideApproval != null) {
                    items(state.currentApprovals, key = { "inline-ap:${it.id}" }, contentType = { "approval" }) { req ->
                        InlineApprovalCard(
                            request = req,
                            busy = req.id in state.decidingApprovals,
                            error = state.approvalErrors[req.id],
                            onDecide = { approved -> onDecideApproval(req.id, approved) },
                            onExpandQueue = if (state.approvals.size > 1) onOpenApprovalQueue else null,
                        )
                    }
                }
                val workspaceId = state.workspace?.id
                val sessionId = state.sessionId
                if (workspaceId != null && sessionId != null) item(key = "generated-images") {
                    com.cordiact.app.image.GeneratedImagesView(workspaceId, sessionId, state.turns, state.running)
                }
                item(key = "transcript-end") { Spacer(Modifier.height(1.dp)) }
            }
        }
        if (state.turns.size >= 12) {
            TurnDirectory(state.turns, Modifier.align(Alignment.TopEnd).padding(top = LocalChromeBoundsPadding.current.calculateTopPadding(), end = 12.dp)) { turnId ->
                val index = state.turns.indexOfFirst { it.id == turnId }
                if (index >= 0) {
                    pinned = false
                    newContent = false
                    scope.launch { list.scrollToItem(index) }
                }
            }
        }
        if (!pinned && state.turns.isNotEmpty()) {
            FrostedSurface(Modifier.align(Alignment.BottomEnd).padding(end = 16.dp,
                bottom = maxOf(8.dp, LocalChromePadding.current.calculateBottomPadding() - 12.dp)), radius = 20.dp) {
                TextButton(
                    onClick = { pinPolicy.pin(); pinned = true; newContent = false; scope.launch { list.scrollToItem((list.layoutInfo.totalItemsCount - 1).coerceAtLeast(0)) } },
                    modifier = Modifier.testTag("jump-to-latest"),
                ) { AppGlyph(GlyphName.ArrowDown, modifier = Modifier.size(15.dp)); Spacer(Modifier.width(6.dp)); Text(if (!list.canScrollForward) "跟随最新" else if (newContent) "有新内容 · 回到最新" else "回到最新") }
            }
        }
    }
}

@Composable
private fun TurnDirectory(turns: List<TranscriptTurn>, modifier: Modifier, onSelect: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box(modifier) {
        FrostedSurface(radius = 20.dp) { TextButton(onClick = { open = true }) { Text("回合目录") } }
        if (open) androidx.compose.ui.window.Dialog(onDismissRequest = { open = false }) {
            Surface(shape = MaterialTheme.shapes.large) {
                Column(Modifier.padding(16.dp)) {
                    Text("回合目录", Modifier.semantics { heading() }, style = MaterialTheme.typography.titleMedium)
                    LazyColumn(Modifier.heightIn(max = 420.dp)) {
                        items(turns, key = { it.id }) { turn ->
                            TextButton(onClick = { open = false; onSelect(turn.id) }, modifier = Modifier.fillParentMaxWidth()) {
                                Text(turn.prompt.ifBlank { "无用户文本的回合" }, Modifier.fillMaxWidth(), maxLines = 2, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                    TextButton(onClick = { open = false }, modifier = Modifier.align(Alignment.End)) { Text("关闭") }
                }
            }
        }
    }
}

@Composable
private fun EmptyConversation(onPrompt: (String) -> Unit, compact: Boolean, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(if (compact) 8.dp else 13.dp, Alignment.CenterVertically)) {
        if (!compact) {
            Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = RoundedCornerShape(17.dp), modifier = Modifier.size(48.dp)) {
                Box(contentAlignment = Alignment.Center) { AppGlyph(GlyphName.Chat, tint = MaterialTheme.colorScheme.primary) }
            }
            Spacer(Modifier.height(8.dp))
        }
        Text("今天想做什么？", style = MaterialTheme.typography.headlineLarge)
        Text("在当前工作区，把一个想法变成下一步。", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
        if (!compact) Spacer(Modifier.height(13.dp))
        Surface(color = MaterialTheme.colorScheme.surface, shape = RoundedCornerShape(16.dp), border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
            Column {
                Suggestion("了解工作区", "检查当前工作区的文件与项目结构，总结正在进行的工作。", GlyphName.Search, onPrompt)
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Suggestion("开始一项任务", "先帮我拆解目标和验证标准，再逐步完成。", GlyphName.Trace, onPrompt)
            }
        }
        Text("建议只会填入输入框，不会自动发送", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun Suggestion(title: String, prompt: String, icon: GlyphName, onPrompt: (String) -> Unit) {
    Row(Modifier.fillMaxWidth().clickable { onPrompt(prompt) }.heightIn(min = 48.dp).padding(horizontal = 14.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            AppGlyph(icon, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
            Text(title, Modifier.weight(1f).padding(start = 12.dp), style = MaterialTheme.typography.bodyMedium)
            AppGlyph(GlyphName.ChevronRight, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(15.dp))
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun TurnView(turn: TranscriptTurn, onInspect: (ToolActivity) -> Unit, onOpenTrace: (() -> Unit)? = null,
    messageNodes: (ExtensionPackageMessageEvent) -> List<MessageNode> = { emptyList() }) {
    var menu by remember { mutableStateOf(false) }
    var contextSourcesOpen by remember { mutableStateOf(false) }
    val clipboard = LocalClipboardManager.current
    Column(verticalArrangement = Arrangement.spacedBy(13.dp)) {
        if (turn.attachments.isNotEmpty()) com.cordiact.app.experience.composer.AttachmentTray(turn.attachments.map { attachment ->
            com.cordiact.app.experience.composer.AttachmentChip(attachment.descriptor, attachment.descriptor.state,
                compatibility = if (attachment.interpretation == com.cordiact.core.attachment.Interpretation.LOCAL_ONLY)
                    com.cordiact.core.composer.AttachmentCompatibility.LocalOnly else com.cordiact.core.composer.AttachmentCompatibility.Sendable(attachment.interpretation),
                renditionId = attachment.renditionId)
        })
        if (turn.prompt.isNotEmpty()) {
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
                Surface(
                    shape = RoundedCornerShape(20.dp, 20.dp, 6.dp, 20.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                    modifier = Modifier.fillMaxWidth(.85f).widthIn(max = 520.dp).combinedClickable(onClick = {}, onLongClick = { menu = true }),
                ) {
                    SelectionContainer { Text(turn.prompt, Modifier.padding(horizontal = 16.dp, vertical = 12.dp), style = MaterialTheme.typography.bodyLarge) }
                }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text("复制消息") }, onClick = { clipboard.setText(AnnotatedString(turn.prompt)); menu = false }, leadingIcon = { AppGlyph(GlyphName.Copy) })
                }
            }
        }
        if (turn.prompt.isNotEmpty() || turn.attachments.isNotEmpty()) {
            PackageMessageNodes(messageNodes(ExtensionPackageMessageEvent.USER))
        }
        turn.subagentPreset?.let { preset ->
            Text("子 Agent 预设 · ${preset.id} · ${preset.source}", style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (turn.packageContextSources.isNotEmpty()) {
            TextButton(onClick = { contextSourcesOpen = true }) { Text("插件上下文 · ${turn.packageContextSources.size} 项") }
        }
        if (contextSourcesOpen) AlertDialog(
            onDismissRequest = { contextSourcesOpen = false },
            title = { Text("插件上下文来源") },
            text = {
                Column(Modifier.heightIn(max = 300.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    turn.packageContextSources.forEach { Text(it, style = MaterialTheme.typography.bodyMedium) }
                }
            },
            confirmButton = { TextButton(onClick = { contextSourcesOpen = false }) { Text("关闭") } },
        )
        ProcessThread(turn, onInspect)
        if (turn.answer.isNotEmpty()) {
            SafeMarkdown(
                markdown = turn.answer,
                modifier = Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.bodyLarge,
                isTextSelectable = !turn.isRunning,
            )
        } else if (turn.isRunning && turn.tools.isEmpty() && turn.reasoning.isEmpty()) {
            Row(Modifier.padding(vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(13.dp), strokeWidth = 1.6.dp)
                Text("正在建立响应…", Modifier.padding(start = 10.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        turn.error?.let { ErrorStrip(it) }
        if (!turn.isRunning && turn.answer.isNotEmpty()) {
            PackageMessageNodes(messageNodes(ExtensionPackageMessageEvent.ASSISTANT))
        }
        if (!turn.isRunning) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    buildString {
                        append(when (turn.phase) {
                            RunPhase.CANCELLED -> "已停止"
                            RunPhase.FAILED -> "执行中断"
                            RunPhase.TRUNCATED -> "已截断 · 达到模型输出上限"
                            RunPhase.PARTIAL -> "部分完成 · 达到工具执行上限"
                            else -> "已完成"
                        })
                        turn.finishedAt?.let { append(" · "); append(durationLabel(it - turn.startedAt)) }
                        turn.outputTokens?.let { append(" · $it tokens") }
                    }, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f),
                )
                if (onOpenTrace != null && (turn.tools.isNotEmpty() || turn.finishedAt != null)) {
                    IconButton(onClick = onOpenTrace, modifier = Modifier.size(44.dp)) {
                        AppGlyph(GlyphName.Trace, "查看执行轨迹", modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                if (turn.answer.isNotEmpty()) IconButton(onClick = { clipboard.setText(AnnotatedString(turn.answer)) }, modifier = Modifier.size(44.dp)) {
                    AppGlyph(GlyphName.Copy, "复制回复", modifier = Modifier.size(17.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun PackageMessageNodes(nodes: List<MessageNode>) {
    nodes.forEach { node ->
        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(node.source, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            ToolResultCardView(node.card)
        }
    }
}

@Composable
fun ToolRow(step: ToolActivity, large: Boolean = false, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).heightIn(min = if (large) 64.dp else 48.dp).padding(horizontal = 14.dp, vertical = if (large) 14.dp else 11.dp), verticalAlignment = Alignment.CenterVertically) {
        val color = when (step.phase) { ToolPhase.FAILED -> MaterialTheme.colorScheme.error; ToolPhase.RUNNING -> MaterialTheme.colorScheme.primary; else -> MaterialTheme.colorScheme.onSurfaceVariant }
        if (step.phase == ToolPhase.RUNNING) CircularProgressIndicator(Modifier.size(12.dp), color = color, strokeWidth = 1.5.dp)
        else AppGlyph(if (step.phase == ToolPhase.SUCCEEDED) GlyphName.Check else GlyphName.Warning, modifier = Modifier.size(13.dp), tint = color)
        Column(Modifier.weight(1f).padding(horizontal = 11.dp)) {
            Text(step.name, fontFamily = FontFamily.Monospace, fontSize = if (large) 13.sp else 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            step.summary?.takeIf { it.isNotBlank() }?.let { Text(it.lineSequence().lastOrNull { line -> line.isNotBlank() }.orEmpty(), style = MaterialTheme.typography.labelSmall, maxLines = 1, color = MaterialTheme.colorScheme.onSurfaceVariant, overflow = TextOverflow.Ellipsis) }
        }
        Text(step.durationMillis?.let(::durationLabel) ?: "…", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
fun ErrorStrip(message: String, onDismiss: (() -> Unit)? = null, onSettings: (() -> Unit)? = null) {
    Surface(color = MaterialTheme.colorScheme.errorContainer, shape = RoundedCornerShape(12.dp)) {
        Row(Modifier.fillMaxWidth().padding(start = 12.dp, top = 8.dp, bottom = 8.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            AppGlyph(GlyphName.Warning, modifier = Modifier.size(17.dp), tint = MaterialTheme.colorScheme.error)
            Text(message, Modifier.weight(1f).padding(horizontal = 10.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onErrorContainer)
            if (onSettings != null) TextButton(onClick = onSettings) { Text("设置") }
            if (onDismiss != null) IconButton(onClick = onDismiss) { AppGlyph(GlyphName.Close, "关闭错误提示", modifier = Modifier.size(17.dp)) }
        }
    }
}
