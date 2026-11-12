package com.cordiact.app.experience.settings

import com.cordiact.app.CordiActApplication
import com.cordiact.app.privilege.PrivilegeSettingsStore
import com.cordiact.app.security.SecurityProfileStore
import com.cordiact.core.conversation.ModelId
import com.cordiact.core.security.*
import com.cordiact.core.settings.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class SecurityWriteReceipt(val revision: Long, val auditRecorded: Boolean)

data class SecurityWorkspace(val id: String, val name: String)
data class SecuritySettingsSnapshot(
    val workspaces: List<SecurityWorkspace>, val workspaceId: String,
    val configuredPreset: PermissionPreset, val effectivePreset: PermissionPreset,
    val reviewer: ModelId?, val reviewerInvalid: Boolean, val revision: Long,
    val root: Boolean, val advanced: Boolean, val privilegeRevision: Long,
    val pendingMigrationAudit: Int = 0,
)

/** Trusted UI management boundary: references and booleans only, never credential material. */
class SecuritySettingsFacade(private val app: CordiActApplication) {
    private val profiles = SecurityProfileStore(app)
    private val privileges = PrivilegeSettingsStore(app)
    private fun writer(workspaceId: String, report: (Boolean) -> Unit) = SettingsWriter(audit = { change ->
        try {
            app.credentialVaultRuntime.database.append(AuditEventDraft(
                category = AuditCategory.SECURITY, phase = AuditPhase.SUCCEEDED,
                outcome = AuditOutcome.SUCCEEDED, reasonCode = "SETTINGS_CHANGED",
                correlationId = java.util.UUID.randomUUID().toString(), workspaceId = workspaceId,
                toolId = change.target, policyVersion = change.revision.toString(),
            ))
            report(true)
        } catch (e: Exception) { report(false) }
    })

    suspend fun snapshot(workspaceId: String? = null): SecuritySettingsSnapshot = withContext(Dispatchers.IO) {
        runCatching { profiles.flushMigrationAudit(app.credentialVaultRuntime.database) }
        val workspaces = app.workspaceManager.list().map { SecurityWorkspace(it.id, it.name) }
        val id = workspaceId?.takeIf { target -> workspaces.any { it.id == target } }
            ?: app.workspaceManager.activeWorkspace().id
        val profile = profiles.profileFor(id)
        val privilege = privileges.read()
        SecuritySettingsSnapshot(workspaces, id, profiles.configuredPreset(id), profile.preset,
            profiles.reviewerReference(id), profiles.reviewerNeedsReselection(id) ||
                (profiles.configuredPreset(id) == PermissionPreset.AUTO_APPROVAL && profile.reviewer == null),
            profile.profileRevision, privilege.root, privilege.advanced, privilege.revision, profiles.pendingMigrationAuditCount())
    }

    suspend fun setPermission(workspaceId: String, expected: Long, preset: PermissionPreset, reviewer: ModelId?) = withContext(Dispatchers.IO) {
        synchronized(WRITE_LOCK) {
            require(app.workspaceManager.get(workspaceId) != null) { "WORKSPACE_UNAVAILABLE" }
            val resolved = if (preset == PermissionPreset.AUTO_APPROVAL) {
                requireNotNull(reviewer) { "REVIEWER_REQUIRED" }
                requireNotNull(profiles.resolveReviewer(reviewer.providerId, reviewer.modelId)) { "REVIEWER_UNAVAILABLE" }
            } else null
            var audited = false
            val revision = writer(workspaceId) { audited = it }.write("security.permission", expected, object : SettingsMutation {
                override fun validate() { check(profiles.profileFor(workspaceId).profileRevision == expected) { "SETTINGS_REVISION_CONFLICT" } }
                override fun commit(expectedRevision: Long) = profiles.setPreset(workspaceId, preset, reviewer = resolved, expectedRevision = expectedRevision)
            })
            SecurityWriteReceipt(revision, audited)
        }
    }
    suspend fun setPrivilege(expected: Long, root: Boolean, advanced: Boolean) = withContext(Dispatchers.IO) {
        synchronized(WRITE_LOCK) {
            var audited = false
            val revision = writer(com.cordiact.core.settings.GLOBAL_SETTINGS_AUDIT_SCOPE) { audited = it }.write("security.privilege", expected, object : SettingsMutation {
                override fun validate() { check(privileges.read().revision == expected) { "SETTINGS_REVISION_CONFLICT" } }
                override fun commit(expectedRevision: Long) = privileges.commit(expectedRevision, root, advanced)
            })
            SecurityWriteReceipt(revision, audited)
        }
    }
    companion object { private val WRITE_LOCK = Any() }
}
