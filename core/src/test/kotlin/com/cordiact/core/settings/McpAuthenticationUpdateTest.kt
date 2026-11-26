package com.cordiact.core.settings

import com.cordiact.core.mcp.McpCredential
import com.cordiact.core.security.AuditEventDraft
import com.cordiact.core.security.AuditLog
import com.cordiact.core.security.AuditSigner
import com.cordiact.core.security.InMemoryAuditLog
import java.util.concurrent.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class McpAuthenticationUpdateTest {
    private val old = McpCredential("Authorization", "old-synthetic-secret")
    private val next = McpCredential("X-API-Key", "new-synthetic-secret")
    private fun log() = InMemoryAuditLog(AuditSigner { _, _ -> "signed" })

    @Test fun successfulChangeUsesFixedAuditTargetAndDoesNotEnableDisabledPlugin() {
        val audit = log()
        var stored: McpCredential? = old
        val receipt = SettingsOperationAudit(audit).run(MCP_AUTHENTICATION_AUDIT_TARGET, { 7L }) {
            applyMcpAuthenticationUpdate(old, next, false, { stored = it }, { error("Disabled plugin must stay disabled") })
        }
        assertEquals(next, stored)
        assertTrue(receipt.completionRecorded)
        val events = audit.query()
        assertEquals(listOf("SETTINGS_CHANGE_REQUESTED", "SETTINGS_CHANGED"), events.map { it.draft.reasonCode })
        assertTrue(events.all { it.draft.toolId == MCP_AUTHENTICATION_AUDIT_TARGET })
        assertEquals(7L, auditDetailFields(events.last())["revision"])
        assertTrue(listOf(old.value, next.value, next.headerName).none(events.toString()::contains))
    }

    @Test fun failedAuditOrFailedDisableNeverWritesVault() {
        val audit = object : AuditLog by log() {
            override fun append(draft: AuditEventDraft) = error("Synthetic audit unavailable")
        }
        assertFailsWith<SettingsAttemptAuditFailed> {
            SettingsOperationAudit(audit).run(MCP_AUTHENTICATION_AUDIT_TARGET, { 1L }) { error("Must not run") }
        }
        var writes = 0
        assertFailsWith<SettingsMutationUncertain> {
            SettingsOperationAudit(log()).run(MCP_AUTHENTICATION_AUDIT_TARGET, { 1L }) {
                applyMcpAuthenticationUpdate(old, next, true, { writes++ }, { error("Cannot disable") })
            }
        }
        assertEquals(0, writes)
    }

    @Test fun failedStartRestoresCredentialBeforeTryingOldConnectionAndReportsUncertain() {
        val audit = log()
        var stored: McpCredential? = old
        val events = mutableListOf<String>()
        val failure = assertFailsWith<SettingsMutationUncertain> {
            SettingsOperationAudit(audit).run(MCP_AUTHENTICATION_AUDIT_TARGET, { 1L }) {
                applyMcpAuthenticationUpdate(old, next, true, { stored = it; events += "write" }, { enabled ->
                    when {
                        !enabled -> events += "disable"
                        stored == next -> { events += "start-new"; error(next.value) }
                        else -> { assertEquals(old, stored); events += "start-old" }
                    }
                })
            }
        }
        assertEquals(listOf("disable", "write", "start-new", "write", "start-old"), events)
        assertEquals(old, stored)
        assertEquals("SETTINGS_CHANGE_FAILED_RECONCILE", audit.query().last().draft.reasonCode)
        assertFalse(failure.toString().contains(next.value))
    }

    @Test fun uncertainVaultRestorationSuppressesRestartAndKeepsCancellation() {
        val cancel = CancellationException("Synthetic cancellation")
        val events = mutableListOf<String>()
        val audit = log()
        assertSame(cancel, assertFailsWith<CancellationException> {
            SettingsOperationAudit(audit).run(MCP_AUTHENTICATION_AUDIT_TARGET, { 1L }) {
                applyMcpAuthenticationUpdate(old, next, true, { value ->
                    if (value == next) { events += "write-new"; throw cancel }
                    events += "restore-old"
                    error("Vault unavailable")
                }, { enabled -> events += if (enabled) "start" else "disable" })
            }
        })
        assertEquals(listOf("disable", "write-new", "restore-old"), events)
        assertEquals("SETTINGS_CHANGE_CANCELLED_RECONCILE", audit.query().last().draft.reasonCode)
    }
}
