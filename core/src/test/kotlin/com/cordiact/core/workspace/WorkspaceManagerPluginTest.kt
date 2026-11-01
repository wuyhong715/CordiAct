package com.cordiact.core.workspace

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WorkspaceManagerPluginTest {
    @Test
    fun defaultWorkspaceInitializationIsIdempotentAndProtected() {
        val store = FakeWorkspaceStore()
        val root = tempRoot()
        val plugin = plugin(store, root)
        plugin.onRegister()
        plugin.onEnable()

        val first = plugin.initializeDefaultIfEligible()
        val second = plugin.initializeDefaultIfEligible()
        assertEquals(first, second)
        assertEquals(DEFAULT_WORKSPACE_ID, first.id)
        assertEquals(DEFAULT_WORKSPACE_NAME, first.name)
        assertFailsWith<IllegalArgumentException> { plugin.rename(DEFAULT_WORKSPACE_ID, "Changed") }
        assertFailsWith<IllegalArgumentException> { plugin.archive(DEFAULT_WORKSPACE_ID) }
        assertTrue(plugin.resolveRoot(DEFAULT_WORKSPACE_ID).canonicalPath.startsWith(root.canonicalPath))
    }

    @Test
    fun workspaceCrudArchiveRestoreAndSelectionAreStable() {
        val root = tempRoot()
        val store = FakeWorkspaceStore()
        val plugin = WorkspaceManagerPlugin(store, root, clockMillis = { 10L }, idFactory = { "project-a" })
        plugin.onRegister()
        plugin.onEnable()

        val created = plugin.create("  Project A  ")
        assertEquals("Project A", created.name)
        assertTrue(plugin.resolveRoot(created.id).isDirectory)
        assertEquals("Renamed", plugin.rename(created.id, "Renamed").name)
        assertEquals(created.id, plugin.select(created.id).id)
        assertEquals(created.id, plugin.activeWorkspace().id)

        val archived = plugin.archive(created.id)
        assertEquals(10L, archived.archivedAtMillis)
        assertNull(plugin.findSessionWorkspace(created.id))
        assertFailsWith<IllegalArgumentException> { plugin.resolveRoot(created.id) }
        assertEquals(listOf(DEFAULT_WORKSPACE_ID), plugin.list().map { it.id })
        assertEquals(2, plugin.list(includeArchived = true).size)

        assertEquals(null, plugin.restore(created.id).archivedAtMillis)
        assertEquals(created.id, plugin.findSessionWorkspace(created.id)?.id)
        assertEquals(root.canonicalPath, plugin.resolveRoot(created.id).canonicalFile.parentFile.parentFile.canonicalPath)
    }

    @Test
    fun invalidNamesAndDisabledCallsAreRejected() {
        val store = FakeWorkspaceStore()
        val plugin = plugin(store)
        assertFailsWith<IllegalStateException> { plugin.list() }
        plugin.onRegister()
        plugin.onEnable()
        assertFailsWith<IllegalArgumentException> { plugin.create("  ") }
        assertFailsWith<IllegalArgumentException> { plugin.rename("missing", "Name") }
        plugin.onDisable()
        assertFailsWith<IllegalStateException> { plugin.activeWorkspace() }
    }

    private fun tempRoot(): File = Files.createTempDirectory("cordiact-workspace-test").toFile()

    private fun plugin(store: WorkspaceStore, root: File = tempRoot()) = WorkspaceManagerPlugin(
        store = store,
        privateRoot = root,
        clockMillis = { 1L },
        idFactory = { "workspace-test" },
    )

    private class FakeWorkspaceStore : WorkspaceStore {
        private val workspaces = linkedMapOf<String, Workspace>()
        private val roots = mutableMapOf<String, String>()
        private var activeId: String? = null

        override fun ensureDefault(nowMillis: Long): Workspace {
            val workspace = workspaces.getOrPut(DEFAULT_WORKSPACE_ID) {
                Workspace(DEFAULT_WORKSPACE_ID, DEFAULT_WORKSPACE_NAME, nowMillis, nowMillis)
            }
            roots.putIfAbsent(DEFAULT_WORKSPACE_ID, "workspaces/default")
            activeId = activeId ?: DEFAULT_WORKSPACE_ID
            return workspace
        }

        override fun createWorkspace(id: String, name: String, rootRelativePath: String, nowMillis: Long): Workspace {
            val workspace = Workspace(id, name, nowMillis, nowMillis)
            workspaces[id] = workspace
            roots[id] = rootRelativePath
            return workspace
        }

        override fun getWorkspace(id: String) = workspaces[id]
        override fun listWorkspaces(includeArchived: Boolean) = workspaces.values.filter { includeArchived || it.archivedAtMillis == null }

        override fun renameWorkspace(id: String, name: String, nowMillis: Long): Workspace = update(id) {
            it.copy(name = name, updatedAtMillis = nowMillis)
        }

        override fun archiveWorkspace(id: String, nowMillis: Long): Workspace = update(id) {
            it.copy(archivedAtMillis = nowMillis, updatedAtMillis = nowMillis)
        }.also {
            if (activeId == id) activeId = DEFAULT_WORKSPACE_ID
        }

        override fun restoreWorkspace(id: String, nowMillis: Long): Workspace = update(id) {
            it.copy(archivedAtMillis = null, updatedAtMillis = nowMillis)
        }

        override fun selectWorkspace(id: String, nowMillis: Long): Workspace =
            findSessionWorkspace(id)?.also { activeId = id } ?: error("missing or archived")

        override fun activeWorkspace() = activeId?.let(workspaces::get)
        override fun rootRelativePath(id: String) = roots[id]
        override fun findSessionWorkspace(id: String) = workspaces[id]?.takeIf { it.archivedAtMillis == null }

        private fun update(id: String, transform: (Workspace) -> Workspace): Workspace {
            val current = workspaces[id]?.takeIf { it.archivedAtMillis == null } ?: throw IllegalArgumentException("missing or archived")
            return transform(current).also { workspaces[id] = it }
        }
    }
}
