package com.cordiact.app.interaction

import com.cordiact.core.conversation.SessionManagerService
import com.cordiact.core.workspace.WorkspaceManagerService
import com.cordiact.integration.interaction.AuthorizationDecision
import com.cordiact.integration.interaction.InteractionAuthorization
import com.cordiact.integration.interaction.InteractionContext
import com.cordiact.integration.interaction.InteractionCommandRegistry
import com.cordiact.integration.interaction.MentionDescriptor

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
            if (target.workspaceId != workspace.id) {
                return AuthorizationDecision(false, reason = "Reference is outside the current Workspace")
            }
            val current = try {
                directory.find(context, target.id)
            } catch (_: Exception) {
                null
            }
            if (current == null || current.type != target.type || current.relativePath != target.relativePath) {
                return AuthorizationDecision(false, reason = "Referenced entity is no longer available")
            }
            if (target.requiredCapabilities.any { it !in SUPPORTED_READ_CAPABILITIES }) {
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
        val SUPPORTED_COMMAND_CAPABILITIES = setOf("interaction.execute")
    }
}
