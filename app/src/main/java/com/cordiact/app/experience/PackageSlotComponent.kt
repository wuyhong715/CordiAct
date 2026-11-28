package com.cordiact.app.experience

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.cordiact.core.plugin.ExtensionPackageComponentNode
import com.cordiact.interaction.InteractionContext
import com.cordiact.interaction.WorkspaceClientContributions

internal class PackageSlotComponent(private val publication: WorkspaceClientContributions.SlotComponent) : ClientComponent {
    @Composable
    override fun Content(host: ClientComponentHost) {
        var menu by remember { mutableStateOf<Any?>(null) }
        Box {
            IconButton(onClick = { menu = Any() }) { AppGlyph(GlyphName.Preview, publication.description.label) }
            val opened = menu
            DropdownMenu(expanded = opened != null, onDismissRequest = { menu = null }) {
                if (opened != null) key(opened) {
                    Nodes(publication.description.nodes, host, { menu === opened }) { menu = null }
                }
            }
        }
    }

    @Composable
    private fun Nodes(
        nodes: List<ExtensionPackageComponentNode>, host: ClientComponentHost, current: () -> Boolean, close: () -> Unit,
    ) {
        for (node in nodes) {
            when (node) {
                is ExtensionPackageComponentNode.Text -> Text(node.text, Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
                is ExtensionPackageComponentNode.Group -> {
                    var open by remember { mutableStateOf(false) }
                    DropdownMenuItem(text = { Text(node.label) }, onClick = { open = !open },
                        modifier = Modifier.semantics { stateDescription = if (open) "已展开" else "已折叠" })
                    if (open) Nodes(node.children, host, { current() && open }, close)
                }
                is ExtensionPackageComponentNode.Action -> {
                    val workspace = host.workspaceId
                    val session = host.sessionId
                    val offer = if (workspace != null && session != null) publication.offer(node.action, InteractionContext(session, workspace)) else null
                    if (offer != null) DropdownMenuItem(
                        text = { Text(offer.action.description.label) }, enabled = host.canInsertAction,
                        onClick = { host.insertAction(offer, current, close) },
                    )
                }
            }
        }
    }
}
