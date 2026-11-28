package com.cordiact.app.experience.tabs

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.padding
import com.cordiact.app.experience.ExperienceState
import com.cordiact.app.experience.GlyphName
import com.cordiact.app.experience.LocalChromePadding
import com.cordiact.app.experience.PreviewView
import com.cordiact.app.experience.TrajectoryView
import androidx.compose.material3.IconButton
import androidx.compose.ui.platform.testTag
import com.cordiact.app.experience.AppGlyph
import com.cordiact.app.experience.CLIENT_COMPONENT_SLOTS
import com.cordiact.app.experience.ClientComponent
import com.cordiact.app.experience.ClientComponentHost
import com.cordiact.app.experience.ClientComponentSlot
import com.cordiact.app.experience.ClientComponentSlots

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
    components: ClientComponentSlots,
) : com.cordiact.core.plugin.RuntimeAwarePlugin {
    override val spec = workbenchTabsSpec()
    private val runtime = com.cordiact.core.plugin.ContextManagedPlugin(spec, kernel, object : com.cordiact.core.plugin.ContextPlugin {
        override fun start(context: com.cordiact.core.plugin.PluginContext) {
            context.provide(CLIENT_COMPONENT_SLOTS, components)
            listOf(TraceTab, ArtifactsTab, GitTreeTab).forEachIndexed { index, tab -> directory.register(context, tab.id, tab, index) }
            components.register(context, ClientComponentSlot.SESSION_TOOLBAR, "cordiact.new-session", NewSessionButton, Int.MAX_VALUE)
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

private object NewSessionButton : ClientComponent {
    @Composable
    override fun Content(host: ClientComponentHost) {
        IconButton(onClick = host::createSession, enabled = host.canCreateSession, modifier = Modifier.testTag("new-tab")) {
            AppGlyph(GlyphName.Plus, if (host.landscape) "新对话" else "新对话标签")
        }
    }
}
