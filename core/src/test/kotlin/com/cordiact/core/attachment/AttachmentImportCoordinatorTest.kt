package com.cordiact.core.attachment

import java.io.ByteArrayInputStream
import java.io.InputStream
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AttachmentImportCoordinatorTest {
    @Test
    fun importWritesBlobAndCatalogueRow() {
        val blobs = LocalAttachmentBlobStore(Files.createTempDirectory("coordinator").toFile())
        val catalog = FakeCatalog()
        val descriptor = AttachmentImportCoordinator(blobs, catalog).import(
            AttachmentImportRequest("ws-1", "notes.txt", "text/plain", 1000L),
            AttachmentSource { ByteArrayInputStream("hello".toByteArray()) },
        )
        assertEquals(AttachmentState.READY, descriptor.state)
        val row = catalog.rows.getValue(descriptor.id.value)
        assertEquals(descriptor, row.first)
        assertTrue(blobs.exists("ws-1", row.second))
    }

    @Test
    fun importIsAuditedWithoutContent() {
        val blobs = LocalAttachmentBlobStore(Files.createTempDirectory("coordinator-audit").toFile())
        val catalog = FakeCatalog()
        val events = mutableListOf<AttachmentAuditEvent>()
        val audit = AttachmentAuditSink { events += it }
        val descriptor = AttachmentImportCoordinator(blobs, catalog, audit = audit).import(
            AttachmentImportRequest("ws-1", "secret-notes.txt", "text/plain", 1000L),
            AttachmentSource { ByteArrayInputStream("top secret body".toByteArray()) },
        )
        val event = events.single()
        assertEquals(AttachmentAuditAction.IMPORT, event.action)
        assertEquals(AttachmentAuditDecision.ALLOWED, event.decision)
        assertEquals(descriptor.id.value, event.attachmentId)
        assertEquals(descriptor.sha256, event.sha256)
        // The event type has no content field at all, so nothing can leak through it.
        assertFalse(event.toString().contains("top secret"))
    }

    @Test
    fun auditFailureRefusesTheImportAndCleansUp() {
        val blobs = LocalAttachmentBlobStore(Files.createTempDirectory("coordinator-audit-fail").toFile())
        val catalog = FakeCatalog()
        val audit = AttachmentAuditSink {
            throw IllegalStateException("audit database is locked")
        }
        val failure = assertFailsWith<AttachmentException> {
            AttachmentImportCoordinator(blobs, catalog, audit = audit).import(
                AttachmentImportRequest("ws-1", "notes.txt", "text/plain", 1000L),
                AttachmentSource { ByteArrayInputStream("hello".toByteArray()) },
            )
        }
        assertEquals(AttachmentErrorCode.STORAGE_FAILURE, failure.code)
        assertTrue(catalog.rows.isEmpty())
        assertEquals(0, catalog.list("ws-1").size)
    }

    @Test
    fun failedImportIsAuditedWithAReasonCode() {
        val blobs = LocalAttachmentBlobStore(Files.createTempDirectory("coordinator-audit-deny").toFile())
        val events = mutableListOf<AttachmentAuditEvent>()
        val audit = AttachmentAuditSink { events += it }
        val failure = assertFailsWith<AttachmentException> {
            AttachmentImportCoordinator(blobs, FakeCatalog(), audit = audit).import(
                AttachmentImportRequest("ws-1", "fake.png", "image/png", 1000L),
                AttachmentSource { ByteArrayInputStream("not a png".toByteArray()) },
            )
        }
        assertEquals(AttachmentErrorCode.INVALID_MEDIA_TYPE, failure.code)
        val event = events.single()
        assertEquals(AttachmentAuditDecision.FAILED, event.decision)
        assertEquals(AttachmentErrorCode.INVALID_MEDIA_TYPE.name, event.reasonCode)
    }

    @Test
    fun catalogueFailureDeletesTheBlob() {
        val delegate = LocalAttachmentBlobStore(Files.createTempDirectory("coordinator-fail").toFile())
        val recording = RecordingBlobStore(delegate)
        val catalog = FakeCatalog().apply { failUpsert = true }
        val failure = assertFailsWith<AttachmentException> {
            AttachmentImportCoordinator(recording, catalog).import(
                AttachmentImportRequest("ws-1", "notes.txt", "text/plain", 1000L),
                AttachmentSource { ByteArrayInputStream("hello".toByteArray()) },
            )
        }
        assertEquals(AttachmentErrorCode.STORAGE_FAILURE, failure.code)
        assertEquals(1, recording.committed.size)
        assertFalse(delegate.exists("ws-1", recording.committed.single()))
    }

    private class FakeCatalog : AttachmentCatalog {
        val rows = mutableMapOf<String, Pair<AttachmentDescriptor, String>>()
        var failUpsert = false

        override fun upsert(descriptor: AttachmentDescriptor, blobKey: String) {
            if (failUpsert) throw AttachmentException(AttachmentErrorCode.STORAGE_FAILURE, "catalogue is unavailable")
            rows[descriptor.id.value] = descriptor to blobKey
        }

        override fun upsertRendition(workspaceId: String, rendition: AttachmentRendition, blobKey: String) = Unit

        override fun deleteRendition(workspaceId: String, attachmentId: AttachmentId, renditionId: String): Boolean = false

        override fun list(workspaceId: String): List<AttachmentDescriptor> =
            rows.values.map { it.first }.filter { it.workspaceId == workspaceId }

        override fun delete(workspaceId: String, id: AttachmentId): Boolean =
            rows.remove(id.value)?.first?.workspaceId == workspaceId

        override fun find(workspaceId: String, id: AttachmentId): AttachmentDescriptor? =
            rows[id.value]?.first?.takeIf { it.workspaceId == workspaceId }

        override fun rendition(workspaceId: String, attachmentId: AttachmentId, renditionId: String): AttachmentRendition? = null

        override fun open(workspaceId: String, attachmentId: AttachmentId, renditionId: String?): InputStream =
            ByteArrayInputStream(ByteArray(0))

        override fun verify(workspaceId: String, attachmentId: AttachmentId, renditionId: String?): Boolean = true
    }

    private class RecordingBlobStore(private val delegate: AttachmentBlobStore) : AttachmentBlobStore by delegate {
        val committed = mutableListOf<String>()

        override fun createStaged(workspaceId: String, hardLimitBytes: Long): StagedAttachmentBlob {
            val stage = delegate.createStaged(workspaceId, hardLimitBytes)
            return object : StagedAttachmentBlob by stage {
                override fun commit(): String = stage.commit().also { committed += it }
            }
        }
    }
}
