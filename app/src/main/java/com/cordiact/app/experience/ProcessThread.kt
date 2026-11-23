package com.cordiact.app.experience

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.cordiact.app.experience.model.*

@Composable
internal fun ProcessThread(turn: TranscriptTurn, onInspect: (ToolActivity) -> Unit) {
    val entries = turn.process.ifEmpty {
        if (turn.tools.isNotEmpty()) turn.tools.map { ProcessEntry.Tool(it.id) }
        else if (turn.reasoning.isNotBlank()) listOf(ProcessEntry.Thinking(0, turn.reasoning.length))
        else emptyList()
    }
    if (entries.isEmpty()) return

    var expanded by rememberSaveable(turn.id) { mutableStateOf(false) }
    val colors = MaterialTheme.colorScheme
    val toolById = remember(turn.tools) { turn.tools.associateBy { it.id } }
    val active = turn.tools.lastOrNull { it.phase == ToolPhase.RUNNING }
    val failed = turn.tools.count { it.phase == ToolPhase.FAILED }
    val interrupted = turn.tools.count { it.phase == ToolPhase.INTERRUPTED }
    val label = when {
        active != null -> "执行中 · ${active.name}"
        failed > 0 -> "执行过程 · $failed 项失败"
        interrupted > 0 -> "执行过程 · $interrupted 项中断"
        turn.tools.isNotEmpty() -> "执行过程 · ${turn.tools.size} 项调用"
        else -> "模型过程"
    }
    val tone = when {
        failed > 0 -> colors.error
        active != null -> colors.primary
        else -> colors.outline
    }
    Column(Modifier.fillMaxWidth().testTag("process-thread")) {
        Row(
            Modifier.fillMaxWidth().semantics { stateDescription = if (expanded) "已展开" else "已收起" }
                .clickable(role = Role.Button, onClickLabel = if (expanded) "收起过程" else "展开过程") { expanded = !expanded }
                .heightIn(min = 48.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(18.dp).drawBehind { drawCircle(tone, radius = 2.5.dp.toPx()) })
            Text(label, Modifier.weight(1f).padding(horizontal = 6.dp), color = colors.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(if (expanded) "收起" else "展开", color = colors.onSurfaceVariant, style = MaterialTheme.typography.labelSmall)
        }
        if (expanded) entries.forEachIndexed { index, entry ->
            when (entry) {
                is ProcessEntry.Thinking -> key(entry.start) {
                    var showDetails by rememberSaveable { mutableStateOf(false) }
                    ProcessNode(index, entries.lastIndex, colors.outline, hollow = true,
                        onClick = { showDetails = !showDetails }) {
                        Text(if (showDetails) "收起模型过程" else "查看模型过程", style = MaterialTheme.typography.bodySmall,
                            color = colors.onSurfaceVariant)
                        if (showDetails) SelectionContainer {
                            Text(turn.reasoning.substring(entry.start, entry.end), Modifier.padding(top = 4.dp),
                                style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                        }
                    }
                }
                is ProcessEntry.Tool -> toolById[entry.id]?.let { step ->
                    val nodeTone = when (step.phase) {
                        ToolPhase.RUNNING -> colors.primary
                        ToolPhase.FAILED -> colors.error
                        else -> colors.outline
                    }
                    val status = when (step.phase) {
                        ToolPhase.RUNNING -> "执行中"
                        ToolPhase.FAILED -> "失败"
                        ToolPhase.SUCCEEDED -> "完成"
                        ToolPhase.INTERRUPTED -> "中断"
                    }
                    ProcessNode(index, entries.lastIndex, nodeTone, onClick = { onInspect(step) }) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(step.name, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall,
                                color = colors.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Text(listOfNotNull(status, step.durationMillis?.let(::durationLabel)).joinToString(" · "),
                                style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ProcessNode(
    index: Int,
    lastIndex: Int,
    tone: Color,
    hollow: Boolean = false,
    onClick: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    val rail = MaterialTheme.colorScheme.outlineVariant
    val direction = androidx.compose.ui.platform.LocalLayoutDirection.current
    Column(
        Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = onClick).heightIn(min = 48.dp)
            .drawBehind {
                val x = if (direction == androidx.compose.ui.unit.LayoutDirection.Ltr) 9.dp.toPx() else size.width - 9.dp.toPx()
                val y = 24.dp.toPx()
                val radius = 3.dp.toPx()
                if (index > 0) drawLine(rail, Offset(x, 0f), Offset(x, y - radius), strokeWidth = 1.dp.toPx())
                if (index < lastIndex) drawLine(rail, Offset(x, y + radius), Offset(x, size.height), strokeWidth = 1.dp.toPx())
                if (hollow) drawCircle(tone, radius, Offset(x, y), style = Stroke(1.dp.toPx()))
                else drawCircle(tone, radius, Offset(x, y))
            }.padding(start = 24.dp, top = 14.dp, bottom = 14.dp),
        content = content,
    )
}
