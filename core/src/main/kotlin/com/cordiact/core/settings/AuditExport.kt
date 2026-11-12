package com.cordiact.core.settings

import com.cordiact.core.security.*
import com.google.gson.GsonBuilder
import java.io.OutputStream
import java.nio.charset.StandardCharsets
import java.util.UUID

/** A non-workspace scope for global settings; never use a blank audit workspace. */
const val GLOBAL_SETTINGS_AUDIT_SCOPE = "@global-settings"

data class AuditExportReceipt(val count: Int, val completionRecorded: Boolean)

/** Exports only the already-displayed bounded snapshot; never queries or exports hidden history. */
class AuditExport(private val audit: AuditLog) {
    fun write(events: List<AuditEvent>, openDestination: () -> OutputStream): AuditExportReceipt {
        require(events.size <= 200) { "AUDIT_EXPORT_LIMIT" }
        val snapshot = events.toList()
        val correlation = UUID.randomUUID().toString()
        fun record(phase: AuditPhase, outcome: AuditOutcome, reason: String) = audit.append(AuditEventDraft(
            category = AuditCategory.SECURITY, phase = phase, outcome = outcome,
            reasonCode = reason, correlationId = correlation, workspaceId = GLOBAL_SETTINGS_AUDIT_SCOPE,
            toolId = "settings.audit.export",
        ))
        // Fail closed: no destination open/write until the attempt has been durably recorded.
        record(AuditPhase.REQUESTED, AuditOutcome.PENDING, "AUDIT_EXPORT_REQUESTED")
        try {
            val payload = linkedMapOf(
                "schema" to "cordiact.audit.redacted.v1", "scope" to "displayed-window",
                "maximumEvents" to 200, "count" to snapshot.size,
                "events" to snapshot.map(::auditDetailFields),
            )
            val bytes = GsonBuilder().serializeNulls().setPrettyPrinting().create().toJson(payload)
                .toByteArray(StandardCharsets.UTF_8)
            openDestination().use { output -> output.write(bytes); output.flush() }
        } catch (failure: Exception) {
            try { record(AuditPhase.FAILED, AuditOutcome.FAILED, "AUDIT_EXPORT_FAILED") }
            catch (auditFailure: Exception) { failure.addSuppressed(auditFailure) }
            throw failure
        }
        val recorded = try {
            record(AuditPhase.SUCCEEDED, AuditOutcome.SUCCEEDED, "AUDIT_EXPORT_SUCCEEDED")
            true
        } catch (_: Exception) { false }
        return AuditExportReceipt(snapshot.size, recorded)
    }
}
