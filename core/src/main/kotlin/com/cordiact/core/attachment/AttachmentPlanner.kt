package com.cordiact.core.attachment

import java.nio.charset.StandardCharsets
import java.security.MessageDigest

/** Everything the planner needs to refuse or approve one send, before any network work happens. */
data class TransmissionRequest(
    val workspaceId: String,
    val sessionId: String,
    val providerId: String,
    val modelId: String,
    val protocol: String,
    val endpointRevision: String,
    val policyRevision: String,
    val capability: AttachmentInputCapability,
    val parts: List<MessagePart>,
)

data class PlannedAttachment(
    val attachmentId: AttachmentId,
    val renditionId: String?,
    val mimeType: String,
    val interpretation: Interpretation,
    val byteSize: Long,
    val sha256: String,
    val displayName: String,
)

/**
 * Immutable snapshot of exactly what will be put on the wire.
 *
 * Any change to an endpoint, a file digest or an interpretation must produce a different plan and
 * therefore a different [fingerprint]; an approval issued for one fingerprint cannot authorise
 * another.
 */
data class TransmissionPlan(
    val providerId: String,
    val modelId: String,
    val protocol: String,
    val endpointRevision: String,
    val policyRevision: String,
    val workspaceId: String,
    val sessionId: String,
    val parts: List<MessagePart>,
    val attachments: List<PlannedAttachment>,
    val textBytes: Long,
    val attachmentBytes: Long,
    val estimatedRequestBytes: Long,
) {
    val fingerprint: String by lazy {
        val canonical = buildString {
            append(providerId).append('\u0000')
            append(modelId).append('\u0000')
            append(protocol).append('\u0000')
            append(endpointRevision).append('\u0000')
            append(policyRevision).append('\u0000')
            append(workspaceId).append('\u0000')
            append(sessionId).append('\u0000')
            for (part in parts) {
                when (part) {
                    is MessagePart.Text -> append("text:").append(part.text.length).append(':')
                        .append(part.text.hashCode())
                    is MessagePart.Attachment -> append("att:").append(part.id.value)
                        .append(':').append(part.renditionId ?: "-")
                        .append(':').append(part.interpretation.name)
                }
                append('\u0000')
            }
            for (attachment in attachments) {
                append(attachment.attachmentId.value).append(':')
                    .append(attachment.renditionId ?: "-").append(':')
                    .append(attachment.mimeType).append(':')
                    .append(attachment.byteSize).append(':')
                    .append(attachment.sha256).append('\u0000')
            }
        }
        MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray(StandardCharsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }
}

/**
 * Validates an ordered part list against local limits and one endpoint's declared capability.
 *
 * This is the only place that decides whether an attachment may leave the device. It fails closed:
 * unknown capability, unknown object, non-READY object, mismatched workspace and every size overrun
 * are errors, never a silent omission or a downgrade to text.
 */
class AttachmentPlanner(
    private val reader: AttachmentReader,
    private val limits: AttachmentLimits = AttachmentLimits(),
) {
    fun plan(request: TransmissionRequest): TransmissionPlan {
        MessageParts.validate(request.parts)

        var textBytes = 0L
        for (part in request.parts) {
            if (part is MessagePart.Text) {
                val bytes = part.text.toByteArray(StandardCharsets.UTF_8).size.toLong()
                if (bytes > limits.maxTextBytes) {
                    throw AttachmentException(
                        AttachmentErrorCode.FILE_TOO_LARGE,
                        "A text part is $bytes bytes; the per-part limit is ${limits.maxTextBytes}",
                    )
                }
                textBytes += bytes
            }
        }
        if (textBytes > limits.maxTextTotalBytes) {
            throw AttachmentException(
                AttachmentErrorCode.FILE_TOO_LARGE,
                "Text parts total $textBytes bytes; the limit is ${limits.maxTextTotalBytes}",
            )
        }

        val attachmentParts = MessageParts.attachmentParts(request.parts)
        if (attachmentParts.isEmpty()) {
            return TransmissionPlan(
                providerId = request.providerId,
                modelId = request.modelId,
                protocol = request.protocol,
                endpointRevision = request.endpointRevision,
                policyRevision = request.policyRevision,
                workspaceId = request.workspaceId,
                sessionId = request.sessionId,
                parts = request.parts,
                attachments = emptyList(),
                textBytes = textBytes,
                attachmentBytes = 0,
                estimatedRequestBytes = textBytes + BASE_ENVELOPE_BYTES,
            )
        }

        if (!request.capability.declared) {
            throw AttachmentException(
                AttachmentErrorCode.UNSUPPORTED_CAPABILITY,
                "${request.providerId}/${request.modelId} has not declared attachment input support",
            )
        }

        val maxCount = minOf(limits.maxAttachmentsPerMessage, request.capability.maxCount)
        if (attachmentParts.size > maxCount) {
            throw AttachmentException(
                AttachmentErrorCode.TOO_MANY_ATTACHMENTS,
                "${attachmentParts.size} attachments were selected; the limit is $maxCount",
            )
        }

        val planned = ArrayList<PlannedAttachment>(attachmentParts.size)
        var attachmentBytes = 0L
        var textExtractBytes = 0L

        for (part in attachmentParts) {
            if (part.interpretation == Interpretation.LOCAL_ONLY) {
                throw AttachmentException(
                    AttachmentErrorCode.LOCAL_ONLY_PART,
                    "An attachment marked LOCAL_ONLY cannot be sent",
                )
            }
            val descriptor = reader.find(request.workspaceId, part.id)
                ?: throw AttachmentException(
                    AttachmentErrorCode.UNKNOWN_ATTACHMENT,
                    "Attachment ${part.id.value} does not exist in this Workspace",
                )
            if (descriptor.workspaceId != request.workspaceId) {
                throw AttachmentException(
                    AttachmentErrorCode.CROSS_WORKSPACE,
                    "Attachment ${part.id.value} belongs to another Workspace",
                )
            }
            if (descriptor.state != AttachmentState.READY) {
                throw AttachmentException(
                    AttachmentErrorCode.NOT_READY,
                    "Attachment ${part.id.value} is ${descriptor.state}, not READY",
                )
            }

            val rendition = part.renditionId?.let {
                reader.rendition(request.workspaceId, part.id, it)
                    ?: throw AttachmentException(
                        AttachmentErrorCode.UNKNOWN_RENDITION,
                        "Rendition $it of ${part.id.value} does not exist",
                    )
            }
            val mimeType = rendition?.mimeType ?: descriptor.mimeType
            val byteSize = rendition?.byteSize ?: descriptor.byteSize
            val sha256 = rendition?.sha256 ?: descriptor.sha256
            val kind = AttachmentFormats.kindOf(mimeType)

            if (part.interpretation !in Interpretation.supportedFor(kind)) {
                throw AttachmentException(
                    AttachmentErrorCode.UNSUPPORTED_INTERPRETATION,
                    "${part.interpretation} cannot be used with $mimeType",
                )
            }
            if (!request.capability.acceptsMimeType(mimeType)) {
                throw AttachmentException(
                    AttachmentErrorCode.UNSUPPORTED_MIME,
                    "${request.providerId}/${request.modelId} does not accept $mimeType",
                )
            }
            if (part.interpretation !in request.capability.interpretations) {
                throw AttachmentException(
                    AttachmentErrorCode.UNSUPPORTED_CAPABILITY,
                    "${request.providerId}/${request.modelId} does not accept ${part.interpretation}",
                )
            }

            val kindLimit = when (kind) {
                AttachmentMediaKind.IMAGE -> limits.maxImageBytes
                AttachmentMediaKind.PDF -> limits.maxPdfBytes
                AttachmentMediaKind.TEXT -> limits.maxTextBytes
                AttachmentMediaKind.BINARY -> throw AttachmentException(
                    AttachmentErrorCode.UNSUPPORTED_MIME,
                    "$mimeType can only be kept locally",
                )
            }
            val maxFileBytes = if (request.capability.maxFileBytes > 0) {
                minOf(kindLimit, request.capability.maxFileBytes)
            } else {
                kindLimit
            }
            if (byteSize > maxFileBytes) {
                throw AttachmentException(
                    AttachmentErrorCode.FILE_TOO_LARGE,
                    "${descriptor.displayName} is $byteSize bytes; the limit is $maxFileBytes",
                )
            }
            if (kind == AttachmentMediaKind.TEXT) textExtractBytes += byteSize
            attachmentBytes += byteSize

            planned += PlannedAttachment(
                attachmentId = part.id,
                renditionId = part.renditionId,
                mimeType = mimeType,
                interpretation = part.interpretation,
                byteSize = byteSize,
                sha256 = sha256,
                displayName = descriptor.displayName,
            )
        }

        if (textExtractBytes > limits.maxTextTotalBytes) {
            throw AttachmentException(
                AttachmentErrorCode.FILE_TOO_LARGE,
                "Text attachments total $textExtractBytes bytes; the limit is ${limits.maxTextTotalBytes}",
            )
        }

        val rawLimit = if (request.capability.maxRequestBytes > 0) {
            minOf(limits.maxRawRequestBytes, request.capability.maxRequestBytes)
        } else {
            limits.maxRawRequestBytes
        }
        if (attachmentBytes + textBytes > rawLimit) {
            throw AttachmentException(
                AttachmentErrorCode.REQUEST_TOO_LARGE,
                "The request carries ${attachmentBytes + textBytes} raw bytes; the limit is $rawLimit",
            )
        }

        var estimated = BASE_ENVELOPE_BYTES + textBytes
        for (attachment in planned) {
            estimated += AttachmentSizes.base64Length(attachment.byteSize) + PER_ATTACHMENT_OVERHEAD_BYTES
        }
        if (estimated > limits.maxEncodedRequestBytes) {
            throw AttachmentException(
                AttachmentErrorCode.BUDGET_EXCEEDED,
                "The encoded request would be about $estimated bytes; the limit is ${limits.maxEncodedRequestBytes}",
            )
        }

        return TransmissionPlan(
            providerId = request.providerId,
            modelId = request.modelId,
            protocol = request.protocol,
            endpointRevision = request.endpointRevision,
            policyRevision = request.policyRevision,
            workspaceId = request.workspaceId,
            sessionId = request.sessionId,
            parts = request.parts,
            attachments = planned,
            textBytes = textBytes,
            attachmentBytes = attachmentBytes,
            estimatedRequestBytes = estimated,
        )
    }

    private companion object {
        const val BASE_ENVELOPE_BYTES = 2L * 1024
        const val PER_ATTACHMENT_OVERHEAD_BYTES = 512L
    }
}

object AttachmentSizes {
    /** Exact base64 length for [byteCount] bytes: 4 * ceil(n / 3). */
    fun base64Length(byteCount: Long): Long {
        require(byteCount >= 0)
        return 4L * ((byteCount + 2L) / 3L)
    }
}
