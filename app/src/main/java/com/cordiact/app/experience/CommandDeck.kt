package com.cordiact.app.experience

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.cordiact.app.experience.model.GraphLane
import com.cordiact.app.experience.model.GraphNode
import com.cordiact.app.experience.model.ToolActivity
import com.cordiact.app.experience.model.ToolPhase
import com.cordiact.app.experience.model.chronological
import com.cordiact.core.action.ApprovalOwner
import com.cordiact.core.action.ApprovalRequest

/**
 * Landscape "command deck": global tree (left) · focus (centre) · what needs you (right).
 * The bottom command line stays the shared [ExperienceFrame] composer, so it always targets
 * the session that owns the focused node.
 */
@Composable
internal fun CommandDeck(
    state: ExperienceState,
    lanes: List<GraphLane>,
    focusedKey: String?,
    onFocusKey: (String) -> Unit,
    onFocusSession: (String) -> Unit,
    onInspect: (ToolActivity) -> Unit,
    onDecide: (String, Boolean) -> Unit,
    onOpenApproval: (String) -> Unit,
    ownerLabel: (ApprovalOwner?) -> String,
    modifier: Modifier = Modifier,
) {
    val timeline = remember(lanes) { lanes.chronological() }
    // Default focus: newest node of the current session; follows it while the user has not picked one.
    val focused = timeline.firstOrNull { it.key == focusedKey }
        ?: timeline.lastOrNull { it.sessionId == state.sessionId }
        ?: timeline.lastOrNull()
    // The live session's turns are fresher than the graph snapshot.
    val focusedTurn = focused?.let { node ->
        if (node.sessionId == state.sessionId) state.turns.firstOrNull { it.id == node.turn.id } ?: node.turn else node.turn
    }
    fun focus(node: GraphNode) {
        onFocusKey(node.key)
        if (node.sessionId != state.sessionId) onFocusSession(node.sessionId)
    }

    val chrome = LocalChromePadding.current
    val dir = LocalLayoutDirection.current
    val pane = PaddingValues(
        start = chrome.calculateStartPadding(dir), end = chrome.calculateEndPadding(dir),
        top = chrome.calculateTopPadding(), bottom = chrome.calculateBottomPadding(),
    )
    Row(modifier.fillMaxSize().testTag("command-deck")) {
        TreePane(lanes, timeline, focused?.key, ::focus, Modifier.width(260.dp).fillMaxHeight(), pane)
        PaneDivider()
        Box(Modifier.weight(1f).fillMaxHeight()) {
            if (focused == null || focusedTurn == null) {
                Text("还没有可展示的轮次", Modifier.align(Alignment.Center), color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(pane).padding(horizontal = 16.dp)) {
                val lane = lanes.firstOrNull { it.sessionId == focused.sessionId }
                Text(
                    listOfNotNull(lane?.title, "第 ${focused.index + 1} 轮").joinToString(" · "),
                    style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
                TurnView(focusedTurn, onInspect)
            }
        }
        PaneDivider()
        QueuePane(state, timeline, onDecide, onOpenApproval, ownerLabel, ::focus, onInspect, Modifier.width(280.dp).fillMaxHeight(), pane)
    }
}

@Composable
private fun PaneDivider() {
    Box(Modifier.width(1.dp).fillMaxHeight().background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = .5f)))
}

private val LaneGap: Dp = 14.dp
private val RowHeight: Dp = 44.dp

