package com.cordiact.core.settings

import com.cordiact.core.file.WorkspaceFileException
import com.cordiact.core.file.WorkspaceFileService
import java.nio.ByteBuffer
import org.tomlj.Toml

/** Workspace files can only restrict existing grants. They never grant tools or select endpoints. */
class WorkspaceToolPreferences(private val files: WorkspaceFileService) {
    data class Snapshot(val revision: String?, val disabled: Set<String>, val searchDisabled: Boolean)
    private data class Document(val value: Snapshot, val original: String)

    fun read(workspace: String): Snapshot = load(workspace).value

    fun allows(workspace: String, toolId: String, searchTool: Boolean): Boolean = try {
        val settings = read(workspace)
        toolId !in settings.disabled && (!searchTool || !settings.searchDisabled)
    } catch (_: Exception) { false }

    fun setSearchDisabled(workspace: String, revision: String?, value: Boolean) = update(workspace, revision) { old ->
        old.copy(searchDisabled = value)
    }

    fun setDisabled(workspace: String, revision: String?, id: String, value: Boolean) = update(workspace, revision) { old ->
        require(validId(id))
        old.copy(disabled = if (value) old.disabled + id else old.disabled - id)
    }

    private fun update(workspace: String, revision: String?, change: (Snapshot) -> Snapshot) {
        val (old, original) = load(workspace)
        if (old.revision != revision) throw WorkspaceFileException(WorkspaceFileException.Code.REVISION_CONFLICT, "Tool preferences changed")
        val next = change(old)
        if (next == old) return
        // Edit one true root key of each setting, keeping unknown typed values; TOML trivia is normalized.
        val root = Toml.parse(original)
        val keys = root.entrySet().associateTo(linkedMapOf()) { it.key to it.value }
        if (next.disabled.isEmpty()) keys.remove("disabledTools")
        else keys["disabledTools"] = requireNotNull(Toml.parse("a = [" + next.disabled.sorted().joinToString(", ") { "\"$it\"" } + "]").getArray("a"))
        if (next.searchDisabled) keys["searchDisabled"] = true else keys.remove("searchDisabled")
        val table = object : org.tomlj.TomlTable by root {
            override fun entrySet(): Set<Map.Entry<String, Any>> = keys.entries
            override fun isEmpty(): Boolean = keys.isEmpty()
            override fun toToml(): String = StringBuilder().also { super<org.tomlj.TomlTable>.toToml(it) }.toString()
        }
        val bytes = table.toToml().encodeToByteArray()
        decode(bytes, revision)
        files.write(workspace, PATH, bytes, expectedRevision = revision)
    }

    private fun load(workspace: String): Document {
        val file = try { files.read(workspace, PATH, maxBytes = MAX_BYTES + 1) }
        catch (e: WorkspaceFileException) {
            if (e.code != WorkspaceFileException.Code.FILE_NOT_FOUND) throw e
            return Document(Snapshot(null, emptySet(), false), "")
        }
        return decode(file.bytes, file.revision)
    }

    private fun decode(bytes: ByteArray, revision: String?): Document {
        require(bytes.size <= MAX_BYTES)
        val text = Charsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString()
        val root = Toml.parse(text)
        require(!root.hasErrors() && (!root.contains("schemaVersion") || root.getLong("schemaVersion") == 1L))
        val blocked = mutableSetOf<String>()
        root.getArray("disabledTools")?.let { array ->
            require(array.size() <= 512)
            repeat(array.size()) { i -> blocked.add(array.getString(i).also { require(validId(it)) }) }
        }
        val search = root.getBoolean("searchDisabled") == true
        require(blocked.size <= 512)
        return Document(Snapshot(revision, blocked, search), text)
    }

    private fun validId(id: String) = ID.matches(id)
    private companion object {
        const val PATH = ".cordiact/tools.toml"
        const val MAX_BYTES = 48 * 1024
        val ID = Regex("[A-Za-z0-9_.:-]{1,128}")
    }
}
