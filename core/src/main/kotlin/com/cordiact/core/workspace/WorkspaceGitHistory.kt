package com.cordiact.core.workspace

import java.io.File
import org.eclipse.jgit.lib.Constants
import org.eclipse.jgit.lib.ObjectId
import org.eclipse.jgit.lib.Repository
import org.eclipse.jgit.revwalk.RevSort
import org.eclipse.jgit.revwalk.RevWalk
import org.eclipse.jgit.storage.file.FileRepositoryBuilder
import org.eclipse.jgit.treewalk.TreeWalk
import org.eclipse.jgit.treewalk.filter.TreeFilter

/** A read-only snapshot of local branches and HEAD, scoped to the managed workspace root. */
data class WorkspaceGitCommit(
    val id: String,
    val title: String,
    val timeMillis: Long,
    val parents: List<String>,
    val refs: List<String>,
    val onHead: Boolean,
)

data class WorkspaceGitSnapshot(
    val commits: List<WorkspaceGitCommit>, // descendants before parents
    val branches: Map<String, String>,
    val head: String?,
    val headBranch: String?,
    val truncated: Boolean,
)

data class WorkspaceGitCommitDetail(val message: String, val paths: List<String>, val pathsTruncated: Boolean)

object WorkspaceGitHistory {
    fun read(root: File, limit: Int = 180): WorkspaceGitSnapshot? = open(root)?.use { repo ->
        val max = limit.coerceIn(1, 300)
        val branches = repo.refDatabase.getRefsByPrefix(Constants.R_HEADS).mapNotNull { ref ->
            ref.objectId?.name?.let { Repository.shortenRefName(ref.name) to it }
        }.toMap().toSortedMap()
        val head = repo.resolve(Constants.HEAD)?.name
        val headBranch = repo.fullBranch?.takeIf { it.startsWith(Constants.R_HEADS) }?.let(Repository::shortenRefName)
        val refs = branches.entries.groupBy({ it.value }, { it.key })
        val ids = (listOfNotNull(head) + branches.values).distinct()
        RevWalk(repo).use { walk ->
            walk.sort(RevSort.TOPO)
            // A dedicated flag follows every parent, including both sides of a merge.
            val onHead = walk.newFlag("reachable from HEAD")
            walk.carry(onHead)
            for (id in ids) {
                val commit = walk.parseCommit(ObjectId.fromString(id))
                if (id == head) commit.add(onHead)
                walk.markStart(commit)
            }
            val rows = buildList {
                repeat(max) {
                    val c = walk.next() ?: return@buildList
                    add(WorkspaceGitCommit(c.name, c.shortMessage, c.commitTime.toLong() * 1000L,
                        c.parents.map { it.name }, refs[c.name].orEmpty(), c.has(onHead)))
                }
            }
            WorkspaceGitSnapshot(rows, branches, head, headBranch, walk.next() != null)
        }
    }

    /** File names only, with merge files measured against the first parent. No blobs are read. */
    fun detail(root: File, id: String): WorkspaceGitCommitDetail? = open(root)?.use { repo ->
        RevWalk(repo).use { walk ->
            val commit = walk.parseCommit(ObjectId.fromString(id))
            val parent = if (commit.parentCount == 0) null else walk.parseCommit(commit.getParent(0)).tree
            TreeWalk(repo).use { tree ->
                if (parent != null) tree.addTree(parent)
                tree.addTree(commit.tree)
                tree.isRecursive = true
                if (parent != null) tree.filter = TreeFilter.ANY_DIFF
                val paths = buildList {
                    repeat(60) { if (!tree.next()) return@buildList; add(tree.pathString) }
                }
                WorkspaceGitCommitDetail(commit.fullMessage.take(2000), paths, tree.next())
            }
        }
    }

    private fun open(root: File): Repository? {
        val safeRoot = root.canonicalFile
        val git = File(safeRoot, Constants.DOT_GIT)
        if (!git.isDirectory || git.canonicalFile != git) return null
        return FileRepositoryBuilder().setGitDir(git).setWorkTree(safeRoot).setMustExist(true).build()
    }
}
