package com.cordiact.app.file

import com.cordiact.core.file.WorkspaceFileException
import java.nio.file.Files
import kotlin.io.path.createTempDirectory
import kotlin.io.path.writeBytes
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class AndroidWorkspaceFileBackendTest {
    private val backend = AndroidWorkspaceFileBackend()

    @Test
    fun writesReadsAndUsesRevisionPreconditions() {
        val root = createTempDirectory("cordiact-workspace").toFile()
        try {
            val created = backend.write(root, "notes/today.txt", "hello".toByteArray(), expectedRevision = null)
            val read = backend.read(root, "notes/today.txt", offset = 0, maxBytes = 100)
            assertArrayEquals("hello".toByteArray(), read.bytes)
            assertEquals(created.revision, read.revision)
            assertThrows(WorkspaceFileException::class.java) {
                backend.write(root, "notes/today.txt", "changed".toByteArray(), expectedRevision = "stale")
            }
            val updated = backend.write(root, "notes/today.txt", "changed".toByteArray(), read.revision)
            assertEquals(updated.revision, backend.read(root, "notes/today.txt", 0, 100).revision)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun rejectsTraversalAndSymlinkEscape() {
        val root = createTempDirectory("cordiact-workspace").toFile()
        val outside = createTempDirectory("cordiact-outside")
        try {
            assertThrows(WorkspaceFileException::class.java) {
                backend.write(root, "../outside.txt", byteArrayOf(1), null)
            }
            val link = root.toPath().resolve("link")
            try {
                Files.createSymbolicLink(link, outside)
            } catch (_: UnsupportedOperationException) {
                return
            } catch (_: java.nio.file.FileSystemException) {
                return
            }
            assertThrows(WorkspaceFileException::class.java) {
                backend.write(root, "link/escape.txt", byteArrayOf(1), null)
            }
        } finally {
            root.deleteRecursively()
            outside.toFile().deleteRecursively()
        }
    }

    @Test
    fun listsWithOpaqueCursorWithoutExposingAbsolutePaths() {
        val root = createTempDirectory("cordiact-workspace").toFile()
        try {
            root.toPath().resolve("a.txt").writeBytes(byteArrayOf(1))
            root.toPath().resolve("b.txt").writeBytes(byteArrayOf(2))
            val first = backend.list(root, "", cursor = null, limit = 1)
            assertEquals(1, first.entries.size)
            val second = backend.list(root, "", cursor = first.nextCursor, limit = 1)
            assertEquals("b.txt", second.entries.single().name)
            assertThrows(WorkspaceFileException::class.java) { backend.list(root, "other", first.nextCursor, 1) }
        } finally {
            root.deleteRecursively()
        }
    }
}
