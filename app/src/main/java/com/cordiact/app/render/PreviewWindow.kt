package com.cordiact.app.render

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.cordiact.core.render.SnapshotChange
import com.cordiact.core.render.SnapshotFinding
import com.cordiact.core.render.UiSnapshot
import com.cordiact.core.render.UiSnapshotChecks
import com.cordiact.core.render.UiSnapshotDiff
import kotlin.math.abs

/**
 * A separate window for looking at what the agent wrote — not an inline chat card.
 *
 * Two rules make this cheap enough to leave open all day:
 *
 *  - **nothing renders by itself.** Content arrives as inert [PreviewItem]s; a WebView is
 *    created only when the user presses 渲染 on one, and is destroyed again when they press
 *    停止渲染 or close the window. `rememberRenderSurfaceHost` sits inside that branch, so
 *    leaving it removes the surface from the composition and takes the WebView with it.
 *  - **one surface at a time.** Switching items re-uses the same window and disposes the
 *    previous surface instead of stacking WebViews.
 *
 * The agent's role is to drop content into [PreviewInbox]; it never decides when something is
 * rendered.
 */
@Composable
fun PreviewWindow(
    sessionId: String?,
    onDismiss: () -> Unit,
) {
    var activeId by remember { mutableStateOf<String?>(null) }
    val queue = PreviewInbox.forSession(sessionId)
    val active = queue.firstOrNull { it.id == activeId }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(modifier = Modifier.fillMaxSize()) {
            Column(modifier = Modifier.fillMaxSize().padding(12.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Column {
                        Text("预览", style = MaterialTheme.typography.titleMedium)
                        Text(
                            text = if (active == null) "选择一项开始渲染（不会自动加载）" else active.title,
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                    Row {
                        TextButton(onClick = {
                            PreviewInbox.push(
                                sessionId = sessionId,
                                title = "示例：本周回顾",
                                html = PreviewSamples.brokenHtml,
                                css = PreviewSamples.brokenCss,
                            )
                        }) { Text("投放示例") }
                        TextButton(onClick = onDismiss) { Text("关闭") }
                    }
                }
                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

                if (active == null) {
                    if (queue.isEmpty()) {
                        Column(
                            modifier = Modifier.fillMaxSize(),
                            verticalArrangement = Arrangement.Center,
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Text("还没有内容", style = MaterialTheme.typography.bodyMedium)
                            Text(
                                "让 agent 生成 HTML/UI 后投放到这里，或点右上角投放示例",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    } else {
                        LazyColumn(modifier = Modifier.fillMaxSize()) {
                            items(queue, key = { it.id }) { item ->
                                InboxRow(
                                    item = item,
                                    onRender = {
                                        PreviewInbox.markSeen(item.id)
                                        activeId = item.id
                                    },
                                    onRemove = { PreviewInbox.remove(item.id) },
                                )
                                HorizontalDivider()
                            }
                        }
                    }
                } else {
                    RenderedPreview(
                        item = active,
                        sessionId = sessionId,
                        onStop = { activeId = null },
                    )
                }
            }
        }
    }
}

@Composable
private fun InboxRow(
    item: PreviewItem,
    onRender: () -> Unit,
    onRemove: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(item.title, style = MaterialTheme.typography.bodyMedium)
            Text(
                text = buildString {
                    append(if (item.origin == PreviewOrigin.AGENT) "agent" else "本地")
                    append(" · ")
                    append("${item.viewport.widthDp}×${item.viewport.heightDp}")
                    if (!item.seen) append(" · 未查看")
                },
                style = MaterialTheme.typography.labelSmall,
            )
        }
        Row {
            OutlinedButton(onClick = onRender) { Text("渲染") }
            TextButton(onClick = onRemove) { Text("移除") }
        }
    }
}

/**
 * The only place a WebView exists in this feature. Everything below is deliberately local
 * state: closing this composable must be enough to release the renderer.
 */
@Composable
private fun RenderedPreview(
    item: PreviewItem,
    sessionId: String?,
    onStop: () -> Unit,
) {
    var viewport by remember(item.id) { mutableStateOf(item.viewport) }
    var findings by remember(item.id) { mutableStateOf<List<SnapshotFinding>>(emptyList()) }
    var status by remember(item.id) { mutableStateOf("正在加载…") }
    var timeMs by remember(item.id) { mutableStateOf(0f) }
    var series by remember(item.id) { mutableStateOf<List<String>>(emptyList()) }
    var grid by remember(item.id) { mutableStateOf(false) }
    var changes by remember(item.id) { mutableStateOf<List<SnapshotChange>>(emptyList()) }

    // The queue is newest-first, so the previous version of this screen is the next entry down.
    val queue = PreviewInbox.forSession(sessionId)
    val earlier = queue.getOrNull(queue.indexOfFirst { it.id == item.id } + 1)

    val host = rememberRenderSurfaceHost { event ->
        when (event) {
            is RenderEvent.Ready -> status = "已就绪"
            is RenderEvent.Mounted -> status = "已渲染 ${event.viewport.widthDp}dp"
            is RenderEvent.Failed -> status = "失败(${event.where}): ${event.message}"
            is RenderEvent.SnapshotReady -> findings = event.findings
            is RenderEvent.LayoutApplied -> status = "布局：${event.frames} 个视口"
            is RenderEvent.Compared ->
                changes = UiSnapshotDiff.compare(event.previous, event.current)
            is RenderEvent.Patched -> status = "已更新"
            else -> Unit
        }
    }

    LaunchedEffect(host) {
        host.setContent(item.html, item.css, item.viewport)
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = {
                    grid = false
                    viewport = PreviewViewport.SmallPhone
                    host.setLayout(PreviewLayout.single(viewport))
                },
                enabled = grid || viewport.widthDp != 360,
            ) { Text("360") }
            OutlinedButton(
                onClick = {
                    grid = false
                    viewport = PreviewViewport.Phone
                    host.setLayout(PreviewLayout.single(viewport))
                },
                enabled = grid || viewport.widthDp != 390,
            ) { Text("390") }
            OutlinedButton(
                onClick = {
                    grid = false
                    viewport = PreviewViewport.Tablet
                    host.setLayout(PreviewLayout.single(viewport))
                },
                enabled = grid || viewport.widthDp != 768,
            ) { Text("768") }
            OutlinedButton(onClick = {
                grid = !grid
                host.setLayout(if (grid) PreviewLayout.Compare3 else PreviewLayout.single(viewport))
            }) { Text(if (grid) "单屏" else "并排") }
            OutlinedButton(onClick = { host.play() }) { Text("播放") }
            OutlinedButton(onClick = { host.pause() }) { Text("暂停") }
            OutlinedButton(onClick = { host.requestSnapshot() }) { Text("检查") }
            OutlinedButton(onClick = {
                host.captureSeries(SERIES_TIMES) { stops -> series = summarizeSeries(SERIES_TIMES, stops) }
            }) { Text("抽帧") }
            OutlinedButton(
                enabled = earlier != null,
                onClick = {
                    val previous = earlier
                    if (previous == null) {
                        status = "没有更早的版本可对比"
                    } else {
                        status = "对比中…"
                        host.compare(previous.html, previous.css)
                    }
                },
            ) { Text("对比上一版") }
            OutlinedButton(onClick = onStop) { Text("停止渲染") }
        }

        RenderSurface(
            host = host,
            modifier = Modifier.fillMaxWidth().height(420.dp).padding(vertical = 8.dp),
        )

        Text("定格 ${timeMs.toInt()} ms", style = MaterialTheme.typography.labelMedium)
        Slider(
            value = timeMs,
            onValueChange = {
                timeMs = it
                host.seek(it.toDouble())
            },
            valueRange = 0f..2000f,
        )

        Text(status, style = MaterialTheme.typography.labelSmall)
        Column(modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
            if (changes.isNotEmpty()) {
                Text(
                    "与「${earlier?.title ?: "上一版"}」相比 ${changes.size} 处变化",
                    style = MaterialTheme.typography.labelMedium,
                )
                changes.take(MAX_CHANGE_LINES).forEach { change ->
                    Text(change.detail, style = MaterialTheme.typography.bodySmall)
                }
            }
            series.forEach { line ->
                Text(line, style = MaterialTheme.typography.bodySmall)
            }
            if (findings.isEmpty()) {
                Text("没有发现问题", style = MaterialTheme.typography.bodySmall)
            } else {
                findings.forEach { finding ->
                    Text("• ${finding.message}", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}


/** Stops the 抽帧 action walks. Four is enough to see a transition without a capture budget. */
private val SERIES_TIMES = listOf(0.0, 400.0, 800.0, 1200.0)

private const val MAX_SERIES_LINES = 24
private const val MAX_CHANGE_LINES = 20

/**
 * Turns a run of snapshots into movement lines.
 *
 * The agent cannot watch an animation; it can read "from t=0 to t=400 #cta moved down 120px and
 * now overlaps #caption". That sentence is built from numbers the page reported, so it is
 * stable, cheap, and diffable between iterations.
 */
private fun summarizeSeries(times: List<Double>, stops: List<UiSnapshot>): List<String> {
    if (stops.isEmpty()) return listOf("抽帧失败：页面没有返回快照")
    if (stops.size == 1) return listOf("只采集到一帧（页面可能还没就绪）")

    val lines = mutableListOf<String>()
    stops.forEachIndexed { index, stop ->
        val problems = UiSnapshotChecks.check(stop)
        val at = times.getOrElse(index) { 0.0 }.toInt()
        lines += "t=${at}ms · ${stop.nodes.size} 个节点 · ${problems.size} 项问题"
        problems.take(2).forEach { problem -> lines += "    · ${problem.message}" }
    }

    stops.zipWithNext().forEachIndexed { index, (before, after) ->
        val from = times.getOrElse(index) { 0.0 }.toInt()
        val to = times.getOrElse(index + 1) { 0.0 }.toInt()
        val earlier = before.nodes.associateBy { it.selector }
        after.nodes.forEach { node ->
            val previous = earlier[node.selector] ?: return@forEach
            val dx = node.rect.x - previous.rect.x
            val dy = node.rect.y - previous.rect.y
            val dw = node.rect.w - previous.rect.w
            val dh = node.rect.h - previous.rect.h
            if (abs(dx) < 1 && abs(dy) < 1 && abs(dw) < 1 && abs(dh) < 1) return@forEach
            lines += "t=${from}→${to}ms · ${node.selector} ${describeDelta(dx, dy, dw, dh)}"
        }
    }
    return lines.take(MAX_SERIES_LINES)
}

private fun describeDelta(dx: Double, dy: Double, dw: Double, dh: Double): String = buildString {
    if (abs(dx) >= 1 || abs(dy) >= 1) {
        append("移动 (${dx.toInt()}, ${dy.toInt()})")
    }
    if (abs(dw) >= 1 || abs(dh) >= 1) {
        if (isNotEmpty()) append(" · ")
        append("尺寸 (${dw.toInt()}, ${dh.toInt()})")
    }
}
