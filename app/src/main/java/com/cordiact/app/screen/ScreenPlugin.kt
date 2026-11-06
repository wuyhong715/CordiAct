package com.cordiact.app.screen

import com.cordiact.core.automation.AutomationCapabilities
import com.cordiact.core.automation.AutomationPlugins
import com.cordiact.core.plugin.ManagedPlugin
import com.cordiact.core.plugin.PluginSpec
import com.cordiact.core.plugin.PluginTarget
import com.cordiact.core.workspace.WORKSPACE_MANAGER_PLUGIN_ID

fun screenPluginSpec(): PluginSpec = PluginSpec(
    id = AutomationPlugins.SCREEN,
    version = "0.1.0",
    target = PluginTarget.ANDROID_NATIVE,
    requestedCapabilities = setOf(
        AutomationCapabilities.SCREEN_OBSERVE,
        AutomationCapabilities.SCREEN_ACT,
    ),
    dependencies = setOf(WORKSPACE_MANAGER_PLUGIN_ID),
)

/**
 * Screen automation plugin.
 *
 * The plugin itself is intentionally thin: the accessibility service is a
 * system-managed component, and the driver lifecycle is owned by the app
 * process. What the plugin contributes is the tool surface and the on/off
 * switch that gates it.
 */
class ScreenPlugin(
    private val service: ScreenService,
) : ManagedPlugin {

    override val spec: PluginSpec = screenPluginSpec()

    @Volatile
    var isEnabled: Boolean = false
        private set

    fun service(): ScreenService = service

    override fun onEnable() {
        isEnabled = true
    }

    override fun onDisable() {
        isEnabled = false
    }
}
