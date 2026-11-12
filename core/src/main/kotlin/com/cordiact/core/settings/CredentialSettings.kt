package com.cordiact.core.settings

import com.cordiact.core.security.*
import java.util.UUID

data class ManagedCredentialMeta(
    val namespace: String, val owner: String, val revision: Long,
    val updatedAtMillis: Long? = null, val lastUsedAtMillis: Long? = null,
)
data class CredentialClearReceipt(val auditRecorded: Boolean)

/** Host settings boundary. No read/decrypt operation exists on this interface. */
interface ManagedCredentials {
    fun list(): List<ManagedCredentialMeta>
    /** Must atomically compare revision and delete; metadata timestamps must not bump revision. */
    fun clear(expected: ManagedCredentialMeta)
}
class CredentialSettings(private val credentials: ManagedCredentials, private val audit: AuditLog) {
    fun list() = credentials.list()
    fun clear(expected: ManagedCredentialMeta): CredentialClearReceipt {
        require(expected.namespace in setOf("model", "search", "ssh", "mcp")) { "UNMANAGED_CREDENTIAL_NAMESPACE" }
        val correlation = UUID.randomUUID().toString()
        fun record(phase: AuditPhase, outcome: AuditOutcome, reason: String) = audit.append(AuditEventDraft(
            category = AuditCategory.CREDENTIAL, phase = phase, outcome = outcome,
            reasonCode = reason, correlationId = correlation, workspaceId = GLOBAL_SETTINGS_AUDIT_SCOPE,
            toolId = "settings.credential.clear",
        ))
        record(AuditPhase.REQUESTED, AuditOutcome.PENDING, "CREDENTIAL_CLEAR_REQUESTED")
        try { credentials.clear(expected) }
        catch (failure: Exception) {
            try { record(AuditPhase.FAILED, AuditOutcome.FAILED, "CREDENTIAL_CLEAR_FAILED") }
            catch (auditFailure: Exception) { failure.addSuppressed(auditFailure) }
            throw failure
        }
        return CredentialClearReceipt(try {
            record(AuditPhase.SUCCEEDED, AuditOutcome.SUCCEEDED, "CREDENTIAL_CLEAR_SUCCEEDED"); true
        } catch (_: Exception) { false })
    }
}
