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
    private val prefs = context.applicationContext.getSharedPreferences("security_profiles", Context.MODE_PRIVATE)

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
                checkNotNull(reviewer) { "REVIEWER_NOT_CONFIGURED" }
                EffectiveSecurityProfile.autoApproval(workspaceId, envelope, reviewer, revision, epoch)
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
    ) {
        require(workspaceId.isNotBlank()) { "workspaceId must not be blank" }
        if (preset == PermissionPreset.AUTO_APPROVAL) {
            require(reviewer != null) { "AUTO_APPROVAL requires a reviewer" }
        }
        val revision = prefs.getLong(key(workspaceId, "profile_revision"), 1L) + 1
        val epoch = prefs.getLong(key(workspaceId, "policy_epoch"), 1L) + 1
        prefs.edit()
            .putString(key(workspaceId, "preset"), preset.name)
            .putString(key(workspaceId, "base_envelope"), baseEnvelope.name)
            .putLong(key(workspaceId, "profile_revision"), revision)
            .putLong(key(workspaceId, "policy_epoch"), epoch)
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
                    putString(key(workspaceId, "reviewer_endpoint"), reviewer.endpointIdentity)
                    putString(key(workspaceId, "reviewer_fallback"), reviewer.fallbackPolicy.name)
                }
            }
            .also { check(it.commit()) { "SECURITY_PROFILE_PERSIST_FAILED" } }
    }

    private fun reviewerFor(workspaceId: String): ReviewerConfig? {
        val id = prefs.getString(key(workspaceId, "reviewer_id"), null) ?: return null
        val provider = prefs.getString(key(workspaceId, "reviewer_provider"), null) ?: return null
        val model = prefs.getString(key(workspaceId, "reviewer_model"), null) ?: return null
        val endpoint = prefs.getString(key(workspaceId, "reviewer_endpoint"), null) ?: return null
        return ReviewerConfig(
            reviewerId = id,
            revision = prefs.getLong(key(workspaceId, "reviewer_revision"), 1L),
            providerId = provider,
            modelId = model,
            endpointIdentity = endpoint,
            fallbackPolicy = prefs.getString(key(workspaceId, "reviewer_fallback"), null)
                ?.let { runCatching { ReviewerFallbackPolicy.valueOf(it) }.getOrNull() }
                ?: ReviewerFallbackPolicy.STOP,
        )
    }

    private fun key(workspaceId: String, field: String): String = "$workspaceId.$field"
}
