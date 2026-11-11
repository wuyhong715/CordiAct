package com.cordiact.core.attachment

/**
 * What happened to an attachment, for the audit trail.
 *
 * The event deliberately has no field for content, bytes, URI, path or display name: an audit
 * writer cannot leak what the type does not carry.
 */
enum class AttachmentAuditAction { IMPORT, READ, TRANSFORM, SEND, LOCAL_DELETE }

enum class AttachmentAuditDecision { ALLOWED, DENIED, FAILED }

data class AttachmentAuditEvent(
    val action: AttachmentAuditAction,
    val decision: AttachmentAuditDecision,
    val workspaceId: String,
    val sessionId: String? = null,
    val turnId: String? = null,
    val attemptId: String? = null,
    val attachmentId: String? = null,
    val renditionId: String? = null,
    val mimeType: String? = null,
    val byteSize: Long? = null,
    val sha256: String? = null,
    /** Fingerprint of the receiving endpoint, never its URL or credential. */
    val endpointFingerprint: String? = null,
    val interpretation: Interpretation? = null,
    val policyRevision: String? = null,
    val reasonCode: String? = null,
    val occurredAtMillis: Long = System.currentTimeMillis(),
) {
    init {
        require(workspaceId.isNotBlank()) { "An attachment audit event belongs to a Workspace" }
        require(reasonCode == null || reasonCode.length <= 96) { "reasonCode is too long" }
    }
}

/** Audit port. A sink that throws makes the audited action fail closed. */
fun interface AttachmentAuditSink {
    fun record(event: AttachmentAuditEvent)
}

/** Used where no audit log is wired yet; never used for a real send path without a decision. */
object NoopAttachmentAuditSink : AttachmentAuditSink {
    override fun record(event: AttachmentAuditEvent) = Unit
}
