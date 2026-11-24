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
    @Composable override fun Content(state: ExperienceState, host: TabHost) =
        TrajectoryView(state, onInspect = host::inspect, onOpenSession = host::openSession)
}

internal object ArtifactsTab : TabContributor {
    override val id = "cordiact.artifacts"
    override val title = "产物"
    override val glyph = GlyphName.Preview
    override fun badge(state: ExperienceState) = state.sessionId?.let(com.cordiact.app.render.PreviewInbox::unseenCount) ?: 0
    @Composable override fun Content(state: ExperienceState, host: TabHost) {
        if (host.landscape) {
            val items = state.sessionId?.let(com.cordiact.app.render.PreviewInbox::forSession).orEmpty()
            com.cordiact.app.experience.ArtifactBoard(items) { host.inspectArtifact(it.id) }
        } else PreviewView(state, modifier = Modifier.padding(LocalChromePadding.current))
    }
}

internal fun workbenchTabsSpec() = com.cordiact.core.plugin.PluginSpec(
    id = "core.workbench-tabs",
    version = "0.1.0",
    target = com.cordiact.core.plugin.PluginTarget.ANDROID_NATIVE,
)

internal class BuiltinWorkbenchTabs(
    kernel: com.cordiact.core.plugin.PluginKernel,
    directory: com.cordiact.interaction.TrustedWorkbenchTabs<TabContributor>,
) : com.cordiact.core.plugin.RuntimeAwarePlugin {
    override val spec = workbenchTabsSpec()
    private val runtime = com.cordiact.core.plugin.ContextManagedPlugin(spec, kernel, object : com.cordiact.core.plugin.ContextPlugin {
        override fun start(context: com.cordiact.core.plugin.PluginContext) {
            listOf(TraceTab, ArtifactsTab, GitTreeTab).forEachIndexed { index, tab -> directory.register(context, tab.id, tab, index) }
        }
    })
    override fun onRegister() = runtime.onRegister()
    override fun onEnable() {
        runtime.onRegister() // A durable system row may rebind without another registration callback.
        runtime.onEnable()
    }
    override fun onDisable() = runtime.onDisable()
    override fun onUninstall() = runtime.onUninstall()
    override fun runtimeStatus() = runtime.runtimeStatus()
}
