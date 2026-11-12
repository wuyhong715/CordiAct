package com.cordiact.core.settings

import com.cordiact.core.security.*
import java.io.IOException
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class SettingsOperationAuditTest {
    private fun log() = InMemoryAuditLog(AuditSigner { _, _ -> "signed" })
    @Test fun connectionAttachmentAndCapabilityWritesUseAuditPipeline() {
        for (target in listOf("ssh.host.save", "ssh.host.pin", "ssh.host.delete", "attachments.policy", "capability:workspace-1:ssh", "capability:workspace-1:mcp.github", "mcp.install", "search.policy")) {
            assertTrue(SettingsOperationAudit(log()).run(target, { 1 }) { Unit }.completionRecorded)
        }
    }
    @Test fun successfulMutationRecordsRevisionAndFixedTarget() {
        val audit = log(); var revision = 2L
        val receipt = SettingsOperationAudit(audit).run("model.provider.update", { revision }) { revision++; "result" }
        assertEquals(SettingsOperationReceipt("result", true), receipt)
        assertEquals(listOf("SETTINGS_CHANGE_REQUESTED", "SETTINGS_CHANGED"), audit.query().map { it.draft.reasonCode })
        val detail = auditDetailFields(audit.query().last())
        assertEquals("model.provider.update", detail["setting"])
        assertEquals(3L, detail["revision"])
        assertEquals("owner", detail["actor"])
    }
    @Test fun attemptFailureDoesNotMutateAndDoesNotLeakError() {
        val audit = object : AuditLog by log() { override fun append(draft: AuditEventDraft): AuditEvent = throw IOException("secret-path") }
        var changed = false
        val failure = assertThrows(SettingsAttemptAuditFailed::class.java) {
            SettingsOperationAudit(audit).run("model.provider.key", { 1 }) { changed = true }
        }
        assertFalse(changed)
        assertFalse(failure.toString().contains("secret-path"))
    }
    @Test fun failureAfterPartialWriteRequiresReconciliationNotBlindRetry() {
        val audit = log(); var changed = false
        val failure = assertThrows(SettingsMutationUncertain::class.java) {
            SettingsOperationAudit(audit).run("model.provider.add", { 1 }) { changed = true; throw IOException("secret-key") }
        }
        assertTrue(changed)
        assertFalse(failure.toString().contains("secret-key"))
        assertEquals("SETTINGS_CHANGE_FAILED_RECONCILE", audit.query().last().draft.reasonCode)
    }
    @Test fun completedMutationWithFailedAuditReturnsWarningReceipt() {
        val store = log()
        val audit = object : AuditLog by store {
            override fun append(draft: AuditEventDraft): AuditEvent {
                if (draft.phase == AuditPhase.SUCCEEDED) throw IOException("offline")
                return store.append(draft)
            }
        }
        assertEquals(SettingsOperationReceipt(42, false), SettingsOperationAudit(audit).run("model.role.summary", { 3 }) { 42 })
    }
    @Test fun revisionReadFailureAfterMutationDoesNotPretendMutationFailed() {
        assertFalse(SettingsOperationAudit(log()).run("model.role.extraction", { throw IOException("offline") }) { Unit }.completionRecorded)
    }
    @Test fun arbitraryTargetsRejectedBeforeAuditOrMutation() {
        val audit = log(); var changed = false
        assertThrows(IllegalArgumentException::class.java) {
            SettingsOperationAudit(audit).run("secret-key", { 1 }) { changed = true }
        }
        assertFalse(changed)
        assertTrue(audit.query().isEmpty())
    }

    @Test fun cancellationIsNotWrappedOrMistakenForSuccess() {
        val audit = log()
        assertThrows(java.util.concurrent.CancellationException::class.java) {
            SettingsOperationAudit(audit).run("model.provider.refresh", { 1 }) {
                throw java.util.concurrent.CancellationException("cancel")
            }
        }
        assertEquals("SETTINGS_CHANGE_CANCELLED_RECONCILE", audit.query().last().draft.reasonCode)
    }
}
