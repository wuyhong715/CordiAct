package com.cordiact.app.ssh

import com.cordiact.core.automation.AutomationCapabilities
import com.cordiact.core.automation.AutomationPlugins
import com.cordiact.core.execution.JSchSshService
import com.cordiact.core.execution.SshExecutionService
import com.cordiact.core.plugin.ManagedPlugin
import com.cordiact.core.plugin.PluginSpec
import com.cordiact.core.plugin.PluginTarget

fun sshPluginSpec(): PluginSpec = PluginSpec(
    id = AutomationPlugins.SSH,
    version = "0.1.0",
    target = PluginTarget.ANDROID_NATIVE,
    requestedCapabilities = setOf(
        AutomationCapabilities.SSH_EXEC,
        AutomationCapabilities.SSH_SFTP,
    ),
)

class SshPlugin(
    val service: SshExecutionService = JSchSshService(),
    val hostStore: SshHostStore,
) : ManagedPlugin {

    override val spec: PluginSpec = sshPluginSpec()

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
