package com.cordiact.app.experience

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.cordiact.app.render.*

/** Agent renderables ONLY: preview.push -> PreviewInbox -> explicit render -> sandboxed host. */
@Composable
fun PreviewView(state: ExperienceState, visible: Boolean = true, modifier: Modifier = Modifier) {
    ArtifactPreviewWindow(state.sessionId, visible, modifier)
}

@Composable
internal fun ArtifactPreviewWindow(
    sessionId: String?,
    visible: Boolean,
    modifier: Modifier = Modifier,
    render: @Composable (PreviewItem) -> Unit = { ArtifactStage(it) },
) {
    val queue = PreviewInbox.forSession(sessionId)
    var activeId by rememberSaveable(sessionId) { mutableStateOf<String?>(null) }
    val active = queue.firstOrNull { it.id == activeId }
    Column(modifier.fillMaxSize().testTag("artifact-preview")) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 13.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("产物预览", style = MaterialTheme.typography.titleMedium)
                Text(if (queue.isEmpty()) "Agent 的可渲染产物" else "${queue.size} 个产物 · 点选后渲染", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (active != null) TextButton(onClick = { activeId = null }) { Text("返回列表") }
        }
        when {
            queue.isEmpty() -> {
                Column(Modifier.padding(horizontal = 22.dp, vertical = 32.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    AppGlyph(GlyphName.Preview, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(28.dp))
                    Text("等待可渲染产物", style = MaterialTheme.typography.headlineSmall)
                    Text("Agent 通过 preview.push 提交的页面、组件和可视化会出现在这里。选择产物后才启动渲染，不会把普通回复当成预览。", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            active == null -> LazyColumn(contentPadding = PaddingValues(horizontal = 18.dp, vertical = 3.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                items(queue, key = { it.id }) { item ->
                    Surface(
                        onClick = { PreviewInbox.markSeen(item.id); activeId = item.id },
                        shape = RoundedCornerShape(17.dp), color = MaterialTheme.colorScheme.surface,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                        modifier = Modifier.fillMaxWidth().testTag("artifact-${item.id}"),
                    ) {
                        Row(Modifier.padding(17.dp), verticalAlignment = Alignment.CenterVertically) {
                            AppGlyph(GlyphName.Code, modifier = Modifier.size(25.dp), tint = MaterialTheme.colorScheme.primary)
                            Column(Modifier.weight(1f).padding(horizontal = 13.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text(item.title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                Text("${item.viewport.widthDp} × ${item.viewport.heightDp} · ${if (item.origin == PreviewOrigin.AGENT) "Agent 产物" else "本地产物"}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Text("渲染", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge)
                        }
                    }
                }
            }
            else -> {
                LazyRow(contentPadding = PaddingValues(horizontal = 18.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(queue, key = { it.id }) { item ->
                        FilterChip(selected = item.id == active.id, onClick = { PreviewInbox.markSeen(item.id); activeId = item.id }, label = { Text(item.title, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 160.dp)) })
                    }
                }
                Box(Modifier.weight(1f).fillMaxWidth().padding(8.dp)) {
                    // Off-screen pager pages must never keep WebViews executing.
                    if (visible) key(sessionId, active.id) { render(active) }
                    else Text("预览已暂停", Modifier.align(Alignment.Center), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun ArtifactStage(item: PreviewItem) {
    var status by remember(item.id) { mutableStateOf("正在初始化渲染器…") }
    var viewport by remember(item.id) { mutableStateOf(item.viewport) }
    var compare by remember(item.id) { mutableStateOf(false) }
    val host = rememberRenderSurfaceHost(allowNetwork = false) { event ->
        status = when (event) {
            is RenderEvent.Ready -> "渲染器已就绪"
            is RenderEvent.Mounted -> "已渲染 ${event.viewport.widthDp} × ${event.viewport.heightDp}"
            is RenderEvent.LayoutApplied -> "${event.frames} 个对比视口"
            is RenderEvent.Failed -> "渲染失败：${event.message}"
            else -> status
        }
    }
    LaunchedEffect(host, item.id) { host.setContent(item.html, item.css, item.viewport) }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf(360, 390, 768).forEach { width ->
                FilterChip(
                    selected = !compare && viewport.widthDp == width,
                    onClick = {
                        compare = false
                        viewport = PreviewViewport(width, if (width == 768) 1024 else 844, if (width == 768) .55f else 1f)
                        host.setLayout(PreviewLayout.single(viewport))
                    }, label = { Text("${width}dp") },
                )
            }
            FilterChip(selected = compare, onClick = { compare = true; host.setLayout(PreviewLayout.Compare3) }, label = { Text("多屏对比") })
        }
        Surface(Modifier.weight(1f).fillMaxWidth(), shape = RoundedCornerShape(13.dp), border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
            RenderSurface(host, Modifier.fillMaxSize().testTag("artifact-render-surface"))
        }
        Text(status, Modifier.padding(horizontal = 6.dp, vertical = 8.dp), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
