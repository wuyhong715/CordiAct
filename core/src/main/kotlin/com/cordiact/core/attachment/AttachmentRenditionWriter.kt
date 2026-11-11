package com.cordiact.core.attachment

import java.security.MessageDigest
import java.util.UUID

/**
 * Writes a derived object (a normalised image today) and registers it as a rendition.
 *
 * The original object is never overwritten. A rendition is always addressed together with its
 * attachment, carries its own digest and records which transform and extractor version produced it,
 * so a replay can explain why the bytes differ from the source.
 */
class AttachmentRenditionWriter(
    private val blobs: AttachmentBlobStore,
    private val catalog: AttachmentCatalog,
    private val audit: AttachmentAuditSink = NoopAttachmentAuditSink,
    private val now: () -> Long = System::currentTimeMillis,
) {
    fun write(
        workspaceId: String,
        attachmentId: AttachmentId,
        mimeType: String,
        bytes: ByteArray,
        transformJson: String,
        extractorVersion: String,
        maxBytes: Long = MAX_RENDITION_BYTES,
    ): AttachmentRendition {
        require(workspaceId.isNotBlank())
        if (bytes.isEmpty()) {
            throw AttachmentException(AttachmentErrorCode.INVALID_MEDIA_TYPE, "A rendition cannot be empty")
        }
        if (bytes.size.toLong() > maxBytes) {
            throw AttachmentException(
                AttachmentErrorCode.FILE_TOO_LARGE,
                "The rendition is ${bytes.size} bytes; the limit is $maxBytes",
            )
        }
        val normalizedMime = AttachmentFormats.normalizeMimeType(mimeType)
        if (normalizedMime.isEmpty()) {
            throw AttachmentException(AttachmentErrorCode.INVALID_MEDIA_TYPE, "A rendition needs a MIME type")
        }

        val staged = blobs.createStaged(workspaceId, maxBytes)
        val digest = MessageDigest.getInstance("SHA-256").apply { update(bytes) }
        val sha256 = digest.digest().joinToString("") { "%02x".format(it) }
        val blobKey = try {
            staged.write(bytes, 0, bytes.size)
            staged.commit()
        } catch (failure: Exception) {
            staged.abort()
            throw failure
        }

        val rendition = AttachmentRendition(
            id = "rnd-" + UUID.randomUUID().toString().replace("-", ""),
            attachmentId = attachmentId,
            mimeType = normalizedMime,
            byteSize = bytes.size.toLong(),
            sha256 = sha256,
            transformJson = transformJson,
            extractorVersion = extractorVersion,
        )
        try {
            catalog.upsertRendition(workspaceId, rendition, blobKey)
        } catch (failure: Exception) {
            runCatching { blobs.delete(workspaceId, blobKey) }
            throw failure
        }
        try {
            audit.record(
                AttachmentAuditEvent(
                    action = AttachmentAuditAction.TRANSFORM,
                    decision = AttachmentAuditDecision.ALLOWED,
                    workspaceId = workspaceId,
                    attachmentId = attachmentId.value,
                    renditionId = rendition.id,
                    mimeType = rendition.mimeType,
                    byteSize = rendition.byteSize,
                    sha256 = rendition.sha256,
                    reasonCode = extractorVersion.take(96),
                    occurredAtMillis = now(),
                ),
            )
        } catch (failure: Exception) {
            runCatching { catalog.deleteRendition(workspaceId, attachmentId, rendition.id) }
            runCatching { blobs.delete(workspaceId, blobKey) }
            throw AttachmentException(
                AttachmentErrorCode.STORAGE_FAILURE,
                "The attachment audit log is unavailable; the rendition was refused",
                failure,
            )
        }
        return rendition
    }

    companion object {
        /** A normalised image is far smaller; anything above this is a bug, not a photo. */
        const val MAX_RENDITION_BYTES: Long = 32L * 1024 * 1024
    }
}
