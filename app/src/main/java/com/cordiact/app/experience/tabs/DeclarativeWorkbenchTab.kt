package com.cordiact.app.experience.tabs

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.cordiact.app.experience.ExperienceState
import com.cordiact.app.experience.GlyphName
import com.cordiact.app.experience.LocalChromePadding
import com.cordiact.core.plugin.ExtensionPackageSettingsPage

internal class DeclarativeWorkbenchTab(
    override val id: String,
    private val panel: ExtensionPackageSettingsPage,
) : TabContributor {
    override val title = panel.title
    override val glyph = GlyphName.Preview

    @Composable
    override fun Content(state: ExperienceState, host: TabHost) {
        LazyColumn(
            Modifier.fillMaxSize().padding(LocalChromePadding.current),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Text(panel.title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
                Text(panel.description, style = MaterialTheme.typography.bodyMedium)
            }
            items(panel.rows) { row ->
                Text(row.label, style = MaterialTheme.typography.titleSmall)
                Text(row.value, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}
