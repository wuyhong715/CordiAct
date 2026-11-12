package com.cordiact.core.settings

import com.cordiact.core.security.*
import java.util.UUID
import java.util.concurrent.CancellationException

val MODEL_SETTINGS_AUDIT_TARGETS: Set<String> = setOf(
    "model.provider.add", "model.provider.refresh", "model.provider.update", "model.provider.remove",
    "model.provider.enabled", "model.provider.defaults", "model.provider.key",
    "model.role.conversation", "model.role.summary", "model.role.extraction",
    "mcp.install", "mcp.remove", "mcp.enabled", "mcp.reconnect", "search.policy", "search.provider",
    "ssh.host.save", "ssh.host.pin", "ssh.host.delete", "attachments.policy",
)
data class SettingsOperationReceipt<T>(val value: T, val completionRecorded: Boolean)
/** Multi-store operations are not transactions. A failure means reconcile, not blindly retry. */
class SettingsMutationUncertain : IllegalStateException("SETTINGS_MUTATION_UNCERTAIN")
class SettingsAttemptAuditFailed : IllegalStateException("SETTINGS_ATTEMPT_AUDIT_FAILED")

class SettingsOperationAudit(private val audit: AuditLog) {
    fun <T> run(target: String, revision: () -> Long, mutate: () -> T): SettingsOperationReceipt<T> {
        require(target in MODEL_SETTINGS_AUDIT_TARGETS || target.matches(Regex("capability:[a-zA-Z0-9._-]+:(ssh|termux|screen|browser|privilege|search|mcp\\.[a-zA-Z0-9_-]+)"))) { "UNKNOWN_SETTINGS_AUDIT_TARGET" }
        val correlation = UUID.randomUUID().toString()
        fun record(phase: AuditPhase, outcome: AuditOutcome, reason: String, version: Long? = null) =
            audit.append(AuditEventDraft(AuditCategory.SECURITY, phase, outcome, reason, correlation,
                workspaceId = GLOBAL_SETTINGS_AUDIT_SCOPE, toolId = target, policyVersion = version?.toString()))
        try { record(AuditPhase.REQUESTED, AuditOutcome.PENDING, "SETTINGS_CHANGE_REQUESTED") }
        catch (_: Exception) { throw SettingsAttemptAuditFailed() }
        val value = try { mutate() } catch (cancelled: CancellationException) {
            try { record(AuditPhase.FAILED, AuditOutcome.FAILED, "SETTINGS_CHANGE_CANCELLED_RECONCILE") }
            catch (_: Exception) { }
            throw cancelled
        } catch (_: Exception) {
            try { record(AuditPhase.FAILED, AuditOutcome.FAILED, "SETTINGS_CHANGE_FAILED_RECONCILE") }
            catch (_: Exception) { /* original operation may already have partly committed */ }
            throw SettingsMutationUncertain()
        }
        val recorded = try {
            record(AuditPhase.SUCCEEDED, AuditOutcome.SUCCEEDED, "SETTINGS_CHANGED", revision()); true
        } catch (_: Exception) { false }
        return SettingsOperationReceipt(value, recorded)
    }
}
