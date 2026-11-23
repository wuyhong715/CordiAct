package com.cordiact.app.experience

import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cordiact.app.CordiActApplication
import com.cordiact.app.experience.model.*
import kotlinx.coroutines.delay

private enum class TraceFilter(val label: String) {
    ALL("全部"),
    FAILED("仅失败"),
    TOOLS("含工具"),
    BRANCHES("子代理/任务"),
}

@Composable
fun TrajectoryView(
    state: ExperienceState,
    onInspect: (ToolActivity) -> Unit,
    modifier: Modifier = Modifier,
    onOpenSession: ((String) -> Unit)? = null,
) {
    val context = LocalContext.current
    val app = remember(context) { context.applicationContext as? CordiActApplication }
    val activity = if (app != null) {
        rememberWorkbenchActivity(app, state.workspace?.id, state.sessionId, state.runningSessionIds)
    } else {
        WorkbenchActivity()
    }

    val turns = state.turns
    val measured = remember(turns) { turns.filter { it.finishedAt != null || it.isRunning } }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(state.running) { while (state.running) { now = System.currentTimeMillis(); delay(500) } }

    var zoom by rememberSaveable(state.sessionId) { mutableFloatStateOf(1f) }
    var pan by rememberSaveable(state.sessionId) { mutableFloatStateOf(0f) }
    var selected by rememberSaveable(state.sessionId) { mutableStateOf<String?>(null) }
    var filter by rememberSaveable(state.sessionId) { mutableStateOf(TraceFilter.ALL) }

    val totalTools = remember(turns) { turns.sumOf { it.tools.size } }
    val failedTools = remember(turns) { turns.sumOf { t -> t.tools.count { it.phase == ToolPhase.FAILED } } }
    val totalTokens = remember(turns) {
        turns.sumOf { (it.inputTokens ?: 0L) + (it.outputTokens ?: 0L) }
    }
    val totalDurationMs = remember(measured, now) {
        measured.sumOf { ((it.finishedAt ?: now) - it.startedAt).coerceAtLeast(0L) }
    }

    val visibleTurns = remember(turns, filter) {
        val indexed = turns.mapIndexed { index, turn -> (index + 1) to turn }.asReversed()
        when (filter) {
            TraceFilter.ALL -> indexed
            TraceFilter.FAILED -> indexed.filter { (_, t) -> t.phase == RunPhase.FAILED || t.tools.any { it.phase == ToolPhase.FAILED } }
            TraceFilter.TOOLS -> indexed.filter { (_, t) -> t.tools.isNotEmpty() }
            TraceFilter.BRANCHES -> indexed
        }
    }

    val colors = MaterialTheme.colorScheme
    LazyColumn(
        modifier.fillMaxSize().testTag("trajectory-view"),
        contentPadding = PaddingValues(
            start = 16.dp, end = 16.dp,
            top = LocalChromePadding.current.calculateTopPadding(),
            bottom = LocalChromePadding.current.calculateBottomPadding(),
        ),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        // Header
        item(key = "trace-header") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("执行轨迹", style = MaterialTheme.typography.headlineSmall)
                    Text(
                        "当前会话 · ${turns.size} 轮 · $totalTools 步调用",
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.onSurfaceVariant,
                    )
                }
                if (measured.isNotEmpty()) {
                    TextButton(onClick = { zoom = 1f; pan = 0f; selected = null }) {
                        Text("重置视图", color = colors.onSurfaceVariant)
                    }
                }
            }
        }

        // Layer 1: Restrained 4-cell KPI hairline strip
        if (turns.isNotEmpty()) item(key = "trace-kpi") {
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = colors.surface,
                border = BorderStroke(1.dp, colors.outlineVariant),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
                    KpiCell(
                        label = "总耗时",
                        value = if (measured.isNotEmpty()) durationLabel(totalDurationMs) else "—",
                        modifier = Modifier.weight(1f),
                    )
                    VerticalDivider(thickness = 0.5.dp, color = colors.outlineVariant)
                    KpiCell(
                        label = "调用 / 失败",
                        value = if (failedTools > 0) "$totalTools / $failedTools" else "$totalTools",
                        valueColor = if (failedTools > 0) colors.error else colors.onSurface,
                        modifier = Modifier.weight(1f),
                    )
                    VerticalDivider(thickness = 0.5.dp, color = colors.outlineVariant)
                    KpiCell(
                        label = "子代理 / 任务",
                        value = "${activity.agents.size} / ${activity.jobs.size}",
                        modifier = Modifier.weight(1f),
                    )
                    VerticalDivider(thickness = 0.5.dp, color = colors.outlineVariant)
                    KpiCell(
                        label = "Tokens",
                        value = if (totalTokens > 0) formatCompactTokens(totalTokens) else "—",
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }

        // Filter row
        if (turns.isNotEmpty()) item(key = "trace-filters") {
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                TraceFilter.entries.forEach { item ->
                    val on = filter == item
                    val label = when (item) {
                        TraceFilter.FAILED -> if (failedTools > 0) "${item.label} ($failedTools)" else item.label
                        TraceFilter.BRANCHES -> "${item.label} (${activity.agents.size + activity.jobs.size})"
                        else -> item.label
                    }
                    Surface(
                        onClick = { filter = item },
                        shape = CircleShape,
                        color = if (on) colors.onSurface else colors.surface,
                        border = if (on) null else BorderStroke(1.dp, colors.outlineVariant),
                    ) {
                        Text(
                            label,
                            style = MaterialTheme.typography.labelSmall,
                            color = if (on) colors.surface else colors.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 11.dp, vertical = 6.dp),
                        )
                    }
                }
            }
        }

        // Layer 2: Multi-lane restrained waterfall timeline (only when measured data exists)
        if (measured.isNotEmpty() && filter != TraceFilter.BRANCHES) item(key = "trace-timeline") {
            val start = measured.minOf { it.startedAt }
            val end = maxOf(
                now.takeIf { state.running } ?: measured.maxOf { it.finishedAt ?: it.startedAt },
                start + 1000L,
            )
            val span = (end - start).coerceAtLeast(1000L)
            val turnColor = colors.onSurfaceVariant
            val toolColor = colors.onSurface.copy(alpha = .58f)
            val runningColor = colors.primary
            val failColor = colors.error
            val grid = colors.outlineVariant

            // Flat list of clickable tool hitboxes computed in Canvas coordinates
            val hitTargets = remember { mutableStateListOf<Pair<Pair<Float, Float>, ToolActivity>>() }

            Surface(
                shape = RoundedCornerShape(16.dp),
                color = colors.surface,
                border = BorderStroke(1.dp, colors.outlineVariant),
            ) {
                Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("时间线", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            listOf(1f to "适应", 2f to "2×", 4f to "4×").forEach { (preset, text) ->
                                val active = kotlin.math.abs(zoom - preset) < 0.15f
                                Surface(
                                    onClick = { zoom = preset; if (preset == 1f) pan = 0f },
                                    shape = CircleShape,
                                    color = if (active) colors.surfaceContainerHigh else Color.Transparent,
                                    border = BorderStroke(0.5.dp, colors.outlineVariant),
                                ) {
                                    Text(
                                        text,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = if (active) colors.onSurface else colors.onSurfaceVariant,
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                                    )
                                }
                            }
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.width(46.dp), verticalArrangement = Arrangement.spacedBy(26.dp)) {
                            Text("主会话", style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
                            Text("工具", style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
                        }
                        Canvas(
                            Modifier.weight(1f).height(94.dp)
                                .semantics { contentDescription = "会话与工具调用时间线；支持缩放平移，轻触工具条查看详情。" }
                                .pointerInput(span) {
                                    detectTransformGestures { _, delta, factor, _ ->
                                        zoom = (zoom * factor).coerceIn(1f, 12f)
                                        pan = (pan + delta.x).coerceIn(-size.width * (zoom - 1f), 0f)
                                    }
                                }
                                .pointerInput(span, measured) {
                                    detectTapGestures { offset ->
                                        if (offset.y >= 46.dp.toPx()) {
                                            val hit = hitTargets.lastOrNull { (range, _) -> offset.x in (range.first - 8f)..(range.second + 8f) }
                                            if (hit != null) onInspect(hit.second)
                                        }
                                    }
                                },
                        ) {
                            hitTargets.clear()
                            repeat(5) { i ->
                                val x = size.width * i / 4
                                drawLine(grid, Offset(x, 0f), Offset(x, size.height), 1f)
                            }
                            fun bar(from: Long, to: Long, y: Float, h: Float, color: Color): Pair<Float, Float>? {
                                val left = ((from - start).toFloat() / span * size.width * zoom + pan).coerceIn(0f, size.width)
                                val right = ((to - start).toFloat() / span * size.width * zoom + pan).coerceIn(0f, size.width)
                                val width = (right - left).coerceAtLeast(4.dp.toPx())
                                if (right <= 0f || left >= size.width) return null
                                drawRoundRect(color, Offset(left, y), Size(width.coerceAtMost(size.width - left), h), CornerRadius(3.dp.toPx()))
                                return left to (left + width)
                            }
                            measured.forEach { turn ->
                                val alpha = if (selected == null || selected == turn.id) .42f else .14f
                                bar(
                                    turn.startedAt,
                                    turn.finishedAt ?: now,
                                    14.dp.toPx(),
                                    16.dp.toPx(),
                                    if (turn.isRunning) runningColor.copy(alpha = .75f) else turnColor.copy(alpha = alpha),
                                )
                                turn.tools.forEach { tool ->
                                    val c = when (tool.phase) {
                                        ToolPhase.FAILED -> failColor
                                        ToolPhase.RUNNING -> runningColor
                                        else -> toolColor.copy(alpha = if (selected == null || selected == turn.id) .85f else .25f)
                                    }
                                    val range = bar(tool.startedAt, tool.finishedAt ?: now, 54.dp.toPx(), 18.dp.toPx(), c)
                                    if (range != null) hitTargets += range to tool
                                }
                            }
                        }
                    }
                    Row {
                        Text("0 s", style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
                        Spacer(Modifier.weight(1f))
                        Text(durationLabel(span), style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
                    }
                }
            }
        }

        // Connected Subagents & Background Jobs section
        if (activity.agents.isNotEmpty() || activity.jobs.isNotEmpty()) item(key = "trace-branches") {
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = colors.surface,
                border = BorderStroke(1.dp, colors.outlineVariant),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("关联子代理与后台任务", style = MaterialTheme.typography.titleSmall)
                    activity.agents.forEach { entry ->
                        Row(
                            Modifier.fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .clickable(enabled = onOpenSession != null) { onOpenSession?.invoke(entry.id) }
                                .padding(vertical = 6.dp, horizontal = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Box(Modifier.size(6.dp).clip(CircleShape).background(if (entry.active) colors.primary else colors.outline))
                            Text("子代理", style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
                            Text(entry.title, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                            Text(entry.status, style = MaterialTheme.typography.labelSmall, color = if (entry.active) colors.primary else colors.onSurfaceVariant)
                        }
                    }
                    if (activity.agents.isNotEmpty() && activity.jobs.isNotEmpty()) {
                        HorizontalDivider(thickness = 0.5.dp, color = colors.outlineVariant)
                    }
                    activity.jobs.forEach { entry ->
                        Row(
                            Modifier.fillMaxWidth().padding(vertical = 5.dp, horizontal = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Box(Modifier.size(6.dp).clip(CircleShape).background(if (entry.active) colors.primary else colors.outline))
                            Text("后台任务", style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
                            Text(entry.title, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                            Text(entry.status, style = MaterialTheme.typography.labelSmall, color = if (entry.active) colors.primary else colors.onSurfaceVariant)
                        }
                    }
                }
            }
        } else if (filter == TraceFilter.BRANCHES) {
            item(key = "trace-branches-empty") {
                Text("当前会话暂无子代理或后台任务记录", style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
            }
        }

        if (measured.isEmpty() && turns.isNotEmpty()) item(key = "trace-legacy") {
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = colors.surfaceContainerLow,
                border = BorderStroke(0.5.dp, colors.outlineVariant),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    "旧回合未落库毫秒时序，仅展示已有记录，不补造时间条。新发起的回合将自动保存完整时序。",
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                )
            }
        }

        if (turns.isEmpty()) item(key = "trace-empty") {
            Column(Modifier.padding(vertical = 40.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("还没有执行记录", style = MaterialTheme.typography.titleMedium)
                Text("发出消息后，这里会按回合展示真实的工具调用、耗时与关联分支。", style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
            }
        }

        // Layer 3: Turn cards with inline DevTools-style mini waterfall per tool
        if (filter != TraceFilter.BRANCHES) {
            itemsIndexed(visibleTurns, key = { _, pair -> pair.second.id }) { _, (ordinal, turn) ->
                TurnTraceCard(
                    ordinal = ordinal,
                    turn = turn,
                    now = now,
                    selected = selected == turn.id,
                    onSelect = { selected = if (selected == turn.id) null else turn.id },
                    onInspect = onInspect,
                )
            }
        }
    }
}

@Composable
private fun KpiCell(label: String, value: String, modifier: Modifier = Modifier, valueColor: Color = MaterialTheme.colorScheme.onSurface) {
    Column(modifier.padding(horizontal = 10.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(value, style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold), color = valueColor, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun TurnTraceCard(
    ordinal: Int,
    turn: TranscriptTurn,
    now: Long,
    selected: Boolean,
    onSelect: () -> Unit,
    onInspect: (ToolActivity) -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val turnEnd = turn.finishedAt ?: now.takeIf { turn.isRunning }
    val turnSpan = turnEnd?.let { (it - turn.startedAt).coerceAtLeast(1L) }
    val statusText = when (turn.phase) {
        RunPhase.RUNNING -> "运行中"
        RunPhase.CANCELLED -> "已停止"
        RunPhase.FAILED -> "失败"
        RunPhase.TRUNCATED -> "已截断"
        RunPhase.PARTIAL -> "达工具上限"
        RunPhase.COMPLETE -> "完成"
    }
    val statusColor = when (turn.phase) {
        RunPhase.FAILED -> colors.error
        RunPhase.RUNNING -> colors.primary
        else -> colors.onSurfaceVariant
    }

    Surface(
        onClick = onSelect,
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, if (selected) colors.outline else colors.outlineVariant),
        color = colors.surface,
    ) {
        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "#$ordinal",
                    style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                    color = colors.onSurfaceVariant,
                    modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(colors.surfaceContainerLow).padding(horizontal = 6.dp, vertical = 2.dp),
                )
                Text(
                    turn.prompt.ifBlank { "会话输出" },
                    Modifier.weight(1f),
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(statusText, style = MaterialTheme.typography.labelSmall, color = statusColor)
            }

            // Meta line
            val meta = buildList {
                turnSpan?.let { add(durationLabel(it)) }
                if (turn.tools.isNotEmpty()) add("${turn.tools.size} 步工具")
                val tokens = (turn.inputTokens ?: 0L) + (turn.outputTokens ?: 0L)
                if (tokens > 0) add("${formatCompactTokens(tokens)} tokens")
            }
            if (meta.isNotEmpty()) {
                Text(meta.joinToString(" · "), style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
            }

            if (turn.tools.isEmpty()) {
                Text(
                    if (turn.isRunning) "正在等待模型响应…" else "本回合无工具调用",
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant,
                )
            } else {
                Column(Modifier.fillMaxWidth()) {
                    turn.tools.forEachIndexed { idx, tool ->
                        if (idx > 0) HorizontalDivider(thickness = 0.5.dp, color = colors.outlineVariant)
                        TraceToolWaterfallRow(
                            step = tool,
                            turnStart = turn.startedAt,
                            turnSpan = turnSpan,
                            now = now,
                            onClick = { onInspect(tool) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun TraceToolWaterfallRow(
    step: ToolActivity,
    turnStart: Long,
    turnSpan: Long?,
    now: Long,
    onClick: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val dotColor = when (step.phase) {
        ToolPhase.FAILED -> colors.error
        ToolPhase.RUNNING -> colors.primary
        else -> colors.outline
    }
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).heightIn(min = 46.dp).padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(Modifier.size(6.dp).clip(CircleShape).background(dotColor))
        Column(Modifier.weight(1f)) {
            Text(step.name, fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = colors.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
            step.summary?.takeIf { it.isNotBlank() }?.let {
                Text(
                    it.lineSequence().firstOrNull { line -> line.isNotBlank() }.orEmpty(),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (step.phase == ToolPhase.FAILED) colors.error else colors.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (turnSpan != null && turnSpan > 0 && step.startedAt >= turnStart) {
            val offsetFrac = ((step.startedAt - turnStart).toFloat() / turnSpan).coerceIn(0f, 0.94f)
            val dur = step.durationMillis ?: ((step.finishedAt ?: now) - step.startedAt).coerceAtLeast(1L)
            val widthFrac = (dur.toFloat() / turnSpan).coerceIn(0.06f, 1f - offsetFrac)
            val barColor = when (step.phase) {
                ToolPhase.FAILED -> colors.error
                ToolPhase.RUNNING -> colors.primary
                else -> colors.onSurfaceVariant.copy(alpha = .55f)
            }
            Canvas(Modifier.width(56.dp).height(8.dp).clip(RoundedCornerShape(4.dp)).background(colors.surfaceContainer)) {
                drawRoundRect(
                    barColor,
                    Offset(size.width * offsetFrac, 0f),
                    Size((size.width * widthFrac).coerceAtLeast(3.dp.toPx()), size.height),
                    CornerRadius(3.dp.toPx()),
                )
            }
        }
        Text(
            step.durationMillis?.let(::durationLabel) ?: if (step.phase == ToolPhase.RUNNING) "…" else "—",
            style = MaterialTheme.typography.labelSmall,
            color = if (step.phase == ToolPhase.FAILED) colors.error else colors.onSurfaceVariant,
        )
    }
}

private fun formatCompactTokens(tokens: Long): String = when {
    tokens >= 1_000_000 -> "%.1fM".format(java.util.Locale.ROOT, tokens / 1_000_000.0)
    tokens >= 1_000 -> "%.1fk".format(java.util.Locale.ROOT, tokens / 1_000.0)
    else -> tokens.toString()
}
