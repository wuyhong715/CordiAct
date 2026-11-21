package com.cordiact.core.workspace

import java.io.File
import java.nio.file.Files
import kotlin.test.*
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.lib.PersonIdent

class WorkspaceGitHistoryTest {
    @Test fun readsRealCommitTopologyAndScopesHistoryToHead() {
        val root = Files.createTempDirectory("cordiact-history").toFile()
        try {
            assertNull(WorkspaceGitHistory.read(root))
            Git.init().setDirectory(root).setInitialBranch("main").call().use { git ->
                assertTrue(assertNotNull(WorkspaceGitHistory.read(root)).commits.isEmpty())
                val ident = PersonIdent("CordiAct test", "test@example.invalid")
                fun commit(path: String) = run {
                    File(root, path).writeText(path)
                    git.add().addFilepattern(path).call()
                    git.commit().setAuthor(ident).setCommitter(ident).setMessage(path).call()
                }
                val first = commit("root.txt")
                git.checkout().setCreateBranch(true).setName("design").call()
                val design = commit("design.txt")
                git.checkout().setName("main").call()
                val main = commit("main.txt")
                git.repository.config.apply {
                    setString("user", null, "name", ident.name)
                    setString("user", null, "email", ident.emailAddress)
                    save()
                }
                git.merge().include(design).call()
                val merge = git.repository.resolve("HEAD").name
                git.checkout().setCreateBranch(true).setStartPoint(first.name).setName("unmerged").call()
                val unmerged = commit("unmerged.txt")
                git.checkout().setName("main").call()
                val history = assertNotNull(WorkspaceGitHistory.read(root))
                assertEquals(merge, history.head)
                assertEquals(setOf("main", "design", "unmerged"), history.branches.keys)
                val byId = history.commits.associateBy { it.id }
                assertEquals(listOf(main.name, design.name), byId.getValue(merge).parents)
                assertTrue(byId.getValue(design.name).onHead)
                assertFalse(byId.getValue(unmerged.name).onHead)
                assertEquals(listOf("design.txt"), assertNotNull(WorkspaceGitHistory.detail(root, merge)).paths)
                assertTrue(assertNotNull(WorkspaceGitHistory.read(root, limit = 2)).truncated)
                assertEquals(merge, git.repository.resolve("HEAD").name)
                assertTrue(git.status().call().isClean)
            }
        } finally { root.deleteRecursively() }
    }
}
