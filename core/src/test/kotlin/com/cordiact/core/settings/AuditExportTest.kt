package com.cordiact.core.settings

import com.cordiact.core.security.*
import java.io.ByteArrayOutputStream
import java.io.IOException
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class AuditExportTest {
    private fun log() = InMemoryAuditLog(AuditSigner { _, _ -> "test" })
    private fun event(log: AuditLog) = log.append(AuditEventDraft(
        AuditCategory.CREDENTIAL, AuditPhase.SUCCEEDED, AuditOutcome.SUCCEEDED, "TEST", "c",
        workspaceId = "workspace", credentialOpaqueId = "secret-credential-id",
        targetDescriptorSanitized = "unsafe-descriptor", policyVersion = "private-policy"))

    @Test fun exportContainsOnlyProjectionAndAuditsAttemptAndCompletion() {
        val log = log()
        val source = event(log)
        val output = ByteArrayOutputStream()
        val receipt = AuditExport(log).write(listOf(source)) { output }
        val text = output.toString("UTF-8")
        assertEquals(AuditExportReceipt(1, true), receipt)
        assertTrue(text.contains("displayed-window"))
        assertTrue(text.contains(source.eventId))
        listOf("secret-credential-id", "unsafe-descriptor", "private-policy", "eventMac", "previousMac").forEach {
            assertFalse(text.contains(it), it)
        }
        val exported = log.query(AuditFilter(workspaceId = GLOBAL_SETTINGS_AUDIT_SCOPE))
        assertEquals(listOf("AUDIT_EXPORT_REQUESTED", "AUDIT_EXPORT_SUCCEEDED"), exported.map { it.draft.reasonCode })
    }
    @Test fun failedAttemptAuditPreventsOpeningDestination() {
        val audit = object : AuditLog by log() {
            override fun append(draft: AuditEventDraft): AuditEvent = throw IOException("audit offline")
        }
        var opened = false
        assertThrows(IOException::class.java) { AuditExport(audit).write(emptyList()) { opened = true; ByteArrayOutputStream() } }
        assertFalse(opened)
    }
    @Test fun destinationFailureIsAuditedWithoutLeakingExceptionText() {
        val audit = log()
        assertThrows(IOException::class.java) { AuditExport(audit).write(emptyList()) { throw IOException("private-path") } }
        assertEquals(listOf("AUDIT_EXPORT_REQUESTED", "AUDIT_EXPORT_FAILED"), audit.query().map { it.draft.reasonCode })
        assertFalse(audit.query().toString().contains("private-path"))
    }
    @Test fun completedFileWithFailedCompletionAuditIsReportedSeparately() {
        val store = log()
        val audit = object : AuditLog by store {
            override fun append(draft: AuditEventDraft): AuditEvent {
                if (draft.phase == AuditPhase.SUCCEEDED) throw IOException("offline")
                return store.append(draft)
            }
        }
        assertFalse(AuditExport(audit).write(emptyList()) { ByteArrayOutputStream() }.completionRecorded)
    }
    @Test fun oversizedWindowIsRejectedBeforeDestinationOrAudit() {
        val audit = log()
        val one = event(audit)
        var opened = false
        assertThrows(IllegalArgumentException::class.java) {
            AuditExport(audit).write(List(201) { one }) { opened = true; ByteArrayOutputStream() }
        }
        assertFalse(opened)
        assertEquals(1, audit.query().size)
    }
}
