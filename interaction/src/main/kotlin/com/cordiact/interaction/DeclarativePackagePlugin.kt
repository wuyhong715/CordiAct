package com.cordiact.interaction

import com.cordiact.core.plugin.ContextManagedPlugin
import com.cordiact.core.plugin.ContextPlugin
import com.cordiact.core.plugin.ExtensionPackageCapability
import com.cordiact.core.plugin.ExtensionPackageCommand
import com.cordiact.core.plugin.ExtensionPackageManifest
import com.cordiact.core.plugin.PluginContext
import com.cordiact.core.plugin.PluginInstanceId
import com.cordiact.core.plugin.PluginKernel
import com.cordiact.core.plugin.PluginSpec
import com.cordiact.core.plugin.extensionPackageSpec
import com.cordiact.core.plugin.RuntimeAwarePlugin
import com.cordiact.core.plugin.PluginInstanceStatus
import com.cordiact.core.plugin.PluginToolContributions

/**
 * Publishes one validated declarative package through the ordinary plugin kernel. Tools and
 * commands are host executors over fixed text or a closed transform set; the package never runs
 * code. The plugin's scope is the workspace/session identity supplied by the host, and disabling
 * it withdraws both contribution kinds before cleanup.
 */
class DeclarativePackagePlugin(
    val manifest: ExtensionPackageManifest,
    kernel: PluginKernel,
    private val tools: PluginToolContributions,
    private val commands: PluginCommandContributions,
    grantedCapabilities: Set<ExtensionPackageCapability>,
    workspaceId: String? = null,
    sessionId: String? = null,
    private val admitted: () -> Boolean = { true },
) : RuntimeAwarePlugin {
    override val spec: PluginSpec = extensionPackageSpec(manifest)

    private val capabilityGrant = grantedCapabilities.toSet()
    private val instanceId = PluginInstanceId(spec.id, workspaceId, sessionId)
    private val bridge = ContextManagedPlugin(spec, kernel, object : ContextPlugin {
        override fun start(context: PluginContext) {
            val missing = manifest.capabilities - capabilityGrant
            require(missing.isEmpty()) {
                "Package capabilities were not granted: ${missing.joinToString { it.wireName }}"
            }
            tools.registerDeclarativePackage(context, manifest, admitted)
            manifest.commands.forEach { commands.registerDeclarativePackageCommand(context, commandDescriptor(it), admitted, it.text) }
        }
    }, instanceId)

    override fun onRegister() = bridge.onRegister()
    override fun onEnable() = bridge.onEnable()
    override fun onDisable() = bridge.onDisable()
    override fun onUninstall() = bridge.onUninstall()
    override fun runtimeStatus(): PluginInstanceStatus? = bridge.runtimeStatus()

    fun toolId(toolName: String): String = "extension.${spec.id}.$toolName"

    private fun commandDescriptor(command: ExtensionPackageCommand): CommandDescriptor = CommandDescriptor(
        id = "extension.${spec.id}.${command.name}",
        name = command.name,
        ownerPluginId = spec.id,
        aliases = command.aliases,
        allowsEmptyBody = true,
        executor = InteractionCommandExecutor { _, _, _, _ ->
            CommandExecutionResult(completed = true, message = command.text)
        },
    )
}
