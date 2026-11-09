package com.cordiact.app.experience

import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.cordiact.app.experience.model.*
import kotlinx.coroutines.delay

@Composable
fun TrajectoryView(state: ExperienceState, onInspect: (ToolActivity) -> Unit, modifier: Modifier = Modifier) {
    val turns = state.turns
    val measured = turns.filter { it.finishedAt != null || it.isRunning }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(state.running) { while (state.running) { now = System.currentTimeMillis(); delay(500) } }
    var zoom by rememberSaveable(state.sessionId) { mutableFloatStateOf(1f) }
    var pan by rememberSaveable(state.sessionId) { mutableFloatStateOf(0f) }
    var selected by rememberSaveable(state.sessionId) { mutableStateOf<String?>(null) }
    LazyColumn(modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = 16.dp, vertical = 18.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("执行轨迹", style = MaterialTheme.typography.headlineSmall)
                    Text("当前会话 · ${turns.size} 个回合", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                TextButton(onClick = { zoom = 1f; pan = 0f; selected = null }) { Text("重置视图") }
            }
        }
        item {
            Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surface, border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
                Column(Modifier.fillMaxWidth().heightIn(min = 250.dp).padding(18.dp), verticalArrangement = Arrangement.spacedBy(15.dp)) {
                    Row {
                        Text("时间线", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                        Text("${"%.1f".format(java.util.Locale.ROOT, zoom)}×", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                    }
                    val start = measured.minOfOrNull { it.startedAt } ?: now
                    val end = maxOf(now.takeIf { state.running } ?: (measured.maxOfOrNull { it.finishedAt ?: it.startedAt } ?: now), start + 1000)
                    val span = (end - start).coerceAtLeast(1000)
                    val primary = MaterialTheme.colorScheme.primary
                    val secondary = Color(0xFF5D9D88)
                    val grid = MaterialTheme.colorScheme.outlineVariant
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.width(50.dp), verticalArrangement = Arrangement.spacedBy(38.dp)) {
                            Text("主会话", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text("工具", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Canvas(
                            Modifier.weight(1f).height(132.dp)
                                .semantics { contentDescription = "会话与工具调用时间线；双指缩放、拖动平移。下方活动列表提供相同信息。" }
                                .pointerInput(span) { detectTransformGestures { _, delta, factor, _ -> zoom = (zoom * factor).coerceIn(0.25f, 12f); pan = (pan + delta.x).coerceIn(-size.width * zoom, size.width.toFloat()) } },
                        ) {
                            repeat(5) { i -> val x = size.width * i / 4; drawLine(grid, Offset(x,0f), Offset(x,size.height), 1f) }
                            fun bar(from: Long, to: Long, y: Float, color: Color) {
                                val left = ((from - start).toFloat() / span * size.width * zoom + pan).coerceIn(0f, size.width)
                                val right = ((to - start).toFloat() / span * size.width * zoom + pan).coerceIn(0f, size.width)
                                if (right > left) drawRoundRect(color, Offset(left,y), Size((right-left).coerceAtLeast(3f),23.dp.toPx()), CornerRadius(4.dp.toPx()))
                            }
                            measured.forEach { turn ->
                                bar(turn.startedAt, turn.finishedAt ?: now, 25.dp.toPx(), primary.copy(alpha = if (selected == null || selected == turn.id) .8f else .25f))
                                turn.tools.forEach { tool -> bar(tool.startedAt, tool.finishedAt ?: now, 78.dp.toPx(), if (tool.phase == ToolPhase.FAILED) Color(0xFFB45C57) else secondary) }
                            }
                        }
                    }
                    if (measured.isEmpty()) Text("暂无已测量的时序", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Row { Text("0 s", style = MaterialTheme.typography.labelSmall); Spacer(Modifier.weight(1f)); Text(durationLabel(span), style = MaterialTheme.typography.labelSmall) }
                    Text("历史消息未存储的执行时序不会补造。子 Agent 与后台任务将在接入独立生命周期事件后显示。", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        if (turns.isEmpty()) item {
            Column(Modifier.padding(vertical = 40.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("还没有执行记录", style = MaterialTheme.typography.titleMedium)
                Text("发出消息后，这里会按回合展示真实的工具调用和耗时。", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        items(turns.asReversed(), key = { it.id }) { turn ->
            Surface(
                onClick = { selected = if (selected == turn.id) null else turn.id },
                shape = RoundedCornerShape(17.dp),
                border = BorderStroke(1.dp, if (selected == turn.id) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant),
                color = MaterialTheme.colorScheme.surface,
            ) {
                Column(Modifier.fillMaxWidth().heightIn(min = 136.dp).padding(18.dp), verticalArrangement = Arrangement.spacedBy(13.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.width(3.dp).height(34.dp).background(MaterialTheme.colorScheme.primary, RoundedCornerShape(2.dp)))
                        Text(turn.prompt.ifBlank { "会话输出" }, Modifier.weight(1f).padding(horizontal = 10.dp), style = MaterialTheme.typography.titleMedium, maxLines = 3, overflow = TextOverflow.Ellipsis)
                        Text(when (turn.phase) { RunPhase.RUNNING -> "运行中"; RunPhase.CANCELLED -> "已停止"; RunPhase.FAILED -> "中断"; else -> "完成" }, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (turn.tools.isEmpty()) Text(if (turn.isRunning) "正在等待模型响应" else "无可用的工具时序记录", Modifier.padding(start = 13.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    turn.tools.forEach { tool ->
                        ToolRow(tool, large = true) { onInspect(tool) }
                    }
                    turn.finishedAt?.let { Text(durationLabel(it - turn.startedAt), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
            }
        }
    }
}
