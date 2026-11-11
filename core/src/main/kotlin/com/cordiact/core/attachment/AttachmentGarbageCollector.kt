package com.cordiact.core.attachment

/** Tells the collector whether a message part or a draft still points at an attachment. */
fun interface AttachmentReferenceIndex {
    fun isReferenced(workspaceId: String, attachmentId: AttachmentId): Boolean
}

/**
 * Reference-aware retention.
 *
 * Only objects that no message part and no draft references, that are not still importing and that
 * are older than [minAgeMillis] are removed. Every other case is left for the next run: deleting a
 * referenced object would break an existing conversation, and deleting a young one would race the
 * import that is about to reference it.
 */
class AttachmentGarbageCollector(
    private val catalog: AttachmentCatalog,
    private val references: AttachmentReferenceIndex,
    private val audit: AttachmentAuditSink = NoopAttachmentAuditSink,
    private val now: () -> Long = System::currentTimeMillis,
) {
    fun collect(workspaceId: String, minAgeMillis: Long = DEFAULT_MIN_AGE_MILLIS): Int {
        require(workspaceId.isNotBlank())
        var deleted = 0
        for (descriptor in catalog.list(workspaceId)) {
            if (descriptor.state == AttachmentState.IMPORTING) continue
            if (now() - descriptor.createdAtMillis < minAgeMillis) continue
            if (references.isReferenced(workspaceId, descriptor.id)) continue
            val removed = try {
                catalog.delete(workspaceId, descriptor.id)
            } catch (_: AttachmentException) {
                // A reference we did not know about still holds it; leave it for the next run.
                false
            }
            if (removed) {
                deleted++
                recordCollected(workspaceId, descriptor)
            }
        }
        return deleted
    }

    private fun recordCollected(workspaceId: String, descriptor: AttachmentDescriptor) {
        // The row is already gone; a failing audit must not resurrect it or fail the sweep.
        runCatching {
            audit.record(
                AttachmentAuditEvent(
                    action = AttachmentAuditAction.LOCAL_DELETE,
                    decision = AttachmentAuditDecision.ALLOWED,
                    workspaceId = workspaceId,
                    attachmentId = descriptor.id.value,
                    mimeType = descriptor.mimeType,
                    byteSize = descriptor.byteSize,
                    sha256 = descriptor.sha256,
                    reasonCode = "GC_UNREFERENCED",
                    occurredAtMillis = now(),
                ),
            )
        }
    }

    companion object {
        /** A day is long enough that a draft restored from disk is not collected mid-edit. */
        const val DEFAULT_MIN_AGE_MILLIS: Long = 24L * 60 * 60 * 1000
    }
}
