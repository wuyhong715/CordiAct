package com.cordiact.interaction

import com.cordiact.core.plugin.PluginContext

/** Host-owned adapter. Never enables an owner: lifecycle and command preflight remain in charge. */
class PluginCommandContributions(private val registry: InteractionCommandRegistry) {
    @Synchronized fun register(context: PluginContext, descriptor: CommandDescriptor) {
        context.scope.requireOpen()
        require(context.id.workspaceId == null && context.id.sessionId == null) {
            "Workspace/session commands require a scoped command registry"
        }
        require(descriptor.ownerPluginId == context.id.pluginId) { "Command owner must match plugin instance" }
        val registration = registry.register(descriptor)
        context.effect { registry.unregisterRegistration(registration) }
    }
}
