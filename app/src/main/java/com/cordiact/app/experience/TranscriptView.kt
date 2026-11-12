package com.cordiact.app.experience

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.spring
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
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
import com.cordiact.app.experience.model.*
import kotlinx.coroutines.launch

@Composable
fun TranscriptView(state: ExperienceState, onPrompt: (String) -> Unit, onInspect: (ToolActivity) -> Unit, modifier: Modifier = Modifier) {
    val list = rememberLazyListState()
    val scope = rememberCoroutineScope()
    var pinned by rememberSaveable(state.sessionId) { mutableStateOf(true) }
    var unread by rememberSaveable(state.sessionId) { mutableIntStateOf(0) }
    val pinPolicy = remember(state.sessionId) { PinnedScrollPolicy() }
    val latestPinned by rememberUpdatedState(pinned)
    val latestCount by rememberUpdatedState(state.turns.size)
    val latestRunning by rememberUpdatedState(state.running)
    val connection = remember(pinPolicy) {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (source == NestedScrollSource.UserInput) {
                    // Positive finger motion reveals earlier content in a normal LazyColumn.
                    if (pinned && !pinPolicy.userDrag(available.y)) pinned = false
                }
                return Offset.Zero
            }
        }
    }
    val revision = state.turns.sumOf { it.answer.length + it.reasoning.length + it.tools.sumOf { step -> step.summary?.length ?: 1 } } + state.turns.size
    var lastRevision by remember(state.sessionId) { mutableIntStateOf(revision) }
    LaunchedEffect(revision, state.sessionId) {
        if (latestPinned && state.turns.isNotEmpty()) list.scrollToItem(state.turns.size)
        else if (revision != lastRevision) unread++
        lastRevision = revision
    }
    LaunchedEffect(list, state.sessionId) {
        snapshotFlow {
            val layout = list.layoutInfo
            val footer = layout.visibleItemsInfo.lastOrNull { it.key == "transcript-end" }
            latestPinned && latestRunning && latestCount > 0 && (footer == null || footer.offset + footer.size > layout.viewportEndOffset)
        }.collect { shouldFollow -> if (shouldFollow) list.scrollToItem(latestCount) }
    }
    Box(modifier.fillMaxSize()) {
        if (state.loading && state.turns.isEmpty()) {
            CircularProgressIndicator(Modifier.align(Alignment.Center).size(25.dp), strokeWidth = 2.dp)
        } else if (state.turns.isEmpty()) {
            EmptyConversation(onPrompt, Modifier.align(Alignment.Center).padding(horizontal = 27.dp))
        } else {
            LazyColumn(
                state = list, contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 18.dp, bottom = 32.dp),
                verticalArrangement = Arrangement.spacedBy(28.dp),
                modifier = Modifier.fillMaxSize().nestedScroll(connection).testTag("transcript"),
            ) {
                items(state.turns, key = { it.id }, contentType = { "turn" }) { turn ->
                    TurnView(turn, onInspect)
                }
                item(key = "transcript-end") { Spacer(Modifier.height(1.dp)) }
            }
        }
        if (!pinned) {
            FilledTonalButton(
                onClick = { pinPolicy.pin(); pinned = true; unread = 0; scope.launch { if (state.turns.isNotEmpty()) list.animateScrollToItem(state.turns.size) } },
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 8.dp).testTag("jump-to-latest"),
                elevation = ButtonDefaults.filledTonalButtonElevation(3.dp),
            ) { AppGlyph(GlyphName.ArrowDown, modifier = Modifier.size(15.dp)); Spacer(Modifier.width(8.dp)); Text(if (unread == 0) "回到最新" else "$unread 次新动态") }
        }
    }
}

