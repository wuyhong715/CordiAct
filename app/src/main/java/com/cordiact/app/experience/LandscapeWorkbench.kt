package com.cordiact.app.experience

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.cordiact.app.experience.model.*
import com.cordiact.app.render.PreviewInbox
import com.cordiact.app.render.PreviewItem
import com.cordiact.core.action.ApprovalOwner
import com.cordiact.core.action.ApprovalRequest

private enum class LeftTool(val title: String) { ROUNDS("轮次"), TRACE("轨迹"), ARTIFACTS("产物") }

/** Body and editor occupy distinct weighted regions; never measure chrome and compensate later. */
@Composable
internal fun LandscapeWorkbench(
    state: ExperienceState,
    pendingSessionId: String?,
    onOverview: () -> Unit,
    onNew: () -> Unit,
    onOpenApproval: (String) -> Unit,
    ownerLabel: (ApprovalOwner?) -> String,
    tabs: @Composable (Modifier) -> Unit,
    composer: @Composable (Int) -> Unit,
) {
    var leftMode by rememberSaveable { mutableStateOf(LeftTool.ROUNDS) }
    var queue by rememberSaveable { mutableStateOf(false) }
    val sessionState = rememberSaveableStateHolder()
    val colors = MaterialTheme.colorScheme
    var toggleQueue by remember { mutableIntStateOf(0) }
    Column(Modifier.fillMaxSize().background(colors.background)
        .windowInsetsPadding(WindowInsets.safeDrawing.union(WindowInsets.ime)).testTag("landscape-workbench")) {
        Row(Modifier.fillMaxWidth().height(48.dp).background(colors.surfaceContainerLowest).testTag("landscape-top"),
            verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onOverview) { AppGlyph(GlyphName.Tabs, "标签总览") }
            tabs(Modifier.weight(1f))
            IconButton(onClick = onNew, enabled = !state.loading && pendingSessionId == null) { AppGlyph(GlyphName.Plus, "新对话") }
            TextButton(onClick = { toggleQueue++ }, modifier = Modifier.testTag("landscape-queue-toggle")) {
                AppGlyph(GlyphName.Shield, modifier = Modifier.size(17.dp))
                Spacer(Modifier.width(4.dp))
                Text(if (state.approvals.isEmpty()) "待处理" else "待处理 ${state.approvals.size}", maxLines = 1)
            }
        }
        HorizontalDivider()
        sessionState.SaveableStateProvider(state.sessionId ?: "initial") {
            var pickedTurnId by rememberSaveable { mutableStateOf<String?>(null) }
            val turn = state.turns.firstOrNull { it.id == pickedTurnId } ?: state.turns.lastOrNull()
            val index = state.turns.indexOfFirst { it.id == turn?.id }
            val readerState = rememberSaveableStateHolder()
            var detailTool by remember { mutableStateOf<ToolActivity?>(null) }
            var detailArtifact by rememberSaveable { mutableStateOf<String?>(null) }
            val artifacts = PreviewInbox.forSession(state.sessionId)
            val artifact = artifacts.firstOrNull { it.id == detailArtifact }
            val tool = detailTool?.let { snapshot -> state.turns.asSequence().flatMap { it.tools.asSequence() }
                .firstOrNull { it.id == snapshot.id } ?: snapshot }
            fun closeRight() { detailTool = null; detailArtifact = null; queue = false }
            fun showTool(step: ToolActivity) { detailArtifact = null; queue = false; detailTool = step }
            fun showArtifact(item: PreviewItem) { detailTool = null; queue = false; detailArtifact = item.id; PreviewInbox.markSeen(item.id) }
            val right = queue || tool != null || artifact != null
            BackHandler(right) { closeRight() }
            var handledQueueToggle by remember { mutableIntStateOf(toggleQueue) }
            LaunchedEffect(toggleQueue) {
                if (toggleQueue != handledQueueToggle) {
                    handledQueueToggle = toggleQueue
                    val open = !queue
                    closeRight()
                    queue = open
                }
            }
            val keyboard = WindowInsets.ime.getBottom(LocalDensity.current) > 0
            BoxWithConstraints(Modifier.weight(1f).fillMaxWidth().clipToBounds()) {
                val leftWidth = if (maxWidth >= 760.dp && !keyboard) 184.dp else 148.dp
                val rightWidth = 244.dp
                val sideBySide = right && maxWidth - leftWidth - rightWidth >= 320.dp
                Row(Modifier.fillMaxSize()) {
                    Column(Modifier.width(leftWidth).fillMaxHeight().background(colors.surfaceContainerLow)
                        .clipToBounds().testTag("landscape-left")) {
                        Row(Modifier.fillMaxWidth().height(48.dp), verticalAlignment = Alignment.CenterVertically) {
                            LeftTool.entries.forEach { item ->
                                val active = leftMode == item
                                Box(Modifier.weight(1f).fillMaxHeight()
                                    .background(if (active) colors.primaryContainer else colors.surfaceContainerLow)
                                    .clickable { leftMode = item }.semantics { selected = active }.testTag("landscape-left-${item.name.lowercase()}"),
                                    contentAlignment = Alignment.Center) {
                                    Text(item.title, style = MaterialTheme.typography.labelMedium, maxLines = 1,
                                        color = if (active) colors.onPrimaryContainer else colors.onSurfaceVariant)
                                }
                            }
                        }
                        HorizontalDivider()
                        key(leftMode) {
                            LazyColumn(Modifier.fillMaxWidth().weight(1f), contentPadding = PaddingValues(4.dp)) {
                                when (leftMode) {
                                    LeftTool.ROUNDS -> {
                                        if (state.turns.isEmpty()) item { PaneHint("暂无轮次") }
                                        items(state.turns.size, key = { state.turns[it].id }) { i ->
                                            val item = state.turns[i]
                                            PaneRow("${i + 1} · ${item.prompt.ifBlank { "无文本指令" }}",
                                                if (item.id == turn?.id) "当前轮" else if (item.isRunning) "运行中" else null,
                                                item.id == turn?.id) {
                                                pickedTurnId = item.id; detailTool = null; detailArtifact = null
                                            }
                                        }
                                    }
                                    LeftTool.TRACE -> {
                                        val steps = turn?.tools.orEmpty()
                                        if (steps.isEmpty()) item { PaneHint("本轮暂无执行轨迹") }
                                        items(steps, key = { it.id }) { step -> PaneRow(step.name, step.statusLabel()) { showTool(step) } }
                                    }
                                    LeftTool.ARTIFACTS -> {
                                        // PreviewInbox records session ownership, not turn ownership.
                                        item { PaneHint("本会话产物") }
                                        if (artifacts.isEmpty()) item { PaneHint("暂无产物") }
                                        items(artifacts, key = { it.id }) { item -> PaneRow(item.title) { showArtifact(item) } }
                                    }
                                }
                            }
                        }
                    }
                    VerticalDivider()
                    Column(Modifier.weight(1f).fillMaxHeight().clipToBounds()) {
                        Box(Modifier.weight(1f).fillMaxWidth().clipToBounds()) {
                            // Compose the reader at its real constraints, never draw it behind the replacement pane.
                            if (right && !sideBySide) {
                                WorkbenchRight(state, queue, tool, artifact, ownerLabel, onOpenApproval, ::showTool, ::closeRight,
                                    Modifier.fillMaxSize())
                            } else {
                                Column(Modifier.fillMaxSize()) {
                                    Row(Modifier.fillMaxWidth().height(34.dp).padding(horizontal = 12.dp),
                                        verticalAlignment = Alignment.CenterVertically) {
                                        Text(if (index >= 0) "第 ${index + 1} 轮" else "暂无轮次", Modifier.weight(1f),
                                            style = MaterialTheme.typography.labelLarge)
                                        val status = when {
                                            pendingSessionId != null || state.loading -> "正在加载会话"
                                            turn?.isRunning == true -> "运行中"
                                            turn?.phase == RunPhase.FAILED -> "失败"
                                            else -> null
                                        }
                                        status?.let { Text(it, style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant) }
                                    }
                                    HorizontalDivider()
                                    if (turn != null) readerState.SaveableStateProvider(turn.id) {
                                        Column(Modifier.weight(1f).fillMaxWidth().testTag("landscape-reader")
                                            .clipToBounds().verticalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 8.dp)) {
                                            TurnView(turn, ::showTool)
                                        }
                                    } else Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                                        PaneHint(if (state.loading) "正在加载会话" else "输入指令，开始新对话")
                                    }
                                }
                            }
                        }
                        state.error?.let { Text(it, Modifier.fillMaxWidth().padding(horizontal = 8.dp), maxLines = 1,
                            overflow = TextOverflow.Ellipsis, color = colors.error, style = MaterialTheme.typography.labelSmall) }
                        Box(Modifier.fillMaxWidth().testTag("landscape-command")) { composer(if (keyboard) 2 else 3) }
                    }
                    if (sideBySide) {
                        VerticalDivider()
                        WorkbenchRight(state, queue, tool, artifact, ownerLabel, onOpenApproval, ::showTool, ::closeRight,
                            Modifier.width(rightWidth).fillMaxHeight())
                    }
                }
            }
        }
    }
}