@Composable
private fun TreePane(
    lanes: List<GraphLane>, timeline: List<GraphNode>, focusedKey: String?,
    onFocus: (GraphNode) -> Unit, modifier: Modifier, pane: PaddingValues,
) {
    val column = remember(lanes) { lanes.withIndex().associate { it.value.sessionId to it.index } }
    // Row span of each lane, so rails are only drawn while that branch exists.
    val span = remember(timeline) {
        timeline.withIndex().groupBy({ it.value.sessionId }, { it.index }).mapValues { (_, rows) -> rows.min()..rows.max() }
    }
    val running = remember(lanes) { lanes.filter { it.running }.map { it.sessionId }.toSet() }
    val parentOf = remember(lanes) { lanes.associate { it.sessionId to it.parentSessionId } }
    val list = rememberLazyListState()
    LaunchedEffect(timeline.size) { if (timeline.isNotEmpty()) list.scrollToItem(timeline.lastIndex) }
    val colors = MaterialTheme.colorScheme
    val laneColors = List(5) { colors.onSurfaceVariant.copy(alpha = .62f) }
    Column(modifier) {
        if (timeline.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(pane), contentAlignment = Alignment.Center) {
                Text("打开的会话会画在这里", color = colors.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
            }
            return@Column
        }
        LazyColumn(Modifier.weight(1f).testTag("deck-tree"), state = list, contentPadding = pane) {
            items(timeline.size, key = { timeline[it].key }) { row ->
                val node = timeline[row]
                val selected = node.key == focusedKey
                Row(
                    Modifier.fillMaxWidth().height(RowHeight).padding(horizontal = 8.dp).clip(RoundedCornerShape(10.dp))
                        .background(if (selected) colors.primary.copy(alpha = .10f) else Color.Transparent)
                        .clickable { onFocus(node) },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    val railWidth = LaneGap * lanes.size.coerceIn(1, 5)
                    Canvas(Modifier.width(railWidth).fillMaxHeight()) {
                        val gap = LaneGap.toPx()
                        fun x(id: String) = gap / 2 + gap * (column[id] ?: 0).coerceAtMost(4)
                        lanes.forEach { lane ->
                            val range = span[lane.sessionId] ?: return@forEach
                            val open = lane.sessionId in running
                            if (row !in range && !(open && row > range.last)) return@forEach
                            val c = laneColors[(column[lane.sessionId] ?: 0) % laneColors.size]
                            val top = if (row == range.first) size.height / 2 else 0f
                            val bottom = if (row == range.last && !open) size.height / 2 else size.height
                            drawLine(c.copy(alpha = .55f), Offset(x(lane.sessionId), top), Offset(x(lane.sessionId), bottom), 2.dp.toPx())
                            // Fork curve from the parent rail into this lane's first commit.
                            val parent = parentOf[lane.sessionId]
                            if (row == range.first && parent != null && parent in column) {
                                val p = Path().apply {
                                    moveTo(x(parent), 0f)
                                    cubicTo(x(parent), size.height / 2, x(lane.sessionId), 0f, x(lane.sessionId), size.height / 2)
                                }
                                drawPath(p, c.copy(alpha = .55f), style = Stroke(2.dp.toPx()))
                            }
                        }
                        val c = laneColors[(column[node.sessionId] ?: 0) % laneColors.size]
                        val center = Offset(x(node.sessionId), size.height / 2)
                        if (node.turn.isRunning) drawCircle(c.copy(alpha = .25f), 8.dp.toPx(), center)
                        drawCircle(if (node.failed > 0) colors.error else c, 4.5.dp.toPx(), center)
                        if (selected) drawCircle(colors.onSurface, 6.5.dp.toPx(), center, style = Stroke(1.5.dp.toPx()))
                    }
                    Spacer(Modifier.width(6.dp))
                    Column(Modifier.weight(1f)) {
                        Text(node.turn.prompt.ifBlank { "（无文本）" }.replace('\n', ' '), maxLines = 1, overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.bodySmall, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal)
                        val meta = buildList {
                            if (node.turn.tools.isNotEmpty()) add("${node.turn.tools.size} 个工具")
                            if (node.failed > 0) add("${node.failed} 失败")
                            if (node.turn.isRunning) add("运行中")
                        }
                        if (meta.isNotEmpty()) Text(meta.joinToString(" · "), style = MaterialTheme.typography.labelSmall,
                            color = if (node.failed > 0) colors.error else colors.onSurfaceVariant, maxLines = 1)
                    }
                }
            }
        }
    }
}

