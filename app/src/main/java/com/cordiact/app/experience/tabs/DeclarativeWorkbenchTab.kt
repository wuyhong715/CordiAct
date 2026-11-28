package com.cordiact.app.experience.tabs

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cordiact.app.experience.ExperienceState
import com.cordiact.app.experience.GlyphName
import com.cordiact.app.experience.LocalChromePadding
import com.cordiact.interaction.InteractionContext
import com.cordiact.interaction.WorkspaceClientContributions

internal class DeclarativeWorkbenchTab(
    override val id: String,
    private val publication: WorkspaceClientContributions.WorkbenchPanel,
) : TabContributor {
    private val panel = publication.description
    override val title = panel.title
    override val glyph = GlyphName.Preview

    @Composable
    override fun Content(state: ExperienceState, host: TabHost) {
        val expanded by publication.expanded.collectAsStateWithLifecycle()
        val workspace = state.workspace?.id
        val owner = state.sessionId?.let { session -> workspace?.let { InteractionContext(session, it) } }
        var mounted by remember(publication, owner) { mutableStateOf(false) }
        DisposableEffect(publication, owner) {
            mounted = true
            onDispose { mounted = false }
        }
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
            items(panel.controls, key = { it.id }) { control ->
                val open = control.id in expanded
                TextButton(
                    onClick = { publication.setExpanded(control.id, !open) },
                    modifier = Modifier.semantics { stateDescription = if (open) "已展开" else "已折叠" },
                ) { Text(control.label) }
                if (open) {
                    Text(control.content, style = MaterialTheme.typography.bodyMedium)
                    val offer = owner?.let { publication.action(control.id, it) }
                    if (offer != null) {
                        TextButton(enabled = host.canInsertPluginAction, onClick = {
                            host.insertPluginAction(offer) {
                                mounted && publication.action(control.id, offer.context)?.action === offer.action
                            }
                        }) { Text(offer.action.description.label) }
                    }
                }
            }
        }
    }
}
