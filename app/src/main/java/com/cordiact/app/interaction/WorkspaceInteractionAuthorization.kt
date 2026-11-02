package com.cordiact.app.interaction

import com.cordiact.core.conversation.SessionManagerService
import com.cordiact.core.workspace.WorkspaceManagerService
import com.cordiact.integration.interaction.AuthorizationDecision
import com.cordiact.integration.interaction.InteractionAuthorization
import com.cordiact.integration.interaction.InteractionContext
import com.cordiact.integration.interaction.InteractionCommandRegistry
import com.cordiact.integration.interaction.MentionDescriptor
import com.cordiact.integration.interaction.MentionType

/** Fail-closed authorization for the current persisted session and its exact Workspace references. */
class WorkspaceInteractionAuthorization(
    private val sessions: SessionManagerService,
    private val workspaces: WorkspaceManagerService,
    private val directory: AndroidInteractionDirectory,
    private val commands: InteractionCommandRegistry,
) : InteractionAuthorization {
    override fun authorize(
        context: InteractionContext,
        command: com.cordiact.integration.interaction.CommandDescriptor?,
        targets: List<MentionDescriptor>,
    ): AuthorizationDecision {
        val session = sessions.getSession(context.sessionId)
            ?: return AuthorizationDecision(false, reason = "Session no longer exists")
        if (session.workspaceId != context.workspaceId) {
            return AuthorizationDecision(false, reason = "Session is not bound to the selected Workspace")
        }
        val workspace = try {
            workspaces.resolveSessionWorkspace(context.workspaceId)
        } catch (_: Exception) {
            return AuthorizationDecision(false, reason = "Workspace is unavailable")
        }
        if (workspace.archivedAtMillis != null) {
            return AuthorizationDecision(false, reason = "Archived Workspaces cannot be used")
        }
        if (command != null && commands.find(command.name) != command) {
            return AuthorizationDecision(false, reason = "Command owner is disabled")
        }
        for (target in targets) {
            val crossWorkspace = target.workspaceId != workspace.id
            if (crossWorkspace) {
                // Cross-Workspace references are allowed only to a *scope node*, never to an
                // individual entity. Two-step access is what keeps each step auditable: the
                // user grants a range first, then reads inside it, and both show in the
                // transcript. A direct single-file grant would make the granted range
                // indistinguishable from the thing read.
                if (target.type != MentionType.WORKSPACE && target.type != MentionType.MEMORY) {
                    return AuthorizationDecision(false, reason = "Only a Workspace scope can be referenced across Workspaces")
                }
                val other = try {
                    workspaces.get(target.workspaceId)
                } catch (_: Exception) {
                    null
                }
                if (other == null) {
                    return AuthorizationDecision(false, reason = "Referenced Workspace no longer exists")
                }
                if (other.archivedAtMillis != null) {
                    return AuthorizationDecision(false, reason = "Archived Workspaces cannot be referenced")
                }
                // A memory scope must carry the prefix it grants; without one it would silently
                // widen to the whole Workspace.
                if (target.type == MentionType.MEMORY && target.authorizedPrefix == null) {
                    return AuthorizationDecision(false, reason = "Memory scope is missing its granted prefix")
                }
            }
            val current = try {
                directory.find(context, target.id)
            } catch (_: Exception) {
                null
            }
            if (current == null || current.type != target.type || current.relativePath != target.relativePath) {
                return AuthorizationDecision(false, reason = "Referenced entity is no longer available")
            }
            val supported = if (crossWorkspace) CROSS_WORKSPACE_CAPABILITIES else SUPPORTED_READ_CAPABILITIES
            if (target.requiredCapabilities.any { it !in supported }) {
                return AuthorizationDecision(false, reason = "Required capability is not granted")
            }
        }
        val required = command?.requiredCapabilities.orEmpty()
        if (required.any { it !in SUPPORTED_COMMAND_CAPABILITIES }) {
            return AuthorizationDecision(false, reason = "Command requires an unsupported capability")
        }
        return AuthorizationDecision(true)
    }

    private companion object {
        val SUPPORTED_READ_CAPABILITIES = setOf("workspace.read", "file.read", "file.list")

        /**
         * Capabilities a cross-Workspace scope may advertise.
         *
         * Separate from the intra-Workspace set so a future read capability cannot silently
         * become sufficient for crossing a Workspace boundary.
         */
        val CROSS_WORKSPACE_CAPABILITIES = setOf("workspace.memory.read.cross")
        val SUPPORTED_COMMAND_CAPABILITIES = setOf("interaction.execute")
    }
}
