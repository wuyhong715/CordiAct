package com.cordiact.core.workspace

import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * These tests run real JGit against a real temporary directory. Faking the repository would
 * prove nothing: the whole point of this service is that the repository lifecycle actually
 * works on the files the memory pipeline writes.
 */
class JGitWorkspaceGitServiceTest {

    private lateinit var root: File
    private val service = JGitWorkspaceGitService()

    @BeforeTest
    fun setUp() {
        root = Files.createTempDirectory("cordiact-git").toFile()
    }

    @AfterTest
    fun tearDown() {
        root.deleteRecursively()
    }

    @Test
    fun initializesRepositoryAndCreatesInitialCommit() {
        val status = service.ensureRepository(WORKSPACE, root)

        assertEquals(WorkspaceGitState.READY, status.state)
        assertNotNull(status.headCommit, "init should produce one commit for .gitignore")
        assertTrue(File(root, ".git").isDirectory)
        assertTrue(File(root, ".gitignore").exists())
    }

    @Test
    fun ensureRepositoryIsIdempotent() {
        val first = service.ensureRepository(WORKSPACE, root)
        val second = service.ensureRepository(WORKSPACE, root)

        assertEquals(WorkspaceGitState.READY, first.state)
        assertEquals(WorkspaceGitState.READY, second.state)
        assertEquals(first.headCommit, second.headCommit, "a second call must not create a commit")
    }

    @Test
    fun statusDoesNotCreateRepository() {
        val status = service.status(WORKSPACE, root)

        assertEquals(WorkspaceGitState.UNVERSIONED, status.state)
        assertTrue(!File(root, ".git").exists(), "status must not initialize")
    }

    @Test
    fun commitsMemoryFilesAndReportsRevision() {
        service.ensureRepository(WORKSPACE, root)
        val memory = File(root, "memory").apply { mkdirs() }
        File(memory, "MEMORY.md").writeText("# Memory\n")

        val commit = service.commit(WORKSPACE, root, listOf("memory/MEMORY.md"), "Add memory")

        assertNotNull(commit)
        assertEquals(commit, service.status(WORKSPACE, root).headCommit)
    }

    @Test
    fun rejectsPathsOutsideMemory() {
        service.ensureRepository(WORKSPACE, root)
        File(root, "notes.txt").writeText("user document")

        // Accepting this would commit the user's documents on every memory write.
        for (path in listOf("notes.txt", "docs/a.md", "/etc/passwd", "../escape.md", "memory/../notes.txt")) {
            assertFailsWith<IllegalArgumentException>("expected rejection for $path") {
                service.commit(WORKSPACE, root, listOf(path), "should fail")
            }
        }
    }

    @Test
    fun gitignoreExcludesEverythingButMemory() {
        service.ensureRepository(WORKSPACE, root)
        File(root, "notes.txt").writeText("user document")
        File(root, "memory").mkdirs()
        File(root, "memory/MEMORY.md").writeText("# Memory\n")

        service.commit(WORKSPACE, root, listOf("memory/MEMORY.md"), "Add memory")

        // The user's document must not be tracked even though it sits in the same repository.
        val tracked = service.changedSince(WORKSPACE, root, null)
        assertTrue("memory/MEMORY.md" in tracked, "memory file should be tracked, got $tracked")
        assertTrue(tracked.none { it.startsWith("notes.txt") }, "user document must not be tracked")
    }

    @Test
    fun commitReturnsNullWhenNothingChanged() {
        service.ensureRepository(WORKSPACE, root)
        File(root, "memory").mkdirs()
        File(root, "memory/MEMORY.md").writeText("# Memory\n")
        val first = service.commit(WORKSPACE, root, listOf("memory/MEMORY.md"), "Add memory")

        // Same content: an empty commit would inflate history with no information.
        val second = service.commit(WORKSPACE, root, listOf("memory/MEMORY.md"), "Add memory again")

        assertNotNull(first)
        assertNull(second, "no-op commit must return null")
    }

    @Test
    fun changedSinceListsMemoryFilesBetweenCommits() {
        service.ensureRepository(WORKSPACE, root)
        File(root, "memory").mkdirs()
        File(root, "memory/MEMORY.md").writeText("# Memory\n")
        val baseline = service.commit(WORKSPACE, root, listOf("memory/MEMORY.md"), "Add memory")

        File(root, "memory/MEMORY.md").writeText("# Memory\n\n- new fact\n")
        File(root, "memory/rollout_summaries").mkdirs()
        File(root, "memory/rollout_summaries/s1.md").writeText("summary")
        service.commit(
            WORKSPACE,
            root,
            listOf("memory/MEMORY.md", "memory/rollout_summaries/s1.md"),
            "Consolidate",
        )

        val changed = service.changedSince(WORKSPACE, root, baseline)

        assertEquals(
            listOf("memory/MEMORY.md", "memory/rollout_summaries/s1.md"),
            changed,
        )
    }

    @Test
    fun deletionIsCommitted() {
        service.ensureRepository(WORKSPACE, root)
        File(root, "memory").mkdirs()
        val file = File(root, "memory/old.md").apply { writeText("obsolete") }
        service.commit(WORKSPACE, root, listOf("memory/old.md"), "Add old")
        val baseline = service.status(WORKSPACE, root).headCommit

        file.delete()
        val deletion = service.commit(WORKSPACE, root, listOf("memory/old.md"), "Forget old")

        // Forgetting must produce a commit, otherwise the file stays tracked forever.
        assertNotNull(deletion)
        val changed = service.changedSince(WORKSPACE, root, baseline)
        assertTrue("memory/old.md" in changed, "deletion should appear in the diff, got $changed")
        assertTrue(!file.exists())
    }

    @Test
    fun brokenRepositoryDegradesInsteadOfThrowing() {
        // A .git path that is a regular file, not a directory, is unrecoverable by this service.
        File(root, ".git").writeText("not a repository")

        val status = service.ensureRepository(WORKSPACE, root)

        assertEquals(WorkspaceGitState.UNVERSIONED, status.state)
        assertNotNull(status.detail, "degradation should explain itself")
    }

    @Test
    fun commitOnMissingRepositoryReturnsNull() {
        // No ensureRepository call: the service must not silently create one during a commit.
        val result = service.commit(WORKSPACE, root, listOf("memory/MEMORY.md"), "nope")

        assertNull(result)
    }

    private companion object {
        const val WORKSPACE = "ws-1"
    }
}
