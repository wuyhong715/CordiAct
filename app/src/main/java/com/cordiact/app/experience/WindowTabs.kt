package com.cordiact.app.experience

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
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
    BoxWithConstraints(modifier.fillMaxWidth().padding(horizontal = 12.dp).height(40.dp).testTag("window-tabs")) {
        val width = minOf(maxWidth / tabs.size, 104.dp)
        val density = androidx.compose.ui.platform.LocalDensity.current
        val offset = with(density) { ((position.coerceIn(0f, 2f) * width.toPx()) + (width - 26.dp).toPx() / 2).roundToInt() }
        Box(Modifier.fillMaxWidth().height(1.dp).align(Alignment.BottomStart).background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = .55f)))
        Row(Modifier.fillMaxHeight()) {
            tabs.forEachIndexed { index, tab -> key(tab.id) {
                val active = selected == index
                val tint = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                Row(
                    Modifier.width(width).fillMaxHeight()
                        .clip(RoundedCornerShape(topStart = 9.dp, topEnd = 9.dp))
                        .background(if (active) MaterialTheme.colorScheme.surfaceContainerLow else androidx.compose.ui.graphics.Color.Transparent)
                        .selectable(active, role = Role.Tab, onClick = { onSelect(index) }).testTag("window-${tab.id}"),
                    horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically,
                ) {
                    AppGlyph(tab.glyph, modifier = Modifier.size(16.dp), tint = tint)
                    Text(tab.title, Modifier.padding(start = 6.dp), color = tint, style = MaterialTheme.typography.labelLarge)
                    val badge = if (index == 1) tools else if (index == 2) previews else 0
                    if (badge > 0) Text(" $badge", color = tint, style = MaterialTheme.typography.labelSmall)
                }
            } }
        }
        Box(Modifier.align(Alignment.BottomStart).offset { IntOffset(offset, 0) }.width(26.dp).height(2.dp).background(MaterialTheme.colorScheme.primary, RoundedCornerShape(1.dp)))
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
    Column(modifier.fillMaxSize().windowInsetsPadding(insets).testTag("experience-frame")) {
        top()
        content(Modifier.weight(1f).fillMaxWidth())
        bottom()
    }
}
