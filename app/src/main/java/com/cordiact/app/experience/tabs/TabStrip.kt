package com.cordiact.app.experience.tabs

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.cordiact.app.experience.AppGlyph
import com.cordiact.app.experience.ExperienceState
import com.cordiact.app.experience.GlyphName
import com.cordiact.app.experience.Lumen
import com.cordiact.app.experience.SignalDot

/** Display facts for one tab, resolved from state. */
@Immutable
data class TabFace(val title: String, val glyph: GlyphName, val running: Boolean, val approvals: Int, val badge: Int, val closable: Boolean)

fun ExperienceState.faceOf(tab: WorkTab, sessionTabs: Int): TabFace = when (tab) {
    is WorkTab.Session -> {
        val title = sidebar.asSequence().flatMap { it.sessions }.firstOrNull { it.id == tab.sessionId }?.title
            ?: sessions.firstOrNull { it.id == tab.sessionId }?.title
        TabFace(
            title = title?.ifBlank { null } ?: "新对话",
            glyph = GlyphName.Chat,
            running = tab.sessionId in runningSessionIds || (tab.sessionId == sessionId && running),
            approvals = approvals.count { it.owner?.sessionId == tab.sessionId },
            badge = 0,
            closable = sessionTabs > 1,
        )
    }
    is WorkTab.Plugin -> {
        val c = TabRegistry.find(tab.pluginId)
        TabFace(c?.title ?: tab.pluginId, c?.glyph ?: GlyphName.Bolt, false, 0, c?.badge(this) ?: 0, true)
    }
}

/**
 * Compact browser strip. Tap selects; long-press then drag sideways reorders.
 * (Long-press + drag down will detach into a mini window in the next step; reserved here.)
 */
