package com.cordiact.core.memory

import com.cordiact.core.workspace.JGitWorkspaceGitService
import com.cordiact.core.workspace.WorkspaceGitState
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

private const val WORKSPACE = "ws-1"
private const val NOW = 1_800_000_000_000L

class MemoryConsolidationRunnerTest {

    private lateinit var root: File
    private lateinit var store: InMemoryArtifactStore
    private lateinit var git: JGitWorkspaceGitService
    private lateinit var runner: MemoryConsolidationRunner

    @BeforeTest
    fun setUp() {
        root = Files.createTempDirectory("cordiact-consolidate").toFile()
        store = InMemoryArtifactStore(root)
        git = JGitWorkspaceGitService()
        runner = MemoryConsolidationRunner(store, clockMillis = { NOW })
        git.ensureRepository(WORKSPACE, root)
    }

    @AfterTest
    fun tearDown() {
        root.deleteRecursively()
    }

    @Test
    fun commitsConsolidatedArtifacts() {
        val outcome = runner.consolidate(
            WORKSPACE,
            listOf(candidate("s1")),
            ConsolidationDiff(added = setOf("s1")),
            git,
            root,
        )

        assertTrue(outcome is ConsolidationOutcome.Committed, "expected commit, got $outcome")
        assertNotNull(outcome.commit)
        val head = git.status(WORKSPACE, root).headCommit
        assertEquals(outcome.commit, head)
        assertTrue(store.exists(WORKSPACE, MemoryPaths.MANUAL))
        assertTrue(store.exists(WORKSPACE, MemoryPaths.SUMMARY))
    }

    @Test
    fun forgetRemovesEvidenceOnDisk() {
        runner.consolidate(
            WORKSPACE,
            listOf(candidate("s1"), candidate("s2")),
            ConsolidationDiff(added = setOf("s1", "s2")),
            git,
            root,
        )
        assertTrue(store.exists(WORKSPACE, MemoryPaths.evidencePath("s2")))

        // s2 leaves the diff set: its evidence must actually disappear, not just vanish from
        // the manual.
        runner.consolidate(
            WORKSPACE,
            listOf(candidate("s1")),
            ConsolidationDiff(retained = setOf("s1"), removed = setOf("s2")),
            git,
            root,
        )

        assertTrue(store.exists(WORKSPACE, MemoryPaths.evidencePath("s1")))
        assertTrue(!store.exists(WORKSPACE, MemoryPaths.evidencePath("s2")), "evicted evidence must be deleted")
        assertTrue("s2" !in (store.read(WORKSPACE, MemoryPaths.MANUAL, 100_000) ?: ""))
    }

    @Test
    fun retreatToNothingStillCommitsTheDeletion() {
        runner.consolidate(
            WORKSPACE,
            listOf(candidate("s1")),
            ConsolidationDiff(added = setOf("s1")),
            git,
            root,
        )
        val headBefore = git.status(WORKSPACE, root).headCommit

        runner.consolidate(
            WORKSPACE,
            emptyList(),
            ConsolidationDiff(removed = setOf("s1")),
            git,
            root,
        )

        val headAfter = git.status(WORKSPACE, root).headCommit
        assertTrue(headBefore != headAfter, "deleting the last memory must advance HEAD")
        assertTrue(!store.exists(WORKSPACE, MemoryPaths.evidencePath("s1")))
    }

    @Test
    fun rejectedRoundLeavesPreviousArtifactsIntact() {
        runner.consolidate(
            WORKSPACE,
            listOf(candidate("s1")),
            ConsolidationDiff(added = setOf("s1")),
            git,
            root,
        )
        val manualBefore = store.read(WORKSPACE, MemoryPaths.MANUAL, 100_000)
        val summaryBefore = store.read(WORKSPACE, MemoryPaths.SUMMARY, 100_000)
        val headBefore = git.status(WORKSPACE, root).headCommit

        // An oversized candidate is rejected during render, before anything is written.
        val tight = MemoryConsolidationRunner(
            store,
            policy = ConsolidationPolicy(maxManualChars = 300, maxSummaryChars = 200),
            clockMillis = { NOW },
        )
        val outcome = tight.consolidate(
            WORKSPACE,
            listOf(candidate("s2", body = "x".repeat(500))),
            ConsolidationDiff(added = setOf("s2")),
            git,
            root,
        )

        assertTrue(outcome is ConsolidationOutcome.Rejected, "expected rejection, got $outcome")
        assertEquals(manualBefore, store.read(WORKSPACE, MemoryPaths.MANUAL, 100_000))
        assertEquals(summaryBefore, store.read(WORKSPACE, MemoryPaths.SUMMARY, 100_000))
        assertEquals(headBefore, git.status(WORKSPACE, root).headCommit)
    }

