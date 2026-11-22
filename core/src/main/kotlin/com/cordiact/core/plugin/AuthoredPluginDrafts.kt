package com.cordiact.core.plugin

import com.cordiact.core.file.WorkspaceFileException
import com.cordiact.core.file.WorkspaceFileException.Code
import com.cordiact.core.file.WorkspaceFileService

/** On-disk metadata only; this says nothing about validity, approval or runtime state. */
data class AuthoredPluginDraftEntry(val id: String, val sizeBytes: Long)

data class AuthoredPluginDraftPage(val entries: List<AuthoredPluginDraftEntry>, val nextCursor: String?)

/** A read-only view of the selected workspace's top-level authored plugin sources. */
class AuthoredPluginDrafts(private val files: WorkspaceFileService) {
    /** Returns one file-service page, which can be empty after filtering even when more pages exist. */
    fun list(workspaceId: String, cursor: String? = null): AuthoredPluginDraftPage {
        validateCursor(cursor)
        val page = try {
            files.list(workspaceId, DRAFT_DIRECTORY, cursor, PAGE_SIZE)
        } catch (failure: WorkspaceFileException) {
            if (failure.code == Code.FILE_NOT_FOUND) return AuthoredPluginDraftPage(emptyList(), null)
            throw WorkspaceFileException(failure.code, "Could not list workspace plugin drafts")
        }
        val nextCursor = page.nextCursor
        validateCursor(nextCursor)
        if (nextCursor != null && nextCursor == cursor) invalidCursor()
        if (page.entries.size > PAGE_SIZE) {
            throw WorkspaceFileException(Code.STORAGE_FAILURE, "Workspace directory page exceeds its limit")
        }
        val entries = page.entries.mapNotNull { file ->
            if (file.directory) return@mapNotNull null
            val id = file.name.substringBeforeLast('.', missingDelimiterValue = "")
            if (id.isEmpty()) return@mapNotNull null
            val path = try {
                AuthoredPluginSource.path(id)
            } catch (_: IllegalArgumentException) {
                return@mapNotNull null
            }
            if (path != "$DRAFT_DIRECTORY/${file.name}") return@mapNotNull null
            AuthoredPluginDraftEntry(id, file.sizeBytes)
        }
        return AuthoredPluginDraftPage(entries, nextCursor)
    }

    /**
     * Re-reads the source; the digest belongs to these exact bytes, not a prior listing.
     * Oversized sources report [Code.SIZE_LIMIT_EXCEEDED]; invalid IDs or sources report
     * [IllegalArgumentException]. Error messages never contain user input.
     */
    fun preview(workspaceId: String, pluginId: String): AuthoredPluginDefinition {
        val path = AuthoredPluginSource.path(pluginId)
        val source = try {
            // A read clipped at MAX_SOURCE_BYTES could accept the prefix of an oversized file.
            files.read(workspaceId, path, offset = 0, maxBytes = AuthoredPluginSource.MAX_SOURCE_BYTES + 1)
        } catch (failure: WorkspaceFileException) {
            if (failure.code == Code.SIZE_LIMIT_EXCEEDED) sourceSizeExceeded()
            throw WorkspaceFileException(failure.code, "Could not read workspace plugin draft")
        }
        if (source.bytes.size > AuthoredPluginSource.MAX_SOURCE_BYTES) sourceSizeExceeded()
        return try {
            AuthoredPluginSource.compile(pluginId, source.bytes)
        } catch (_: IllegalArgumentException) {
            throw IllegalArgumentException("Invalid plugin source")
        }
    }

    private fun validateCursor(cursor: String?) {
        // The token belongs to the file service; bound it without interpreting or rewriting it.
        if (cursor != null && (cursor.length > MAX_CURSOR_CHARS || cursor.isEmpty())) invalidCursor()
    }

    private fun invalidCursor(): Nothing =
        throw WorkspaceFileException(Code.CURSOR_INVALID, "Invalid plugin draft cursor")

    private fun sourceSizeExceeded(): Nothing =
        throw WorkspaceFileException(Code.SIZE_LIMIT_EXCEEDED, "Plugin source size limit exceeded")

    private companion object {
        const val DRAFT_DIRECTORY = "plugins"
        const val PAGE_SIZE = 50
        const val MAX_CURSOR_CHARS = 8 * 1024
    }
}