@Composable
fun TabStrip(state: ExperienceState, controller: TabsController, onClose: (String) -> Unit, modifier: Modifier = Modifier) {
    val haptics = LocalHapticFeedback.current
    val list = rememberLazyListState()
    val sessionTabs = controller.tabs.count { it is WorkTab.Session }
    var dragKey by remember { mutableStateOf<String?>(null) }
    var dragDx by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(controller.activeKey) {
        val index = controller.tabs.indexOfFirst { it.key == controller.activeKey }
        if (index >= 0) list.animateScrollToItem(index, -120)
    }
    LazyRow(
        state = list,
        modifier = modifier.fillMaxWidth().testTag("tab-strip"),
        contentPadding = PaddingValues(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        itemsIndexed(controller.tabs, key = { _, tab -> tab.key }) { index, tab ->
            val face = state.faceOf(tab, sessionTabs)
            val selected = tab.key == controller.activeKey
            val dragging = dragKey == tab.key
            TabChip(
                face = face, selected = selected,
                modifier = Modifier.animateItem()
                    .graphicsLayer { if (dragging) { translationX = dragDx; scaleX = 1.04f; scaleY = 1.04f; shadowElevation = 12f } }
                    .pointerInput(tab.key) {
                        detectDragGesturesAfterLongPress(
                            onDragStart = { dragKey = tab.key; dragDx = 0f; haptics.performHapticFeedback(HapticFeedbackType.LongPress) },
                            onDragEnd = { dragKey = null; dragDx = 0f },
                            onDragCancel = { dragKey = null; dragDx = 0f },
                        ) { change, amount ->
                            change.consume()
                            dragDx += amount.x
                            val from = controller.tabs.indexOfFirst { it.key == tab.key }
                            val step = size.width * .6f
                            if (dragDx > step && from < controller.tabs.lastIndex) { controller.move(from, from + 1); dragDx -= size.width + 6.dp.toPx() }
                            if (dragDx < -step && from > 0) { controller.move(from, from - 1); dragDx += size.width + 6.dp.toPx() }
                        }
                    }
                    .testTag("tab-${tab.key}"),
                onClick = { controller.select(tab.key) },
                onClose = { onClose(tab.key) },
            )
        }
    }
}

@Composable
private fun TabChip(face: TabFace, selected: Boolean, modifier: Modifier, onClick: () -> Unit, onClose: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val width by animateDpAsState(if (selected) 168.dp else 120.dp, label = "tabw")
    Row(
        modifier.width(width).height(34.dp).clip(CircleShape)
            .background(if (selected) colors.surface else colors.surfaceContainer.copy(alpha = .7f))
            .then(if (selected) Modifier.border(.7.dp, colors.outlineVariant, CircleShape) else Modifier)
            .clickable(onClick = onClick)
            .semantics(mergeDescendants = true) {
                this.selected = selected
                role = Role.Tab
                contentDescription = buildString {
                    append(face.title)
                    if (face.running) append("，运行中")
                    if (face.approvals > 0) append("，待审批 ${face.approvals} 项")
                }
                if (face.closable) customActions = listOf(CustomAccessibilityAction("关闭标签") { onClose(); true })
            }
            .padding(start = 12.dp, end = if (selected && face.closable) 4.dp else 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        when {
            face.approvals > 0 -> Box(Modifier.size(8.dp).clip(CircleShape).background(Lumen.Approve))
            face.running -> SignalDot(size = 8.dp)
            else -> AppGlyph(face.glyph, tint = if (selected) colors.onSurface else colors.onSurfaceVariant, modifier = Modifier.size(14.dp))
        }
        Spacer(Modifier.width(7.dp))
        Text(face.title, Modifier.weight(1f), style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis,
            color = if (selected) colors.onSurface else colors.onSurfaceVariant)
        if (face.badge > 0) Text("${face.badge}", style = MaterialTheme.typography.labelSmall, color = colors.primary, modifier = Modifier.padding(start = 4.dp))
        if (selected && face.closable) {
            Box(Modifier.size(28.dp).clip(CircleShape).clickable(onClick = onClose), contentAlignment = Alignment.Center) {
                AppGlyph(GlyphName.Close, tint = colors.onSurfaceVariant, modifier = Modifier.size(12.dp))
            }
        }
    }
}

/**
 * Full-screen overview (Safari tab grid). Open tabs as cards; the second segment is the
 * complete conversation index (the old drawer content), passed in as [allSessions].
 */
@Composable
fun TabOverview(
    state: ExperienceState,
    controller: TabsController,
    onClose: (String) -> Unit,
    onDismiss: () -> Unit,
    onNewSession: () -> Unit,
    onSettings: () -> Unit,
    allSessions: @Composable (Modifier) -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    var segment by rememberSaveable { mutableIntStateOf(0) }
    val sessionTabs = controller.tabs.count { it is WorkTab.Session }
    Surface(color = colors.background, modifier = Modifier.fillMaxSize().testTag("tab-overview")) {
        Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.systemBars)) {
            Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(if (segment == 0) "标签页" else "全部会话", style = MaterialTheme.typography.headlineLarge, modifier = Modifier.weight(1f).semantics { heading() })
                IconButton(onClick = onSettings) { AppGlyph(GlyphName.Settings, "设置") }
            }
            Row(Modifier.padding(horizontal = 20.dp, vertical = 10.dp).clip(CircleShape).background(colors.surfaceContainer).padding(3.dp)) {
                listOf("已打开 ${controller.tabs.size}", "全部会话").forEachIndexed { i, label ->
                    val on = segment == i
                    Box(Modifier.clip(CircleShape).background(if (on) colors.surface else colors.surfaceContainer)
                        .clickable { segment = i }.padding(horizontal = 16.dp, vertical = 7.dp)
                        .semantics { selected = on; role = Role.Tab }) {
                        Text(label, style = MaterialTheme.typography.labelLarge, color = if (on) colors.onSurface else colors.onSurfaceVariant)
                    }
                }
            }
            Box(Modifier.weight(1f).fillMaxWidth()) {
                if (segment == 0) {
                    androidx.compose.foundation.lazy.grid.LazyVerticalGrid(
                        columns = androidx.compose.foundation.lazy.grid.GridCells.Adaptive(160.dp),
                        contentPadding = PaddingValues(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        items(controller.tabs.size, key = { controller.tabs[it].key }) { i ->
                            val tab = controller.tabs[i]
                            val face = state.faceOf(tab, sessionTabs)
                            TabCard(face, selected = tab.key == controller.activeKey,
                                onClick = { controller.select(tab.key); onDismiss() }, onClose = { onClose(tab.key) })
                        }
                        val closed = TabRegistry.contributors.filter { c -> controller.tabs.none { it.key == "p:${c.id}" } }
                        items(closed.size, key = { "add:" + closed[it].id }) { i ->
                            val c = closed[i]
                            Surface(onClick = { controller.open(WorkTab.Plugin(c.id)); onDismiss() }, shape = RoundedCornerShape(18.dp),
                                color = colors.surfaceContainerLow, modifier = Modifier.height(132.dp)) {
                                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                                    AppGlyph(c.glyph, tint = colors.onSurfaceVariant)
                                    Spacer(Modifier.height(8.dp))
                                    Text("打开 ${c.title}", style = MaterialTheme.typography.labelLarge, color = colors.onSurfaceVariant)
                                }
                            }
                        }
                    }
                } else allSessions(Modifier.fillMaxSize())
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onNewSession, modifier = Modifier.heightIn(min = 48.dp).testTag("tab-new")) {
                    AppGlyph(GlyphName.Plus, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("新对话")
                }
                Spacer(Modifier.weight(1f))
                Button(onClick = onDismiss, shape = CircleShape, colors = ButtonDefaults.buttonColors(containerColor = colors.onSurface, contentColor = colors.surface),
                    modifier = Modifier.heightIn(min = 44.dp).testTag("tab-overview-done")) { Text("完成") }
            }
        }
    }
}

