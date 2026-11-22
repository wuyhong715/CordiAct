package com.cordiact.core.plugin

import com.cordiact.core.file.WorkspaceFileContent
import com.cordiact.core.file.WorkspaceFileEntry
import com.cordiact.core.file.WorkspaceFileException
import com.cordiact.core.file.WorkspaceFileException.Code
import com.cordiact.core.file.WorkspaceFilePage
import com.cordiact.core.file.WorkspaceFileService
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull

class AuthoredPluginDraftsTest {
    @Test
    fun listsOnlyCanonicalTopLevelDraftMetadataInTheRequestedWorkspace() {
        val files = Files()
        val drafts = AuthoredPluginDrafts(files)
        val longest = "a".repeat(24)
        files.onList = { call ->
            when (call.workspaceId) {
                "workspace-a" -> page(
                    file("a-2.json", 0),
                    file("big-file.json", AuthoredPluginSource.MAX_SOURCE_BYTES.toLong() + 1),
                    file("$longest.json", 37),
                    file("directory.json", directory = true),
                    file("UPPER.json"), file("x.json"), file("a_b.json"), file("a.b.json"), file("9start.json"),
                    file("${longest}z.json"), file(".hidden.json"), file("backup.json.bak"), file("note.JSON"),
                    file("noextension"), file(".json"), file("../escape.json"), file("other/escape.json"),
                    file("other\\escape.json"), file("/absolute.json"), file("plugins/escape.json"), file("nu\u0000ll.json"),
                )
                "workspace-b" -> page(file("different.json", 25))
                else -> error("Unexpected workspace")
            }
        }

        assertEquals(
            AuthoredPluginDraftPage(listOf(
                AuthoredPluginDraftEntry("a-2", 0),
                AuthoredPluginDraftEntry("big-file", AuthoredPluginSource.MAX_SOURCE_BYTES.toLong() + 1),
                AuthoredPluginDraftEntry(longest, 37),
            ), null),
            drafts.list("workspace-a"),
        )
        assertEquals(listOf(AuthoredPluginDraftEntry("different", 25)), drafts.list("workspace-b").entries)
        assertEquals(listOf(
            ListCall("workspace-a", "plugins", null, 50),
            ListCall("workspace-b", "plugins", null, 50),
        ), files.listCalls)
        assertEquals(emptyList(), files.readCalls)
    }

    @Test
    fun passesThroughOpaqueCursorsAndKeepsTheCursorOfAnEmptyPage() {
        val files = Files()
        val drafts = AuthoredPluginDrafts(files)
        val firstCursor = "opaque:First/1+="
        val secondCursor = "opaque:Second/2+="
        val thirdCursor = "opaque:Third/3+="
        files.onList = { call ->
            when (call.cursor) {
                null -> page(file("aa.json"), next = firstCursor)
                firstCursor -> page(file("not-a-draft.txt"), file("nested.json", directory = true), next = secondCursor)
                secondCursor -> page(next = thirdCursor)
                thirdCursor -> page(file("bb.json"))
                else -> error("Cursor must be passed through unchanged")
            }
        }

        assertEquals(AuthoredPluginDraftPage(listOf(AuthoredPluginDraftEntry("aa", 7)), firstCursor), drafts.list("workspace"))
        assertEquals(1, files.listCalls.size)
        assertEquals(AuthoredPluginDraftPage(emptyList(), secondCursor), drafts.list("workspace", firstCursor))
        assertEquals(2, files.listCalls.size)
        assertEquals(AuthoredPluginDraftPage(emptyList(), thirdCursor), drafts.list("workspace", secondCursor))
        assertEquals(3, files.listCalls.size)
        assertEquals(AuthoredPluginDraftPage(listOf(AuthoredPluginDraftEntry("bb", 7)), null), drafts.list("workspace", thirdCursor))
        assertEquals(listOf(null, firstCursor, secondCursor, thirdCursor), files.listCalls.map { it.cursor })
        assertEquals(emptyList(), files.readCalls)
    }

