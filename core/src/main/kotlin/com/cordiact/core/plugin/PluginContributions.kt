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
import java.nio.ByteBuffer

/**
 * Host adapter for trusted code. A contribution owns a publication, never execution permission.
 * The kernel makes registrations visible only when start succeeds and hides them before cleanup.
 */
class PluginToolContributions(private val registry: InMemoryToolDefinitionRegistry) {
    fun register(context: PluginContext, definition: ToolDefinition) = register(context, definition, null) { true }

    /** The host constructs the executor; callers cannot attach PURE to an arbitrary definition. */
    fun registerDeclarativePackage(
        context: PluginContext,
        manifest: ExtensionPackageManifest,
        admitted: () -> Boolean = { true },
        scriptRuntime: PackageScriptRuntime? = null,
    ) {
        val spec = extensionPackageSpec(manifest)
        require(context.id.pluginId == spec.id) { "Package must match plugin instance" }
        require(manifest.tools.isEmpty() || ExtensionPackageCapability.TOOL_PUBLISH in manifest.capabilities) {
            "Package tools require tool.publish"
        }
        val scriptSession = if (manifest.tools.any { it.script != null }) {
            require(ExtensionPackageCapability.TOOL_SCRIPT_EXECUTE in manifest.capabilities)
            require(context.id.workspaceId != null && context.id.sessionId == null) { "Script tools require a workspace installation" }
            val runtime = checkNotNull(scriptRuntime) { "Isolated script runtime unavailable" }
            context.scope.own(runtime.open(context.id))
        } else null
        for (tool in manifest.tools) {
            val operation = tool.operation
            val script = tool.script?.toByteArray(Charsets.UTF_8)
            val definition = ToolDefinition(
                ToolDescriptor(
                    id = "extension.${spec.id}.${tool.name}",
                    name = tool.name,
                    description = "${tool.description} Package ${manifest.id} ${manifest.version}.",
                    inputSchema = if (operation == null && script == null) ToolInputSchema() else ToolInputSchema(listOf(
                        ToolParameter(
                            name = "text", type = ToolValueType.STRING, required = true,
                            description = "Literal text, at most $BOUNDED_TEXT_TRANSFORM_MAX_BYTES UTF-8 bytes.",
                            maxLength = BOUNDED_TEXT_TRANSFORM_MAX_BYTES,
                        ),
                    )),
                    ownerPluginId = spec.id,
                    defaultEnabled = true,
                    concurrencySafe = script == null,
                ),
            ) { _, arguments ->
                val result = if (operation == null && script == null) tool.text else {
                    val input = (arguments.values["text"] as? ToolValue.StringValue)?.value
                        ?: throw ToolManagerException(
                            ToolManagerException.Code.INVALID_ARGUMENT, "text is required and must be a string",
                        )
                    if (script == null) applyBoundedTextTransform(requireNotNull(operation), input)
                    else {
                        check(context.isContributionActive() && admitted()) { "Script tool was withdrawn" }
                        val output = checkNotNull(scriptSession).evaluate(script.copyOf(), encodeScriptInput(input))
                        check(context.isContributionActive() && admitted()) { "Script tool was withdrawn" }
                        check(output.size <= PACKAGE_SCRIPT_MAX_TEXT_BYTES) { "Script output exceeds the byte limit" }
                        Charsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(output)).toString()
                    }
                }
                ToolResult(mapOf(
                    "text" to ToolValue.StringValue(result),
                    "untrusted" to ToolValue.BooleanValue(true),
                    "package" to ToolValue.StringValue("${manifest.id}@${manifest.version}"),
                ))
            }
            val hasCard = manifest.client?.conversation?.toolResults.orEmpty().any { it.selector == tool.name }
            register(context, definition, ToolEffectClass.PURE, manifest.sha256.takeIf { hasCard }, admitted)
        }
    }

    private fun register(context: PluginContext, definition: ToolDefinition, hostEffect: ToolEffectClass?,
        packageSha256: String? = null, admitted: () -> Boolean) {
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
        context.scope.own(registry.publish(definition, scope, hostEffect, packageSha256) { context.isContributionActive() && admitted() })
    }
}
