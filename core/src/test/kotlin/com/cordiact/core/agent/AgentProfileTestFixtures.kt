package com.cordiact.core.agent

import com.cordiact.core.workspace.Workspace
import com.cordiact.core.workspace.WorkspaceManagerService
import java.io.File

internal const val PROFILE_WORKSPACE = "ws-1"
internal const val PROFILE_OTHER_WORKSPACE = "ws-2"

/** In-memory profile store shared by the agent profile tests. */
internal class InMemoryProfileStore : AgentProfileStore {
    private val rows = mutableMapOf<Triple<String, String, Int>, AgentProfile>()

    override fun list(workspaceId: String) = rows.values.filter { it.workspaceId == workspaceId }

    override fun get(workspaceId: String, id: AgentProfileId, version: Int) =
        rows[Triple(workspaceId, id.value, version)]

    override fun latest(workspaceId: String, id: AgentProfileId) =
        rows.values.filter { it.workspaceId == workspaceId && it.id == id }.maxByOrNull { it.version }

    override fun insert(profile: AgentProfile): AgentProfile {
        rows[Triple(profile.workspaceId, profile.id.value, profile.version)] = profile
        return profile
    }

    override fun exists(workspaceId: String, id: AgentProfileId, version: Int) =
        rows.containsKey(Triple(workspaceId, id.value, version))
}

/** Workspace manager stand-in; [archived] flips the archived marker on every workspace. */
internal class FakeWorkspaceManager(private val archived: Boolean = false) : WorkspaceManagerService {

    private fun workspace(id: String) = Workspace(
        id = id,
        name = id,
        createdAtMillis = 0,
        updatedAtMillis = 0,
        archivedAtMillis = if (archived) 1 else null,
    )

    override fun initializeDefaultIfEligible() = workspace(PROFILE_WORKSPACE)
    override fun create(name: String) = workspace(PROFILE_WORKSPACE)
    override fun get(workspaceId: String) =
        if (workspaceId == PROFILE_WORKSPACE || workspaceId == PROFILE_OTHER_WORKSPACE) workspace(workspaceId) else null
    override fun list(includeArchived: Boolean) = listOf(workspace(PROFILE_WORKSPACE))
    override fun rename(workspaceId: String, name: String) = workspace(workspaceId)
    override fun archive(workspaceId: String) = workspace(workspaceId)
    override fun restore(workspaceId: String) = workspace(workspaceId)
    override fun select(workspaceId: String) = workspace(workspaceId)
    override fun activeWorkspace() = workspace(PROFILE_WORKSPACE)
    override fun resolveRoot(workspaceId: String): File = File(System.getProperty("java.io.tmpdir"), workspaceId)
    override fun resolveSessionWorkspace(workspaceId: String) = workspace(workspaceId)
    override fun findSessionWorkspace(workspaceId: String) = get(workspaceId)
}
