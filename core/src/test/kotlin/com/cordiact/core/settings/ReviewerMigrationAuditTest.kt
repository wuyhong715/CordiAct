package com.cordiact.core.settings

import com.cordiact.core.security.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.io.IOException

class ReviewerMigrationAuditTest {
    private val notice = ReviewerMigrationNotice("workspace", 8, "migration-id")
    private class Outbox(val values: MutableList<ReviewerMigrationNotice>) : ReviewerMigrationOutbox {
        var failAck = false
        override fun pending() = values.toList()
        override fun acknowledge(notice: ReviewerMigrationNotice) {
            if (failAck) throw IOException("ack unavailable")
            values.remove(notice)
        }
    }
    private fun log() = InMemoryAuditLog(AuditSigner { _, _ -> "signed" })
    @Test fun deliveryContainsOnlyMigrationMetadataAndAcknowledges() {
        val outbox = Outbox(mutableListOf(notice)); val audit = log()
        assertEquals(1, ReviewerMigrationAudit(outbox, audit).flush())
        assertTrue(outbox.pending().isEmpty())
        val event = audit.query().single()
        assertEquals("REVIEWER_SNAPSHOT_DISCARDED", event.draft.reasonCode)
        assertEquals("8", event.draft.policyVersion)
        assertNull(event.draft.targetDescriptorSanitized)
        assertNull(event.draft.credentialOpaqueId)
        assertEquals("migration", auditDetailFields(event)["actor"])
        assertEquals(8L, auditDetailFields(event)["revision"])
    }
    @Test fun failedAppendKeepsDurableNoticeForRetry() {
        val outbox = Outbox(mutableListOf(notice))
        val audit = object : AuditLog by log() {
            override fun append(draft: AuditEventDraft): AuditEvent = throw IOException("offline")
        }
        assertThrows(IOException::class.java) { ReviewerMigrationAudit(outbox, audit).flush() }
        assertEquals(listOf(notice), outbox.pending())
        assertEquals(1, ReviewerMigrationAudit(outbox, log()).flush())
    }
    @Test fun crashAfterAppendBeforeAckDoesNotDuplicateOnRetry() {
        val outbox = Outbox(mutableListOf(notice)); val audit = log()
        outbox.failAck = true
        assertThrows(IOException::class.java) { ReviewerMigrationAudit(outbox, audit).flush() }
        assertEquals(1, audit.query().size)
        outbox.failAck = false
        assertEquals(1, ReviewerMigrationAudit(outbox, audit).flush())
        assertEquals(1, audit.query().size)
        assertEquals(0, ReviewerMigrationAudit(outbox, audit).flush())
    }
    @Test fun separateWorkspaceNoticesDeliveredSeparately() {
        val outbox = Outbox(mutableListOf(notice, notice.copy(workspaceId = "other", correlationId = "other-id")))
        val audit = log()
        assertEquals(2, ReviewerMigrationAudit(outbox, audit).flush())
        assertEquals(2, audit.query().size)
    }

    @Test fun unrelatedAuditWithSameCorrelationDoesNotSuppressMigrationRecord() {
        val audit = log()
        audit.append(AuditEventDraft(AuditCategory.SECURITY, AuditPhase.SUCCEEDED, AuditOutcome.SUCCEEDED,
            "UNRELATED", notice.correlationId, workspaceId = notice.workspaceId))
        val outbox = Outbox(mutableListOf(notice))
        assertEquals(1, ReviewerMigrationAudit(outbox, audit).flush())
        assertEquals(2, audit.query().size)
    }
    @Test fun invalidPendingNoticeIsRetainedAndNotAudited() {
        val invalid = notice.copy(revision = 0)
        val outbox = Outbox(mutableListOf(invalid)); val audit = log()
        assertThrows(IllegalArgumentException::class.java) { ReviewerMigrationAudit(outbox, audit).flush() }
        assertEquals(listOf(invalid), outbox.pending())
        assertTrue(audit.query().isEmpty())
    }

    @Test fun failedDeduplicationQueryDoesNotAcknowledgeOrAppend() {
        val outbox = Outbox(mutableListOf(notice)); val store = log()
        val audit = object : AuditLog by store {
            override fun query(filter: AuditFilter): List<AuditEvent> = throw IOException("query offline")
        }
        assertThrows(IOException::class.java) { ReviewerMigrationAudit(outbox, audit).flush() }
        assertEquals(listOf(notice), outbox.pending())
        assertTrue(store.query().isEmpty())
    }
}