@Composable
private fun TabCard(face: TabFace, selected: Boolean, onClick: () -> Unit, onClose: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Surface(onClick = onClick, shape = RoundedCornerShape(18.dp), color = colors.surface,
        border = androidx.compose.foundation.BorderStroke(if (selected) 2.dp else .7.dp, if (selected) Lumen.Signal else colors.outlineVariant),
        modifier = Modifier.height(132.dp).semantics { this.selected = selected }) {
        Column(Modifier.padding(start = 14.dp, end = 4.dp, top = 6.dp, bottom = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                AppGlyph(face.glyph, tint = colors.onSurfaceVariant, modifier = Modifier.size(14.dp))
                Spacer(Modifier.weight(1f))
                if (face.closable) IconButton(onClick = onClose, modifier = Modifier.size(40.dp)) { AppGlyph(GlyphName.Close, "关闭 ${face.title}", modifier = Modifier.size(12.dp)) }
                else Spacer(Modifier.height(40.dp))
            }
            Spacer(Modifier.weight(1f))
            Text(face.title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(end = 10.dp))
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                when {
                    face.approvals > 0 -> { Box(Modifier.size(7.dp).clip(CircleShape).background(Lumen.Approve)); Spacer(Modifier.width(6.dp)); Text("待审批 ${face.approvals}", style = MaterialTheme.typography.labelSmall, color = Lumen.Approve) }
                    face.running -> { SignalDot(size = 7.dp); Spacer(Modifier.width(6.dp)); Text("运行中", style = MaterialTheme.typography.labelSmall, color = colors.primary) }
                    face.badge > 0 -> Text("${face.badge} 项新内容", style = MaterialTheme.typography.labelSmall, color = colors.primary)
                    else -> Text(if (face.glyph == GlyphName.Chat) "对话" else "插件页", style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
                }
            }
        }
    }
}
