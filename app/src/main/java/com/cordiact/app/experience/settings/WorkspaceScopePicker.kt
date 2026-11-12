package com.cordiact.app.experience.settings

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*

@Composable
internal fun WorkspaceScopePicker(state: SettingsSnapshot, disabled: Boolean, select: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val selected = state.workspaces.firstOrNull { it.id == state.capabilityWorkspaceId }
    Box {
        OutlinedButton(enabled = !disabled, onClick = { expanded = true }) { Text("作用于：${selected?.name ?: state.capabilityWorkspaceId} ▾") }
        DropdownMenu(expanded, onDismissRequest = { expanded = false }) {
            state.workspaces.forEach { workspace -> DropdownMenuItem(text = { Text(workspace.name) }, onClick = { expanded = false; select(workspace.id) }) }
        }
    }
}
