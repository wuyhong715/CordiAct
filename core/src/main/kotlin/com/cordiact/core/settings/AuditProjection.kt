package com.cordiact.core.settings

import com.cordiact.core.security.AuditEvent
import com.cordiact.core.security.AuditOutcome
import java.time.Instant

/** Applies UI-only filters to an explicitly bounded query window. */
fun filterAuditWindow(events: List<AuditEvent>, outcome: AuditOutcome?, since: Instant?): List<AuditEvent> =
    events.filter { (outcome == null || it.draft.outcome == outcome) && (since == null || !it.occurredAt.isBefore(since)) }

/** Explicit allowlist; arbitrary descriptors and credential identifiers never reach this projection. */
fun auditDetailFields(event: AuditEvent): Map<String, Any?> = linkedMapOf(
    "eventId" to event.eventId, "sequence" to event.sequence, "occurredAt" to event.occurredAt.toString(),
    "category" to event.draft.category.name, "phase" to event.draft.phase.name,
    "outcome" to event.draft.outcome.name, "reasonCode" to event.draft.reasonCode,
    "workspaceId" to event.draft.workspaceId, "approvalAuthority" to event.draft.approvalAuthority?.name,
    "permissionPreset" to event.draft.permissionPreset?.name,
).apply {
    // Only our settings writer's fixed identifiers are safe to expose as action metadata.
    if (event.draft.reasonCode == "SETTINGS_CHANGED" &&
        event.draft.toolId in MODEL_SETTINGS_AUDIT_TARGETS + setOf("security.permission", "security.privilege")) {
        put("setting", event.draft.toolId)
        put("revision", event.draft.policyVersion?.toLongOrNull())
        put("actor", "owner")
    }
    if (event.draft.reasonCode == "REVIEWER_SNAPSHOT_DISCARDED" &&
        event.draft.toolId == "security.reviewer.migration") {
        put("setting", "security.reviewer.migration")
        put("revision", event.draft.policyVersion?.toLongOrNull())
        put("actor", "migration")
    }
}