@Composable
private fun PaneHint(value: String) {
    Text(value, Modifier.padding(8.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun PaneRow(title: String, note: String? = null, selected: Boolean = false, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Surface(onClick = onClick, selected = selected, shape = RoundedCornerShape(8.dp),
        color = if (selected) colors.primaryContainer else colors.surfaceContainerLow, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.heightIn(min = 48.dp).padding(horizontal = 8.dp, vertical = 5.dp), verticalArrangement = Arrangement.Center) {
            Text(title, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            note?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant, maxLines = 1,
                overflow = TextOverflow.Ellipsis) }
        }
    }
}

private fun ToolActivity.statusLabel() = when (phase) {
    ToolPhase.SUCCEEDED -> "已完成"
    ToolPhase.FAILED -> "失败"
    ToolPhase.RUNNING -> "运行中"
    else -> "已中断"
}

@Composable
private fun WorkbenchRight(
    state: ExperienceState, queue: Boolean, tool: ToolActivity?, artifact: PreviewItem?,
    ownerLabel: (ApprovalOwner?) -> String, onApproval: (String) -> Unit, onTool: (ToolActivity) -> Unit,
    onClose: () -> Unit, modifier: Modifier,
) {
    val colors = MaterialTheme.colorScheme
    Column(modifier.background(colors.surfaceContainerLow).clipToBounds().testTag("landscape-right")) {
        Row(Modifier.fillMaxWidth().height(48.dp).padding(start = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(if (queue) "待处理" else tool?.name ?: artifact?.title ?: "详情", Modifier.weight(1f),
                style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            IconButton(onClick = onClose) { AppGlyph(GlyphName.Close, "关闭右板") }
        }
        HorizontalDivider()
        if (queue) {
            val approvals = state.approvals.sortedWith(compareByDescending<ApprovalRequest> { it.riskLevel.ordinal }.thenBy { it.createdAtMillis })
            val failures = state.turns.asReversed().flatMap { turn -> turn.tools.filter { it.phase == ToolPhase.FAILED } }
            LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(5.dp)) {
                if (approvals.isEmpty() && failures.isEmpty()) item { PaneHint("没有待处理事项") }
                if (approvals.isNotEmpty()) item { PaneHint("待审批 · ${approvals.size}") }
                items(approvals, key = { "approval:${it.id}" }) { req ->
                    val risk = if (req.riskLevel >= ApprovalRequest.RiskLevel.HIGH) "高风险 · " else ""
                    PaneRow(req.toolName, risk + ownerLabel(req.owner)) { onApproval(req.id) }
                }
                if (failures.isNotEmpty()) item { PaneHint("当前会话失败步骤 · ${failures.size}") }
                items(failures, key = { "tool:${it.id}" }) { step -> PaneRow(step.name, step.summary) { onTool(step) } }
            }
        } else if (tool != null) {
            Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(10.dp)) {
                Text(tool.statusLabel(), style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant)
                tool.summary?.takeIf(String::isNotBlank)?.let { Text(it, Modifier.padding(top = 6.dp), style = MaterialTheme.typography.bodyMedium) }
                tool.arguments.takeIf(String::isNotBlank)?.let { Text(it, Modifier.padding(top = 8.dp), style = MaterialTheme.typography.bodySmall) }
            }
        } else if (artifact != null) {
            Box(Modifier.weight(1f).fillMaxWidth().clipToBounds()) { ArtifactStage(artifact) }
        }
    }
}
