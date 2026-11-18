package com.cordiact.core.plugin

import com.cordiact.core.tool.ToolDefinition
import com.cordiact.core.tool.ToolDefinitionRegistry

/**
 * Trusted host adapter. Registration is not execution permission: existing ToolManager
 * visibility, workspace grants and approval checks remain authoritative.
 *
 * The current registry is global. Until it supports per-instance namespaces, only a global
 * instance can contribute tools; silently exposing workspace-local tools globally is forbidden.
 */
class PluginToolContributions(private val registry: ToolDefinitionRegistry) {
    @Synchronized fun register(context: PluginContext, definition: ToolDefinition) {
        context.scope.requireOpen()
        require(context.id.workspaceId == null && context.id.sessionId == null) {
            "Workspace/session tool contributions require a scoped tool registry"
        }
        require(definition.descriptor.ownerPluginId == context.id.pluginId) { "Tool owner must match plugin instance" }
        val id = definition.descriptor.id
        // A unique forwarding executor is the generation token, even when the plugin reuses
        // a singleton/stateless executor across registrations. Preserve the normal tool pipeline.
        val token = com.cordiact.core.tool.ToolExecutor { executionContext, arguments ->
            definition.executor.invoke(executionContext, arguments)
        }
        registry.register(definition.copy(executor = token))
        context.effect {
            synchronized(registry) {
                if (registry.get(id)?.executor === token) registry.unregister(id)
            }
        }
    }
}
