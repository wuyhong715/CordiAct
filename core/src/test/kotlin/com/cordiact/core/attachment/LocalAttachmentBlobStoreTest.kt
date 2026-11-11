package com.cordiact.core.attachment

import java.nio.file.Files
import kotlin.io.path.readBytes
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LocalAttachmentBlobStoreTest {
    @Test
    fun stagedWriteBecomesVisibleOnlyAfterCommit() {
        val root = Files.createTempDirectory("blob-store").toFile()
        val store = LocalAttachmentBlobStore(root)
        val staged = store.createStaged("workspace-a", 1024)
        staged.write("hello ".toByteArray(), 0, 6)
        staged.write("world".toByteArray(), 0, 5)
        assertEquals(11, staged.bytesWritten)
        val key = staged.commit()
        assertTrue(store.exists("workspace-a", key))
        assertEquals(11, store.length("workspace-a", key))
        assertContentEquals("hello world".toByteArray(), store.open("workspace-a", key).use { it.readBytes() })
    }

    @Test
    fun exceedingTheLimitFailsAndAbortRemovesThePartFile() {
        val root = Files.createTempDirectory("blob-store-limit").toFile()
        val store = LocalAttachmentBlobStore(root)
        val staged = store.createStaged("workspace-a", 4)
        val failure = assertFailsWith<AttachmentException> {
            staged.write(ByteArray(5), 0, 5)
        }
        assertEquals(AttachmentErrorCode.FILE_TOO_LARGE, failure.code)
        staged.abort()
        assertEquals(0, store.sweepStaging("workspace-a", 0, Long.MAX_VALUE))
    }

    @Test
    fun workspacesAreIsolated() {
        val root = Files.createTempDirectory("blob-store-scope").toFile()
        val store = LocalAttachmentBlobStore(root)
        val staged = store.createStaged("workspace-a", 1024)
        staged.write("a".toByteArray(), 0, 1)
        val key = staged.commit()
        assertTrue(store.exists("workspace-a", key))
        assertFalse(store.exists("workspace-b", key))
        assertFailsWith<AttachmentException> { store.open("workspace-b", key) }
    }

    @Test
    fun rejectsUnsafeBlobKeys() {
        val root = Files.createTempDirectory("blob-store-keys").toFile()
        val store = LocalAttachmentBlobStore(root)
        assertFailsWith<IllegalArgumentException> { store.open("workspace-a", "../escape") }
    }

    @Test
    fun sweepsOldStagingFiles() {
        val root = Files.createTempDirectory("blob-store-sweep").toFile()
        val store = LocalAttachmentBlobStore(root)
        val staged = store.createStaged("workspace-a", 1024)
        staged.write("x".toByteArray(), 0, 1)
        // Not committed: the staged file is an orphan.
        val removed = store.sweepStaging("workspace-a", olderThanMillis = 0, nowMillis = System.currentTimeMillis() + 1)
        assertEquals(1, removed)
        staged.abort()
    }
}
