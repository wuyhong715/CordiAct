package com.cordiact.app.experience.settings

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.cordiact.app.experience.FrostedSurface

internal val LocalSettingsSection = staticCompositionLocalOf { "" }
internal class SettingsEditorState { var active by mutableStateOf(false) }
internal val LocalSettingsEditor = staticCompositionLocalOf { SettingsEditorState() }

/** Editors are never unmounted by a section switch. Hide navigation while editing secrets. */
@Composable
internal fun SettingsEditor(active: Boolean) {
    val state = LocalSettingsEditor.current
    SideEffect { state.active = active }
    DisposableEffect(state) { onDispose { state.active = false } }
}

@Composable
internal fun SettingsSectionBar(sections: List<SettingsSection>, selected: String, select: (String) -> Unit, modifier: Modifier = Modifier, maxBarWidth: Dp = 440.dp) {
    if (sections.isEmpty()) return
    FrostedSurface(modifier.widthIn(max = maxBarWidth).fillMaxWidth().testTag("settings-sections"), radius = 30.dp) {
        BoxWithConstraints(Modifier.padding(6.dp)) {
            val width = maxWidth / sections.size
            val index = sections.indexOfFirst { it.id == selected }.coerceAtLeast(0)
            val offset by animateDpAsState(width * index, tween(200), label = "section-indicator")
            // Large text uses a native selector rather than squeezing or truncating labels.
            if (LocalDensity.current.fontScale > 1.4f || width < 88.dp) {
                var expanded by remember { mutableStateOf(false) }
                Box {
                    TextButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(sections[index].label + " ▾") }
                    DropdownMenu(expanded, { expanded = false }) {
                        sections.forEach { section -> DropdownMenuItem(text = { Text(section.label) }, onClick = { select(section.id); expanded = false }) }
                    }
                }
            } else {
                Box(Modifier.offset(x = offset).width(width).height(48.dp).background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = .72f), RoundedCornerShape(24.dp)))
                Row {
                    sections.forEach { section ->
                        Box(Modifier.width(width).heightIn(min = 48.dp).selectable(section.id == selected, role = Role.Tab, onClick = { select(section.id) }).testTag("settings-section-${section.id}"), contentAlignment = Alignment.Center) {
                            Text(section.label, color = if (section.id == selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        }
    }
}