@Composable
private fun QueuePane(
    state: ExperienceState, timeline: List<GraphNode>,
    onDecide: (String, Boolean) -> Unit, onOpenApproval: (String) -> Unit, ownerLabel: (ApprovalOwner?) -> String,
    onFocus: (GraphNode) -> Unit, onInspect: (ToolActivity) -> Unit,
    modifier: Modifier, pane: PaddingValues,
) {
    val colors = MaterialTheme.colorScheme
    val approvals = remember(state.approvals) { state.approvals.sortedWith(compareByDescending<ApprovalRequest> { it.riskLevel.ordinal }.thenBy { it.createdAtMillis }) }
    val failures = remember(timeline) {
        timeline.flatMap { node -> node.turn.tools.filter { it.phase == ToolPhase.FAILED }.map { node to it } }.takeLast(20).reversed()
    }
    LazyColumn(modifier.testTag("deck-queue"), contentPadding = pane, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item { SectionLabel("待审批", approvals.size) }
        if (approvals.isEmpty()) item { EmptyLine("没有等你批准的操作") }
        items(approvals, key = { "a:${it.id}" }) { request ->
            val busy = request.id in state.decidingApprovals
            val high = request.riskLevel >= ApprovalRequest.RiskLevel.HIGH
            QueueCard(Modifier.clickable { onOpenApproval(request.id) }) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(8.dp).clip(CircleShape).background(riskColor(request.riskLevel)))
                    Spacer(Modifier.width(6.dp))
                    Text(request.toolName, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    Text(ownerLabel(request.owner), style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant, maxLines = 1)
                }
                Text(request.summary, style = MaterialTheme.typography.bodySmall, maxLines = 3, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp))
                state.approvalErrors[request.id]?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = colors.error) }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = { onDecide(request.id, false) }, enabled = !busy) { Text("拒绝") }
                    // High-risk requests go through the full dialog (slide to approve), never a single tap.
                    TextButton(onClick = { if (high) onOpenApproval(request.id) else onDecide(request.id, true) }, enabled = !busy) {
                        Text(if (high) "查看并批准" else "批准")
                    }
                }
            }
        }
        item { SectionLabel("失败的步骤", failures.size) }
        if (failures.isEmpty()) item { EmptyLine("没有失败") }
        items(failures, key = { (node, tool) -> "f:${node.key}/${tool.id}" }) { (node, tool) ->
            QueueCard(Modifier.clickable { onFocus(node) }) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    AppGlyph(GlyphName.Warning, tint = colors.error)
                    Spacer(Modifier.width(6.dp))
                    Text(tool.name, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    IconButton(onClick = { onFocus(node); onInspect(tool) }, modifier = Modifier.size(32.dp)) {
                        AppGlyph(GlyphName.ChevronRight, "查看详情")
                    }
                }
                tool.summary?.let { Text(it, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis, color = colors.onSurfaceVariant) }
            }
        }
    }
}

@Composable
private fun SectionLabel(title: String, count: Int) {
    Text(if (count > 0) "$title · $count" else title, Modifier.padding(start = 14.dp, top = 6.dp),
        style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun EmptyLine(text: String) {
    Text(text, Modifier.padding(horizontal = 14.dp), style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = .7f))
}

@Composable
private fun QueueCard(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val shape = RoundedCornerShape(14.dp)
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 10.dp).clip(shape)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = .6f), shape)
            .background(MaterialTheme.colorScheme.surface.copy(alpha = .6f))
            .then(modifier).padding(horizontal = 12.dp, vertical = 10.dp),
    ) { content() }
}

@Composable
private fun riskColor(level: ApprovalRequest.RiskLevel): Color = when (level) {
    ApprovalRequest.RiskLevel.LOW -> MaterialTheme.colorScheme.onSurfaceVariant
    ApprovalRequest.RiskLevel.MEDIUM -> Lumen.Approve
    ApprovalRequest.RiskLevel.HIGH -> MaterialTheme.colorScheme.error
    ApprovalRequest.RiskLevel.CRITICAL -> MaterialTheme.colorScheme.error
}
