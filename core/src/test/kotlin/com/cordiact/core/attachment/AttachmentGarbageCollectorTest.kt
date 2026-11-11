package com.cordiact.core.attachment

import java.io.ByteArrayInputStream
import java.io.InputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AttachmentGarbageCollectorTest {
    private val now = 10_000_000_000L

    @Test
    fun collectsOnlyUnreferencedOldObjects() {
        val old = descriptor("att-old", createdAt = now - 48L * 60 * 60 * 1000)
        val young = descriptor("att-young", createdAt = now - 60_000)
        val referenced = descriptor("att-ref", createdAt = now - 48L * 60 * 60 * 1000)
        val importing = descriptor(
            "att-importing",
            createdAt = now - 48L * 60 * 60 * 1000,
            state = AttachmentState.IMPORTING,
        )
        val catalog = FakeCatalog(listOf(old, young, referenced, importing))
        val collector = AttachmentGarbageCollector(
            catalog = catalog,
            references = AttachmentReferenceIndex { _, id -> id.value == "att-ref" },
            now = { now },
        )
        assertEquals(1, collector.collect("ws-1"))
        assertEquals(setOf("att-young", "att-ref", "att-importing"), catalog.list("ws-1").map { it.id.value }.toSet())
    }

    @Test
    fun aFailingDeleteLeavesTheObjectForTheNextRun() {
        val old = descriptor("att-old", createdAt = now - 48L * 60 * 60 * 1000)
        val catalog = FakeCatalog(listOf(old)).apply { failDelete = true }
        val collector = AttachmentGarbageCollector(catalog, AttachmentReferenceIndex { _, _ -> false }, now = { now })
        assertEquals(0, collector.collect("ws-1"))
        assertTrue(catalog.list("ws-1").isNotEmpty())
    }

    private fun descriptor(
        id: String,
        createdAt: Long,
        state: AttachmentState = AttachmentState.READY,
    ) = AttachmentDescriptor(
        id = AttachmentId(id),
        workspaceId = "ws-1",
        mimeType = "text/plain",
        displayName = "$id.txt",
        byteSize = 10,
        sha256 = if (state == AttachmentState.READY) "a".repeat(64) else "",
        state = state,
        createdAtMillis = createdAt,
        verifiedAtMillis = if (state == AttachmentState.READY) createdAt else null,
    )

    private class FakeCatalog(initial: List<AttachmentDescriptor>) : AttachmentCatalog {
        private val rows = initial.associateBy { it.id.value }.toMutableMap()
        var failDelete = false

        override fun upsert(descriptor: AttachmentDescriptor, blobKey: String) {
            rows[descriptor.id.value] = descriptor
        }

        override fun upsertRendition(workspaceId: String, rendition: AttachmentRendition, blobKey: String) = Unit

        override fun deleteRendition(workspaceId: String, attachmentId: AttachmentId, renditionId: String): Boolean = false

        override fun list(workspaceId: String): List<AttachmentDescriptor> =
            rows.values.filter { it.workspaceId == workspaceId }.sortedBy { it.createdAtMillis }

        override fun delete(workspaceId: String, id: AttachmentId): Boolean {
            if (failDelete) throw AttachmentException(AttachmentErrorCode.STORAGE_FAILURE, "locked")
            return rows.remove(id.value) != null
        }

        override fun find(workspaceId: String, id: AttachmentId): AttachmentDescriptor? =
            rows[id.value]?.takeIf { it.workspaceId == workspaceId }

        override fun rendition(workspaceId: String, attachmentId: AttachmentId, renditionId: String): AttachmentRendition? = null

        override fun open(workspaceId: String, attachmentId: AttachmentId, renditionId: String?): InputStream =
            ByteArrayInputStream(ByteArray(0))

        override fun verify(workspaceId: String, attachmentId: AttachmentId, renditionId: String?): Boolean = true
    }
}
