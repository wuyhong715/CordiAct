package com.cordiact.interaction

import com.cordiact.core.plugin.ExtensionPackageManifest
import com.cordiact.core.plugin.ExtensionPackageMessageEvent
import com.cordiact.core.conversation.ToolHistoryFormat
import com.cordiact.core.conversation.ToolHistoryProvenance
import com.cordiact.core.conversation.ToolHistorySource
import com.cordiact.core.plugin.ExtensionPackageSessionAction
import com.cordiact.core.plugin.ExtensionPackageSettingsPage
import com.cordiact.core.plugin.PluginContext
import com.cordiact.core.plugin.extensionPackageSpec
import java.util.Collections
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * App-owned directory for declarative client slots. Registration grants no write,
 * navigation, send, or execution authority. The UI must use an offer tied to the session it showed.
 */
class WorkspaceClientContributions(
    private val commands: InteractionCommandRegistry,
    private val publishPanel: ((PluginContext, ExtensionPackageSettingsPage, () -> Boolean) -> Unit)? = null,
) {
    class SettingsPage internal constructor(
        val ownerPluginId: String,
        val description: ExtensionPackageSettingsPage,
    )

    class SessionAction internal constructor(
        val ownerPluginId: String,
        val description: ExtensionPackageSessionAction,
    )

    class SessionActionOffer internal constructor(
        val context: InteractionContext,
        val action: SessionAction,
    )

    data class MessageNode(val source: String, val card: ToolResultCard)

    class Snapshot internal constructor(private val groups: List<Group>) {
        fun settingsPages(workspaceId: String): List<SettingsPage> = Collections.unmodifiableList(
            groups.asSequence().filter { it.workspaceId == workspaceId && it.active() }.mapNotNull { it.settings }.toList(),
        )

        fun sessionActions(context: InteractionContext): List<SessionActionOffer> = Collections.unmodifiableList(
            groups.asSequence().filter { it.workspaceId == context.workspaceId && it.active() }
                .flatMap { it.actions.asSequence() }.map { SessionActionOffer(context, it) }.toList(),
        )

        fun messageNodes(workspaceId: String, event: ExtensionPackageMessageEvent): List<MessageNode> =
            groups.asSequence().filter { it.workspaceId == workspaceId && it.active() }
                .mapNotNull { it.messageNodes[event] }.toList()

        fun toolResultCard(workspaceId: String, trace: ToolHistoryProvenance): ToolResultCard? =
            groups.asSequence().filter { it.workspaceId == workspaceId && it.active() }
                .firstNotNullOfOrNull { it.toolResultCards[trace] }
    }

    internal class Group(
        val workspaceId: String,
        val settings: SettingsPage?,
        val actions: List<SessionAction>,
        val messageNodes: Map<ExtensionPackageMessageEvent, MessageNode>,
        val toolResultCards: Map<ToolHistoryProvenance, ToolResultCard>,
        val active: () -> Boolean,
    )

    private class Registration(
        val publisher: PluginContext,
        val admitted: () -> Boolean,
        val group: Group,
        val actionCommands: Map<SessionAction, CommandDescriptor>,
    ) {
        fun isActive() = publisher.isContributionActive() && admitted()
    }

    private val lock = Any()
    private val registrations = mutableMapOf<com.cordiact.core.plugin.PluginInstanceId, Registration>()
    private val snapshots = MutableStateFlow(Snapshot(emptyList()))
    val snapshot = snapshots.asStateFlow()

    internal fun register(
        context: PluginContext, manifest: ExtensionPackageManifest,
        commandPublications: Map<String, CommandDescriptor>, admitted: () -> Boolean,
    ) {
        val client = manifest.client ?: return
        context.scope.requireOpen()
        val instance = context.id
        val workspace = requireNotNull(instance.workspaceId) { "Client contributions require a workspace" }
        require(instance.sessionId == null && instance.pluginId == extensionPackageSpec(manifest).id) {
            "Client contributions must belong to this installed workspace package"
        }
        require(client.sessionActions.map { it.id }.toSet().size == client.sessionActions.size)
        val actions = client.sessionActions.sortedBy { it.id }.map { SessionAction(instance.pluginId, it) }
        val actionCommands = actions.associateWith { action ->
            requireNotNull(commandPublications[action.description.command]).also {
                require(it.name == action.description.command && it.ownerPluginId == instance.pluginId)
            }
        }
        val registration = Registration(context, admitted, Group(
            workspaceId = workspace,
            settings = client.settingsPage?.let {
                SettingsPage(instance.pluginId, it.copy(rows = Collections.unmodifiableList(it.rows.toList())))
            },
            actions = actions,
            messageNodes = client.conversation?.messages.orEmpty().associate { view ->
                view.selector to MessageNode("${manifest.id}@${manifest.version}", ToolResultCard(view.title, view.lines))
            },
            toolResultCards = client.conversation?.toolResults.orEmpty().associate { view ->
                require(manifest.tools.any { it.name == view.selector }) { "Tool card must belong to its own package" }
                ToolHistoryProvenance(instance.pluginId, "extension.${instance.pluginId}.${view.selector}",
                    ToolHistorySource.HOST_REGISTRATION, ToolHistoryFormat.BOUNDED_SUMMARY_V1, manifest.sha256) to ToolResultCard(view.title, view.lines)
            },
            active = { context.isContributionActive() && admitted() },
        ), actionCommands)
        synchronized(lock) {
            check(instance !in registrations) { "Client contributions already registered: $instance" }
            require(registration.group.messageNodes.keys.all { event ->
                registrations.values.count { it.group.workspaceId == workspace && event in it.group.messageNodes } < 4
            }) { "Too many package nodes for the same message event" }
            registrations[instance] = registration
        }
        context.effect {
            synchronized(lock) { registrations.remove(instance, registration) }
            refreshAdmission()
        }
        client.workbenchPanel?.let { panel ->
            checkNotNull(publishPanel) { "Host does not provide declarative workbench panels" }(context, panel, admitted)
        }
    }

    /** Called by the workspace runtime at admission edges, before any stop disposer can block. */
    fun refreshAdmission() = synchronized(lock) {
        snapshots.value = Snapshot(registrations.values.asSequence().filter(Registration::isActive)
            .sortedWith(compareBy({ it.group.workspaceId }, { it.publisher.id.pluginId }))
            .map { it.group }.toList())
    }

    /** A fast final recheck after [draftInsertion] returned from IO. No workspace or session reads. */
    fun isInsertionCurrent(offer: SessionActionOffer): Boolean {
        val (registration, command) = binding(offer) ?: return false
        return commands.remainsPublishedLocalPrimary(command, registration.publisher) && registration.isActive()
    }

    private fun binding(offer: SessionActionOffer): Pair<Registration, CommandDescriptor>? = synchronized(lock) {
        val instance = com.cordiact.core.plugin.PluginInstanceId(offer.action.ownerPluginId, offer.context.workspaceId)
        val registration = registrations[instance]?.takeIf { it.isActive() } ?: return@synchronized null
        registration.actionCommands[offer.action]?.let { registration to it }
    }

    /** Return only the exact bare command for an unchanged, empty draft. Never dispatch it. */
    fun draftInsertion(
        offer: SessionActionOffer,
        currentContext: InteractionContext,
        draft: String,
        hasAttachments: Boolean,
    ): String? {
        if (offer.context != currentContext || draft.isNotEmpty() || hasAttachments) return null
        val (registration, command) = binding(offer) ?: return null
        if (!commands.isCurrentLocalPrimary(command, currentContext, registration.publisher)) return null
        return "/${command.name}".takeIf { registration.isActive() }
    }
}
