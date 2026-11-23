package com.cordiact.core.settings

import com.cordiact.core.conversation.ModelDescriptor
import com.cordiact.core.conversation.ModelId
import com.cordiact.core.conversation.ReasoningEffort
import com.cordiact.core.file.WorkspaceFileException
import com.cordiact.core.file.WorkspaceFileService
import org.tomlj.Toml
import org.tomlj.TomlTable
import java.nio.ByteBuffer

/** Non-sensitive defaults only. This does not configure providers, execute tools, or grant access. */
class WorkspaceDefaults(private val files: WorkspaceFileService) {
    enum class Document(val path: String) { AGENT(".cordiact/agent.toml"), MODELS(".cordiact/models.toml") }

    data class Snapshot(
        val presetId: String?, val model: ModelId?, val effort: ReasoningEffort?,
        val revisions: Map<Document, String?>, val errors: Set<Document>,
    )

    private data class Entry(val table: TomlTable, val revision: String?)
    private data class Models(val model: ModelId?, val effort: ReasoningEffort?)

    fun read(workspaceId: String): Snapshot {
        val agent = runCatching { readDocument(workspaceId, Document.AGENT).let { it to preset(it.table) } }
        val models = runCatching { readDocument(workspaceId, Document.MODELS).let { it to models(it.table) } }
        return Snapshot(agent.getOrNull()?.second, models.getOrNull()?.second?.model, models.getOrNull()?.second?.effort,
            buildMap {
                agent.getOrNull()?.let { put(Document.AGENT, it.first.revision) }
                models.getOrNull()?.let { put(Document.MODELS, it.first.revision) }
            }, buildSet {
                if (agent.isFailure) add(Document.AGENT)
                if (models.isFailure) add(Document.MODELS)
            })
    }

    fun setPreset(workspaceId: String, revision: String?, id: String?) =
        update(workspaceId, Document.AGENT, revision) { it.withValue("presetId", id) }

    fun setModel(workspaceId: String, revision: String?, model: ModelId?) =
        update(workspaceId, Document.MODELS, revision) { table ->
            if (model == null) {
                val old = table["defaultModel"] as? TomlTable
                val other = old?.withValue("providerId", null)?.withValue("modelId", null)
                table.withValue("defaultModel", other?.takeUnless { it.isEmpty })
            } else {
                val old = table["defaultModel"] as? TomlTable ?: Toml.parse("")
                table.withValue("defaultModel", old.withValue("providerId", model.providerId).withValue("modelId", model.modelId))
            }
        }

    fun setEffort(workspaceId: String, revision: String?, effort: ReasoningEffort?) =
        update(workspaceId, Document.MODELS, revision) { it.withValue("reasoningEffort", effort?.name) }

    private fun readDocument(workspaceId: String, document: Document): Entry {
        val content = try { files.read(workspaceId, document.path, maxBytes = MAX_BYTES + 1) }
        catch (error: WorkspaceFileException) {
            if (error.code != WorkspaceFileException.Code.FILE_NOT_FOUND) throw error
            return Entry(Toml.parse(""), null)
        }
        require(content.bytes.size <= MAX_BYTES)
        val text = Charsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(content.bytes)).toString()
        val table = Toml.parse(text)
        require(!table.hasErrors() && (!table.contains("schemaVersion") || table.getLong("schemaVersion") == 1L))
        return Entry(table, content.revision)
    }

    private fun validate(document: Document, table: TomlTable) {
        when (document) { Document.AGENT -> preset(table); Document.MODELS -> models(table) }
    }

    private fun update(workspaceId: String, document: Document, revision: String?, change: (TomlTable) -> TomlTable) {
        val entry = readDocument(workspaceId, document)
        if (entry.revision != revision) throw WorkspaceFileException(WorkspaceFileException.Code.REVISION_CONFLICT, "Settings changed")
        validate(document, entry.table)
        val updated = change(entry.table)
        val serialized = updated.toToml()
        if (entry.table.toToml() == serialized) return
        val reparsed = Toml.parse(serialized)
        require(!reparsed.hasErrors())
        validate(document, reparsed)
        val bytes = serialized.encodeToByteArray()
        require(bytes.size <= MAX_BYTES)
        files.write(workspaceId, document.path, bytes, expectedRevision = revision)
    }

    private fun preset(table: TomlTable): String? = table.optionalText("presetId")
    private fun models(table: TomlTable): Models = Models(
        table.getTable("defaultModel")?.let {
            val provider = it.optionalText("providerId")
            val model = it.optionalText("modelId")
            if (provider == null && model == null) null else ModelId(requireNotNull(provider), requireNotNull(model))
        }, table.optionalText("reasoningEffort")?.let(ReasoningEffort::valueOf),
    )

    private fun TomlTable.optionalText(key: String): String? = getString(key)?.also {
        require(it.isNotBlank() && it.length <= 512 && it.none(Char::isISOControl))
    }

    // Tomlj's serializer consumes entrySet. Keep unknown typed values, including nested tables.
    private fun TomlTable.withValue(key: String, value: Any?): TomlTable {
        val entries = entrySet().associateTo(linkedMapOf()) { it.key to it.value }
        if (value == null) entries.remove(key) else entries[key] = value
        return object : TomlTable by this {
            override fun entrySet(): Set<Map.Entry<String, Any>> = entries.entries
            override fun isEmpty(): Boolean = entries.isEmpty()
            override fun toToml(): String = StringBuilder().also { super<TomlTable>.toToml(it) }.toString()
        }
    }

    private companion object { const val MAX_BYTES = 48 * 1024 }

}

/** Until provider authorization is workspace-scoped, a file cannot reroute conversation data. */
fun resolveWorkspaceConversationModel(
    usable: List<ModelDescriptor>, inheritedPreset: ModelId?, preset: ModelId?, appDefault: ModelId?, explicit: ModelId?,
): ModelDescriptor? {
    val base = usable.firstOrNull { it.id == inheritedPreset }
        ?: usable.firstOrNull { it.id == appDefault } ?: usable.firstOrNull() ?: return null
    fun onAuthorizedProvider(id: ModelId?) = usable.firstOrNull { it.id == id && it.id.providerId == base.id.providerId }
    return onAuthorizedProvider(explicit) ?: onAuthorizedProvider(preset) ?: base
}
