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

    override fun listSkillFiles(workspaceId: String): List<String> {
        val paths = mutableListOf<String>()
        var cursor: String? = null
        do {
            val page = try {
                files.list(workspaceId, SKILLS_ROOT, cursor = cursor, limit = PAGE_SIZE)
            } catch (e: WorkspaceFileException) {
                if (e.code == WorkspaceFileException.Code.FILE_NOT_FOUND) return emptyList()
                throw e
            }
            for (entry in page.entries) {
                if (!entry.directory) continue
                // Skill names are validated by SkillParser; filter other folders now so they
                // cannot force reads outside the intended skills/<name>/SKILL.md layout.
                if (!SKILL_DIR.matches(entry.name)) continue
                paths += "$SKILLS_ROOT/${entry.name}/$SKILL_FILE_NAME"
                if (paths.size > MAX_SKILLS) error("Too many skills in $SKILLS_ROOT")
            }
            cursor = page.nextCursor
        } while (cursor != null)
        return paths.sorted()
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
        const val SKILLS_ROOT = "skills"
        const val SKILL_FILE_NAME = "SKILL.md"
        val SKILL_DIR = Regex("^[a-z0-9][a-z0-9-]*$")
        const val PAGE_SIZE = 100
        const val MAX_SKILLS = 500
        const val MAX_SKILL_BYTES = 256 * 1024
    }
}
