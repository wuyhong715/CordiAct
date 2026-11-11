package com.cordiact.core.attachment

/**
 * Opaque handle for one imported attachment.
 *
 * Deliberately not a path, a content hash or a `content://` URI: a handle must never grant
 * access on its own. Every read is resolved with `(workspaceId, attachmentId, operation)`
 * so that guessing an id cannot reach another Workspace.
 */
@JvmInline
value class AttachmentId(val value: String) {
    init {
        require(value.isNotBlank()) { "Attachment id must not be blank" }
        require(value.length <= 128) { "Attachment id must be at most 128 characters" }
        require(value.all { it.isLetterOrDigit() || it == '-' || it == '_' }) {
            "Attachment id must be an opaque token of letters, digits, '-' and '_'"
        }
    }

    override fun toString(): String = value
}

/** Lifecycle of one stored object. Only [READY] objects may be sent to a model. */
enum class AttachmentState { IMPORTING, VERIFYING, READY, FAILED, CANCELLED }

/** How a model is asked to interpret one attachment. */
enum class Interpretation {
    /** Image handed to a vision-capable model as pixels. */
    VISION,

    /** Document (PDF today) handed to a model that reads the document itself. */
    NATIVE_DOCUMENT,

    /** Text projected out of a file by CordiAct before sending. */
    TEXT_EXTRACT,

    /** Kept in the Workspace only; never transmitted. Rejected by the transmission planner. */
    LOCAL_ONLY,
    ;

    val isTransmittable: Boolean get() = this != LOCAL_ONLY

    companion object {
        /** Interpretations that make sense for a media kind, before model capability is applied. */
        fun supportedFor(kind: AttachmentMediaKind): Set<Interpretation> = when (kind) {
            AttachmentMediaKind.IMAGE -> setOf(VISION, LOCAL_ONLY)
            AttachmentMediaKind.PDF -> setOf(NATIVE_DOCUMENT, TEXT_EXTRACT, LOCAL_ONLY)
            AttachmentMediaKind.TEXT -> setOf(TEXT_EXTRACT, LOCAL_ONLY)
            AttachmentMediaKind.BINARY -> setOf(LOCAL_ONLY)
        }
    }
}

/** Coarse media class. Fine-grained MIME support is decided by [AttachmentInputCapability]. */
enum class AttachmentMediaKind { IMAGE, PDF, TEXT, BINARY }

/**
 * One imported file.
 *
 * [sha256] is the digest of the bytes actually stored; it is empty only while the object is
 * still being imported. [state] is authoritative: a descriptor that is not [AttachmentState.READY]
 * must not be read or sent.
 */
data class AttachmentDescriptor(
    val id: AttachmentId,
    val workspaceId: String,
    val mimeType: String,
    val displayName: String,
    val byteSize: Long,
    val sha256: String,
    val state: AttachmentState,
    val createdAtMillis: Long,
    val verifiedAtMillis: Long? = null,
    val width: Int? = null,
    val height: Int? = null,
    val pageCount: Int? = null,
) {
    init {
        require(workspaceId.isNotBlank()) { "An attachment belongs to a Workspace" }
        require(byteSize >= 0) { "byteSize must not be negative" }
        require(displayName.isNotBlank()) { "displayName must not be blank" }
        require(width == null || width > 0) { "width must be positive when known" }
        require(height == null || height > 0) { "height must be positive when known" }
        require(pageCount == null || pageCount > 0) { "pageCount must be positive when known" }
        if (state == AttachmentState.READY) {
            require(sha256.isValidSha256()) { "A READY attachment must carry a SHA-256 digest" }
            require(verifiedAtMillis != null) { "A READY attachment must record when it was verified" }
        }
    }

    val mediaKind: AttachmentMediaKind get() = AttachmentFormats.kindOf(mimeType)
}

/**
 * A derived object: a re-encoded image, an extracted text projection, and so on.
 *
 * The original object is never overwritten. [transformJson] and [extractorVersion] are stored so
 * a replay can explain why the derived bytes differ from the source bytes.
 */
data class AttachmentRendition(
    val id: String,
    val attachmentId: AttachmentId,
    val mimeType: String,
    val byteSize: Long,
    val sha256: String,
    val transformJson: String? = null,
    val extractorVersion: String? = null,
) {
    init {
        require(id.isNotBlank()) { "A rendition needs an id" }
        require(byteSize >= 0) { "byteSize must not be negative" }
        require(sha256.isValidSha256()) { "A rendition must carry a SHA-256 digest" }
    }
}

