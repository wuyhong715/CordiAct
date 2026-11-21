package com.cordiact.app.experience.tabs

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.padding
import com.cordiact.app.experience.ExperienceState
import com.cordiact.app.experience.GlyphName
import com.cordiact.app.experience.LocalChromePadding
import com.cordiact.app.experience.PreviewView
import com.cordiact.app.experience.TrajectoryView

/** The old top-right views, now ordinary plugin tabs that follow the most recent conversation. */
internal object TraceTab : TabContributor {
    override val id = "cordiact.trace"
    override val title = "执行轨迹"
    override val glyph = GlyphName.Trace
    override fun badge(state: ExperienceState) = state.activeTools
    @Composable override fun Content(state: ExperienceState, host: TabHost) = TrajectoryView(state, onInspect = host::inspect)
}

internal object ArtifactsTab : TabContributor {
    override val id = "cordiact.artifacts"
    override val title = "产物"
    override val glyph = GlyphName.Preview
    override fun badge(state: ExperienceState) = com.cordiact.app.render.PreviewInbox.unseenCount(state.sessionId)
    @Composable override fun Content(state: ExperienceState, host: TabHost) =
        PreviewView(state, modifier = Modifier.padding(LocalChromePadding.current))
}

internal fun registerBuiltinTabs() {
    TabRegistry.register(TraceTab)
    TabRegistry.register(ArtifactsTab)
    TabRegistry.register(GitTreeTab)
}
