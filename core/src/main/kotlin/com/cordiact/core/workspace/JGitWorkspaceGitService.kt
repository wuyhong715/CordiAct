package com.cordiact.core.workspace

import java.io.ByteArrayOutputStream
import java.io.File
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.diff.DiffEntry
import org.eclipse.jgit.diff.DiffFormatter
import org.eclipse.jgit.lib.Constants
import org.eclipse.jgit.lib.ObjectId
import org.eclipse.jgit.lib.PersonIdent
import org.eclipse.jgit.lib.Repository
import org.eclipse.jgit.revwalk.RevWalk
import org.eclipse.jgit.storage.file.FileRepositoryBuilder
import org.eclipse.jgit.treewalk.AbstractTreeIterator
import org.eclipse.jgit.treewalk.CanonicalTreeParser
import org.eclipse.jgit.treewalk.EmptyTreeIterator
import org.eclipse.jgit.treewalk.filter.PathFilterGroup

/**
 * JGit-backed implementation of [WorkspaceGitService].
 *
 * JGit is used rather than shelling out because stock Android ships no git binary. It stays in
 * the app module: the sandbox and plugins must not gain access to a general git client, and
 * this service only ever touches `memory/`.
 *
 * Every failure is swallowed into [WorkspaceGitState.UNVERSIONED] or a null commit. Version
 * control here is an audit and rollback aid for memory, not a precondition for the user's work:
 * a broken repository must not stop them from using the Workspace.
 */
class JGitWorkspaceGitService : WorkspaceGitService {

    private val lock = Any()

    override fun ensureRepository(workspaceId: String, root: File): WorkspaceGitStatus = synchronized(lock) {
        try {
            if (File(root, ".git").isDirectory) {
                return@synchronized statusOf(workspaceId, root)
            }
            root.mkdirs()
            Git.init().setDirectory(root).setInitialBranch(INITIAL_BRANCH).call().use { git ->
                writeGitignore(root)
                commitInternal(git, root, listOf(GITIGNORE_NAME), "Initialize CordiAct Workspace")
            }
            statusOf(workspaceId, root)
        } catch (e: Exception) {
            WorkspaceGitStatus(workspaceId, WorkspaceGitState.UNVERSIONED, detail = e.describe())
        }
    }

    override fun status(workspaceId: String, root: File): WorkspaceGitStatus = synchronized(lock) {
        if (!File(root, ".git").isDirectory) {
            return@synchronized WorkspaceGitStatus(
                workspaceId,
                WorkspaceGitState.UNVERSIONED,
                detail = "Repository not initialized",
            )
        }
        statusOf(workspaceId, root)
    }

    override fun commit(workspaceId: String, root: File, paths: List<String>, message: String): String? {
        if (paths.isEmpty()) return null
        // Validate before touching the repository: a caller bug must not silently widen the commit.
        paths.forEach(::requireMemoryPath)
        synchronized(lock) {
            return try {
                val git = openOrNull(root) ?: return null
                git.use { commitInternal(it, root, paths, message) }
            } catch (e: Exception) {
                // A failed commit must not report a revision that does not exist.
                null
            }
        }
    }

