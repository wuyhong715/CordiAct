package com.cordiact.core.attachment

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.security.MessageDigest
import java.util.UUID

/** Opens a fresh stream for one import attempt. The caller owns the underlying content grant. */
fun interface AttachmentSource {
    fun open(): InputStream
}

data class AttachmentImportRequest(
    val workspaceId: String,
    val displayName: String?,
    val declaredMimeType: String,
    val nowMillis: Long,
)

/** A verified descriptor plus the opaque blob key the repository must persist alongside it. */
data class ImportedAttachment(
    val descriptor: AttachmentDescriptor,
    val blobKey: String,
)

/**
 * Streams one source into a verified, immutable local object.
 *
 * The importer enforces byte limits while reading, hashes the bytes it actually stored and only
 * publishes an object after format verification. A failed import leaves no READY descriptor.
 */
class AttachmentImporter(
    private val blobs: AttachmentBlobStore,
    private val limits: AttachmentLimits = AttachmentLimits(),
) {
    fun import(request: AttachmentImportRequest, source: AttachmentSource): ImportedAttachment {
        val mimeType = AttachmentFormats.normalizeMimeType(request.declaredMimeType)
        if (mimeType.isEmpty()) {
            throw AttachmentException(
                AttachmentErrorCode.INVALID_MEDIA_TYPE,
                "The source did not report a usable MIME type",
            )
        }
        val kind = AttachmentFormats.kindOf(mimeType)
        val byteLimit = limitFor(kind)
        val attachmentId = AttachmentId("att-" + UUID.randomUUID().toString().replace("-", ""))
        val displayName = AttachmentNames.sanitizeDisplayName(request.displayName)

        val staged = blobs.createStaged(request.workspaceId, byteLimit)
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            val head = ByteArray(HEAD_LIMIT)
            var headLength = 0
            var total = 0L
            val dimensions: ImageDimensions?
            val pageCount: Int?

            if (kind == AttachmentMediaKind.TEXT) {
                // Text must be decoded as a whole: a multi-byte character may straddle chunk edges.
                val collected = ByteArrayOutputStream()
                source.open().use { input ->
                    val buffer = ByteArray(COPY_BUFFER)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        if (read == 0) continue
                        if (collected.size().toLong() + read > byteLimit) {
                            throw AttachmentException(
                                AttachmentErrorCode.FILE_TOO_LARGE,
                                "The text file exceeds the $byteLimit byte limit",
                            )
                        }
                        collected.write(buffer, 0, read)
                    }
                }
                val bytes = collected.toByteArray()
                if (bytes.isEmpty()) {
                    throw AttachmentException(AttachmentErrorCode.INVALID_MEDIA_TYPE, "The source is empty")
                }
                if (AttachmentFormats.decodeUtf8OrNull(bytes) == null) {
                    throw AttachmentException(
                        AttachmentErrorCode.INVALID_MEDIA_TYPE,
                        "The text file is not valid UTF-8; convert it explicitly before importing",
                    )
                }
                if (AttachmentFormats.looksBinary(bytes)) {
                    throw AttachmentException(
                        AttachmentErrorCode.INVALID_MEDIA_TYPE,
                        "Declared a text type but the bytes contain binary control characters",
                    )
                }
                headLength = minOf(bytes.size, HEAD_LIMIT)
                bytes.copyInto(head, 0, 0, headLength)
                digest.update(bytes)
                staged.write(bytes, 0, bytes.size)
                total = bytes.size.toLong()
                dimensions = null
                pageCount = null
            } else {
                source.open().use { input ->
                    val buffer = ByteArray(COPY_BUFFER)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        if (read == 0) continue
                        if (total + read > byteLimit) {
                            throw AttachmentException(
                                AttachmentErrorCode.FILE_TOO_LARGE,
                                "The file exceeds the $byteLimit byte import limit",
                            )
                        }
                        if (headLength < HEAD_LIMIT) {
                            val take = minOf(read, HEAD_LIMIT - headLength)
                            buffer.copyInto(head, headLength, 0, take)
                            headLength += take
                        }
                        digest.update(buffer, 0, read)
                        staged.write(buffer, 0, read)
                        total += read
                    }
                }
                if (total == 0L) {
                    throw AttachmentException(AttachmentErrorCode.INVALID_MEDIA_TYPE, "The source is empty")
                }
                AttachmentFormats.verifyDeclaredType(mimeType, head, headLength)
                dimensions = if (kind == AttachmentMediaKind.IMAGE) {
                    val parsed = AttachmentFormats.readImageDimensions(head, headLength)
                        ?: throw AttachmentException(
                            AttachmentErrorCode.INVALID_MEDIA_TYPE,
                            "The image header could not be read; the file may be truncated or malformed",
                        )
                    if (parsed.pixels > limits.maxImagePixels) {
                        throw AttachmentException(
                            AttachmentErrorCode.IMAGE_TOO_LARGE,
                            "The image has ${parsed.pixels} pixels; the limit is ${limits.maxImagePixels}",
                        )
                    }
                    if (parsed.longestEdge > limits.maxImageEdge) {
                        throw AttachmentException(
                            AttachmentErrorCode.IMAGE_TOO_LARGE,
                            "The image edge is ${parsed.longestEdge}px; the limit is ${limits.maxImageEdge}px",
                        )
                    }
                    parsed
                } else {
                    null
                }
                if (kind == AttachmentMediaKind.PDF && AttachmentFormats.looksEncryptedPdf(head, headLength)) {
                    throw AttachmentException(
                        AttachmentErrorCode.INVALID_MEDIA_TYPE,
                        "Encrypted PDFs are not supported; remove the password and import again",
                    )
                }
                pageCount = null
            }

            val sha256 = digest.digest().joinToString("") { "%02x".format(it) }
            val blobKey = staged.commit()
            return ImportedAttachment(
                descriptor = AttachmentDescriptor(
                    id = attachmentId,
                    workspaceId = request.workspaceId,
                    mimeType = mimeType,
                    displayName = displayName,
                    byteSize = total,
                    sha256 = sha256,
                    state = AttachmentState.READY,
                    createdAtMillis = request.nowMillis,
                    verifiedAtMillis = request.nowMillis,
                    width = dimensions?.width,
                    height = dimensions?.height,
                    pageCount = pageCount,
                ),
                blobKey = blobKey,
            )
        } catch (failure: AttachmentException) {
            staged.abort()
            throw failure
        } catch (failure: Exception) {
            staged.abort()
            throw AttachmentException(
                AttachmentErrorCode.STORAGE_FAILURE,
                "The attachment could not be imported",
                failure,
            )
        }
    }

    private fun limitFor(kind: AttachmentMediaKind): Long = when (kind) {
        AttachmentMediaKind.IMAGE -> limits.maxImageBytes
        AttachmentMediaKind.PDF -> limits.maxPdfBytes
        AttachmentMediaKind.TEXT -> limits.maxTextBytes
        AttachmentMediaKind.BINARY -> limits.maxImportBytes
    }

    private companion object {
        const val COPY_BUFFER = 64 * 1024
        const val HEAD_LIMIT = 64 * 1024
    }
}