    @Test
    fun rejectsInvalidAndNonProgressingPaginationWithoutRetrying() {
        val files = Files()
        val drafts = AuthoredPluginDrafts(files)
        val oversized = "x".repeat(16 * 1024)
        for (cursor in listOf("", oversized)) {
            assertWorkspaceError(Code.CURSOR_INVALID) { drafts.list("workspace", cursor) }
        }
        assertEquals(emptyList(), files.listCalls)

        files.onList = { page(next = it.cursor) }
        assertWorkspaceError(Code.CURSOR_INVALID) { drafts.list("workspace", "same-token") }
        assertEquals(1, files.listCalls.size)

        for (next in listOf("", oversized)) {
            files.onList = { page(next = next) }
            assertWorkspaceError(Code.CURSOR_INVALID) { drafts.list("workspace") }
        }
        assertEquals(3, files.listCalls.size)

        files.onList = { WorkspaceFilePage(List(it.limit + 1) { file("aa.json") }, null) }
        assertWorkspaceError(Code.STORAGE_FAILURE) { drafts.list("workspace") }
        assertEquals(4, files.listCalls.size)
    }

    @Test
    fun distinguishesMissingDirectoryFromStorageWorkspaceAndCursorErrors() {
        val files = Files()
        val drafts = AuthoredPluginDrafts(files)
        files.onList = { throw untrustedFailure(Code.FILE_NOT_FOUND) }
        assertEquals(AuthoredPluginDraftPage(emptyList(), null), drafts.list("workspace"))
        for (code in listOf(Code.WORKSPACE_UNAVAILABLE, Code.NOT_A_REGULAR_FILE, Code.SYMLINK_NOT_ALLOWED,
            Code.PATH_OUTSIDE_WORKSPACE, Code.CURSOR_INVALID, Code.STORAGE_FAILURE)) {
            files.onList = { throw untrustedFailure(code) }
            assertWorkspaceError(code) { drafts.list("workspace") }
        }
        assertEquals(emptyList(), files.readCalls)
    }

    @Test
    fun validatesPreviewIdsBeforeAccessingFilesAndPreservesFileErrorCodes() {
        val files = Files()
        val drafts = AuthoredPluginDrafts(files)
        for (id in listOf("../escape", "/absolute", "ab/cd", "ab\\cd", "UPPER", "hello.json", "ab\u0000cd", "x")) {
            val failure = assertFailsWith<IllegalArgumentException> { drafts.preview("workspace", id) }
            assertEquals("Invalid plugin ID", failure.message)
            assertNull(failure.cause)
        }
        assertEquals(emptyList(), files.readCalls)
        for (code in listOf(Code.FILE_NOT_FOUND, Code.SYMLINK_NOT_ALLOWED, Code.NOT_A_REGULAR_FILE,
            Code.WORKSPACE_UNAVAILABLE, Code.STORAGE_FAILURE)) {
            files.onRead = { throw untrustedFailure(code) }
            assertWorkspaceError(code) { drafts.preview("workspace", "hello") }
        }
        assertEquals(setOf(ReadCall("workspace", "plugins/hello.json", 0, AuthoredPluginSource.MAX_SOURCE_BYTES + 1)),
            files.readCalls.toSet())
        assertEquals(emptyList(), files.listCalls)
    }

    @Test
    fun usesMaxPlusOneAndDistinguishesAnOversizedValidPrefixFromInvalidSource() {
        val files = Files()
        val drafts = AuthoredPluginDrafts(files)
        val max = AuthoredPluginSource.MAX_SOURCE_BYTES
        val source = V1.encodeToByteArray()
        val exact = source + ByteArray(max - source.size) { ' '.code.toByte() }
        files.onRead = { content(exact.copyOf(minOf(exact.size, it.maxBytes))) }
        assertEquals("hello", drafts.preview("workspace", "hello").id)

        val oversized = exact + " ".encodeToByteArray()
        files.onRead = { content(oversized.copyOf(minOf(oversized.size, it.maxBytes))) }
        assertWorkspaceError(Code.SIZE_LIMIT_EXCEEDED) { drafts.preview("workspace", "hello") }
        files.onRead = { throw untrustedFailure(Code.SIZE_LIMIT_EXCEEDED) }
        assertWorkspaceError(Code.SIZE_LIMIT_EXCEEDED) { drafts.preview("workspace", "hello") }

        val badInputs = listOf(
            byteArrayOf(), byteArrayOf(0xff.toByte()),
            "/* $RAW_MARKER */$V1".encodeToByteArray(),
            V1.replace("\"id\":\"hello\"", "\"id\":\"$RAW_MARKER\"").encodeToByteArray(),
            (V1.dropLast(1) + ",\"$RAW_MARKER\":true}").encodeToByteArray(),
        )
        for (bad in badInputs) {
            files.onRead = { content(bad) }
            val failure = assertFailsWith<IllegalArgumentException> { drafts.preview("workspace", "hello") }
            assertEquals("Invalid plugin source", failure.message)
            assertNull(failure.cause)
        }
        assertEquals(setOf(ReadCall("workspace", "plugins/hello.json", 0, max + 1)), files.readCalls.toSet())
        assertEquals(emptyList(), files.listCalls)
    }

