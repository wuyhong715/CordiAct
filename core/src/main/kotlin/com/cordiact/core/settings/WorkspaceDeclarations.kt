package com.cordiact.core.settings

import com.cordiact.core.file.WorkspaceFileException
import com.cordiact.core.file.WorkspaceFileService
import org.tomlj.Toml
import java.net.URI
import java.nio.ByteBuffer

/** Untrusted, offline requests only. Never activates a provider, resolves a credential or connects to an endpoint. */
class WorkspaceDeclarations(private val files: WorkspaceFileService) {
    enum class Kind(val path: String, val section: String) {
        PROVIDER(".cordiact/models.toml", "providerDrafts"), MCP(".cordiact/plugins.toml", "mcpDrafts"),
    }
    data class Draft(val id: String, val name: String, val endpoint: String, val protocol: String)
    data class Snapshot(val revision: String?, val drafts: List<Draft>)

    fun read(workspace: String, kind: Kind): Snapshot = load(workspace, kind).first

    fun add(workspace: String, kind: Kind, revision: String?, id: String, name: String, endpoint: String, protocol: String) {
        val (snapshot, text) = load(workspace, kind)
        if (snapshot.revision != revision) throw WorkspaceFileException(WorkspaceFileException.Code.REVISION_CONFLICT, "Draft changed")
        require(ID.matches(id) && snapshot.drafts.size < 60 && snapshot.drafts.none { it.id == id })
        require(validName(name) && allowedProtocol(kind, protocol) && validEndpoint(endpoint))
        val next = buildString {
            append(text.trimEnd()).append("\n\n[").append(kind.section).append('.').append(id).append("]\n")
            // Name forbids control characters; preserve the rest as TOML basic-string literals.
            for ((key, value) in listOf("name" to name, "endpoint" to endpoint, "protocol" to protocol)) {
                append(key).append(" = \"").append(value.replace("\\", "\\\\").replace("\"", "\\\"")).append("\"\n")
            }
        }
        val encoded = next.encodeToByteArray()
        require(encoded.size <= MAX && !Toml.parse(next).hasErrors())
        files.write(workspace, kind.path, encoded, expectedRevision = revision)
    }

    private fun load(workspace: String, kind: Kind): Pair<Snapshot, String> {
        val file = try { files.read(workspace, kind.path, maxBytes = MAX + 1) }
        catch (e: WorkspaceFileException) {
            if (e.code != WorkspaceFileException.Code.FILE_NOT_FOUND) throw e
            return Snapshot(null, emptyList()) to ""
        }
        require(file.bytes.size <= MAX)
        val text = Charsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(file.bytes)).toString()
        val parsed = Toml.parse(text)
        require(!parsed.hasErrors() && (!parsed.contains("schemaVersion") || parsed.getLong("schemaVersion") == 1L))
        val table = parsed.getTable(kind.section)
        val drafts = table?.keySet()?.sorted()?.map { id ->
            require(ID.matches(id))
            val data = requireNotNull(table.getTable(listOf(id)))
            val name = requireNotNull(data.getString("name"))
            val endpoint = requireNotNull(data.getString("endpoint"))
            val protocol = requireNotNull(data.getString("protocol"))
            require(validName(name) && validEndpoint(endpoint) && allowedProtocol(kind, protocol))
            Draft(id, name, endpoint, protocol)
        }.orEmpty()
        return Snapshot(file.revision, drafts) to text
    }

    companion object {
        private const val MAX = 48 * 1024
        private val ID = Regex("[a-z][a-z0-9_-]{0,63}")
        private fun validName(name: String) = name.isNotBlank() && name.length <= 96 && name.none(Char::isISOControl)
        private fun validEndpoint(text: String): Boolean {
            if (text.length > 1024 || text.any(Char::isISOControl)) return false
            val url = runCatching { URI(text) }.getOrNull() ?: return false
            return url.scheme.equals("https", true) && !url.host.isNullOrBlank() &&
                url.rawUserInfo == null && url.rawQuery == null && url.rawFragment == null
        }
        fun protocols(kind: Kind): List<String> = when (kind) {
            Kind.MCP -> listOf("STREAMABLE_HTTP")
            Kind.PROVIDER -> listOf("OPENAI_CHAT_COMPLETIONS", "OPENAI_RESPONSES", "ANTHROPIC_MESSAGES", "GEMINI_GENERATE_CONTENT", "OPENAI_IMAGES")
        }
        private fun allowedProtocol(kind: Kind, protocol: String) = protocol in protocols(kind)
    }
}
