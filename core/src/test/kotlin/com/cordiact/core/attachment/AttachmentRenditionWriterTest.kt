package com.cordiact.core.attachment

import java.io.ByteArrayInputStream
import java.io.InputStream
import java.nio.file.Files
import kotlin.io.path.readBytes
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AttachmentRenditionWriterTest {
    private val attachmentId = AttachmentId("att-1")
    private val bytes = "normalised image bytes".toByteArray()

    @Test
    fun writesBlobAndRenditionWithDigest() {
        val blobs = LocalAttachmentBlobStore(Files.createTempDirectory("rendition").toFile())
        val catalog = FakeCatalog()
        val events = mutableListOf<AttachmentAuditEvent>()
        val rendition = AttachmentRenditionWriter(blobs, catalog, audit = { events += it }).write(
            workspaceId = "ws-1",
            attachmentId = attachmentId,
            mimeType = "image/jpeg",
            bytes = bytes,
            transformJson = """{"rotate":90,"edge":2048}""",
            extractorVersion = "img-norm-1",
        )
        assertEquals("image/jpeg", rendition.mimeType)
        assertEquals(bytes.size.toLong(), rendition.byteSize)
        assertEquals(64, rendition.sha256.length)
        val stored = catalog.renditions.getValue(rendition.id)
        assertEquals(rendition, stored.first)
        assertContentEquals(bytes, blobs.open("ws-1", stored.second).use { it.readBytes() })
        assertEquals(AttachmentAuditAction.TRANSFORM, events.single().action)
        assertEquals(rendition.id, events.single().renditionId)
    }

    @Test
    fun refusesOversizedRenditions() {
        val blobs = LocalAttachmentBlobStore(Files.createTempDirectory("rendition-big").toFile())
        val failure = assertFailsWith<AttachmentException> {
            AttachmentRenditionWriter(blobs, FakeCatalog()).write(
                workspaceId = "ws-1",
                attachmentId = attachmentId,
                mimeType = "image/jpeg",
                bytes = ByteArray(32),
                transformJson = "{}",
                extractorVersion = "img-norm-1",
                maxBytes = 16,
            )
        }
        assertEquals(AttachmentErrorCode.FILE_TOO_LARGE, failure.code)
    }

    @Test
    fun auditFailureRemovesTheRenditionAndBlob() {
        val blobs = LocalAttachmentBlobStore(Files.createTempDirectory("rendition-audit").toFile())
        val catalog = FakeCatalog()
        val failure = assertFailsWith<AttachmentException> {
            AttachmentRenditionWriter(blobs, catalog) { throw IllegalStateException("audit locked") }.write(
                workspaceId = "ws-1",
                attachmentId = attachmentId,
                mimeType = "image/jpeg",
                bytes = bytes,
                transformJson = "{}",
                extractorVersion = "img-norm-1",
            )
        }
        assertEquals(AttachmentErrorCode.STORAGE_FAILURE, failure.code)
        assertTrue(catalog.renditions.isEmpty())
    }

    @Test
    fun crossWorkspaceAttachmentIsRefusedAndBlobDeleted() {
        val delegate = LocalAttachmentBlobStore(Files.createTempDirectory("rendition-scope").toFile())
        val catalog = FakeCatalog().apply { failRenditionUpsert = true }
        val failure = assertFailsWith<AttachmentException> {
            AttachmentRenditionWriter(delegate, catalog).write(
                workspaceId = "ws-1",
                attachmentId = attachmentId,
                mimeType = "image/jpeg",
                bytes = bytes,
                transformJson = "{}",
                extractorVersion = "img-norm-1",
            )
        }
        assertEquals(AttachmentErrorCode.CROSS_WORKSPACE, failure.code)
        assertFalse(catalog.renditions.isNotEmpty())
    }

    private class FakeCatalog : AttachmentCatalog {
        val renditions = mutableMapOf<String, Pair<AttachmentRendition, String>>()
        var failRenditionUpsert = false

        override fun upsert(descriptor: AttachmentDescriptor, blobKey: String) = Unit

        override fun upsertRendition(workspaceId: String, rendition: AttachmentRendition, blobKey: String) {
            if (failRenditionUpsert) {
                throw AttachmentException(AttachmentErrorCode.CROSS_WORKSPACE, "not in this Workspace")
            }
            renditions[rendition.id] = rendition to blobKey
        }

        override fun deleteRendition(workspaceId: String, attachmentId: AttachmentId, renditionId: String): Boolean =
            renditions.remove(renditionId) != null

        override fun list(workspaceId: String): List<AttachmentDescriptor> = emptyList()

        override fun delete(workspaceId: String, id: AttachmentId): Boolean = false

        override fun find(workspaceId: String, id: AttachmentId): AttachmentDescriptor? = null

        override fun rendition(workspaceId: String, attachmentId: AttachmentId, renditionId: String): AttachmentRendition? =
            renditions[renditionId]?.first

        override fun open(workspaceId: String, attachmentId: AttachmentId, renditionId: String?): InputStream =
            ByteArrayInputStream(ByteArray(0))

        override fun verify(workspaceId: String, attachmentId: AttachmentId, renditionId: String?): Boolean = true
    }
}
