package com.cordiact.app.display

import com.cordiact.core.automation.AutomationCapabilities
import com.cordiact.core.automation.AutomationPlugins
import com.cordiact.core.plugin.ManagedPlugin
import com.cordiact.core.plugin.PluginSpec
import com.cordiact.core.plugin.PluginTarget

fun displayPluginSpec(): PluginSpec = PluginSpec(
    id = AutomationPlugins.DISPLAY,
    version = "0.1.0",
    target = PluginTarget.ANDROID_NATIVE,
    requestedCapabilities = setOf(AutomationCapabilities.DISPLAY_CONTROL),
    dependencies = setOf(AutomationPlugins.PRIVILEGE),
)

/**
 * Background virtual-display plugin.
 *
 * It exposes no tools of its own: [com.cordiact.app.screen.ScreenPlugin] is the
 * single model-facing surface, and this plugin only supplies the `b*` drivers
 * behind it. Keeping the split means the model never chooses between two
 * vocabularies, while the user can still disable virtual screens alone.
 */
class DisplayPlugin : ManagedPlugin {

    override val spec: PluginSpec = displayPluginSpec()

    @Volatile
    var isEnabled: Boolean = false
        private set

    override fun onEnable() {
        isEnabled = true
    }

    override fun onDisable() {
        isEnabled = false
    }
}
