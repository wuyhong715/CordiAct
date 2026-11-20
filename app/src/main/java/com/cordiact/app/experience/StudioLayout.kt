package com.cordiact.app.experience

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import dev.chrisbanes.haze.hazeSource
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.unit.dp

/** A single entry to the current session’s secondary views. */
@Composable
internal fun StudioPaneMenu(pane: StudioPane, tools: Int, previews: Int, onSelect: (StudioPane) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val label = if (pane != StudioPane.Conversation) pane.label
        else if (previews > 0) "产物 $previews" else if (tools > 0) "执行中 $tools" else "工作台"
    Box {
        TextButton(onClick = { expanded = true }, modifier = Modifier.testTag("studio-pane-menu")) {
            Text(label, maxLines = 1)
            AppGlyph(GlyphName.ChevronDown, modifier = Modifier.size(16.dp))
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            StudioPane.entries.forEach { item ->
                val count = when (item) {
                    StudioPane.Conversation -> 0
                    StudioPane.Activity -> tools
                    StudioPane.Artifacts -> previews
                }
                DropdownMenuItem(
                    text = { Text(if (count > 0) "${item.label} · $count" else item.label) },
                    trailingIcon = if (item == pane) {{ AppGlyph(GlyphName.Check) }} else null,
                    modifier = Modifier.testTag("studio-pane-${item.name.lowercase()}").semantics { selected = item == pane },
                    onClick = { expanded = false; onSelect(item) },
                )
            }
        }
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
    CompositionLocalProvider(
        LocalBackdrop provides backdrop,
        LocalChromeBoundsPadding provides PaddingValues(top = topPadding, bottom = bottomPadding),
        LocalChromePadding provides PaddingValues(top = topPadding + 12.dp, bottom = bottomPadding + 12.dp),
    ) {
        Box(modifier.fillMaxSize().windowInsetsPadding(insets).testTag("experience-frame")) {
            content(Modifier.fillMaxSize().hazeSource(backdrop))
            ViewportFade(backdrop, top = true, Modifier.align(Alignment.TopCenter).fillMaxWidth().height(topPadding + 12.dp))
            ViewportFade(backdrop, top = false, Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(bottomPadding + 12.dp))
            Column(Modifier.align(Alignment.TopCenter).fillMaxWidth().onSizeChanged { topHeight = it.height }) { top() }
            Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().onSizeChanged { bottomHeight = it.height }) { bottom() }
        }
    }
}
