package com.cordiact.core.security

import com.cordiact.core.attachment.AttachmentAuditAction
import com.cordiact.core.attachment.AttachmentAuditDecision
import com.cordiact.core.attachment.AttachmentAuditEvent
import com.cordiact.core.attachment.Interpretation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SecurityAuditAttachmentSinkTest {
    private val sha = "a".repeat(64)

    @Test
    fun recordsABoundedSanitizedAllowedSend() {
        val log = InMemoryAuditLog(HmacAuditSigner("test-key-0123456789".toByteArray()))
        SecurityAuditAttachmentSink(log).record(
            AttachmentAuditEvent(
                action = AttachmentAuditAction.SEND,
                decision = AttachmentAuditDecision.ALLOWED,
                workspaceId = "ws-1",
                sessionId = "s-1",
                attachmentId = "att-1",
                mimeType = "image/png",
                byteSize = 1234,
                sha256 = sha,
                endpointFingerprint = "abcd1234deadbeef",
                interpretation = Interpretation.VISION,
                policyRevision = "p0-1",
            ),
        )
        val draft = log.query().single().draft
        assertEquals(AuditCategory.SECURITY, draft.category)
        assertEquals(AuditOutcome.ALLOWED, draft.outcome)
        assertEquals(AuditPhase.EXECUTION_STARTED, draft.phase)
        assertEquals("attachment", draft.toolId)
        assertEquals("attachment:SEND:att-1", draft.correlationId)
        assertEquals("p0-1", draft.policyVersion)
        val target = draft.targetDescriptorSanitized.orEmpty()
        assertTrue(target.contains("id=att-1"))
        assertTrue(target.contains("mime=image/png"))
        assertTrue(target.contains("bytes=1234"))
        assertTrue(target.contains("sha256=aaaaaaaaaaaaaaaa"))
        assertFalse(target.contains(sha), "Only a digest prefix may be stored")
        assertFalse(target.contains("photo.png"), "Display names are not audit material")
        assertEquals("endpoint:abcd1234deadbeef", draft.receiverDescriptorSanitized)
    }

    @Test
    fun deniedEventCarriesTheReasonCode() {
        val log = InMemoryAuditLog(HmacAuditSigner("test-key-0123456789".toByteArray()))
        SecurityAuditAttachmentSink(log).record(
            AttachmentAuditEvent(
                action = AttachmentAuditAction.SEND,
                decision = AttachmentAuditDecision.DENIED,
                workspaceId = "ws-1",
                reasonCode = "UNSUPPORTED_CAPABILITY",
            ),
        )
        val draft = log.query().single().draft
        assertEquals(AuditOutcome.DENIED, draft.outcome)
        assertEquals(AuditPhase.POLICY_EVALUATED, draft.phase)
        assertEquals("UNSUPPORTED_CAPABILITY", draft.reasonCode)
    }

    @Test
    fun readEventUsesSucceededPhase() {
        val log = InMemoryAuditLog(HmacAuditSigner("test-key-0123456789".toByteArray()))
        SecurityAuditAttachmentSink(log).record(
            AttachmentAuditEvent(
                action = AttachmentAuditAction.READ,
                decision = AttachmentAuditDecision.ALLOWED,
                workspaceId = "ws-1",
                attachmentId = "att-1",
            ),
        )
        assertEquals(AuditPhase.SUCCEEDED, log.query().single().draft.phase)
    }
}
