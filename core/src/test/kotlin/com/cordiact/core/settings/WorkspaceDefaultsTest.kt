package com.cordiact.core.settings

import com.cordiact.core.conversation.ModelDescriptor
import com.cordiact.core.conversation.ModelId
import com.cordiact.core.conversation.ReasoningEffort
import com.cordiact.core.file.WorkspaceFileContent
import com.cordiact.core.file.WorkspaceFileException
import com.cordiact.core.file.WorkspaceFileMutation
import com.cordiact.core.file.WorkspaceFilePage
import com.cordiact.core.file.WorkspaceFileService
import kotlin.test.*

class WorkspaceDefaultsTest {
    private val files = TestFiles()
    private val defaults = WorkspaceDefaults(files)
    private val document = WorkspaceDefaults.Document.MODELS

    @Test fun `editor preserves unrelated fields and sees an external file edit`() {
        assertNull(defaults.read("a").model)
        assertTrue(files.docs.isEmpty())
        val original = files.external("a", document.path, """schemaVersion = 1
reasoningEffort = "HIGH"
[future]
keep = true""")
        val id = ModelId("provider", "model")
        defaults.setModel("a", original, id)
        val snapshot = defaults.read("a")
        assertEquals(id, snapshot.model)
        assertEquals(ReasoningEffort.HIGH, snapshot.effort)
        assertEquals(true, org.tomlj.Toml.parse(files.docs.getValue("a" to document.path).bytes.decodeToString()).getBoolean("future.keep"))
        assertNull(defaults.read("b").model)
        defaults.setModel("a", snapshot.revisions[document], null)
        assertNull(defaults.read("a").model)
        assertEquals(ReasoningEffort.HIGH, defaults.read("a").effort)
    }

    @Test fun `stale edit fails and invalid document is left untouched`() {
        val path = document.path
        val original = files.external("a", path, "")
        val newer = files.external("a", path, """reasoningEffort = "LOW"""")
        assertEquals(WorkspaceFileException.Code.REVISION_CONFLICT,
            assertFailsWith<WorkspaceFileException> { defaults.setEffort("a", original, ReasoningEffort.HIGH) }.code)
        assertEquals(ReasoningEffort.LOW, defaults.read("a").effort)
        assertEquals(newer, defaults.read("a").revisions[document])
        val invalid = files.external("a", path, """reasoningEffort = 42""")
        assertTrue(document in defaults.read("a").errors)
        assertFailsWith<RuntimeException> { defaults.setEffort("a", invalid, ReasoningEffort.HIGH) }
        assertEquals(invalid, files.docs.getValue("a" to path).revision)
    }

    @Test fun `agent preset document can be edited independently`() {
        val path = WorkspaceDefaults.Document.AGENT.path
        val revision = files.external("a", path, """presetId = "reviewer"""")
        defaults.setEffort("a", null, ReasoningEffort.AUTO)
        assertEquals("reviewer", defaults.read("a").presetId)
        defaults.setPreset("a", revision, null)
        assertNull(defaults.read("a").presetId)
        assertEquals(ReasoningEffort.AUTO, defaults.read("a").effort)
    }

    @Test fun `file overrides cannot change the provider used by a new conversation`() {
        val inherited = ModelId("approved", "baseline")
        val same = ModelId("approved", "other")
        val cross = ModelId("other-provider", "candidate")
        val usable = listOf(inherited, same, cross).map { ModelDescriptor(it, it.modelId) }
        assertEquals(inherited, resolveWorkspaceConversationModel(usable, inherited, cross, cross, cross)?.id)
        assertEquals(same, resolveWorkspaceConversationModel(usable, inherited, cross, null, same)?.id)
        assertEquals(same, resolveWorkspaceConversationModel(usable, null, same, inherited, cross)?.id)
    }

    @Test fun `declarations are offline workspace text and conflicts preserve source`() {
        val drafts = WorkspaceDeclarations(files)
        val kind = WorkspaceDeclarations.Kind.PROVIDER
        val revision = files.external("a", kind.path, "reasoningEffort = \"HIGH\"")
        assertFailsWith<IllegalArgumentException> {
            drafts.add("a", kind, revision, "unsafe", "Name", "https://user:secret@example.com", "OPENAI_RESPONSES")
        }
        drafts.add("a", kind, revision, "service", "Name", "https://example.invalid/v1", "OPENAI_RESPONSES")
        assertEquals("service", drafts.read("a", kind).drafts.single().id)
        assertTrue(drafts.read("b", kind).drafts.isEmpty())
        assertEquals(ReasoningEffort.HIGH, defaults.read("a").effort)
        assertFailsWith<WorkspaceFileException> {
            drafts.add("a", kind, revision, "another", "Name", "https://example.invalid/v1", "OPENAI_RESPONSES")
        }
    }

    @Test fun `tool files only restrict the chosen workspace and fail closed`() {
        val prefs = WorkspaceToolPreferences(files)
        val path = ".cordiact/tools.toml"
        val first = files.external("a", path, """disabledTools = ["core.mcp.one"]""" + "\n[future]\nkeep = true")
        assertFalse(prefs.allows("a", "core.mcp.one", false))
        assertTrue(prefs.allows("b", "core.mcp.one", false))
        prefs.setSearchDisabled("a", first, true)
        assertFalse(prefs.allows("a", "web_search", true))
        assertTrue(prefs.allows("a", "other", false))
        assertFailsWith<WorkspaceFileException> { prefs.setDisabled("a", first, "core.mcp.one", false) }
        prefs.setDisabled("a", prefs.read("a").revision, "core.mcp.one", false)
        assertTrue(prefs.allows("a", "core.mcp.one", false))
        assertEquals(true, org.tomlj.Toml.parse(files.docs.getValue("a" to path).bytes.decodeToString()).getBoolean("future.keep"))
        files.external("a", path, "disabledTools = [42]")
        assertFalse(prefs.allows("a", "other", false))
    }

    private class TestFiles : WorkspaceFileService {
        val docs = mutableMapOf<Pair<String, String>, WorkspaceFileContent>()
        private var revision = 0
        fun external(workspace: String, path: String, text: String): String =
            requireNotNull(write(workspace, path, text.encodeToByteArray(), docs[workspace to path]?.revision).revision)
        override fun read(workspaceId: String, relativePath: String, offset: Long, maxBytes: Int) =
            docs[workspaceId to relativePath] ?: throw WorkspaceFileException(WorkspaceFileException.Code.FILE_NOT_FOUND, "Missing")
        override fun write(workspaceId: String, relativePath: String, bytes: ByteArray, expectedRevision: String?): WorkspaceFileMutation {
            val key = workspaceId to relativePath
            if (docs[key]?.revision != expectedRevision) throw WorkspaceFileException(WorkspaceFileException.Code.REVISION_CONFLICT, "Stale")
            val next = (++revision).toString()
            docs[key] = WorkspaceFileContent(bytes, next)
            return WorkspaceFileMutation(next)
        }
        override fun list(workspaceId: String, relativeDirectory: String, cursor: String?, limit: Int): WorkspaceFilePage = error("unused")
        override fun delete(workspaceId: String, relativePath: String, expectedRevision: String): WorkspaceFileMutation = error("unused")
        override fun move(workspaceId: String, sourceRelativePath: String, destinationRelativePath: String, expectedRevision: String): WorkspaceFileMutation = error("unused")
    }
}
