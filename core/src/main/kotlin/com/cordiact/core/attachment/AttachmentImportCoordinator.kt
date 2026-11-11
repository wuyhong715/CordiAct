package com.cordiact.core.attachment

/**
 * Imports bytes and registers the resulting descriptor, keeping the two stores consistent.
 *
 * Bytes are written first and the catalogue row second. If the catalogue write fails, the blob is
 * deleted again so a failed import never leaves invisible bytes behind. The audit record is part of
 * the success contract: if it cannot be written, the import is undone and refused.
 */
class AttachmentImportCoordinator(
    private val blobs: AttachmentBlobStore,
    private val catalog: AttachmentCatalog,
    private val limits: AttachmentLimits = AttachmentLimits(),
    private val audit: AttachmentAuditSink = NoopAttachmentAuditSink,
    private val now: () -> Long = System::currentTimeMillis,
) {
    fun import(request: AttachmentImportRequest, source: AttachmentSource): AttachmentDescriptor =
        importDetailed(request, source).descriptor

    /** Same pipeline, but also returns the blob key so a caller can derive a rendition from it. */
    fun importDetailed(request: AttachmentImportRequest, source: AttachmentSource): ImportedAttachment {
        val imported = try {
            AttachmentImporter(blobs, limits).import(request, source)
        } catch (failure: AttachmentException) {
            recordFailure(request, failure.code.name)
            throw failure
        }

        try {
            catalog.upsert(imported.descriptor, imported.blobKey)
        } catch (failure: Exception) {
            runCatching { blobs.delete(request.workspaceId, imported.blobKey) }
            recordFailure(request, codeOf(failure))
            throw failure
        }

        try {
            audit.record(
                AttachmentAuditEvent(
                    action = AttachmentAuditAction.IMPORT,
                    decision = AttachmentAuditDecision.ALLOWED,
                    workspaceId = request.workspaceId,
                    attachmentId = imported.descriptor.id.value,
                    mimeType = imported.descriptor.mimeType,
                    byteSize = imported.descriptor.byteSize,
                    sha256 = imported.descriptor.sha256,
                    occurredAtMillis = now(),
                ),
            )
        } catch (failure: Exception) {
            runCatching { catalog.delete(request.workspaceId, imported.descriptor.id) }
            runCatching { blobs.delete(request.workspaceId, imported.blobKey) }
            throw AttachmentException(
                AttachmentErrorCode.STORAGE_FAILURE,
                "The attachment audit log is unavailable; the import was refused",
                failure,
            )
        }
        return imported
    }

    private fun recordFailure(request: AttachmentImportRequest, reason: String) {
        // A failing audit must not mask the original import failure; the action is already refused.
        runCatching {
            audit.record(
                AttachmentAuditEvent(
                    action = AttachmentAuditAction.IMPORT,
                    decision = AttachmentAuditDecision.FAILED,
                    workspaceId = request.workspaceId,
                    mimeType = request.declaredMimeType,
                    reasonCode = reason,
                    occurredAtMillis = now(),
                ),
            )
        }
    }

    private fun codeOf(failure: Exception): String =
        (failure as? AttachmentException)?.code?.name ?: AttachmentAuditDecision.FAILED.name
}
