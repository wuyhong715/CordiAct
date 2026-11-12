package com.cordiact.app.security

import android.content.Context
import com.cordiact.core.security.AccessEnvelope
import com.cordiact.core.security.EffectiveSecurityProfile
import com.cordiact.core.security.PermissionPreset
import com.cordiact.core.security.ReviewerConfig
import com.cordiact.core.security.ReviewerFallbackPolicy

/**
 * Local, non-secret profile store. The profile is user-controlled; tools cannot write it.
 *
 * A missing or invalid AUTO profile falls back to WORKSPACE_ONLY rather than silently granting
 * auto-approval without a configured reviewer.
 */
class SecurityProfileStore(context: Context) {
    private val appContext = context.applicationContext
    private val prefs = context.applicationContext.getSharedPreferences("security_profiles", Context.MODE_PRIVATE)

    init { migrateReviewerSnapshots() }

    /** One-way v2 migration, authorized by the owner: remove snapshots, require reselection. */
    private fun migrateReviewerSnapshots() = synchronized(MIGRATION_LOCK) {
        if (prefs.getInt("reviewer_storage_version", 0) >= 2) return@synchronized
        val all = prefs.all
        val editor = prefs.edit()
        val suffixes = listOf("reviewer_id", "reviewer_revision", "reviewer_provider", "reviewer_model", "reviewer_endpoint", "reviewer_fallback")
        all.keys.filter { key -> suffixes.any { key.endsWith(".$it") } }.forEach(editor::remove)
        val affected = all.keys.filter { name -> suffixes.any { name.endsWith(".$it") } }
            .map { it.substringBeforeLast('.') }.toSet() +
            all.filter { (name, value) -> name.endsWith(".preset") && value == PermissionPreset.AUTO_APPROVAL.name }
                .keys.map { it.removeSuffix(".preset") }
        affected.forEach { workspace ->
            require(workspace.isNotBlank()) { "REVIEWER_MIGRATION_INVALID_WORKSPACE" }
            val revision = prefs.getLong(key(workspace, "profile_revision"), 1)
            val epoch = prefs.getLong(key(workspace, "policy_epoch"), 1)
            check(revision < Long.MAX_VALUE && epoch < Long.MAX_VALUE) { "REVIEWER_MIGRATION_REVISION_EXHAUSTED" }
            val migratedRevision = if (all[key(workspace, "preset")] == PermissionPreset.AUTO_APPROVAL.name) revision + 1 else revision
            editor.putString(key(workspace, "reviewer_migration_audit"), "$migratedRevision:${java.util.UUID.randomUUID()}")
        }
        all.filter { (key, value) -> key.endsWith(".preset") && value == PermissionPreset.AUTO_APPROVAL.name }.forEach { (key, _) ->
            val workspace = key.removeSuffix(".preset")
            editor.putString(key, PermissionPreset.WORKSPACE_ONLY.name)
            editor.putBoolean(key(workspace, "reviewer_reselection_required"), true)
            editor.putLong(key(workspace, "profile_revision"), prefs.getLong(key(workspace, "profile_revision"), 1) + 1)
            editor.putLong(key(workspace, "policy_epoch"), prefs.getLong(key(workspace, "policy_epoch"), 1) + 1)
        }
        check(editor.putInt("reviewer_storage_version", 2).commit()) { "REVIEWER_MIGRATION_FAILED" }
    }

    fun pendingMigrationAuditCount(): Int = synchronized(MIGRATION_LOCK) {
        prefs.all.keys.count { it.endsWith(".reviewer_migration_audit") }
    }

    fun flushMigrationAudit(audit: com.cordiact.core.security.AuditLog): Int = synchronized(MIGRATION_LOCK) {
        com.cordiact.core.settings.ReviewerMigrationAudit(object : com.cordiact.core.settings.ReviewerMigrationOutbox {
            override fun pending() = prefs.all.filterKeys { it.endsWith(".reviewer_migration_audit") }
                .map { (name, raw) ->
                    val parts = (raw as String).split(':', limit = 2)
                    check(parts.size == 2) { "REVIEWER_AUDIT_OUTBOX_INVALID" }
                    val notice = com.cordiact.core.settings.ReviewerMigrationNotice(
                        name.removeSuffix(".reviewer_migration_audit"), parts[0].toLong(),
                        java.util.UUID.fromString(parts[1]).toString())
                    notice
                }
            override fun acknowledge(notice: com.cordiact.core.settings.ReviewerMigrationNotice) {
                check(prefs.edit().remove(key(notice.workspaceId, "reviewer_migration_audit")).commit()) {
                    "REVIEWER_AUDIT_ACK_FAILED"
                }
            }
        }, audit).flush()
    }

    fun reviewerNeedsReselection(workspaceId: String): Boolean =
        prefs.getBoolean(key(workspaceId, "reviewer_reselection_required"), false)