@Composable
private fun EmptyConversation(onPrompt: (String) -> Unit, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(13.dp)) {
        Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = RoundedCornerShape(17.dp), modifier = Modifier.size(48.dp)) {
            Box(contentAlignment = Alignment.Center) { AppGlyph(GlyphName.Chat, tint = MaterialTheme.colorScheme.primary) }
        }
        Spacer(Modifier.height(8.dp))
        Text("从一个想法开始。", style = MaterialTheme.typography.headlineLarge)
        Text("对话保持清晰，执行过程有迹可循。\n把复杂的工作，交给你的 Agent。", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(13.dp))
        Suggestion("了解工作区", "检查当前工作区的文件与项目结构，总结正在进行的工作。", GlyphName.Search, onPrompt)
        Suggestion("开始一项任务", "先帮我拆解目标和验证标准，再逐步完成。", GlyphName.Trace, onPrompt)
        Text("建议只会填入输入框，不会自动发送", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun Suggestion(title: String, prompt: String, icon: GlyphName, onPrompt: (String) -> Unit) {
    Surface(onClick = { onPrompt(prompt) }, color = MaterialTheme.colorScheme.surface, shape = RoundedCornerShape(16.dp), border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
        Row(Modifier.fillMaxWidth().padding(15.dp), verticalAlignment = Alignment.CenterVertically) {
            AppGlyph(icon, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
            Text(title, Modifier.weight(1f).padding(start = 12.dp), style = MaterialTheme.typography.bodyMedium)
            Text("↗", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TurnView(turn: TranscriptTurn, onInspect: (ToolActivity) -> Unit) {
    var thoughtsExpanded by rememberSaveable(turn.id) { mutableStateOf(false) }
    var toolsExpanded by rememberSaveable(turn.id) { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
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
                    shape = RoundedCornerShape(20.dp, 20.dp, 5.dp, 20.dp),
                    color = MaterialTheme.colorScheme.surfaceContainer,
                    modifier = Modifier.widthIn(max = 520.dp).combinedClickable(onClick = {}, onLongClick = { menu = true }),
                ) {
                    SelectionContainer { Text(turn.prompt, Modifier.padding(horizontal = 16.dp, vertical = 12.dp), style = MaterialTheme.typography.bodyLarge) }
                }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text("复制消息") }, onClick = { clipboard.setText(AnnotatedString(turn.prompt)); menu = false }, leadingIcon = { AppGlyph(GlyphName.Copy) })
                }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 2.dp)) {
            Surface(shape = RoundedCornerShape(7.dp), color = MaterialTheme.colorScheme.primary, modifier = Modifier.size(23.dp)) {
                Box(contentAlignment = Alignment.Center) { Text("c", color = MaterialTheme.colorScheme.onPrimary, fontWeight = FontWeight.Bold, fontSize = 16.sp) }
            }
            Text("CordiAct", Modifier.padding(start = 8.dp), style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.width(8.dp))
            Text(when(turn.phase) { RunPhase.RUNNING -> "进行中"; RunPhase.FAILED -> "未完成"; RunPhase.CANCELLED -> "已停止"; else -> "" }, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelSmall)
        }
        if (turn.reasoning.isNotEmpty()) {
            Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, shape = RoundedCornerShape(13.dp), modifier = Modifier.animateContentSize(spring())) {
                Column {
                    Row(Modifier.fillMaxWidth().clickable { thoughtsExpanded = !thoughtsExpanded }.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(6.dp).background(MaterialTheme.colorScheme.primary, CircleShape))
                        Text(if (turn.isRunning && turn.answer.isEmpty()) "正在思考" else "思考过程", Modifier.weight(1f).padding(start = 9.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(if (thoughtsExpanded) "收起" else "展开", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                    }
                    if (thoughtsExpanded) SelectionContainer { Text(turn.reasoning, Modifier.padding(start = 14.dp, end = 14.dp, bottom = 14.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
            }
        }
        if (turn.tools.isNotEmpty()) {
            Surface(color = MaterialTheme.colorScheme.surface, shape = RoundedCornerShape(15.dp), border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant), modifier = Modifier.animateContentSize(spring())) {
                Column {
                    Row(
                        Modifier.fillMaxWidth().combinedClickable(onClick = { toolsExpanded = !toolsExpanded }, onDoubleClick = { toolsExpanded = !toolsExpanded }).padding(13.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        AppGlyph(GlyphName.Terminal, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(17.dp))
                        val live = turn.tools.count { it.phase == ToolPhase.RUNNING }
                        Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
                            Text(if (live > 0) "正在执行 $live 个步骤" else "完成 ${turn.tools.count { it.phase == ToolPhase.SUCCEEDED }} / ${turn.tools.size} 个步骤", style = MaterialTheme.typography.titleSmall)
                            Text(turn.tools.last().name, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        AppGlyph(if (toolsExpanded) GlyphName.Close else GlyphName.ChevronDown, modifier = Modifier.size(16.dp))
                    }
                    if (toolsExpanded) turn.tools.forEach { step -> ToolRow(step) { onInspect(step) } }
                }
            }
        }
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
        if (!turn.isRunning) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    buildString {
                        append(if (turn.phase == RunPhase.CANCELLED) "已停止" else if (turn.phase == RunPhase.FAILED) "执行中断" else "已完成")
                        turn.finishedAt?.let { append(" · "); append(durationLabel(it - turn.startedAt)) }
                        turn.outputTokens?.let { append(" · $it tokens") }
                    }, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f),
                )
                if (turn.answer.isNotEmpty()) IconButton(onClick = { clipboard.setText(AnnotatedString(turn.answer)) }, modifier = Modifier.size(48.dp)) {
                    AppGlyph(GlyphName.Copy, "复制回复", modifier = Modifier.size(17.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
fun ToolRow(step: ToolActivity, large: Boolean = false, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).heightIn(min = if (large) 64.dp else 48.dp).padding(horizontal = 14.dp, vertical = if (large) 14.dp else 11.dp), verticalAlignment = Alignment.CenterVertically) {
        val color = when (step.phase) { ToolPhase.SUCCEEDED -> Color(0xFF3F866C); ToolPhase.FAILED -> MaterialTheme.colorScheme.error; ToolPhase.RUNNING -> MaterialTheme.colorScheme.primary; else -> MaterialTheme.colorScheme.onSurfaceVariant }
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
