package com.cordiact.interaction

import com.cordiact.core.plugin.PluginContext
import java.util.Collections

/** Host-owned command directory. Aliases never shadow another registration in the same view. */
class InteractionCommandRegistry(
    // The caller supplies a persisted, usable session-to-workspace check; no check means global only.
    private val isLiveContext: (InteractionContext) -> Boolean = { false },
) {
    private class Registration(
        val descriptor: CommandDescriptor,
        val publisher: PluginContext?,
        val requiresOwnerSwitch: Boolean,
        val admitted: () -> Boolean,
        val localText: String?,
    ) {
        private val workspaceId = publisher?.id?.workspaceId
        private val sessionId = publisher?.id?.sessionId
        val isGlobal = workspaceId == null
        val names = descriptor.aliases + descriptor.name

        fun overlaps(other: Registration): Boolean = isGlobal || other.isGlobal ||
            (workspaceId == other.workspaceId && (sessionId == null || other.sessionId == null || sessionId == other.sessionId))

        fun belongsTo(context: InteractionContext?): Boolean = isGlobal ||
            (context != null && workspaceId == context.workspaceId && (sessionId == null || sessionId == context.sessionId))
    }

    private val registrations = mutableListOf<Registration>()
    private val enabledOwners = mutableSetOf<String>()

    /** Legacy built-ins remain global and use the existing owner enable switch. */
    fun register(descriptor: CommandDescriptor): CommandDescriptor = add(descriptor, null, requiresOwnerSwitch = true, admitted = { true }, localText = null)

    internal class Publication(val descriptor: CommandDescriptor, private val remove: () -> Unit) : AutoCloseable {
        override fun close() = remove()
    }

    internal fun publish(
        context: PluginContext, descriptor: CommandDescriptor, requiresOwnerSwitch: Boolean, admitted: () -> Boolean,
        localText: String?,
    ): Publication {
        val registration = add(descriptor, context, requiresOwnerSwitch, admitted, localText)
        return Publication(registration) { unregisterRegistration(registration) }
    }

    @Synchronized
    private fun add(
        descriptor: CommandDescriptor, publisher: PluginContext?, requiresOwnerSwitch: Boolean, admitted: () -> Boolean,
        localText: String?,
    ): CommandDescriptor {
        val snapshot = descriptor.copy(
            aliases = Collections.unmodifiableSet(descriptor.aliases.toSet()),
            allowedMentionTypes = Collections.unmodifiableSet(descriptor.allowedMentionTypes.toSet()),
            requiredCapabilities = Collections.unmodifiableSet(descriptor.requiredCapabilities.toSet()),
        )
        val registration = Registration(snapshot, publisher, requiresOwnerSwitch, admitted, localText)
        require(registration.names.size == snapshot.aliases.size + 1) { "Command name duplicates an alias" }
        for (existing in registrations) {
            if (!existing.overlaps(registration)) continue
            require(existing.descriptor.id != snapshot.id) { "Command id is already registered in an overlapping scope" }
            require(existing.names.none { it in registration.names }) { "Command name is already registered in an overlapping scope" }
        }
        registrations.add(registration)
        return snapshot
    }

    /** Remove only this exact registration, never another generation with the same descriptor. */
    @Synchronized
    fun unregisterRegistration(registration: CommandDescriptor): Boolean =
        registrations.removeAll { it.descriptor === registration }

    /** Context-free ID management cannot remove private registrations with the same ID. */
    @Synchronized
    fun unregister(id: String): Boolean = registrations.removeAll { it.isGlobal && it.descriptor.id == id }

    @Synchronized
    fun unregisterOwner(ownerPluginId: String) {
        registrations.removeAll { it.isGlobal && it.descriptor.ownerPluginId == ownerPluginId }
        enabledOwners.remove(ownerPluginId)
    }

    /** An independent host gate; enabling an owner never revives an unpublished generation. */
    @Synchronized
    fun setOwnerEnabled(ownerPluginId: String, enabled: Boolean) {
        if (enabled) enabledOwners.add(ownerPluginId) else enabledOwners.remove(ownerPluginId)
    }

    fun find(name: String, context: InteractionContext? = null): CommandDescriptor? =
        visible(context, name).singleOrNull()

    /** Text from the exact host-owned registration, never from a descriptor supplied by a caller. */
    internal fun localText(command: CommandDescriptor, context: InteractionContext): String? =
        visibleRegistrations(context, command.name).singleOrNull { it.descriptor === command }?.localText

    /** Session-action insertion must not resolve a same-name replacement, alias, or model command. */
    internal fun isCurrentLocalPrimary(
        command: CommandDescriptor, context: InteractionContext, publisher: PluginContext,
    ): Boolean = visibleRegistrations(context, command.name).singleOrNull()?.let {
        it.publisher === publisher && it.descriptor === command && it.localText != null
    } == true

    /** Main-thread half of an already checked session action; does not perform a storage read. */
    @Synchronized
    internal fun remainsPublishedLocalPrimary(command: CommandDescriptor, publisher: PluginContext): Boolean =
        publisher.isContributionActive() && registrations.any {
            it.publisher === publisher && it.descriptor === command && it.localText != null && it.admitted()
        }

    fun candidates(context: InteractionContext? = null): List<CommandDescriptor> =
        visible(context).sortedBy { it.name }

    private fun visible(context: InteractionContext?, name: String? = null): List<CommandDescriptor> =
        visibleRegistrations(context, name).map { it.descriptor }

    private fun visibleRegistrations(context: InteractionContext?, name: String?): List<Registration> {
        // Do not read host storage to resolve a global-only view. Never hold the registry lock
        // across the persisted-session check, and sample publication after it returns.
        val needsScopedContext = context != null && synchronized(this) {
            registrations.any { !it.isGlobal && it.belongsTo(context) && (name == null || name in it.names) }
        }
        val scopedContext = if (needsScopedContext) context.takeIf(isLiveContext) else null
        return synchronized(this) {
            // Sample each publisher once so its whole group is present or absent in a single view.
            val publication = mutableMapOf<PluginContext, Boolean>()
            registrations.asSequence().filter { registration ->
                (name == null || name in registration.names) &&
                    (!registration.requiresOwnerSwitch || registration.descriptor.ownerPluginId in enabledOwners) &&
                    registration.belongsTo(scopedContext) &&
                    (registration.publisher?.let { publisher ->
                         publication.getOrPut(publisher) { publisher.isContributionActive() && registration.admitted() }
                     } ?: true)
            }.toList()
        }
    }
}
