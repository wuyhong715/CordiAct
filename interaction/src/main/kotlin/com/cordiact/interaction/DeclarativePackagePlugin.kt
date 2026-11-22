package com.cordiact.interaction

import com.cordiact.core.plugin.BOUNDED_TEXT_TRANSFORM_MAX_BYTES
import com.cordiact.core.plugin.ContextManagedPlugin
import com.cordiact.core.plugin.ContextPlugin
import com.cordiact.core.plugin.ExtensionPackageCapability
import com.cordiact.core.plugin.ExtensionPackageCommand
import com.cordiact.core.plugin.ExtensionPackageManifest
import com.cordiact.core.plugin.ExtensionPackageTool
import com.cordiact.core.plugin.PluginContext
import com.cordiact.core.plugin.PluginInstanceId
import com.cordiact.core.plugin.PluginKernel
import com.cordiact.core.plugin.PluginSpec
import com.cordiact.core.plugin.extensionPackageSpec
import com.cordiact.core.plugin.RuntimeAwarePlugin
import com.cordiact.core.plugin.PluginInstanceStatus
import com.cordiact.core.plugin.PluginToolContributions
import com.cordiact.core.plugin.applyBoundedTextTransform
import com.cordiact.core.tool.ToolDefinition
import com.cordiact.core.tool.ToolDescriptor
import com.cordiact.core.tool.ToolInputSchema
import com.cordiact.core.tool.ToolManagerException
import com.cordiact.core.tool.ToolParameter
import com.cordiact.core.tool.ToolResult
import com.cordiact.core.tool.ToolValue
import com.cordiact.core.tool.ToolValueType

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
    private val commandRegistry: InteractionCommandRegistry,
    private val grantedCapabilities: Set<ExtensionPackageCapability> = manifest.capabilities,
    workspaceId: String? = null,
    sessionId: String? = null,
) : RuntimeAwarePlugin {
    override val spec: PluginSpec = extensionPackageSpec(manifest)

    private val instanceId = PluginInstanceId(spec.id, workspaceId, sessionId)
    private val bridge = ContextManagedPlugin(spec, kernel, object : ContextPlugin {
        override fun start(context: PluginContext) {
            val missing = manifest.capabilities - grantedCapabilities
            require(missing.isEmpty()) {
                "Package capabilities were not granted: ${missing.joinToString { it.wireName }}"
            }
            manifest.tools.forEach { tools.register(context, toolDefinition(it)) }
            manifest.commands.forEach { commands.register(context, commandDescriptor(it)) }
            commandRegistry.setOwnerEnabled(spec.id, true)
            context.effect { commandRegistry.setOwnerEnabled(spec.id, false) }
        }
    }, instanceId)

    override fun onRegister() = bridge.onRegister()
    override fun onEnable() = bridge.onEnable()
    override fun onDisable() = bridge.onDisable()
    override fun onUninstall() = bridge.onUninstall()
    override fun runtimeStatus(): PluginInstanceStatus? = bridge.runtimeStatus()

    fun toolId(toolName: String): String = "extension.${spec.id}.$toolName"

    private fun toolDefinition(tool: ExtensionPackageTool): ToolDefinition {
        val id = toolId(tool.name)
        val operation = tool.operation
        return ToolDefinition(
            ToolDescriptor(
                id = id,
                name = tool.name,
                description = "${tool.description} Package ${manifest.id} ${manifest.version}.",
                inputSchema = if (operation == null) ToolInputSchema() else ToolInputSchema(listOf(
                    ToolParameter(
                        name = "text",
                        type = ToolValueType.STRING,
                        required = true,
                        description = "Literal text, at most $BOUNDED_TEXT_TRANSFORM_MAX_BYTES UTF-8 bytes.",
                        maxLength = BOUNDED_TEXT_TRANSFORM_MAX_BYTES,
                    ),
                )),
                ownerPluginId = spec.id,
                defaultEnabled = true,
                concurrencySafe = true,
            ),
        ) { _, arguments ->
            val result = if (operation == null) {
                tool.text
            } else {
                val input = (arguments.values["text"] as? ToolValue.StringValue)?.value
                    ?: throw ToolManagerException(
                        ToolManagerException.Code.INVALID_ARGUMENT,
                        "text is required and must be a string",
                    )
                applyBoundedTextTransform(operation, input)
            }
            ToolResult(mapOf(
                "text" to ToolValue.StringValue(result),
                "untrusted" to ToolValue.BooleanValue(true),
                "package" to ToolValue.StringValue("${manifest.id}@${manifest.version}"),
            ))
        }
    }

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