    @Test
    fun worksWithoutGit() {
        // Memory must still consolidate before a repository exists; it just cannot be rolled back.
        val outcome = runner.consolidate(
            WORKSPACE,
            listOf(candidate("s1")),
            ConsolidationDiff(added = setOf("s1")),
            git = null,
            workspaceRoot = null,
        )

        assertTrue(outcome is ConsolidationOutcome.Committed)
        assertTrue(store.exists(WORKSPACE, MemoryPaths.MANUAL))
    }

    @Test
    fun noOpRoundsProduceNoCommit() {
        val candidates = listOf(candidate("s1"))
        val diff = ConsolidationDiff(added = setOf("s1"))

        val first = runner.consolidate(WORKSPACE, candidates, diff, git, root)
        val headAfterFirst = git.status(WORKSPACE, root).headCommit
        // Same content, but the session is now retained rather than newly added.
        val second = runner.consolidate(WORKSPACE, candidates, ConsolidationDiff(retained = setOf("s1")), git, root)

        assertTrue(first is ConsolidationOutcome.Committed, "expected first commit, got $first")
        assertTrue(second is ConsolidationOutcome.Committed, "expected second to be accepted, got $second")
        assertEquals(null, (second as ConsolidationOutcome.Committed).commit, "identical artifacts must not produce a commit")
        assertEquals(headAfterFirst, git.status(WORKSPACE, root).headCommit)
    }

    @Test
    fun gitRepositoryStaysCleanAfterConsolidation() {
        runner.consolidate(
            WORKSPACE,
            listOf(candidate("s1")),
            ConsolidationDiff(added = setOf("s1")),
            git,
            root,
        )

        // Everything written under memory/ should be committed, so a follow-up diff is empty.
        val head = git.status(WORKSPACE, root).headCommit
        assertEquals(WorkspaceGitState.READY, git.status(WORKSPACE, root).state)
        assertEquals(emptyList(), git.changedSince(WORKSPACE, root, head))
    }

    private fun candidate(session: String, body: String = "body of $session") = MemoryCandidate(
        sessionId = session,
        title = "title $session",
        body = body,
        generatedAtMillis = NOW,
    )
}

/**
 * Artifact store backed by a real directory.
 *
 * It must touch the filesystem, not a map: the consolidation runner commits through git, and
 * an in-memory store would make every commit a no-op. That mismatch is exactly what a
 * fake-backed test would hide.
 */
private class InMemoryArtifactStore(private val root: File) : MemoryArtifactStore {

    override fun read(workspaceId: String, path: String, maxChars: Int): String? {
        val file = File(root, path)
        if (!file.isFile) return null
        return file.readText().take(maxChars)
    }

    override fun write(workspaceId: String, path: String, content: String) {
        // Only memory paths are storable, mirroring the real store's constraint.
        require(MemoryPaths.contains(path)) { "Refusing to store outside memory/: $path" }
        val file = File(root, path)
        file.parentFile?.mkdirs()
        file.writeText(content)
    }

    override fun delete(workspaceId: String, path: String) {
        File(root, path).delete()
    }

    override fun exists(workspaceId: String, path: String): Boolean = File(root, path).isFile

    override fun evidenceFiles(workspaceId: String): List<String> {
        val dir = File(root, MemoryPaths.EVIDENCE_PREFIX)
        if (!dir.isDirectory) return emptyList()
        return dir.listFiles()?.filter { it.isFile }
            ?.map { MemoryPaths.EVIDENCE_PREFIX + it.name }
            ?.sorted()
            ?: emptyList()
    }
}
