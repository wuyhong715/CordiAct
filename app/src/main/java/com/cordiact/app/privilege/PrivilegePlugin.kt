package com.cordiact.app.privilege

import com.cordiact.core.automation.AutomationCapabilities
import com.cordiact.core.automation.AutomationPlugins
import com.cordiact.core.plugin.ManagedPlugin
import com.cordiact.core.plugin.PluginSpec
import com.cordiact.core.plugin.PluginTarget

fun privilegePluginSpec(): PluginSpec = PluginSpec(
    id = AutomationPlugins.PRIVILEGE,
    version = "0.1.0",
    target = PluginTarget.ANDROID_NATIVE,
    requestedCapabilities = setOf(
        AutomationCapabilities.PRIV_SHELL,
        AutomationCapabilities.PRIV_SHELL_ADVANCED,
        AutomationCapabilities.PRIV_ROOT,
    ),
)

class PrivilegePlugin(
    private val service: PrivilegeService,
) : ManagedPlugin {

    override val spec: PluginSpec = privilegePluginSpec()

    @Volatile
    var isEnabled: Boolean = false
        private set

    fun service(): PrivilegeService = service

    override fun onEnable() {
        isEnabled = true
    }

    override fun onDisable() {
        isEnabled = false
    }
}
