package com.cordiact.app.interaction

import android.content.Context
import com.cordiact.core.conversation.SessionManagerService
import com.cordiact.core.file.WorkspaceFileEntry
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
    override fun children(context: InteractionContext, parentId: String?): List<MentionDescriptor> {
        requireContext(context)
        if (parentId == null) {
            val workspace = workspaces.resolveSessionWorkspace(context.workspaceId)
            return listOf(
                MentionDescriptor(
                    id = workspace.id,
                    type = MentionType.WORKSPACE,
                    label = workspace.name,
                    workspaceId = workspace.id,
                    requiredCapabilities = setOf("workspace.read"),
                ),
            )
        }

        val parent = find(context, parentId) ?: return emptyList()
        val directory = when (parent.type) {
            MentionType.WORKSPACE -> ""
            MentionType.FOLDER -> parent.relativePath ?: return emptyList()
            else -> return emptyList()
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
        if (descriptor.workspaceId != context.workspaceId || descriptor.type != MentionType.FILE) return null
        val path = descriptor.relativePath ?: return null
        val content = files.read(context.workspaceId, path, maxBytes = maxBytes)
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
