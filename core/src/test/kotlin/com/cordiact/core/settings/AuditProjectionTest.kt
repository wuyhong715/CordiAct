package com.cordiact.core.settings

import com.cordiact.core.security.*
import java.time.Instant
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class AuditProjectionTest {
    private fun event(id: String, time: Long, outcome: AuditOutcome) = AuditEvent(id, time,
        Instant.ofEpochSecond(time), "boot", AuditEventDraft(AuditCategory.SECURITY, AuditPhase.SUCCEEDED,
            outcome, "TEST", "correlation", workspaceId = "workspace", credentialOpaqueId = "credential-secret",
            targetDescriptorSanitized = "untrusted-extra-data"), null, "mac")
    @Test fun historicalQueryFiltersBeforeNewestLimit() {
        val log = InMemoryAuditLog(AuditSigner { _, _ -> "test-mac" })
        repeat(4) { index -> log.append(AuditEventDraft(AuditCategory.SECURITY, AuditPhase.SUCCEEDED,
            if (index == 1) AuditOutcome.DENIED else AuditOutcome.SUCCEEDED, "EVENT_$index", "c", workspaceId = "workspace")) }
        assertEquals(listOf("EVENT_3", "EVENT_2"), log.query(AuditFilter(limit = 2, newestFirst = true)).map { it.draft.reasonCode })
        assertEquals("EVENT_1", log.query(AuditFilter(outcome = AuditOutcome.DENIED, limit = 1, newestFirst = true)).single().draft.reasonCode)
    }
    @Test fun filtersByOutcomeAndInclusiveTimeBoundary() {
        val a = event("a", 10, AuditOutcome.SUCCEEDED)
        val b = event("b", 20, AuditOutcome.DENIED)
        assertEquals(listOf(a), filterAuditWindow(listOf(a, b), AuditOutcome.SUCCEEDED, Instant.ofEpochSecond(10)))
        assertTrue(filterAuditWindow(listOf(a), null, Instant.ofEpochSecond(11)).isEmpty())
    }
    @Test fun detailProjectionExcludesSecretsAndUntrustedDescriptors() {
        val fields = auditDetailFields(event("a", 10, AuditOutcome.SUCCEEDED))
        assertFalse(fields.toString().contains("credential-secret"))
        assertFalse(fields.toString().contains("untrusted-extra-data"))
        assertEquals("workspace", fields["workspaceId"])
        assertEquals(10L, fields["sequence"])
    }

    @Test fun settingsProjectionOnlyExposesAllowlistedTargetsAndNumericRevision() {
        val source = event("a", 10, AuditOutcome.SUCCEEDED)
        val fields = auditDetailFields(source.copy(draft = source.draft.copy(
            reasonCode = "SETTINGS_CHANGED", toolId = "security.permission", policyVersion = "7")))
        assertEquals("security.permission", fields["setting"])
        assertEquals(7L, fields["revision"])
        assertEquals("owner", fields["actor"])
        assertFalse(fields.toString().contains("credential-secret"))
        val untrusted = auditDetailFields(source.copy(draft = source.draft.copy(
            reasonCode = "SETTINGS_CHANGED", toolId = "untrusted-target", policyVersion = "secret")))
        assertFalse(untrusted.containsKey("setting"))
    }
}
