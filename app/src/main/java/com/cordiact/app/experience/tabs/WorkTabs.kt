package com.cordiact.app.experience.tabs

import androidx.compose.runtime.Composable
import com.cordiact.app.experience.ExperienceState
import com.cordiact.app.experience.GlyphName
import com.cordiact.app.experience.model.ToolActivity
import com.cordiact.interaction.WorkspaceClientContributions

/** What a plugin page may ask of the shell. Kept tiny on purpose: shells evolve, plugins should not break. */
interface TabHost {
    val landscape: Boolean
    fun inspect(step: ToolActivity)
    fun inspectArtifact(id: String)
    fun openSession(sessionId: String)
    val canInsertPluginAction: Boolean get() = false
    fun insertPluginAction(offer: WorkspaceClientContributions.SessionActionOffer, isStillVisible: () -> Boolean) = Unit
}

/**
 * Plugin tab contract. The shell owns the strip, the chrome, the material and the overview card;
 * a contributor only supplies identity, a badge and the page body.
 */
interface TabContributor {
    /** Stable, unique, `[a-z0-9._-]`. Persisted in the tab strip. */
    val id: String
    val title: String
    val glyph: GlyphName
    /** Short count shown on the tab; 0 hides it. Must be cheap: it is read on every frame of the strip. */
    fun badge(state: ExperienceState): Int = 0
    @Composable fun Content(state: ExperienceState, host: TabHost)
}

internal typealias PublishedTab = com.cordiact.interaction.TrustedWorkbenchTabs.Publication<TabContributor>
