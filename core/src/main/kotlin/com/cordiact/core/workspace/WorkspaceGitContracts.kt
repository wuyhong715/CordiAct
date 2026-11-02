package com.cordiact.core.workspace

import java.io.File

/** Outcome of ensuring a Workspace root is a git repository. */
enum class WorkspaceGitState {
    /** The repository exists and is usable. */
    READY,

    /** Initialization was attempted and failed. Memory work degrades, the Workspace still works. */
    UNVERSIONED,
}

data class WorkspaceGitStatus(
    val workspaceId: String,
    val state: WorkspaceGitState,
    /** The current HEAD commit id when READY and at least one commit exists. */
    val headCommit: String? = null,
    /** Human-readable reason when UNVERSIONED. */
    val detail: String? = null,
)

/**
 * Version control for Workspace memory.
 *
 * Scope is deliberately narrow: commit, diff and rollback for paths under `memory/`, plus
 * initializing the repository. It is not a general git client and must not become one.
 *
 * Initialization is lazy and never blocks Workspace use. A failure downgrades memory work to
 * [WorkspaceGitState.UNVERSIONED]; it does not prevent the user from working.
 */
interface WorkspaceGitService {
    /** Ensures the repository exists, creating it on first call. Safe to call repeatedly. */
    fun ensureRepository(workspaceId: String, root: File): WorkspaceGitStatus

    /** Current status without creating anything. */
    fun status(workspaceId: String, root: File): WorkspaceGitStatus

    /**
     * Commits the given Workspace-relative paths.
     *
     * Implementations must reject paths outside [MEMORY_DIRECTORY]: committing the whole
     * Workspace would capture the user's media and documents on every memory write.
     */
    fun commit(workspaceId: String, root: File, paths: List<String>, message: String): String?

    /** Files changed between [fromCommit] (exclusive) and HEAD. Empty when nothing changed. */
    fun changedSince(workspaceId: String, root: File, fromCommit: String?): List<String>
}

/** The only subtree version control operates on. */
const val MEMORY_DIRECTORY = "memory/"

/**
 * Default ignores for a Workspace repository.
 *
 * Everything is ignored except `memory/`. This is the second line of defence behind the
 * path check in the implementation: even an accidental `add -A` cannot capture the user's
 * documents, media or build output.
 */
val WORKSPACE_GITIGNORE = buildString {
    appendLine("# CordiAct manages this repository for memory versioning.")
    appendLine("# Everything outside memory/ is intentionally ignored: a memory write must never")
    appendLine("# commit the user's documents, media or build output.")
    appendLine("*")
    appendLine("!memory/")
    appendLine("!memory/**")
    appendLine("!.gitignore")
}
