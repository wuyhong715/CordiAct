package com.cordiact.app.experience

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.cordiact.app.experience.model.*
import com.cordiact.app.render.PreviewInbox
import com.cordiact.app.render.PreviewItem
import com.cordiact.core.action.ApprovalOwner
import com.cordiact.core.action.ApprovalRequest
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeSource

internal data class WorkbenchEntry(val id: String, val title: String, val status: String, val active: Boolean = false)
internal data class WorkbenchActivity(val agents: List<WorkbenchEntry> = emptyList(), val jobs: List<WorkbenchEntry> = emptyList())
private enum class Detail(val label: String) { QUEUE("待处理"), AGENTS("子 Agent"), JOBS("后台任务"), TOOL("步骤详情"), ARTIFACT("产物预览") }
internal const val LANDSCAPE_DOCK_MIN_WIDTH = 950

/** The left board reports context; only the top tab strip changes the main view. */
@Composable
internal fun LandscapeWorkbench(
    state: ExperienceState,
    pendingSessionId: String?,
    viewKey: String,
    pluginPage: Boolean,
    activity: WorkbenchActivity,
    onOverview: () -> Unit,
    onNew: () -> Unit,
    onOpenApproval: (String) -> Unit,
    ownerLabel: (ApprovalOwner?) -> String,
    tabs: @Composable (Modifier) -> Unit,
    body: @Composable ((ToolActivity) -> Unit, (String) -> Unit) -> Unit,
    composer: @Composable (Int) -> Unit,
    sessionPluginDetail: (@Composable () -> Unit)? = null,
    onCloseSessionPluginDetail: () -> Unit = {},
    tabPublication: Any? = null,
    resultCardFor: (ToolActivity) -> com.cordiact.interaction.ToolResultCard? = { null },
    docked: (@Composable (Modifier, (ToolActivity) -> Unit, (String) -> Unit) -> Unit)? = null,
) {
    val colors = MaterialTheme.colorScheme
    val backdrop = remember { HazeState() }
    val bodyState = rememberSaveableStateHolder()
    val artifacts = state.sessionId?.let(PreviewInbox::forSession).orEmpty()
    val tools = remember(state.turns) { state.turns.flatMap { it.tools } }
    val failures = remember(tools) { tools.filter { it.phase == ToolPhase.FAILED } }
    var detail by remember(state.workspace?.id, state.sessionId, viewKey, tabPublication) { mutableStateOf<Detail?>(null) }
    var tool by remember(state.workspace?.id, state.sessionId, viewKey, tabPublication) { mutableStateOf<ToolActivity?>(null) }
    var artifactId by remember(state.workspace?.id, state.sessionId, viewKey, tabPublication) { mutableStateOf<String?>(null) }
    val sessionPluginOpen = sessionPluginDetail != null
    val visibleDetail = detail.takeUnless { sessionPluginOpen }
    val hasInspector = sessionPluginOpen || visibleDetail != null
    LaunchedEffect(sessionPluginOpen) { if (sessionPluginOpen) detail = null }
    // Preserve the route (including modal state) when window resizing moves it between slots.
    val currentSessionPluginDetail = rememberUpdatedState(sessionPluginDetail)
    val pluginDetailContent = remember { movableContentOf { -> currentSessionPluginDetail.value?.invoke() } }
    // Existing requests do not reopen a dismissed panel; a newly arrived one for this
    // conversation may open it, without replacing a detail the user is reading.
    val approvalIds = state.currentApprovals.map { it.id }
    var seenApprovals by remember(state.sessionId) { mutableStateOf(approvalIds.toSet()) }
    LaunchedEffect(approvalIds, pendingSessionId, sessionPluginOpen) {
        // Leave approvals that arrive over the inventory unread until its slot becomes free.
        if (!sessionPluginOpen) {
            if (pendingSessionId == null && approvalIds.any { it !in seenApprovals } && detail == null) detail = Detail.QUEUE
            seenApprovals = approvalIds.toSet()
        }
    }
    val closeDetail = {
        if (sessionPluginOpen) onCloseSessionPluginDetail() else detail = null
    }
    fun openInspector(target: Detail?) {
        detail = target
        if (sessionPluginOpen) onCloseSessionPluginDetail()
    }
    fun toggleInspector(target: Detail) = openInspector(if (visibleDetail == target) null else target)
    val inspectTool: (ToolActivity) -> Unit = { tool = it; openInspector(Detail.TOOL) }
    val inspectArtifact: (String) -> Unit = { id ->
        if (state.sessionId?.let(PreviewInbox::forSession)?.any { it.id == id } == true) {
            PreviewInbox.markSeen(id)
            artifactId = id
            openInspector(Detail.ARTIFACT)
        }
    }
    val inspector: @Composable (Modifier) -> Unit = { modifier ->
        if (sessionPluginOpen) {
            Box(modifier.testTag("landscape-session-plugin-detail"), propagateMinConstraints = true) { pluginDetailContent() }
        } else if (visibleDetail != null) {
            WorkbenchDetail(visibleDetail, state.approvals, failures,
                tools.firstOrNull { it.id == tool?.id } ?: tool, artifacts.firstOrNull { it.id == artifactId },
                activity, ownerLabel, onOpenApproval, closeDetail, inspectTool, modifier, resultCardFor)
        }
    }
    BackHandler(hasInspector, onBack = closeDetail)
    val inventoryApproval = if (sessionPluginOpen) {
        state.currentApprovals.firstOrNull() ?: state.approvals.firstOrNull()
    } else null
    val subtitle = state.workspace?.name ?: "默认工作区"
    val title = state.sessions.firstOrNull { it.id == state.sessionId }?.title?.ifBlank { null } ?: "新对话"

    Column(Modifier.fillMaxSize().background(colors.background)
        .windowInsetsPadding(WindowInsets.safeDrawing.union(WindowInsets.ime)).testTag("landscape-workbench")) {
        Row(Modifier.fillMaxWidth().heightIn(min = 50.dp).padding(horizontal = 8.dp).testTag("landscape-top"),
            verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onOverview) { AppGlyph(GlyphName.Tabs, "标签总览") }
            Column(Modifier.weight(1f).padding(start = 4.dp, end = 6.dp)) {
                Text(title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(subtitle, style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (inventoryApproval != null) {
                    IconButton(onClick = { onOpenApproval(inventoryApproval.id) }, modifier = Modifier.testTag("landscape-plugin-approval")) {
                        AppGlyph(GlyphName.Shield, "待审批 ${state.approvals.size} 项")
                    }
                }
                StatusKey(GlyphName.Role, "子 Agent", activity.agents.count { it.active }, visibleDetail == Detail.AGENTS) {
                    toggleInspector(Detail.AGENTS)
                }
                StatusKey(GlyphName.Bolt, "后台任务", activity.jobs.count { it.active }, visibleDetail == Detail.JOBS) {
                    toggleInspector(Detail.JOBS)
                }
                IconButton(onClick = onNew, enabled = !state.loading && pendingSessionId == null) {
                    AppGlyph(GlyphName.Plus, "新对话")
                }
            }
        }
        tabs(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 2.dp))
        BoxWithConstraints(Modifier.fillMaxWidth().weight(1f)) {
            val boardWidth = (maxWidth * 0.245f).coerceIn(134.dp, 244.dp)
            val dockInspector = maxWidth >= LANDSCAPE_DOCK_MIN_WIDTH.dp
            val editorLines = if (maxHeight < 305.dp) 1 else 3
            val shortBody = maxHeight < 275.dp
            if (pluginPage) {
                Box(Modifier.fillMaxSize()) {
                    val dockPlugin = dockInspector && sessionPluginOpen
                    Row(Modifier.fillMaxSize()) {
                        Box(Modifier.weight(1f).fillMaxHeight().hazeSource(backdrop)) {
                            bodyState.SaveableStateProvider("${state.sessionId}:$viewKey") {
                                body(inspectTool, inspectArtifact)
                            }
                        }
                        if (dockPlugin) inspector(Modifier.width(252.dp).fillMaxHeight().padding(end = 12.dp, top = 5.dp))
                    }
                    if (!dockPlugin && hasInspector) inspector(
                        Modifier.align(Alignment.CenterEnd).widthIn(max = 320.dp).fillMaxWidth(.86f).fillMaxHeight(),
                    )
                }
            } else {
                Row(Modifier.fillMaxSize()) {
                    WorkbenchBoard(state, tools, activity, subtitle, Modifier.width(boardWidth).fillMaxHeight(),
                        onAgents = { toggleInspector(Detail.AGENTS) },
                        onJobs = { toggleInspector(Detail.JOBS) },
                        onQueue = { toggleInspector(Detail.QUEUE) })
                    Column(Modifier.weight(1f).fillMaxHeight()) {
                        Box(Modifier.weight(1f).fillMaxWidth()) {
                            Row(Modifier.fillMaxSize()) {
                                Box(Modifier.weight(1f).fillMaxHeight()) {
                                    Box(Modifier.fillMaxSize().hazeSource(backdrop).padding(start = 8.dp, end = 10.dp)) {
                                        bodyState.SaveableStateProvider("${state.sessionId}:$viewKey") {
                                            body(inspectTool, inspectArtifact)
                                        }
                                    }
                                    ViewportFade(backdrop, top = true, Modifier.align(Alignment.TopCenter).fillMaxWidth().height(9.dp))
                                    ViewportFade(backdrop, top = false, Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(13.dp))
                                }
                                if (dockInspector && hasInspector) inspector(
                                    Modifier.width(252.dp).fillMaxHeight().padding(end = 12.dp, top = 5.dp),
                                )
                                if (!hasInspector) docked?.invoke(
                                    Modifier.width(252.dp).fillMaxHeight().padding(end = 12.dp, top = 5.dp),
                                    inspectTool,
                                    inspectArtifact,
                                )
                            }
                            if (!dockInspector && hasInspector) inspector(
                                Modifier.align(Alignment.CenterEnd).widthIn(max = 320.dp).fillMaxWidth(if (shortBody) 1f else .84f)
                                    .fillMaxHeight().padding(top = 2.dp, end = 10.dp),
                            )
                        }
                        CompositionLocalProvider(LocalBackdrop provides backdrop) {
                            Box(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 5.dp), contentAlignment = Alignment.Center) {
                                Box(Modifier.widthIn(max = 900.dp).fillMaxWidth()) { composer(editorLines) }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun StatusKey(glyph: GlyphName, label: String, count: Int, selected: Boolean, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val chip = RoundedCornerShape(24.dp)
    Row(Modifier.heightIn(min = 48.dp).clip(chip).clickable(role = Role.Button, onClick = onClick)
        .padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        val tint = if (count > 0 || selected) colors.primary else colors.onSurfaceVariant
        AppGlyph(glyph, modifier = Modifier.size(16.dp), tint = tint)
        Text(label, style = MaterialTheme.typography.labelSmall, color = tint, maxLines = 1)
        if (count > 0) Text("$count", style = MaterialTheme.typography.labelSmall, color = tint)
    }
}

@Composable
private fun WorkbenchBoard(
    state: ExperienceState,
    tools: List<ToolActivity>,
    activity: WorkbenchActivity,
    workspace: String,
    modifier: Modifier,
    onAgents: () -> Unit,
    onJobs: () -> Unit,
    onQueue: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val finished = tools.count { it.phase != ToolPhase.RUNNING }
    val failed = tools.count { it.phase == ToolPhase.FAILED }
    val recent = state.turns.takeLast(3).asReversed()
    val approvals = state.approvals.size
    LazyColumn(modifier.testTag("landscape-board"), contentPadding = PaddingValues(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 12.dp),
        verticalArrangement = Arrangement.spacedBy(15.dp)) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("工作看板", style = MaterialTheme.typography.titleSmall)
                BoardNote(workspace)
            }
        }
        item {
            BoardGroup("最近轮次") {
                if (recent.isEmpty()) BoardNote("还没有对话轮次")
                recent.forEachIndexed { index, turn ->
                    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                        .background(if (index == 0) colors.primaryContainer.copy(alpha = .37f) else colors.background)
                        .padding(horizontal = 8.dp, vertical = 5.dp), verticalAlignment = Alignment.Top,
                        horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                        Box(Modifier.padding(top = 6.dp).size(6.dp).background(
                            if (index == 0) colors.primary else colors.outlineVariant, RoundedCornerShape(50)))
                        Column {
                            Text(turn.prompt.ifBlank { "第 ${state.turns.size - index} 轮" }, style = MaterialTheme.typography.bodySmall,
                                maxLines = 2, overflow = TextOverflow.Ellipsis)
                            BoardNote(if (turn.isRunning) "进行中" else "第 ${state.turns.size - index} 轮")
                        }
                    }
                }
            }
        }
        item {
            BoardGroup("执行概览") {
                BoardNote(if (tools.isEmpty()) "暂无工具活动" else "$finished / ${tools.size} 步已结束" + if (failed > 0) " · $failed 失败" else "")
                if (tools.isNotEmpty()) LinearProgressIndicator(progress = { finished.toFloat() / tools.size },
                    modifier = Modifier.fillMaxWidth().height(3.dp).clip(RoundedCornerShape(50)),
                    color = colors.primary, trackColor = colors.surfaceContainerHighest)
            }
        }
        item {
            BoardGroup("协作状态") {
                DetailRow("子 Agent", "${activity.agents.count { it.active }} 运行中", onAgents)
                DetailRow("后台任务", "${activity.jobs.count { it.active }} 进行中", onJobs)
                if (approvals > 0 || failed > 0) TextButton(onClick = onQueue,
                    contentPadding = PaddingValues(horizontal = 4.dp), modifier = Modifier.testTag("landscape-queue-toggle")) {
                    AppGlyph(GlyphName.Shield, modifier = Modifier.size(16.dp)); Spacer(Modifier.width(5.dp))
                    Text("待处理 ${approvals + failed}", style = MaterialTheme.typography.labelMedium)
                }
            }
        }
    }
}

@Composable private fun BoardGroup(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Text(title, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        content()
    }
}
@Composable private fun BoardNote(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 2, overflow = TextOverflow.Ellipsis)
}

@Composable
internal fun ArtifactBoard(artifacts: List<PreviewItem>, open: (PreviewItem) -> Unit) {
    LazyColumn(Modifier.fillMaxSize().testTag("artifact-preview"), contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(9.dp)) {
        item { Text("产物预览", style = MaterialTheme.typography.titleLarge) }
        if (artifacts.isEmpty()) item { BoardNote("此会话还没有可预览的产物") }
        items(artifacts, key = { it.id }) { item ->
            Surface(onClick = { open(item) }, color = MaterialTheme.colorScheme.surfaceContainerLow,
                shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth().testTag("artifact-${item.id}")) {
                Row(Modifier.padding(13.dp), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                    AppGlyph(GlyphName.Preview)
                    Text(item.title, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

@Composable
private fun WorkbenchDetail(detail: Detail, approvals: List<ApprovalRequest>, failures: List<ToolActivity>,
    tool: ToolActivity?, artifact: PreviewItem?, activity: WorkbenchActivity, ownerLabel: (ApprovalOwner?) -> String,
    onApproval: (String) -> Unit, onClose: () -> Unit, onTool: (ToolActivity) -> Unit, modifier: Modifier,
    resultCardFor: (ToolActivity) -> com.cordiact.interaction.ToolResultCard?,
) {
    val shape = RoundedCornerShape(19.dp)
    Surface(modifier.testTag("landscape-inspector"), color = MaterialTheme.colorScheme.surfaceContainerLow, shape = shape) {
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth().padding(start = 14.dp, end = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(detail.label, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                IconButton(onClick = onClose) { AppGlyph(GlyphName.Close, "关闭检查器") }
            }
            if (detail == Detail.ARTIFACT && artifact != null) {
                Box(Modifier.weight(1f).fillMaxWidth().padding(start = 6.dp, end = 6.dp, bottom = 6.dp)) { ArtifactStage(artifact) }
            } else LazyColumn(Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(start = 13.dp, end = 13.dp, bottom = 12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)) {
                when (detail) {
                    Detail.QUEUE -> {
                        if (approvals.isEmpty() && failures.isEmpty()) item { BoardNote("没有待处理事项") }
                        if (approvals.isNotEmpty()) item { BoardNote("待审批 · ${approvals.size}") }
                        items(approvals, key = { "approval:${it.id}" }) { req ->
                            val risk = if (req.riskLevel >= ApprovalRequest.RiskLevel.HIGH) "高风险 · " else ""
                            DetailRow(req.toolName, risk + ownerLabel(req.owner)) { onApproval(req.id) }
                        }
                        if (failures.isNotEmpty()) item { BoardNote("当前会话失败步骤 · ${failures.size}") }
                        items(failures, key = { "tool:${it.id}" }) { step -> DetailRow(step.name, step.forToolInspector().summary) { onTool(step) } }
                    }
                    Detail.TOOL -> tool?.let { step ->
                        val preview = step.forToolInspector()
                        item { DetailRow(step.name, step.statusLabel()) }
                        val card = resultCardFor(preview)
                        if (card != null) item { ToolResultCardView(card) }
                        if (card == null || preview.provenance?.packageSha256 != null) {
                            item { Text(preview.summary?.takeIf(String::isNotBlank) ?: "工具尚未返回可显示摘要",
                                style = MaterialTheme.typography.bodySmall) }
                        }
                        preview.arguments.takeIf(String::isNotBlank)?.let { item { Text(it, style = MaterialTheme.typography.bodySmall) } }
                    } ?: Unit
                    Detail.AGENTS, Detail.JOBS -> {
                        val entries = if (detail == Detail.AGENTS) activity.agents else activity.jobs
                        if (entries.isEmpty()) item { BoardNote(if (detail == Detail.AGENTS) "当前会话没有子 Agent" else "当前工作区没有后台任务记录") }
                        items(entries, key = { it.id }) { entry -> DetailRow(entry.title, entry.status) }
                    }
                    Detail.ARTIFACT -> item { BoardNote("产物已不可用") }
                }
            }
        }
    }
}

@Composable private fun DetailRow(title: String, subtitle: String?, onClick: (() -> Unit)? = null) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(11.dp)).then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
        .padding(horizontal = 4.dp, vertical = 5.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(title, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
        subtitle?.let { BoardNote(it) }
    }
}

private fun ToolActivity.statusLabel() = when (phase) {
    ToolPhase.SUCCEEDED -> "已完成"
    ToolPhase.FAILED -> "失败"
    ToolPhase.RUNNING -> "运行中"
    ToolPhase.INTERRUPTED -> "已中断"
}
