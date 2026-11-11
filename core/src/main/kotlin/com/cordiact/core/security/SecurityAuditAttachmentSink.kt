package com.cordiact.core.security

import com.cordiact.core.attachment.AttachmentAuditAction
import com.cordiact.core.attachment.AttachmentAuditDecision
import com.cordiact.core.attachment.AttachmentAuditEvent
import com.cordiact.core.attachment.AttachmentAuditSink

/**
 * Writes attachment events into the existing tamper-evident audit log.
 *
 * The log's canonical payload is unchanged: everything attachment-specific travels in
 * `targetDescriptorSanitized` / `receiverDescriptorSanitized` and bounded reason codes, so old
 * events keep verifying. No display name, URI, path, base64 or content is ever written.
 */
class SecurityAuditAttachmentSink(private val audit: AuditLog) : AttachmentAuditSink {
    override fun record(event: AttachmentAuditEvent) {
        audit.append(
            AuditEventDraft(
                category = AuditCategory.SECURITY,
                phase = when (event.decision) {
                    AttachmentAuditDecision.ALLOWED ->
                        if (event.action == AttachmentAuditAction.SEND) AuditPhase.EXECUTION_STARTED else AuditPhase.SUCCEEDED

                    AttachmentAuditDecision.DENIED -> AuditPhase.POLICY_EVALUATED
                    AttachmentAuditDecision.FAILED -> AuditPhase.FAILED
                },
                outcome = when (event.decision) {
                    AttachmentAuditDecision.ALLOWED -> AuditOutcome.ALLOWED
                    AttachmentAuditDecision.DENIED -> AuditOutcome.DENIED
                    AttachmentAuditDecision.FAILED -> AuditOutcome.FAILED
                },
                reasonCode = boundedReason(event),
                correlationId = "attachment:${event.action.name}:${event.attachmentId ?: event.workspaceId}",
                workspaceId = event.workspaceId,
                sessionId = event.sessionId,
                turnId = event.turnId,
                attemptId = event.attemptId,
                toolId = "attachment",
                policyVersion = event.policyRevision,
                targetDescriptorSanitized = describeTarget(event),
                receiverDescriptorSanitized = event.endpointFingerprint?.let { "endpoint:$it" },
            ),
        )
    }

    private fun boundedReason(event: AttachmentAuditEvent): String {
        val code = event.reasonCode ?: event.decision.name
        return code.take(96)
    }

    private fun describeTarget(event: AttachmentAuditEvent): String = buildString {
        append("attachment")
        event.attachmentId?.let { append(" id=").append(it.take(64)) }
        event.renditionId?.let { append(" rendition=").append(it.take(64)) }
        event.mimeType?.let { append(" mime=").append(it.take(64)) }
        event.byteSize?.let { append(" bytes=").append(it) }
        event.sha256?.let { append(" sha256=").append(it.take(16)) }
        event.interpretation?.let { append(" mode=").append(it.name) }
    }.take(240)
}
