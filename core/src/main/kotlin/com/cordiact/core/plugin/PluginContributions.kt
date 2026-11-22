package com.cordiact.core.plugin

import com.cordiact.core.tool.InMemoryToolDefinitionRegistry
import com.cordiact.core.tool.ToolContributionScope
import com.cordiact.core.tool.ToolDefinition

/**
 * Host adapter for trusted code. A contribution owns a publication, never execution permission.
 * The kernel makes registrations visible only when start succeeds and hides them before cleanup.
 */
class PluginToolContributions(private val registry: InMemoryToolDefinitionRegistry) {
    fun register(context: PluginContext, definition: ToolDefinition) {
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
        context.scope.own(registry.publish(definition, scope, context::isContributionActive))
    }
}
