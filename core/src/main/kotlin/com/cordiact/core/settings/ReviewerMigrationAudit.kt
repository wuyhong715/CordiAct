package com.cordiact.core.settings

import com.cordiact.core.security.*

data class ReviewerMigrationNotice(val workspaceId: String, val revision: Long, val correlationId: String)
interface ReviewerMigrationOutbox {
    fun pending(): List<ReviewerMigrationNotice>
    fun acknowledge(notice: ReviewerMigrationNotice)
}
/** At-least-once delivery with stable audit identity; crash after append is safe to retry. */
class ReviewerMigrationAudit(private val outbox: ReviewerMigrationOutbox, private val audit: AuditLog) {
    @Synchronized fun flush(): Int {
        var delivered = 0
        outbox.pending().forEach { notice ->
            require(notice.workspaceId.isNotBlank() && notice.revision > 0 && notice.correlationId.isNotBlank())
            val exists = audit.query(AuditFilter(workspaceId = notice.workspaceId,
                correlationId = notice.correlationId, limit = 10)).any {
                it.draft.reasonCode == "REVIEWER_SNAPSHOT_DISCARDED" &&
                    it.draft.policyVersion == notice.revision.toString()
            }
            if (!exists) audit.append(AuditEventDraft(
                category = AuditCategory.SECURITY, phase = AuditPhase.SUCCEEDED,
                outcome = AuditOutcome.SUCCEEDED, reasonCode = "REVIEWER_SNAPSHOT_DISCARDED",
                correlationId = notice.correlationId, workspaceId = notice.workspaceId,
                toolId = "security.reviewer.migration", policyVersion = notice.revision.toString(),
            ))
            outbox.acknowledge(notice)
            delivered++
        }
        return delivered
    }
}
