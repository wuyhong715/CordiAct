package com.cordiact.core.workspace

import com.cordiact.core.plugin.ManagedPlugin
import com.cordiact.core.plugin.PluginSpec
import com.cordiact.core.plugin.PluginTarget
import java.io.File
import java.util.UUID

const val WORKSPACE_MANAGER_PLUGIN_ID = "core.workspace-manager"
const val WORKSPACE_PLUGIN_VERSION = "0.1.0"
const val DEFAULT_WORKSPACE_ID = "workspace.default"
const val DEFAULT_WORKSPACE_NAME = "默认工作区"

/** Workspace is the owner of local sessions, files, and agent configuration. */
data class Workspace(
    val id: String,
    val name: String,
    val createdAtMillis: Long,
    val updatedAtMillis: Long,
    val archivedAtMillis: Long? = null,
)

/** Durable workspace port, implemented by the same database as conversation sessions. */
fun interface WorkspaceSessionResolver {
    fun findSessionWorkspace(workspaceId: String): Workspace?
}

interface WorkspaceStore {
    fun ensureDefault(nowMillis: Long): Workspace
    fun createWorkspace(id: String, name: String, rootRelativePath: String, nowMillis: Long): Workspace
    fun getWorkspace(id: String): Workspace?
    fun listWorkspaces(includeArchived: Boolean): List<Workspace>
    fun renameWorkspace(id: String, name: String, nowMillis: Long): Workspace
    fun archiveWorkspace(id: String, nowMillis: Long): Workspace
    fun restoreWorkspace(id: String, nowMillis: Long): Workspace
    fun selectWorkspace(id: String, nowMillis: Long): Workspace
    fun activeWorkspace(): Workspace?
    fun rootRelativePath(id: String): String?
    fun findSessionWorkspace(id: String): Workspace?
}

interface WorkspaceManagerService : WorkspaceSessionResolver {
    fun initializeDefaultIfEligible(): Workspace
    fun create(name: String): Workspace
    fun get(workspaceId: String): Workspace?
    fun list(includeArchived: Boolean = false): List<Workspace>
    fun rename(workspaceId: String, name: String): Workspace
    fun archive(workspaceId: String): Workspace
    fun restore(workspaceId: String): Workspace
    fun select(workspaceId: String): Workspace
    fun activeWorkspace(): Workspace
    fun resolveRoot(workspaceId: String): File
    fun resolveSessionWorkspace(workspaceId: String): Workspace
}

fun workspaceManagerPluginSpec() = PluginSpec(
    id = WORKSPACE_MANAGER_PLUGIN_ID,
    version = WORKSPACE_PLUGIN_VERSION,
    target = PluginTarget.ANDROID_NATIVE,
)

/** Protected bundled system plugin. It owns workspace identity, active selection, and private roots. */
class WorkspaceManagerPlugin(
    private val store: WorkspaceStore,
    private val privateRoot: File,
    private val clockMillis: () -> Long = System::currentTimeMillis,
    private val idFactory: () -> String = { UUID.randomUUID().toString() },
) : ManagedPlugin, WorkspaceManagerService {
    override val spec = workspaceManagerPluginSpec()
    @Volatile private var enabled = false

    override fun onRegister() { ensureDefaultRoot() }
    override fun onEnable() { ensureDefaultRoot(); enabled = true }
    override fun onDisable() { enabled = false }
    override fun onUninstall() { enabled = false }

    override fun initializeDefaultIfEligible(): Workspace { requireEnabled(); return ensureDefaultRoot() }

    override fun create(name: String): Workspace {
        requireEnabled()
        val normalized = validateName(name)
        val id = idFactory().also {
            require(it.isNotBlank() && it != DEFAULT_WORKSPACE_ID) { "Workspace id factory returned a reserved or blank id" }
        }
        val relative = workspaceRelativePath(id)
        val root = resolveContainedPath(relative)
        check(root.isDirectory || root.mkdirs()) { "Could not create workspace storage" }
        return try {
            store.createWorkspace(id, normalized, relative, clockMillis())
        } catch (failure: Exception) {
            root.delete()
            throw failure
        }
    }

    override fun get(workspaceId: String): Workspace? { requireEnabled(); return store.getWorkspace(requireId(workspaceId)) }
    override fun list(includeArchived: Boolean): List<Workspace> { requireEnabled(); return store.listWorkspaces(includeArchived) }

    override fun rename(workspaceId: String, name: String): Workspace {
        requireEnabled()
        val id = requireId(workspaceId)
        require(id != DEFAULT_WORKSPACE_ID) { "Default workspace cannot be renamed" }
        return store.renameWorkspace(id, validateName(name), clockMillis())
    }

    override fun archive(workspaceId: String): Workspace {
        requireEnabled()
        val id = requireId(workspaceId)
        require(id != DEFAULT_WORKSPACE_ID) { "Default workspace cannot be archived" }
        return store.archiveWorkspace(id, clockMillis())
    }

    override fun restore(workspaceId: String): Workspace { requireEnabled(); return store.restoreWorkspace(requireId(workspaceId), clockMillis()) }

    override fun select(workspaceId: String): Workspace {
        requireEnabled()
        val selected = store.selectWorkspace(requireId(workspaceId), clockMillis())
        ensureRoot(selected)
        return selected
    }

    override fun activeWorkspace(): Workspace {
        requireEnabled()
        return (store.activeWorkspace()?.takeIf { it.archivedAtMillis == null } ?: ensureDefaultRoot()).also(::ensureRoot)
    }

    override fun findSessionWorkspace(workspaceId: String): Workspace? {
        requireEnabled()
        return store.findSessionWorkspace(requireId(workspaceId))
    }

    override fun resolveSessionWorkspace(workspaceId: String): Workspace {
        requireEnabled()
        return findSessionWorkspace(workspaceId)
            ?: throw IllegalArgumentException("Workspace does not exist or is archived")
    }

    override fun resolveRoot(workspaceId: String): File {
        val workspace = resolveSessionWorkspace(workspaceId)
        return ensureRoot(workspace)
    }

    private fun ensureDefaultRoot(): Workspace = store.ensureDefault(clockMillis()).also(::ensureRoot)

    private fun ensureRoot(workspace: Workspace): File {
        val relative = requireNotNull(store.rootRelativePath(workspace.id)) { "Workspace has no storage root" }
        val root = resolveContainedPath(relative)
        check(root.isDirectory || root.mkdirs()) { "Could not create workspace storage" }
        return root
    }

    private fun resolveContainedPath(relative: String): File {
        val base = privateRoot.canonicalFile
        val root = File(base, relative).canonicalFile
        require(root.toPath().startsWith(base.toPath()) && root != base) { "Workspace root escapes private storage" }
        return root
    }

    private fun workspaceRelativePath(id: String): String {
        requireId(id)
        return "workspaces/${UUID.nameUUIDFromBytes(id.toByteArray())}"
    }

    private fun validateName(name: String): String = name.trim().take(80).also {
        require(it.isNotBlank()) { "Workspace name must not be blank" }
    }

    private fun requireId(id: String): String = id.trim().also {
        require(it.isNotEmpty()) { "Workspace id must not be blank" }
    }

    private fun requireEnabled() = check(enabled) { "Workspace Manager plugin is not enabled" }
}
