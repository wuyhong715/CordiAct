package com.cordiact.app.attachment

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.cordiact.core.attachment.AttachmentAuditSink
import com.cordiact.core.attachment.AttachmentBlobStore
import com.cordiact.core.attachment.AttachmentCatalog
import com.cordiact.core.attachment.AttachmentDescriptor
import com.cordiact.core.attachment.AttachmentErrorCode
import com.cordiact.core.attachment.AttachmentException
import com.cordiact.core.attachment.AttachmentImportCoordinator
import com.cordiact.core.attachment.AttachmentImportRequest
import com.cordiact.core.attachment.AttachmentLimits
import com.cordiact.core.attachment.AttachmentMediaKind
import com.cordiact.core.attachment.AttachmentRendition
import com.cordiact.core.attachment.AttachmentRenditionWriter
import com.cordiact.core.attachment.AttachmentSource
import com.cordiact.core.attachment.ImageNormalizationPolicy
import com.cordiact.core.attachment.ImportedAttachment
import com.cordiact.core.attachment.NoopAttachmentAuditSink
import kotlin.io.readBytes

/** What one import produced: the stored object and, for images, the send-ready rendition. */
data class ImportedAttachmentResult(
    val descriptor: AttachmentDescriptor,
    val sendRendition: AttachmentRendition?,
)

/**
 * Imports one `content://` item into a Workspace's private attachment store.
 *
 * The bytes are copied immediately, so the caller's temporary URI grant is never kept and never
 * becomes a dependency of a stored message. A successful call means "stored locally", not
 * "uploaded": nothing here talks to a model provider.
 *
 * For images a normalised rendition is produced as well (orientation applied, metadata including
 * GPS dropped, long edge bounded). The original object stays untouched; callers that send the image
 * use [ImportedAttachmentResult.sendRendition].
 *
 * Every method performs blocking I/O and must be called off the main thread.
 */
class AndroidAttachmentImportService(
    private val context: Context,
    private val blobs: AttachmentBlobStore,
    private val catalog: AttachmentCatalog,
    private val limits: AttachmentLimits = AttachmentLimits(),
    private val audit: AttachmentAuditSink = NoopAttachmentAuditSink,
    private val imageNormalizer: AndroidImageNormalizer? = AndroidImageNormalizer(),
) {
    private val coordinator = AttachmentImportCoordinator(blobs, catalog, limits, audit)
    private val renditionWriter = AttachmentRenditionWriter(blobs, catalog, audit)
    private val resolver: ContentResolver get() = context.contentResolver

    fun importUri(
        workspaceId: String,
        uri: Uri,
        declaredMimeType: String? = null,
        displayName: String? = null,
        nowMillis: Long = System.currentTimeMillis(),
    ): ImportedAttachmentResult {
        if (uri.scheme != ContentResolver.SCHEME_CONTENT) {
            throw AttachmentException(
                AttachmentErrorCode.INVALID_MEDIA_TYPE,
                "Only content:// URIs can be imported",
            )
        }
        val metadata = readMetadata(uri)
        val mimeType = declaredMimeType?.takeIf { it.isNotBlank() } ?: metadata.mimeType
        if (mimeType.isNullOrBlank()) {
            throw AttachmentException(
                AttachmentErrorCode.INVALID_MEDIA_TYPE,
                "The picked item does not report a MIME type; choose the file type explicitly",
            )
        }
        return importSource(
            workspaceId = workspaceId,
            source = AttachmentSource {
                resolver.openInputStream(uri) ?: throw AttachmentException(
                    AttachmentErrorCode.STORAGE_FAILURE,
                    "The picked item could not be opened; it may have moved or the grant was revoked",
                )
            },
            displayName = displayName ?: metadata.displayName,
            declaredMimeType = mimeType,
            nowMillis = nowMillis,
        )
    }

    /** Testable entry point: same pipeline, caller-provided bytes. */
    fun importSource(
        workspaceId: String,
        source: AttachmentSource,
        displayName: String?,
        declaredMimeType: String,
        nowMillis: Long = System.currentTimeMillis(),
    ): ImportedAttachmentResult {
        val imported = coordinator.importDetailed(
            AttachmentImportRequest(
                workspaceId = workspaceId,
                displayName = displayName,
                declaredMimeType = declaredMimeType,
                nowMillis = nowMillis,
            ),
            source,
        )
        val rendition = try {
            normalizeIfNeeded(workspaceId, imported)
        } catch (failure: Exception) {
            // Without a safe rendition the image could leak metadata if it were sent, so the whole
            // import is refused instead of leaving a half-prepared object behind.
            runCatching { catalog.delete(workspaceId, imported.descriptor.id) }
            runCatching { blobs.delete(workspaceId, imported.blobKey) }
            throw failure
        }
        return ImportedAttachmentResult(imported.descriptor, rendition)
    }

    private fun normalizeIfNeeded(workspaceId: String, imported: ImportedAttachment): AttachmentRendition? {
        val normalizer = imageNormalizer ?: return null
        val descriptor = imported.descriptor
        if (descriptor.mediaKind != AttachmentMediaKind.IMAGE) return null
        val width = descriptor.width ?: return null
        val height = descriptor.height ?: return null

        val source = blobs.open(workspaceId, imported.blobKey).use { it.readBytes() }
        val orientation = normalizer.readOrientation(source)
        val plan = ImageNormalizationPolicy.plan(descriptor.mimeType, width, height, orientation)
        if (!plan.requiresTransform && !normalizer.hasLocationMetadata(source)) return null

        val normalized = normalizer.normalize(source, plan)
        val transform = buildString {
            append("{\"policy\":\"img-norm-1\"")
            append(",\"rotate\":").append(plan.rotationDegrees)
            append(",\"flipH\":").append(plan.flipHorizontal)
            append(",\"flipV\":").append(plan.flipVertical)
            append(",\"edge\":").append(ImageNormalizationPolicy.MAX_SEND_EDGE)
            append(",\"stripMetadata\":true}")
        }
        return renditionWriter.write(
            workspaceId = workspaceId,
            attachmentId = descriptor.id,
            mimeType = normalized.mimeType,
            bytes = normalized.bytes,
            transformJson = transform,
            extractorVersion = NORMALIZER_VERSION,
        )
    }

    private data class PickedMetadata(val displayName: String?, val mimeType: String?)

    private fun readMetadata(uri: Uri): PickedMetadata {
        var displayName: String? = null
        runCatching {
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) displayName = cursor.getString(0)
            }
        }
        val mimeType = runCatching { resolver.getType(uri) }.getOrNull()
        return PickedMetadata(displayName, mimeType)
    }

    private companion object {
        const val NORMALIZER_VERSION = "img-norm-1"
    }
}
