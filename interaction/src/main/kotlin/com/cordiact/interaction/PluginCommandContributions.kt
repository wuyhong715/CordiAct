package com.cordiact.interaction

import com.cordiact.core.plugin.PluginContext

/** Trusted-code adapter. Publication follows the kernel; host owner and dispatch gates still apply. */
class PluginCommandContributions(private val registry: InteractionCommandRegistry) {
    fun register(context: PluginContext, descriptor: CommandDescriptor) {
        context.scope.requireOpen()
        require(descriptor.ownerPluginId == context.id.pluginId) { "Command owner must match plugin instance" }
        if (context.id.workspaceId != null) {
            val prefix = "extension.${context.id.pluginId}."
            require(descriptor.id.startsWith(prefix) && descriptor.id.length > prefix.length) {
                "Scoped command id must start with $prefix and include a local id"
            }
            require((descriptor.aliases + descriptor.name).all { name -> name.none { it.isWhitespace() || it in "/:：" } }) {
                "Scoped command names and aliases must be single tokens"
            }
        }
        context.scope.own(registry.publish(context, descriptor))
    }
}