    override fun changedSince(workspaceId: String, root: File, fromCommit: String?): List<String> = synchronized(lock) {
        try {
            val git = openOrNull(root) ?: return@synchronized emptyList()
            git.use { g ->
                val repo = g.repository
                val head = repo.resolve(Constants.HEAD) ?: return@synchronized emptyList()
                val oldTree: AbstractTreeIterator = if (fromCommit == null) {
                    EmptyTreeIterator()
                } else {
                    val from = repo.resolve(fromCommit) ?: return@synchronized emptyList()
                    treeIterator(repo, from)
                }
                val newTree = treeIterator(repo, head)
                diffPaths(repo, oldTree, newTree)
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    // ---- internals ----

    private fun commitInternal(git: Git, root: File, paths: List<String>, message: String): String? {
        for (path in paths) {
            val staged = if (File(root, path).exists()) {
                git.add().addFilepattern(path).call()
                true
            } else {
                // A removed memory file must be staged as a deletion, or it stays tracked forever.
                git.rm().addFilepattern(path).call()
                true
            }
            if (!staged) return null
        }

        val status = git.status().call()
        val dirty = status.added.isNotEmpty() ||
            status.changed.isNotEmpty() ||
            status.removed.isNotEmpty() ||
            status.missing.isNotEmpty()
        if (!dirty) return null

        val ident = PersonIdent("CordiAct", "memory@cordiact.local")
        return git.commit()
            .setMessage(message)
            .setAuthor(ident)
            .setCommitter(ident)
            .setAllowEmpty(false)
            .call()
            .name
    }

    private fun statusOf(workspaceId: String, root: File): WorkspaceGitStatus = try {
        val git = openOrNull(root)
            ?: return WorkspaceGitStatus(workspaceId, WorkspaceGitState.UNVERSIONED, detail = "No repository")
        git.use { g ->
            val head = g.repository.resolve(Constants.HEAD)?.let { ObjectId.toString(it) }
            WorkspaceGitStatus(workspaceId, WorkspaceGitState.READY, headCommit = head)
        }
    } catch (e: Exception) {
        WorkspaceGitStatus(workspaceId, WorkspaceGitState.UNVERSIONED, detail = e.describe())
    }

    private fun openOrNull(root: File): Git? {
        // Must be a directory: a stray regular file named .git is not a repository, and asking
        // JGit to treat it as one produces a "valid" repository with no objects.
        if (!File(root, ".git").isDirectory) return null
        return try {
            val repo = FileRepositoryBuilder().setGitDir(File(root, ".git")).setWorkTree(root).build()
            Git(repo)
        } catch (e: Exception) {
            null
        }
    }

    private fun writeGitignore(root: File) {
        val file = File(root, GITIGNORE_NAME)
        if (!file.exists()) file.writeText(WORKSPACE_GITIGNORE)
    }

    private fun treeIterator(repo: Repository, commit: ObjectId): AbstractTreeIterator {
        RevWalk(repo).use { walk ->
            val tree = walk.parseCommit(commit).tree
            val parser = CanonicalTreeParser()
            repo.newObjectReader().use { reader -> parser.reset(reader, tree) }
            return parser
        }
    }

    private fun diffPaths(
        repo: Repository,
        oldTree: AbstractTreeIterator,
        newTree: AbstractTreeIterator,
    ): List<String> = ByteArrayOutputStream().use { sink ->
        DiffFormatter(sink).use { formatter ->
            formatter.setRepository(repo)
            formatter.setDetectRenames(true)
            formatter.pathFilter = PathFilterGroup.createFromStrings(MEMORY_DIRECTORY.trimEnd('/'))
            formatter.scan(oldTree, newTree)
                .mapNotNull { entry: DiffEntry ->
                    // A deletion has no new path (it is /dev/null), so the old path identifies
                    // the file that disappeared. Deletions must stay visible: forgetting is
                    // exactly the change the consolidation step needs to detect.
                    val path = when (entry.changeType) {
                        DiffEntry.ChangeType.DELETE -> entry.oldPath
                        else -> entry.newPath.ifEmpty { entry.oldPath }
                    }
                    path.takeIf { it.isNotBlank() && it.startsWith(MEMORY_DIRECTORY) }
                }
                .distinct()
                .sorted()
        }
    }

    private fun Throwable.describe(): String = "${this::class.java.simpleName}: ${message ?: "no message"}"

    companion object {
        const val INITIAL_BRANCH = "main"
        private const val GITIGNORE_NAME = ".gitignore"
    }
}

/** Rejects a path that version control must not touch. */
private fun requireMemoryPath(path: String) {
    require(path.isNotBlank()) { "Git path must not be blank" }
    require(!path.startsWith("/")) { "Git path must be Workspace-relative: $path" }
    require(path.split('/').none { it == ".." || it.isEmpty() }) { "Git path must not traverse: $path" }
    require(path.trimEnd('/') == MEMORY_DIRECTORY.trimEnd('/') || path.startsWith(MEMORY_DIRECTORY)) {
        "Git may only operate on $MEMORY_DIRECTORY, got: $path"
    }
}
