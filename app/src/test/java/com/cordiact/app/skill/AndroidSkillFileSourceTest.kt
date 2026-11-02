package com.cordiact.app.skill

import com.cordiact.app.file.AndroidWorkspaceFileBackend
import com.cordiact.core.file.WorkspaceFileContent
import com.cordiact.core.file.WorkspaceFileMutation
import com.cordiact.core.file.WorkspaceFilePage
import com.cordiact.core.file.WorkspaceFileService
import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidSkillFileSourceTest {
    private val workspaceId = "ws-1"

    private fun withRoot(block: (File, AndroidSkillFileSource) -> Unit) {
        val root = Files.createTempDirectory("skills-test").toFile()
        val backend = AndroidWorkspaceFileBackend()
        val service = object : WorkspaceFileService {
            override fun list(workspaceId: String, relativeDirectory: String, cursor: String?, limit: Int): WorkspaceFilePage =
                backend.list(root, relativeDirectory, cursor, limit)
            override fun read(workspaceId: String, relativePath: String, offset: Long, maxBytes: Int): WorkspaceFileContent =
                backend.read(root, relativePath, offset, maxBytes)
            override fun write(workspaceId: String, relativePath: String, bytes: ByteArray, expectedRevision: String?): WorkspaceFileMutation =
                backend.write(root, relativePath, bytes, expectedRevision)
            override fun delete(workspaceId: String, relativePath: String, expectedRevision: String): WorkspaceFileMutation =
                backend.delete(root, relativePath, expectedRevision)
            override fun move(workspaceId: String, sourceRelativePath: String, destinationRelativePath: String, expectedRevision: String): WorkspaceFileMutation =
                backend.move(root, sourceRelativePath, destinationRelativePath, expectedRevision)
        }
        try { block(root, AndroidSkillFileSource(service)) } finally { root.deleteRecursively() }
    }

    @Test fun missingSkillsDirectoryIsEmpty() = withRoot { _, source ->
        assertTrue(source.listSkillFiles(workspaceId).isEmpty())
    }

    @Test fun findsSkillsBeyondTheFirstDirectoryPage() = withRoot { root, source ->
        repeat(105) { index ->
            val dir = File(root, "skills/skill-${index.toString().padStart(3, '0')}")
            dir.mkdirs()
            File(dir, "SKILL.md").writeText("---\nname: ${dir.name}\ndescription: test\n---\nbody")
        }
        val paths = source.listSkillFiles(workspaceId)
        assertEquals(105, paths.size)
        assertTrue("skills/skill-104/SKILL.md" in paths)
    }

    @Test fun rejectsOversizedSkillRatherThanReadingTruncatedHeader() = withRoot { root, source ->
        val file = File(root, "skills/large/SKILL.md")
        file.parentFile.mkdirs()
        file.writeText("---\nname: large\ndescription: test\n---\n" + "x".repeat(300_000))
        assertNull(source.read(workspaceId, "skills/large/SKILL.md"))
    }

    @Test fun readsNormalUtf8Skill() = withRoot { root, source ->
        val file = File(root, "skills/review/SKILL.md")
        file.parentFile.mkdirs()
        file.writeText("---\nname: review\ndescription: 审查变更\n---\n详细步骤")
        assertEquals("skills/review/SKILL.md", source.listSkillFiles(workspaceId).single())
        assertTrue(source.read(workspaceId, "skills/review/SKILL.md")!!.contains("详细步骤"))
    }
}