/** Local, conservative caps. They are not vendor maxima and are always applied before a vendor cap. */
data class AttachmentLimits(
    val maxAttachmentsPerMessage: Int = 10,
    val maxImageBytes: Long = 10L * 1024 * 1024,
    val maxImagePixels: Long = 20_000_000,
    val maxImageEdge: Int = 8192,
    val maxPdfBytes: Long = 10L * 1024 * 1024,
    val maxPdfPages: Int = 100,
    val maxTextBytes: Long = 256L * 1024,
    val maxTextTotalBytes: Long = 512L * 1024,
    val maxRawRequestBytes: Long = 16L * 1024 * 1024,
    val maxEncodedRequestBytes: Long = 24L * 1024 * 1024,
) {
    init {
        require(maxAttachmentsPerMessage > 0)
        require(maxImageBytes > 0 && maxPdfBytes > 0 && maxTextBytes > 0)
        require(maxTextTotalBytes >= maxTextBytes)
        require(maxRawRequestBytes > 0 && maxEncodedRequestBytes >= maxRawRequestBytes)
    }

    /** Upper bound for a single imported object, before its media kind is verified. */
    val maxImportBytes: Long
        get() = maxOf(maxImageBytes, maxPdfBytes, maxTextTotalBytes)
}

/** Remote Files API, when a provider has one and it has been declared explicitly. */
data class FilesApiCapability(
    val uploadEndpoint: String,
    val maxFileBytes: Long,
    val retentionMillis: Long? = null,
) {
    init {
        require(uploadEndpoint.startsWith("https://")) { "A Files endpoint must be HTTPS" }
        require(maxFileBytes > 0)
    }
}

/**
 * What one `(provider, model, protocol, endpoint revision)` actually accepts.
 *
 * Absence of a declaration means "unsupported": nothing is inferred from a model name.
 */
data class AttachmentInputCapability(
    val interpretations: Set<Interpretation> = emptySet(),
    val mimeTypes: Set<String> = emptySet(),
    val maxFileBytes: Long = 0,
    val maxRequestBytes: Long = 0,
    val maxCount: Int = 0,
    val filesApi: FilesApiCapability? = null,
) {
    init {
        require(maxFileBytes >= 0 && maxRequestBytes >= 0 && maxCount >= 0)
    }

    /** True when the endpoint declared media support at all. */
    val declared: Boolean get() = interpretations.isNotEmpty() && maxCount > 0

    /** Declared MIME entries may be exact (`image/png`) or a wildcard suffix such as `text/…`. */
    fun acceptsMimeType(mimeType: String): Boolean {
        val normalized = AttachmentFormats.normalizeMimeType(mimeType)
        if (normalized.isEmpty()) return false
        return mimeTypes.any { declared ->
            val candidate = AttachmentFormats.normalizeMimeType(declared)
            candidate == normalized || (candidate.endsWith("/*") && normalized.startsWith(candidate.dropLast(1)))
        }
    }

    fun supports(mimeType: String, interpretation: Interpretation): Boolean =
        interpretation.isTransmittable &&
            interpretation in interpretations &&
            interpretation in Interpretation.supportedFor(AttachmentFormats.kindOf(mimeType)) &&
            acceptsMimeType(mimeType)

    companion object {
        /** Nothing may be sent. This is the default for every endpoint that has not declared media. */
        val UNSUPPORTED = AttachmentInputCapability()
    }
}

enum class AttachmentErrorCode {
    EMPTY_MESSAGE,
    UNKNOWN_ATTACHMENT,
    UNKNOWN_RENDITION,
    CROSS_WORKSPACE,
    NOT_READY,
    LOCAL_ONLY_PART,
    UNSUPPORTED_CAPABILITY,
    UNSUPPORTED_MIME,
    UNSUPPORTED_INTERPRETATION,
    FILE_TOO_LARGE,
    IMAGE_TOO_LARGE,
    TOO_MANY_ATTACHMENTS,
    REQUEST_TOO_LARGE,
    BUDGET_EXCEEDED,
    INVALID_MEDIA_TYPE,
    INTEGRITY_MISMATCH,
    INVALID_DISPLAY_NAME,
    STORAGE_FAILURE,
    CANCELLED,
}

/** Typed attachment failure. Callers must surface [code]; none of these may be swallowed. */
class AttachmentException(
    val code: AttachmentErrorCode,
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause) {
    override fun toString(): String = "AttachmentException($code): ${message}"
}

internal fun String.isValidSha256(): Boolean =
    length == 64 && all { it in '0'..'9' || it in 'a'..'f' }
