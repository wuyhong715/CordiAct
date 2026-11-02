package com.cordiact.core.memory

import com.cordiact.core.workspace.WorkspaceGitService
import com.cordiact.core.workspace.WorkspaceGitState
import java.io.File

/**
 * Runs one Phase 2 consolidation: rank, render, validate, write, commit.
 *
 * The order is the design, not an implementation detail. Artifacts are rendered, validated
 * against budget and citation rules, and only then written and committed. A rejected round
 * therefore leaves the previous artifacts untouched rather than replacing good memory with a
 * half-consistent set.
 */
class MemoryConsolidationRunner(
    private val store: MemoryArtifactStore,
    private val validator: MemoryArtifactValidator = MemoryArtifactValidator(),
    private val policy: ConsolidationPolicy = ConsolidationPolicy(),
    private val clockMillis: () -> Long = System::currentTimeMillis,
) {

    /**
     * Performs one consolidation.
     *
     * [git] and [workspaceRoot] are optional so the runner stays usable before a Workspace has
     * a repository: memory still consolidates, it just cannot be committed or rolled back.
     */
    fun consolidate(
        workspaceId: String,
        candidates: List<MemoryCandidate>,
        diff: ConsolidationDiff,
        git: WorkspaceGitService? = null,
        workspaceRoot: File? = null,
    ): ConsolidationOutcome {
        val ranked = MemoryCandidateRanker.rank(candidates, policy, clockMillis())

        val artifacts = try {
            MemoryConsolidator.render(ranked, diff, policy)
        } catch (e: MemoryArtifactException) {
            return ConsolidationOutcome.Rejected(e.message ?: "Render exceeded a budget", e)
        }

        // Validation runs against the evidence this round will produce plus what is already on
        // disk. Checking only the new set would report every retained citation as dangling.
        val evidencePaths = artifacts.evidence.keys + survivingEvidenceOnDisk(workspaceId, diff)
        val validation = try {
            validator.validate(artifacts.summary, artifacts.manual, evidencePaths)
        } catch (e: MemoryArtifactException) {
            return ConsolidationOutcome.Rejected(e.message ?: "Artifact validation failed", e)
        }
        if (validation.hasDanglingCitations) {
            return ConsolidationOutcome.Rejected(
                "Dangling citations: " + validation.danglingCitations.joinToString { "${it.file}:${it.line}" },
            )
        }

        val previousHead = gitHead(git, workspaceId, workspaceRoot)
        // Whether this round actually changes anything on disk. An idempotent round must be
        // accepted without a commit: git is right not to record a no-op, and treating that as
        // a failure would make every repeat run look broken.
        val changed = artifactsDiffer(workspaceId, artifacts)

        val written = try {
            writeArtifacts(workspaceId, artifacts)
        } catch (e: Exception) {
            return ConsolidationOutcome.Rejected("Could not write artifacts: ${e.message}")
        }

        val commit = commitIfPossible(git, workspaceId, workspaceRoot, written)

        // The git check is the last gate. If a repository is present, HEAD must advance after a
        // write that changed content; otherwise the write did not take effect and the round is
        // void. This is the stand-in for codex's git-diff verification, and it only makes sense
        // because the artifacts are committed rather than merely overwritten.
        if (changed && git != null && workspaceRoot != null && written.isNotEmpty() && previousHead != null) {
            val status = try {
                git.status(workspaceId, workspaceRoot)
            } catch (e: Exception) {
                null
            }
            if (status != null && status.state == WorkspaceGitState.READY && status.headCommit == previousHead) {
                return ConsolidationOutcome.Rejected("HEAD did not advance after a non-empty write")
            }
        }

        return ConsolidationOutcome.Committed(commit)
    }

    private fun writeArtifacts(
        workspaceId: String,
        artifacts: ConsolidatedArtifacts,
    ): List<String> {
        val written = mutableListOf<String>()
        store.write(workspaceId, MemoryPaths.SUMMARY, artifacts.summary)
        written += MemoryPaths.SUMMARY
        store.write(workspaceId, MemoryPaths.MANUAL, artifacts.manual)
        written += MemoryPaths.MANUAL
        for ((path, content) in artifacts.evidence) {
            store.write(workspaceId, path, content)
            written += path
        }
        // Evicted sessions must lose their evidence, or "forget" only hides the entry while the
        // underlying record survives on disk.
        for (session in artifacts.evictedSessions) {
            val path = MemoryPaths.evidencePath(session)
            if (store.exists(workspaceId, path)) {
                store.delete(workspaceId, path)
                written += path
            }
        }
        return written
    }

    private fun commitIfPossible(
        git: WorkspaceGitService?,
        workspaceId: String,
        workspaceRoot: File?,
        paths: List<String>,
    ): String? {
        if (git == null || workspaceRoot == null || paths.isEmpty()) return null
        return try {
            git.commit(workspaceId, workspaceRoot, paths, "Consolidate memory")
        } catch (e: Exception) {
            null
        }
    }

    private fun gitHead(git: WorkspaceGitService?, workspaceId: String, root: File?): String? {
        if (git == null || root == null) return null
        return try {
            git.status(workspaceId, root).headCommit
        } catch (e: Exception) {
            null
        }
    }

    /** True when writing [artifacts] would change something compared to what is on disk now. */
    private fun artifactsDiffer(workspaceId: String, artifacts: ConsolidatedArtifacts): Boolean {
        if (store.read(workspaceId, MemoryPaths.SUMMARY, Int.MAX_VALUE) != artifacts.summary) return true
        if (store.read(workspaceId, MemoryPaths.MANUAL, Int.MAX_VALUE) != artifacts.manual) return true
        for ((path, content) in artifacts.evidence) {
            if (store.read(workspaceId, path, Int.MAX_VALUE) != content) return true
        }
        for (session in artifacts.evictedSessions) {
            if (store.exists(workspaceId, MemoryPaths.evidencePath(session))) return true
        }
        return false
    }

    private fun survivingEvidenceOnDisk(workspaceId: String, diff: ConsolidationDiff): Set<String> =
        try {
            store.evidenceFiles(workspaceId)
                .filter { path -> diff.surviving.any { path == MemoryPaths.evidencePath(it) } }
                .toSet()
        } catch (e: Exception) {
            emptySet()
        }
}
