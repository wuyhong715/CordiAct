package com.cordiact.app.experience

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import dev.chrisbanes.haze.hazeSource
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

private data class WindowTab(val id: String, val title: String, val glyph: GlyphName)
private val tabs = listOf(WindowTab("chat", "对话", GlyphName.Chat), WindowTab("trace", "轨迹", GlyphName.Trace), WindowTab("preview", "预览", GlyphName.Preview))

/** Browser-like rail: bounded-width tabs, fixed glyphs and one pager-driven underline. */
@Composable
internal fun WindowTabs(selected: Int, position: Float, tools: Int, previews: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    BoxWithConstraints(modifier.fillMaxWidth().padding(horizontal = 12.dp).height(48.dp).testTag("window-tabs")) {
        val width = minOf(maxWidth / tabs.size, 78.dp)
        val density = androidx.compose.ui.platform.LocalDensity.current
        val offset = with(density) { ((position.coerceIn(0f, 2f) * width.toPx()) + (width - 26.dp).toPx() / 2).roundToInt() }
        Box(Modifier.width(width * tabs.size).height(32.dp).align(Alignment.CenterStart).background(MaterialTheme.colorScheme.surfaceContainer.copy(alpha = .78f), RoundedCornerShape(11.dp)))
        Row(Modifier.fillMaxHeight()) {
            tabs.forEachIndexed { index, tab -> key(tab.id) {
                val active = selected == index
                val tint = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                Row(
                    Modifier.width(width).fillMaxHeight()
                        .clip(RoundedCornerShape(topStart = 9.dp, topEnd = 9.dp))
                        .selectable(active, role = Role.Tab, onClick = { onSelect(index) }).testTag("window-${tab.id}"),
                    horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically,
                ) {
                    AppGlyph(tab.glyph, modifier = Modifier.size(14.dp), tint = tint)
                    Text(tab.title, Modifier.padding(start = 4.dp), color = tint, style = MaterialTheme.typography.labelMedium)
                    val badge = if (index == 1) tools else if (index == 2) previews else 0
                    if (badge > 0) Text(" $badge", color = tint, style = MaterialTheme.typography.labelSmall)
                }
            } }
        }
        Box(Modifier.align(Alignment.BottomStart).padding(bottom = 8.dp).offset { IntOffset(offset, 0) }.width(26.dp).height(2.dp).background(MaterialTheme.colorScheme.primary, RoundedCornerShape(1.dp)))
    }
}

/** The ONLY inset owner. IME/system bars use max/union, never additive keyboard padding. */
@Composable
internal fun ExperienceFrame(
    modifier: Modifier = Modifier,
    insets: WindowInsets = WindowInsets.systemBars.union(WindowInsets.ime),
    top: @Composable () -> Unit,
    bottom: @Composable () -> Unit,
    content: @Composable (Modifier) -> Unit,
) {
    val backdrop = remember { dev.chrisbanes.haze.HazeState() }
    var topHeight by remember { mutableIntStateOf(0) }
    var bottomHeight by remember { mutableIntStateOf(0) }
    val density = androidx.compose.ui.platform.LocalDensity.current
    val topPadding = with(density) { topHeight.toDp() }
    val bottomPadding = with(density) { bottomHeight.toDp() }
    CompositionLocalProvider(LocalBackdrop provides backdrop,
        LocalChromePadding provides PaddingValues(top = topPadding + 56.dp, bottom = bottomPadding + 56.dp)) {
        Box(modifier.fillMaxSize().windowInsetsPadding(insets).testTag("experience-frame")) {
            content(Modifier.fillMaxSize().hazeSource(backdrop))
            ViewportFade(backdrop, top = true, Modifier.align(Alignment.TopCenter).fillMaxWidth().height(topPadding + 48.dp))
            ViewportFade(backdrop, top = false, Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(bottomPadding + 48.dp))
            Column(Modifier.align(Alignment.TopCenter).fillMaxWidth().onSizeChanged { topHeight = it.height }) { top() }
            Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().onSizeChanged { bottomHeight = it.height }) { bottom() }
        }
    }
}
