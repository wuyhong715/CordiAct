package com.cordiact.app.skill

import com.cordiact.core.file.WorkspaceFileService
import com.cordiact.core.file.WorkspaceFileException
import com.cordiact.core.skill.SkillFileSource

/**
 * Reads `skills/` from a Workspace through the existing file service.
 *
 * Going through [WorkspaceFileService] rather than the filesystem directly means the hidden-path
 * and traversal rules that already guard every other read apply here too: a skill cannot use its
 * header to reach `.git` or step outside the Workspace.
 */
class AndroidSkillFileSource(private val files: WorkspaceFileService) : SkillFileSource {

    /**
     * Lists skills from both roots.
     *
     * `skills/` holds what the user wrote; `memory/skills/` holds procedures distilled from
     * memory. They are scanned together because the catalog needs one index, but their origins
     * stay distinguishable so a generated skill can never shadow a user-authored one of the
     * same name.
     */
    override fun listSkillFiles(workspaceId: String): List<String> {
        val paths = mutableListOf<String>()
        for (root in SKILL_ROOTS) {
            paths += listUnder(workspaceId, root)
        }
        return paths.sorted()
    }

    private fun listUnder(workspaceId: String, root: String): List<String> {
        val paths = mutableListOf<String>()
        var cursor: String? = null
        do {
            val page = try {
                files.list(workspaceId, root, cursor = cursor, limit = PAGE_SIZE)
            } catch (e: WorkspaceFileException) {
                // A missing root is normal: most Workspaces never generate a skill.
                if (e.code == WorkspaceFileException.Code.FILE_NOT_FOUND) return paths
                throw e
            }
            for (entry in page.entries) {
                if (!entry.directory) continue
                // Names are validated by SkillParser; filtering here stops other folders from
                // forcing reads outside the intended <root>/<name>/SKILL.md layout.
                if (!SKILL_DIR.matches(entry.name)) continue
                paths += "$root/${entry.name}/$SKILL_FILE_NAME"
                if (paths.size > MAX_SKILLS) error("Too many skills under $root")
            }
            cursor = page.nextCursor
        } while (cursor != null)
        return paths
    }

    override fun read(workspaceId: String, path: String): String? {
        return try {
            val content = files.read(workspaceId, path, offset = 0, maxBytes = MAX_SKILL_BYTES + 1)
            if (content.bytes.size > MAX_SKILL_BYTES) {
                null
            } else {
                Charsets.UTF_8.newDecoder()
                    .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                    .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
                    .decode(java.nio.ByteBuffer.wrap(content.bytes))
                    .toString()
            }
        } catch (e: Exception) {
            // Covers absent, unreadable and non-UTF8 alike; none produces a usable skill.
            null
        }
    }

    private companion object {
        /** User-authored skills, then generated ones. */
        val SKILL_ROOTS = listOf("skills", "memory/skills")
        const val SKILL_FILE_NAME = "SKILL.md"
        val SKILL_DIR = Regex("^[a-z0-9][a-z0-9-]*$")
        const val PAGE_SIZE = 100
        const val MAX_SKILLS = 500
        const val MAX_SKILL_BYTES = 256 * 1024
    }
}
