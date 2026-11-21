package com.cordiact.core.settings

import com.cordiact.core.file.WorkspaceFileException
import com.cordiact.core.file.WorkspaceFileService
import java.nio.ByteBuffer

/** Root-only workspace document. No parent traversal, includes, or permission directives. */
class WorkspaceInstructions(private val files: WorkspaceFileService) {
    data class Snapshot(val text: String, val revision: String?)

    fun read(workspaceId: String): Snapshot {
        val content = try { files.read(workspaceId, PATH, maxBytes = MAX_BYTES + 1) }
        catch (e: WorkspaceFileException) {
            if (e.code != WorkspaceFileException.Code.FILE_NOT_FOUND) throw e
            return Snapshot("", null)
        }
        require(content.bytes.size <= MAX_BYTES) { "AGENTS.md exceeds the size limit" }
        return Snapshot(Charsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(content.bytes)).toString(), content.revision)
    }

    fun write(workspaceId: String, expected: String?, text: String): Snapshot {
        val bytes = text.toByteArray(Charsets.UTF_8)
        require(bytes.size <= MAX_BYTES) { "AGENTS.md exceeds the size limit" }
        files.write(workspaceId, PATH, bytes, expected)
        return read(workspaceId)
    }

    private companion object {
        const val PATH = "AGENTS.md"
        const val MAX_BYTES = 48 * 1024
    }
}
