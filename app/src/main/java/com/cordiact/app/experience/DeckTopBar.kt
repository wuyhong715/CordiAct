package com.cordiact.app.experience

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * Landscape deck header: one 48dp row — overview · workspace · scrollable tabs · new · approvals.
 * The session name is deliberately absent: the focused round already says it, and switching tabs
 * must not fade or resize any label.
 */
@Composable
internal fun DeckTopBar(
    workspace: String,
    approvalCount: Int,
    onOverview: () -> Unit,
    onApprovals: () -> Unit,
    onNew: () -> Unit,
    enabledNew: Boolean,
    modifier: Modifier = Modifier,
    tabs: @Composable (Modifier) -> Unit,
) {
    Row(
        modifier.fillMaxWidth().height(48.dp).padding(horizontal = 4.dp).testTag("deck-top-bar"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        IconButton(onClick = onOverview, modifier = Modifier.size(48.dp).testTag("open-overview")) {
            AppGlyph(GlyphName.Tabs, "标签总览")
        }
        Text(
            workspace,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.widthIn(max = 104.dp).padding(horizontal = 4.dp),
        )
        tabs(Modifier.weight(1f))
        IconButton(onClick = onNew, enabled = enabledNew, modifier = Modifier.size(48.dp).testTag("new-tab")) {
            AppGlyph(GlyphName.Plus, "新对话标签")
        }
        IconButton(onClick = onApprovals, modifier = Modifier.size(48.dp).testTag("deck-approvals")) {
            Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) {
                AppGlyph(
                    GlyphName.Shield, "待审批 $approvalCount 项",
                    tint = if (approvalCount > 0) Lumen.Approve else LocalContentColor.current,
                )
                if (approvalCount > 0) Surface(
                    color = Lumen.Approve, shape = CircleShape,
                    modifier = Modifier.align(Alignment.TopEnd).offset(x = 8.dp, y = (-6).dp),
                ) {
                    Text(
                        "$approvalCount",
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White,
                        modifier = Modifier.padding(horizontal = 4.dp),
                    )
                }
            }
        }
    }
}
