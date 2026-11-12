package com.cordiact.core.settings

import com.cordiact.core.security.*
import java.io.IOException
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class CredentialSettingsTest {
    private val meta = ManagedCredentialMeta("model", "provider", 2, 100L, null)
    private class Store(var value: ManagedCredentialMeta?) : ManagedCredentials {
        override fun list() = listOfNotNull(value)
        override fun clear(expected: ManagedCredentialMeta) {
            check(expected == value) { "CREDENTIAL_REVISION_CONFLICT" }
            value = null
        }
    }
    private fun log() = InMemoryAuditLog(AuditSigner { _, _ -> "test" })
    @Test fun listOnlyReturnsMetadataAndClearRecordsAttemptAndCompletion() {
        val store = Store(meta); val audit = log(); val service = CredentialSettings(store, audit)
        assertEquals(listOf(meta), service.list())
        assertTrue(service.clear(meta).auditRecorded)
        assertTrue(service.list().isEmpty())
        assertEquals(listOf("CREDENTIAL_CLEAR_REQUESTED", "CREDENTIAL_CLEAR_SUCCEEDED"), audit.query().map { it.draft.reasonCode })
    }
    @Test fun staleConfirmationDoesNotDeleteReplacement() {
        val changed = meta.copy(revision = 3)
        val store = Store(changed); val audit = log()
        assertThrows(IllegalStateException::class.java) { CredentialSettings(store, audit).clear(meta) }
        assertEquals(changed, store.value)
        assertEquals(AuditOutcome.FAILED, audit.query().last().draft.outcome)
    }
    @Test fun attemptAuditFailurePreventsClear() {
        val store = Store(meta)
        val audit = object : AuditLog by log() { override fun append(draft: AuditEventDraft): AuditEvent = throw IOException("offline") }
        assertThrows(IOException::class.java) { CredentialSettings(store, audit).clear(meta) }
        assertEquals(meta, store.value)
    }
    @Test fun completionAuditFailureDoesNotClaimDeletionFailed() {
        val store = Store(meta); val log = log()
        val audit = object : AuditLog by log {
            override fun append(draft: AuditEventDraft): AuditEvent {
                if (draft.phase == AuditPhase.SUCCEEDED) throw IOException("offline")
                return log.append(draft)
            }
        }
        assertFalse(CredentialSettings(store, audit).clear(meta).auditRecorded)
        assertNull(store.value)
    }
    @Test fun unknownNamespaceRefusedWithoutAuditOrDeletion() {
        val unknown = meta.copy(namespace = "unknown")
        val store = Store(unknown); val audit = log()
        assertThrows(IllegalArgumentException::class.java) { CredentialSettings(store, audit).clear(unknown) }
        assertEquals(unknown, store.value)
        assertTrue(audit.query().isEmpty())
    }
}
