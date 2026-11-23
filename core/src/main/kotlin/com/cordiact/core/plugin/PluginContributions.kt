package com.cordiact.core.plugin

import com.cordiact.core.security.ToolEffectClass
import com.cordiact.core.tool.InMemoryToolDefinitionRegistry
import com.cordiact.core.tool.ToolContributionScope
import com.cordiact.core.tool.ToolDefinition
import com.cordiact.core.tool.ToolDescriptor
import com.cordiact.core.tool.ToolInputSchema
import com.cordiact.core.tool.ToolManagerException
import com.cordiact.core.tool.ToolParameter
import com.cordiact.core.tool.ToolResult
import com.cordiact.core.tool.ToolValue
import com.cordiact.core.tool.ToolValueType

/**
 * Host adapter for trusted code. A contribution owns a publication, never execution permission.
 * The kernel makes registrations visible only when start succeeds and hides them before cleanup.
 */
class PluginToolContributions(private val registry: InMemoryToolDefinitionRegistry) {
    fun register(context: PluginContext, definition: ToolDefinition) = register(context, definition, null) { true }

    /** The host constructs the executor; callers cannot attach PURE to an arbitrary definition. */
    fun registerDeclarativePackage(context: PluginContext, manifest: ExtensionPackageManifest, admitted: () -> Boolean = { true }) {
        val spec = extensionPackageSpec(manifest)
        require(context.id.pluginId == spec.id) { "Package must match plugin instance" }
        require(manifest.tools.isEmpty() || ExtensionPackageCapability.TOOL_PUBLISH in manifest.capabilities) {
            "Package tools require tool.publish"
        }
        for (tool in manifest.tools) {
            val operation = tool.operation
            val definition = ToolDefinition(
                ToolDescriptor(
                    id = "extension.${spec.id}.${tool.name}",
                    name = tool.name,
                    description = "${tool.description} Package ${manifest.id} ${manifest.version}.",
                    inputSchema = if (operation == null) ToolInputSchema() else ToolInputSchema(listOf(
                        ToolParameter(
                            name = "text", type = ToolValueType.STRING, required = true,
                            description = "Literal text, at most $BOUNDED_TEXT_TRANSFORM_MAX_BYTES UTF-8 bytes.",
                            maxLength = BOUNDED_TEXT_TRANSFORM_MAX_BYTES,
                        ),
                    )),
                    ownerPluginId = spec.id,
                    defaultEnabled = true,
                    concurrencySafe = true,
                ),
            ) { _, arguments ->
                val result = if (operation == null) tool.text else {
                    val input = (arguments.values["text"] as? ToolValue.StringValue)?.value
                        ?: throw ToolManagerException(
                            ToolManagerException.Code.INVALID_ARGUMENT, "text is required and must be a string",
                        )
                    applyBoundedTextTransform(operation, input)
                }
                ToolResult(mapOf(
                    "text" to ToolValue.StringValue(result),
                    "untrusted" to ToolValue.BooleanValue(true),
                    "package" to ToolValue.StringValue("${manifest.id}@${manifest.version}"),
                ))
            }
            register(context, definition, ToolEffectClass.PURE, admitted)
        }
    }

    private fun register(context: PluginContext, definition: ToolDefinition, hostEffect: ToolEffectClass?, admitted: () -> Boolean) {
        context.scope.requireOpen()
        val instance = context.id
        val descriptor = definition.descriptor
        require(descriptor.ownerPluginId == instance.pluginId) { "Tool owner must match plugin instance" }
        if (instance.workspaceId != null) {
            // Scoped extensions cannot masquerade as a host builtin (including inactive builtins).
            require(descriptor.id.startsWith("extension.${instance.pluginId}.")) {
                "Scoped tool id must start with extension.${instance.pluginId}."
            }
        }
        val scope = ToolContributionScope(instance.workspaceId, instance.sessionId)
        context.scope.own(registry.publish(definition, scope, hostEffect) { context.isContributionActive() && admitted() })
    }
}
