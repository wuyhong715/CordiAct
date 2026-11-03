package com.cordiact.app.memory

import com.cordiact.core.file.WorkspaceFileService
import com.cordiact.core.memory.MemoryArtifactStore
import com.cordiact.core.memory.MemoryPaths

/**
 * Stores memory artifacts as Workspace files under `memory/`.
 *
 * Going through [WorkspaceFileService] rather than the filesystem directly is what keeps the
 * hidden-path and traversal rules in force: `.git` is unreachable, and a malformed artifact path
 * cannot escape the Workspace. It also means the artifacts participate in the same revision
 * tracking as everything else, which the git check during consolidation relies on.
 */
class WorkspaceMemoryArtifactStore(private val files: WorkspaceFileService) : MemoryArtifactStore {

    override fun read(workspaceId: String, path: String, maxChars: Int): String? {
        if (!MemoryPaths.contains(path)) return null
        val content = try {
            files.read(workspaceId, path, offset = 0, maxBytes = maxChars.coerceIn(1, MAX_ARTIFACT_BYTES))
        } catch (e: Exception) {
            return null
        }
        return try {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
                .decode(java.nio.ByteBuffer.wrap(content.bytes))
                .toString()
        } catch (e: Exception) {
            null
        }
    }

    override fun write(workspaceId: String, path: String, content: String) {
        // Only the memory subtree is writable from here. A wrong path is a bug, not something
        // to correct silently by writing somewhere else.
        require(MemoryPaths.contains(path)) { "Refusing to write outside memory/: $path" }
        val expected = revisionOf(workspaceId, path)
        files.write(workspaceId, path, content.toByteArray(Charsets.UTF_8), expected)
    }

    override fun delete(workspaceId: String, path: String) {
        require(MemoryPaths.contains(path)) { "Refusing to delete outside memory/: $path" }
        val expected = revisionOf(workspaceId, path) ?: return
        try {
            files.delete(workspaceId, path, expected)
        } catch (e: Exception) {
            // Already gone is the desired end state; only contention would reach here.
        }
    }

    override fun exists(workspaceId: String, path: String): Boolean =
        MemoryPaths.contains(path) && revisionOf(workspaceId, path) != null

    override fun evidenceFiles(workspaceId: String): List<String> {
        val page = try {
            files.list(workspaceId, MemoryPaths.EVIDENCE_PREFIX.trimEnd('/'), cursor = null, limit = MAX_EVIDENCE)
        } catch (e: Exception) {
            return emptyList()
        }
        return page.entries.filter { !it.directory }
            .map { MemoryPaths.EVIDENCE_PREFIX + it.name }
    }

    /** The stored revision, used as optimistic-concurrency token on the next write. */
    private fun revisionOf(workspaceId: String, path: String): String? = try {
        files.read(workspaceId, path, offset = 0, maxBytes = 1).revision
    } catch (e: Exception) {
        null
    }

    private companion object {
        const val MAX_ARTIFACT_BYTES = 1024 * 1024
        const val MAX_EVIDENCE = 500
    }
}