    @Test
    fun previewsFrozenV1AndV2DigestsFromExactBytesWithoutMutatingTheWorkspace() {
        val files = Files()
        val drafts = AuthoredPluginDrafts(files)
        val v1 = V1.encodeToByteArray()
        val v2 = V2.encodeToByteArray()
        files.onRead = { content(if (it.workspaceId == "workspace-a") v1 else v2) }

        val first = drafts.preview("workspace-a", "hello")
        assertEquals(AuthoredPluginDefinition(
            "hello", "问候", listOf(AuthoredToolDefinition("greet", "提供提示", "你好😀\n\t世界")), V1_DIGEST, 1,
        ), first)
        val second = drafts.preview("workspace-b", "hello")
        assertEquals(AuthoredPluginDefinition(
            "hello", "Trim", listOf(AuthoredToolDefinition("cut", "Trim text", "", AuthoredTextOperation.TRIM)), V2_DIGEST, 2,
        ), second)
        files.onRead = { content(v2) }
        assertEquals(second, drafts.preview("workspace-a", "hello"))
        assertEquals(V1_DIGEST, first.sha256)
        assertEquals("你好😀\n\t世界", first.tools.single().text)
        assertEquals(listOf("workspace-a", "workspace-b", "workspace-a"), files.readCalls.map { it.workspaceId })
        assertEquals(emptyList(), files.listCalls)
    }

    private fun assertWorkspaceError(code: Code, block: () -> Unit) {
        val failure = assertFailsWith<WorkspaceFileException>(block = block)
        assertEquals(code, failure.code)
        assertFalse(failure.message.orEmpty().contains(RAW_MARKER))
        assertNull(failure.cause)
    }

    private fun untrustedFailure(code: Code) = WorkspaceFileException(code, RAW_MARKER).also {
        it.initCause(IllegalStateException(RAW_MARKER))
    }

    private data class ListCall(val workspaceId: String, val path: String, val cursor: String?, val limit: Int)
    private data class ReadCall(val workspaceId: String, val path: String, val offset: Long, val maxBytes: Int)

    private class Files : WorkspaceFileService {
        val listCalls = mutableListOf<ListCall>()
        val readCalls = mutableListOf<ReadCall>()
        var onList: (ListCall) -> WorkspaceFilePage = { error("Unexpected file list") }
        var onRead: (ReadCall) -> WorkspaceFileContent = { error("Unexpected file read") }

        override fun list(workspaceId: String, relativeDirectory: String, cursor: String?, limit: Int): WorkspaceFilePage =
            onList(ListCall(workspaceId, relativeDirectory, cursor, limit).also { listCalls += it })

        override fun read(workspaceId: String, relativePath: String, offset: Long, maxBytes: Int): WorkspaceFileContent =
            onRead(ReadCall(workspaceId, relativePath, offset, maxBytes).also { readCalls += it })

        override fun write(workspaceId: String, relativePath: String, bytes: ByteArray, expectedRevision: String?) =
            error("Drafts must not write")

        override fun delete(workspaceId: String, relativePath: String, expectedRevision: String) = error("Drafts must not delete")

        override fun move(workspaceId: String, sourceRelativePath: String, destinationRelativePath: String, expectedRevision: String) =
            error("Drafts must not move files")
    }

    private companion object {
        const val RAW_MARKER = "PRIVATE_SOURCE_OR_BACKEND_MESSAGE"
        const val V1 = """{"schemaVersion":1,"id":"hello","title":"问候","tools":[{"name":"greet","description":"提供提示","text":"你好😀\n\t世界"}]}"""
        const val V1_DIGEST = "7f1a627b2bb47386fceb2fb9631403cf1267a0ce7f19aee5c96516f318c6e027"
        const val V2 = """{"tools":[{"kind":"text_transform","name":"cut","description":"Trim text","operation":"trim"}],"title":"Trim","id":"hello","schemaVersion":2}"""
        const val V2_DIGEST = "e210653549eb8b08ade0212a24d3feebb7f2f965e67a4c7ce2c663542f822b7d"

        fun content(bytes: ByteArray) = WorkspaceFileContent(bytes, revision = "not-a-preview-digest")
        fun file(name: String, size: Long = 7, directory: Boolean = false) = WorkspaceFileEntry(name, directory, size, revision = null)
        fun page(vararg entries: WorkspaceFileEntry, next: String? = null) = WorkspaceFilePage(entries.toList(), next)
    }
}
