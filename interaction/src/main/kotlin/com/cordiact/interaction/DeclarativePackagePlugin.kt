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
import com.cordiact.core.plugin.PackageScriptRuntime
import com.cordiact.core.plugin.PackageAgentContributions
import com.cordiact.core.plugin.PackageSearchContributions
import com.cordiact.core.plugin.PackageModelContributions
import com.cordiact.core.plugin.PackageToolPolicies
import com.cordiact.core.plugin.PackageTurnPolicies
import com.cordiact.core.plugin.PackageWorkflows

/**
 * Publishes a validated package through the ordinary plugin kernel. Script evaluation uses the
 * host-provided isolated runtime and shares the installation's revocable scope.
 */
class DeclarativePackagePlugin(
    val manifest: ExtensionPackageManifest,
    kernel: PluginKernel,
    private val tools: PluginToolContributions,
    private val commands: PluginCommandContributions,
    grantedCapabilities: Set<ExtensionPackageCapability>,
    workspaceId: String? = null,
    sessionId: String? = null,
    private val clientContributions: WorkspaceClientContributions? = null,
    private val admitted: () -> Boolean = { true },
    private val scriptRuntime: PackageScriptRuntime? = null,
    private val agentContributions: PackageAgentContributions? = null,
    private val searchContributions: PackageSearchContributions? = null,
    private val modelContributions: PackageModelContributions? = null,
    private val retireModelProvider: (PackageModelContributions.Offer) -> Unit = {},
    private val toolPolicies: PackageToolPolicies? = null,
    private val workflows: PackageWorkflows? = null,
    private val turnPolicies: PackageTurnPolicies? = null,
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
            tools.registerDeclarativePackage(context, manifest, admitted, scriptRuntime)
            val commandPublications = manifest.commands.associate { command ->
                command.name to commands.registerDeclarativePackageCommand(context, commandDescriptor(command), admitted, command.text)
            }
            if (manifest.client != null) {
                checkNotNull(clientContributions) { "Host does not provide declarative client slots" }
                    .register(context, manifest, commandPublications, admitted)
            }
            if (!manifest.agent?.skills.isNullOrEmpty() || manifest.agent?.context != null || !manifest.agent?.subagents.isNullOrEmpty()) {
                checkNotNull(agentContributions) { "Host does not provide Agent contributions" }.register(context, manifest, admitted)
            }
            if (!manifest.agent?.beforeTool.isNullOrEmpty()) {
                checkNotNull(toolPolicies) { "Host does not provide package tool policies" }.register(
                    context, manifest, checkNotNull(scriptRuntime) { "Isolated script runtime unavailable" }, admitted,
                )
            }
            if (manifest.agent?.beforeTurn != null) {
                checkNotNull(turnPolicies) { "Host does not provide package turn policies" }.register(
                    context, manifest, checkNotNull(scriptRuntime) { "Isolated script runtime unavailable" }, admitted,
                )
            }
            if (manifest.searchProviders.isNotEmpty()) {
                checkNotNull(searchContributions) { "Host does not provide search contributions" }.register(
                    context, manifest, checkNotNull(scriptRuntime) { "Isolated script runtime unavailable" }, admitted,
                )
            }
            if (manifest.modelProviders.isNotEmpty()) {
                checkNotNull(modelContributions) { "Host does not provide model declarations" }
                    .register(context, manifest, admitted, retireModelProvider)
            }
            if (manifest.workflows.isNotEmpty()) {
                checkNotNull(workflows) { "Host does not provide package workflows" }.register(
                    context, manifest, checkNotNull(scriptRuntime) { "Isolated script runtime unavailable" }, admitted,
                )
            }
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
