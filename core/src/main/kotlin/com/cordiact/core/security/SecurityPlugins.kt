package com.cordiact.core.security

import com.cordiact.core.plugin.ManagedPlugin
import com.cordiact.core.plugin.PluginSpec
import com.cordiact.core.plugin.PluginTarget
import com.cordiact.core.tool.TOOL_MANAGER_PLUGIN_ID
import com.cordiact.core.vault.CredentialVaultService

const val CREDENTIAL_VAULT_PLUGIN_ID = "core.credential-vault"
const val CREDENTIAL_VAULT_VERSION = "0.1.0"
const val SECURITY_AUDIT_PLUGIN_ID = "core.security-audit"
const val SECURITY_AUDIT_VERSION = "0.1.0"

fun credentialVaultPluginSpec() = PluginSpec(
    id = CREDENTIAL_VAULT_PLUGIN_ID,
    version = CREDENTIAL_VAULT_VERSION,
    target = PluginTarget.ANDROID_NATIVE,
    dependencies = setOf(TOOL_MANAGER_PLUGIN_ID),
)

fun securityAuditPluginSpec() = PluginSpec(
    id = SECURITY_AUDIT_PLUGIN_ID,
    version = SECURITY_AUDIT_VERSION,
    target = PluginTarget.ANDROID_NATIVE,
    dependencies = setOf(TOOL_MANAGER_PLUGIN_ID),
)

class CredentialVaultPlugin(
    val service: CredentialVaultService,
) : ManagedPlugin {
    override val spec: PluginSpec = credentialVaultPluginSpec()
}

/**
 * The audit plugin owns query/presentation. It never owns the mandatory sink and cannot approve
 * or deny an operation. Disabling this plugin must not disable the host gate.
 */
class SecurityAuditPlugin(
    private val audit: AuditLog,
) : ManagedPlugin {
    override val spec: PluginSpec = securityAuditPluginSpec()

    fun queryCurrentRun(workspaceId: String, runId: String, limit: Int = 100): List<AuditEvent> {
        require(workspaceId.isNotBlank()) { "workspaceId must not be blank" }
        require(runId.isNotBlank()) { "runId must not be blank" }
        return audit.query(AuditFilter(workspaceId = workspaceId, limit = limit))
            .filter { it.draft.runId == runId }
    }

    fun explainDecision(workspaceId: String, correlationId: String): List<AuditEvent> {
        require(workspaceId.isNotBlank()) { "workspaceId must not be blank" }
        require(correlationId.isNotBlank()) { "correlationId must not be blank" }
        return audit.query(AuditFilter(workspaceId = workspaceId, correlationId = correlationId))
    }
}