    @Synchronized
    fun profileFor(workspaceId: String): EffectiveSecurityProfile {
        require(workspaceId.isNotBlank()) { "workspaceId must not be blank" }
        val preset = prefs.getString(key(workspaceId, "preset"), null)
            ?.let { runCatching { PermissionPreset.valueOf(it) }.getOrNull() }
            ?: PermissionPreset.WORKSPACE_ONLY
        val revision = prefs.getLong(key(workspaceId, "profile_revision"), 1L)
        val epoch = prefs.getLong(key(workspaceId, "policy_epoch"), 1L)
        return when (preset) {
            PermissionPreset.READ_ONLY -> EffectiveSecurityProfile.readOnly(workspaceId, revision, epoch)
            PermissionPreset.WORKSPACE_ONLY -> EffectiveSecurityProfile.workspaceOnly(workspaceId, revision, epoch)
            PermissionPreset.FULL_ACCESS -> EffectiveSecurityProfile.fullAccess(workspaceId, revision, epoch)
            PermissionPreset.AUTO_APPROVAL -> {
                val envelope = prefs.getString(key(workspaceId, "base_envelope"), null)
                    ?.let { runCatching { AccessEnvelope.valueOf(it) }.getOrNull() }
                    ?: AccessEnvelope.WORKSPACE_ONLY
                val reviewer = reviewerFor(workspaceId)
                if (reviewer == null) EffectiveSecurityProfile.workspaceOnly(workspaceId, revision, epoch)
                else EffectiveSecurityProfile.autoApproval(workspaceId, envelope, reviewer, revision, epoch)
            }
        }
    }

    /** Trusted settings entry point. Bumps profile revision and policy epoch atomically. */
    @Synchronized
    fun setPreset(
        workspaceId: String,
        preset: PermissionPreset,
        baseEnvelope: AccessEnvelope = AccessEnvelope.WORKSPACE_ONLY,
        reviewer: ReviewerConfig? = null,
        expectedRevision: Long? = null,
    ): Long = synchronized(MIGRATION_LOCK) {
        require(workspaceId.isNotBlank()) { "workspaceId must not be blank" }
        if (preset == PermissionPreset.AUTO_APPROVAL) {
            require(reviewer != null) { "AUTO_APPROVAL requires a reviewer" }
            val resolved = resolveReviewer(reviewer.providerId, reviewer.modelId)
            require(resolved != null) { "REVIEWER_MODEL_UNAVAILABLE" }
            require(resolved.endpointIdentity == reviewer.endpointIdentity) { "REVIEWER_ENDPOINT_CHANGED" }
        }
        val current = profileFor(workspaceId)
        check(expectedRevision == null || current.profileRevision == expectedRevision) { "SETTINGS_REVISION_CONFLICT" }
        require(current.profileRevision < Long.MAX_VALUE && current.policyEpoch < Long.MAX_VALUE)
        val revision = prefs.getLong(key(workspaceId, "profile_revision"), 1L) + 1
        val epoch = prefs.getLong(key(workspaceId, "policy_epoch"), 1L) + 1
        prefs.edit()
            .putString(key(workspaceId, "preset"), preset.name)
            .putString(key(workspaceId, "base_envelope"), baseEnvelope.name)
            .putLong(key(workspaceId, "profile_revision"), revision)
            .putLong(key(workspaceId, "policy_epoch"), epoch)
            .putBoolean(key(workspaceId, "reviewer_reselection_required"), false)
            .apply {
                if (reviewer == null) {
                    remove(key(workspaceId, "reviewer_id"))
                    remove(key(workspaceId, "reviewer_revision"))
                    remove(key(workspaceId, "reviewer_provider"))
                    remove(key(workspaceId, "reviewer_model"))
                    remove(key(workspaceId, "reviewer_endpoint"))
                    remove(key(workspaceId, "reviewer_fallback"))
                } else {
                    putString(key(workspaceId, "reviewer_id"), reviewer.reviewerId)
                    putLong(key(workspaceId, "reviewer_revision"), reviewer.revision)
                    putString(key(workspaceId, "reviewer_provider"), reviewer.providerId)
                    putString(key(workspaceId, "reviewer_model"), reviewer.modelId)
                    remove(key(workspaceId, "reviewer_endpoint"))
                    putString(key(workspaceId, "reviewer_fallback"), reviewer.fallbackPolicy.name)
                }
            }
            .also { check(it.commit()) { "SECURITY_PROFILE_PERSIST_FAILED" } }
        revision
    }

    private fun reviewerFor(workspaceId: String): ReviewerConfig? {
        val provider = prefs.getString(key(workspaceId, "reviewer_provider"), null) ?: return null
        val model = prefs.getString(key(workspaceId, "reviewer_model"), null) ?: return null
        return resolveReviewer(provider, model)
    }

    fun configuredPreset(workspaceId: String): PermissionPreset =
        prefs.getString(key(workspaceId, "preset"), null)?.let { runCatching { PermissionPreset.valueOf(it) }.getOrNull() }
            ?: PermissionPreset.WORKSPACE_ONLY

    fun reviewerReference(workspaceId: String): com.cordiact.core.conversation.ModelId? {
        val provider = prefs.getString(key(workspaceId, "reviewer_provider"), null) ?: return null
        val model = prefs.getString(key(workspaceId, "reviewer_model"), null) ?: return null
        return com.cordiact.core.conversation.ModelId(provider, model)
    }

    fun resolveReviewer(provider: String, model: String): ReviewerConfig? {
        val selected = com.cordiact.app.model.ConversationModelSelectionStore(appContext).defaultModel()
        if (selected?.providerId == provider && selected.modelId == model) return null
        val store = com.cordiact.app.model.AndroidModelProviderConfigStore(appContext)
        val before = store.revision()
        val config = store.load().firstOrNull { it.providerId == provider && model in it.models } ?: return null
        // A concurrent write must not combine an old endpoint with a new revision.
        if (store.revision() != before) return null
        return ReviewerConfig(reviewerId = "$provider/$model", revision = before,
            providerId = provider, modelId = model, endpointIdentity = config.endpoint,
            fallbackPolicy = ReviewerFallbackPolicy.STOP,
            credentialRevision = AndroidSystemCredentials.get(appContext).revision("model", provider))
    }

    companion object { private val MIGRATION_LOCK = Any() }

    private fun key(workspaceId: String, field: String): String = "$workspaceId.$field"
}
