package com.cordiact.app.termux

import com.cordiact.core.automation.AutomationCapabilities
import com.cordiact.core.automation.AutomationPlugins
import com.cordiact.core.plugin.ManagedPlugin
import com.cordiact.core.plugin.PluginSpec
import com.cordiact.core.plugin.PluginTarget

fun termuxPluginSpec(): PluginSpec = PluginSpec(
    id = AutomationPlugins.TERMUX,
    version = "0.1.0",
    target = PluginTarget.ANDROID_NATIVE,
    requestedCapabilities = setOf(
        AutomationCapabilities.TERMUX_EXEC,
        AutomationCapabilities.TERMUX_SESSION,
    ),
)

class TermuxPlugin(
    val bridge: TermuxBridge,
    val sessionManager: TermuxSessionManager = TermuxSessionManager(bridge),
) : ManagedPlugin {

    override val spec: PluginSpec = termuxPluginSpec()

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
