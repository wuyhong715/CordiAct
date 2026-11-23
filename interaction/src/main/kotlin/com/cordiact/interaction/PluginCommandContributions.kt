package com.cordiact.interaction

import com.cordiact.core.plugin.PluginContext

/** Trusted-code adapter. Publication follows the kernel; host owner and dispatch gates still apply. */
class PluginCommandContributions(private val registry: InteractionCommandRegistry) {
    fun register(context: PluginContext, descriptor: CommandDescriptor) =
        register(context, descriptor, requiresOwnerSwitch = true, admitted = { true }, localText = null)

    /** The host already checked the package grant; its instance owns command visibility. */
    internal fun registerDeclarativePackageCommand(context: PluginContext, descriptor: CommandDescriptor, admitted: () -> Boolean, text: String) =
        register(context, descriptor, requiresOwnerSwitch = false, admitted = admitted, localText = text)

    private fun register(context: PluginContext, descriptor: CommandDescriptor, requiresOwnerSwitch: Boolean, admitted: () -> Boolean, localText: String?) {
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
        context.scope.own(registry.publish(context, descriptor, requiresOwnerSwitch, admitted, localText))
    }
}
