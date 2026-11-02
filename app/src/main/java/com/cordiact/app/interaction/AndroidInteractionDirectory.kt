package com.cordiact.app.interaction

import android.content.Context
import com.cordiact.core.conversation.SessionManagerService
import com.cordiact.core.file.WorkspaceFileEntry
import com.cordiact.integration.interaction.CrossWorkspaceAccess
import com.cordiact.integration.interaction.CrossWorkspaceGrant
import com.cordiact.integration.interaction.ScopePathGuard
import com.cordiact.core.file.WorkspaceFileService
import com.cordiact.core.workspace.WorkspaceManagerService
import com.cordiact.integration.interaction.InteractionContext
import com.cordiact.integration.interaction.MentionDescriptor
import com.cordiact.integration.interaction.MentionDirectory
import com.cordiact.integration.interaction.MentionType
import com.cordiact.integration.interaction.MutableFillValueStore
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.Base64

/** Exposes only the selected persisted Workspace and its Workspace File Manager entries. */
class AndroidInteractionDirectory(
    private val workspaces: WorkspaceManagerService,
    private val files: WorkspaceFileService,
    private val sessions: SessionManagerService,
) : MentionDirectory {
    val sessionService: SessionManagerService get() = sessions

    private companion object {
        /** Distinguishes scope ids from file ids, which are path-derived. */
        const val SCOPE_ID_PREFIX = "ws:"

        /**
         * The only subtree a cross-Workspace grant may name today.
         *
         * Kept here rather than derived from the memory module so this directory stays a
         * visibility component: it lists what exists, it does not decide what is allowed.
         */
        const val MEMORY_PREFIX = "memory/"

        /** Capability a cross-Workspace reference advertises. Enforced elsewhere. */
        const val CROSS_WORKSPACE_READ = "workspace.memory.read.cross"
    }
    override fun children(context: InteractionContext, parentId: String?): List<MentionDescriptor> {
        requireContext(context)
        if (parentId == null) {
            val workspace = workspaces.resolveSessionWorkspace(context.workspaceId)
            // The current Workspace, plus one scope node per other Workspace.
            //
            // Only the scope is listed, never its contents: `@team` must be a deliberate
            // two-step act (reference the scope, then read inside it), so that each step is
            // separately visible in the transcript and separately auditable.
            return listOf(
                MentionDescriptor(
                    id = workspace.id,
                    type = MentionType.WORKSPACE,
                    label = workspace.name,
                    workspaceId = workspace.id,
                    requiredCapabilities = setOf("workspace.read"),
                ),
            ) + otherWorkspaceScopes(context)
        }

        val parent = find(context, parentId) ?: return emptyList()
        val directory = when (parent.type) {
            MentionType.WORKSPACE -> ""
            MentionType.FOLDER -> parent.relativePath ?: return emptyList()
            // Expanding a memory scope lists that Workspace's memory, which is the second step
            // of the two-step grant. Cross-Workspace expansion reads through the same file
            // service the authorization layer checked, never a bypass.
            MentionType.MEMORY -> parent.authorizedPrefix?.trimEnd('/') ?: return emptyList()
            else -> return emptyList()
        }
        if (parent.type == MentionType.MEMORY) {
            return memoryEntries(parent)
        }
        return files.list(context.workspaceId, directory, limit = 500).entries.map { entry ->
            entry.toMention(context.workspaceId, directory, parent.id)
        }
    }

    override fun find(context: InteractionContext, id: String): MentionDescriptor? {
        if (!isContextValid(context)) return null
        val workspace = workspaces.get(context.workspaceId)?.takeIf { it.archivedAtMillis == null } ?: return null
        if (id == workspace.id) {
            return MentionDescriptor(
                id = workspace.id,
                type = MentionType.WORKSPACE,
                label = workspace.name,
                workspaceId = workspace.id,
                requiredCapabilities = setOf("workspace.read"),
            )
        }
        // Scope ids are resolved before file ids: a scope id is `ws:<workspaceId>` or
        // `ws:<workspaceId>:memory`, and must not be mistaken for a file path.
        decodeScopeId(id)?.let { return it }

        val decoded = decodeFileId(id, context.workspaceId) ?: return null
        val relativePath = decoded
        if (relativePath.isBlank() || relativePath.startsWith('/') || relativePath.split('/').any { it.isBlank() || it == "." || it == ".." }) return null
        val parentPath = relativePath.substringBeforeLast('/', "")
        val name = relativePath.substringAfterLast('/')
        val entry = files.list(context.workspaceId, parentPath, limit = 500).entries.singleOrNull { it.name == name } ?: return null
        val parentId = if (parentPath.isEmpty()) workspace.id else fileId(context.workspaceId, parentPath)
        return entry.toMention(context.workspaceId, parentPath, parentId)
    }

    fun readText(context: InteractionContext, descriptor: MentionDescriptor, maxBytes: Int = 64 * 1024): String? {
        requireContext(context)
        val path = descriptor.relativePath ?: return null

        val readableType = descriptor.type == MentionType.FILE || descriptor.type == MentionType.MEMORY
        if (!readableType) return null

        if (descriptor.workspaceId != context.workspaceId) {
            // Cross-Workspace reads go through the single containment check rather than a
            // local prefix test, so there is one place where "inside the granted subtree" is
            // decided. The grant comes from the descriptor the authorization step approved.
            val grant = CrossWorkspaceGrant(
                workspaceId = descriptor.workspaceId,
                prefix = descriptor.authorizedPrefix,
            )
            if (CrossWorkspaceAccess.check(grant, path) !is CrossWorkspaceAccess.Decision.Allowed) {
                return null
            }
        } else if (descriptor.type == MentionType.MEMORY) {
            // Even intra-Workspace memory reads must stay inside memory/, since a stray
            // descriptor must not become a way to read arbitrary files.
            if (!ScopePathGuard.withinPrefix(path, MEMORY_PREFIX)) return null
        }

        val content = files.read(descriptor.workspaceId, path, maxBytes = maxBytes)
        return try {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(content.bytes))
                .toString()
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Lists one scope node per other Workspace.
     *
     * Archived Workspaces are excluded, matching [find]: a scope the user cannot open should not
     * appear in the picker only to fail when expanded.
     */
    private fun otherWorkspaceScopes(context: InteractionContext): List<MentionDescriptor> =
        workspaces.list(includeArchived = false)
            .filter { it.id != context.workspaceId }
            .sortedBy { it.name }
            .flatMap { workspace ->
                listOf(
                    // Whole-Workspace scope: `@name`.
                    MentionDescriptor(
                        id = scopeId(workspace.id, memory = false),
                        type = MentionType.WORKSPACE,
                        label = workspace.name,
                        workspaceId = workspace.id,
                        requiredCapabilities = setOf(CROSS_WORKSPACE_READ),
                    ),
                    // Memory-only scope: `@name：记忆`.
                    MentionDescriptor(
                        id = scopeId(workspace.id, memory = true),
                        type = MentionType.MEMORY,
                        label = "${workspace.name}：记忆",
                        workspaceId = workspace.id,
                        relativePath = MEMORY_PREFIX.trimEnd('/'),
                        authorizedPrefix = MEMORY_PREFIX,
                        requiredCapabilities = setOf(CROSS_WORKSPACE_READ),
                    ),
                )
            }

    /** Entries inside a memory scope. Read-only and limited to the granted prefix. */
    private fun memoryEntries(parent: MentionDescriptor): List<MentionDescriptor> {
        val prefix = parent.authorizedPrefix ?: return emptyList()
        val page = try {
            files.list(parent.workspaceId, prefix.trimEnd('/'), limit = 500)
        } catch (e: Exception) {
            // An unreadable scope must read as empty, not crash the picker.
            return emptyList()
        }
        return page.entries.map { entry ->
            val path = if (prefix.endsWith("/")) prefix + entry.name else prefix + "/" + entry.name
            MentionDescriptor(
                id = memoryId(parent.workspaceId, path),
                type = if (entry.directory) MentionType.FOLDER else MentionType.MEMORY,
                label = entry.name,
                workspaceId = parent.workspaceId,
                parentId = parent.id,
                relativePath = path,
                authorizedPrefix = prefix,
                requiredCapabilities = setOf(CROSS_WORKSPACE_READ),
            )
        }
    }

    /** Resolves a scope id back to its descriptor. Returns null for non-scope ids. */
    private fun decodeScopeId(id: String): MentionDescriptor? {
        if (!id.startsWith(SCOPE_ID_PREFIX)) return null
        val rest = id.removePrefix(SCOPE_ID_PREFIX)
        val workspaceId = rest.substringBefore(':')
        val isMemory = rest.contains(':')
        if (workspaceId.isBlank()) return null
        val workspace = workspaces.get(workspaceId)?.takeIf { it.archivedAtMillis == null } ?: return null
        return if (isMemory) {
            MentionDescriptor(
                id = id,
                type = MentionType.MEMORY,
                label = "${workspace.name}：记忆",
                workspaceId = workspace.id,
                relativePath = MEMORY_PREFIX.trimEnd('/'),
                authorizedPrefix = MEMORY_PREFIX,
                requiredCapabilities = setOf(CROSS_WORKSPACE_READ),
            )
        } else {
            MentionDescriptor(
                id = id,
                type = MentionType.WORKSPACE,
                label = workspace.name,
                workspaceId = workspace.id,
                authorizedPrefix = null,
                requiredCapabilities = setOf(CROSS_WORKSPACE_READ),
            )
        }
    }

    private fun scopeId(workspaceId: String, memory: Boolean): String =
        if (memory) "$SCOPE_ID_PREFIX$workspaceId:memory" else "$SCOPE_ID_PREFIX$workspaceId"

    private fun memoryId(workspaceId: String, path: String): String =
        "$SCOPE_ID_PREFIX$workspaceId:memory:$path"

    private fun requireContext(context: InteractionContext) {
        check(isContextValid(context)) { "Session is not bound to an available Workspace" }
    }

    private fun isContextValid(context: InteractionContext): Boolean {
        val session = sessions.getSession(context.sessionId) ?: return false
        if (session.workspaceId != context.workspaceId) return false
        val workspace = workspaces.get(context.workspaceId) ?: return false
        if (workspace.archivedAtMillis != null) return false
        return try {
            workspaces.resolveSessionWorkspace(context.workspaceId).id == context.workspaceId
        } catch (_: Exception) {
            false
        }
    }

    private fun WorkspaceFileEntry.toMention(workspaceId: String, parentPath: String, parentId: String): MentionDescriptor {
        val path = if (parentPath.isEmpty()) name else "$parentPath/$name"
        return MentionDescriptor(
            id = fileId(workspaceId, path),
            type = if (directory) MentionType.FOLDER else MentionType.FILE,
            label = name,
            workspaceId = workspaceId,
            parentId = parentId,
            relativePath = path,
            requiredCapabilities = setOf(if (directory) "file.list" else "file.read"),
        )
    }

    private fun fileId(workspaceId: String, path: String): String =
        "file:$workspaceId:${Base64.getUrlEncoder().withoutPadding().encodeToString(path.toByteArray(Charsets.UTF_8))}"

    private fun decodeFileId(id: String, workspaceId: String): String? {
        val prefix = "file:$workspaceId:"
        if (!id.startsWith(prefix)) return null
        return try {
            String(Base64.getUrlDecoder().decode(id.removePrefix(prefix)), Charsets.UTF_8)
        } catch (_: IllegalArgumentException) {
            null
        }
    }
}

/** Local non-secret fill values. Do not use this store for API keys or authentication tokens. */
class AndroidFillValueStore(context: Context) : MutableFillValueStore {
    private val preferences = context.getSharedPreferences("interaction-fill-values", Context.MODE_PRIVATE)

    override fun get(key: String): String? = preferences.getString("value.$key", null)

    override fun keys(): Set<String> = preferences.all.keys
        .filter { it.startsWith("value.") }
        .map { it.removePrefix("value.") }
        .toSet()

    override fun set(key: String, value: String) {
        require(key.matches(Regex("[A-Za-z0-9_-]{1,64}"))) { "Invalid fill key" }
        check(preferences.edit().putString("value.$key", value).commit()) { "Could not save fill value" }
    }

    override fun remove(key: String) {
        check(preferences.edit().remove("value.$key").commit()) { "Could not remove fill value" }
    }
}
