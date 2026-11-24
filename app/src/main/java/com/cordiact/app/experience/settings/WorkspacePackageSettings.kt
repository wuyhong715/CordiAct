package com.cordiact.app.experience.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.cordiact.app.experience.GlyphName
import com.cordiact.app.experience.settings.components.SettingsCard
import com.cordiact.app.experience.settings.components.SettingsGroup
import com.cordiact.app.experience.settings.components.SettingsRow
import com.cordiact.app.experience.settings.components.SettingsSectionLabel
import com.cordiact.core.plugin.ExtensionPackageSettingsPage
import com.cordiact.interaction.WorkspaceClientContributions

@Composable
internal fun WorkspacePackageSettingsEntries(
    pages: List<WorkspaceClientContributions.SettingsPage>,
    enabled: Boolean,
    managePackages: () -> Unit,
    openDescription: (WorkspaceClientContributions.SettingsPage) -> Unit,
) {
    // The protected built-in row is never constructed by a package or replaced by its title.
    SettingsSectionLabel("内置管理")
    SettingsGroup {
        SettingsRow(SettingsRoute.WORKSPACE_PACKAGES.title, "导入、启停和管理已安装的声明包", GlyphName.Folder, enabled, managePackages)
    }
    if (pages.isNotEmpty()) {
        SettingsSectionLabel("第三方插件说明")
        SettingsGroup {
            pages.forEach { page -> key(page.ownerPluginId) {
                SettingsRow(page.description.title, page.ownerPluginId, GlyphName.Info, enabled) {
                    openDescription(page)
                }
            } }
        }
    }
}

/** Only literal text goes into this renderer. There are no routes, runtime calls or actions. */
@Composable
internal fun WorkspacePackageDescription(ownerPluginId: String, description: ExtensionPackageSettingsPage) {
    SettingsCard {
        SelectionContainer {
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("第三方插件 · $ownerPluginId", color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.labelMedium)
                Text(description.title, Modifier.semantics { heading() }, style = MaterialTheme.typography.titleLarge)
                Text(description.description)
                description.rows.forEach { row ->
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(row.label, style = MaterialTheme.typography.labelLarge)
                        Text(row.value)
                    }
                }
            }
        }
    }
}
