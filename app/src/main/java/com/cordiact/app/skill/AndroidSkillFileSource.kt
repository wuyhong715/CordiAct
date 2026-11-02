package com.cordiact.app.skill

import com.cordiact.core.file.WorkspaceFileService
import com.cordiact.core.skill.SkillFileSource

/**
 * Reads `skills/` from a Workspace through the existing file service.
 *
 * Going through [WorkspaceFileService] rather than the filesystem directly means the hidden-path
 * and traversal rules that already guard every other read apply here too: a skill cannot use its
 * header to reach `.git` or step outside the Workspace.
 */
class AndroidSkillFileSource(private val files: WorkspaceFileService) : SkillFileSource {

    override fun listSkillFiles(workspaceId: String): List<String> {
        val found = mutableListOf<String>()
        // Depth is capped so a pathological tree cannot turn discovery into an unbounded walk.
        walk(workspaceId, SKILLS_ROOT, depth = 0, into = found)
        return found.sorted()
    }

    private fun walk(workspaceId: String, directory: String, depth: Int, into: MutableList<String>) {
        if (depth > MAX_DEPTH) return
        val entries = try {
            files.list(workspaceId, directory, cursor = null, limit = MAX_ENTRIES).entries
        } catch (e: Exception) {
            // A missing skills/ directory is normal, not an error.
            return
        }
        for (entry in entries) {
            val path = if (directory.isEmpty()) entry.name else "$directory/${entry.name}"
            if (entry.directory) {
                walk(workspaceId, path, depth + 1, into)
            } else if (entry.name == SKILL_FILE_NAME) {
                into += path
            }
        }
    }

    override fun read(workspaceId: String, path: String): String? = try {
        val content = files.read(workspaceId, path, offset = 0, maxBytes = MAX_SKILL_BYTES)
        Charsets.UTF_8.newDecoder()
            .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
            .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
            .decode(java.nio.ByteBuffer.wrap(content.bytes))
            .toString()
    } catch (e: Exception) {
        // Covers absent, unreadable and non-UTF8 alike: from the catalog's side they are all
        // "this skill has no usable header".
        null
    }

    private companion object {
        const val SKILLS_ROOT = "skills"
        const val SKILL_FILE_NAME = "SKILL.md"
        const val MAX_DEPTH = 4
        const val MAX_ENTRIES = 500
        const val MAX_SKILL_BYTES = 256 * 1024
    }
}
