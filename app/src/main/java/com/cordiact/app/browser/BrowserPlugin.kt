package com.cordiact.app.browser

import com.cordiact.core.automation.AutomationCapabilities
import com.cordiact.core.automation.AutomationPlugins
import com.cordiact.core.plugin.ManagedPlugin
import com.cordiact.core.plugin.PluginSpec
import com.cordiact.core.plugin.PluginTarget

fun browserPluginSpec(): PluginSpec = PluginSpec(
    id = AutomationPlugins.BROWSER,
    version = "0.1.0",
    target = PluginTarget.ANDROID_NATIVE,
    requestedCapabilities = setOf(AutomationCapabilities.BROWSER_CONTROL),
)

class BrowserPlugin(
    val pool: BrowserSessionPool,
) : ManagedPlugin {

    override val spec: PluginSpec = browserPluginSpec()

    @Volatile
    var isEnabled: Boolean = false
        private set

    override fun onEnable() {
        isEnabled = true
    }

    override fun onDisable() {
        isEnabled = false
        pool.closeAll()
    }
}
