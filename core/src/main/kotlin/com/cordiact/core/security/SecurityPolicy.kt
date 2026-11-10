package com.cordiact.core.security

/** A policy decision is deliberately typed: a reviewer's allow can never silently become a host policy. */
sealed interface PolicyDecision {
    val reasonCode: String

    data class Allow(override val reasonCode: String = "ALLOWED") : PolicyDecision

    data class RequireHuman(override val reasonCode: String) : PolicyDecision

    data class RequireReviewer(override val reasonCode: String) : PolicyDecision

    data class Deny(override val reasonCode: String) : PolicyDecision
}

/**
 * Deterministic policy for credential operations.
 *
 * The engine enforces the hard boundaries first: workspace ownership, global-resource binding,
 * read-only effects and target normalization. Only after those pass does the configured approval
 * authority decide who answers. A reviewer can never expand an envelope or override a hard deny.
 */
class SecurityPolicyEngine {
    fun evaluate(context: SecurityContext, intent: SensitiveIntent): PolicyDecision {
        // Identity and freshness are structural: a request assembled from another context is not
        // eligible for approval at all.
        if (intent.workspaceId != context.workspaceId) return deny("WORKSPACE_MISMATCH")
        if (intent.sessionId != context.sessionId) return deny("SESSION_MISMATCH")
        if (intent.runId != context.runId) return deny("RUN_MISMATCH")
        if (intent.turnId != context.turnId) return deny("TURN_MISMATCH")
        if (intent.toolCallId != context.toolCallId) return deny("TOOL_CALL_MISMATCH")
        if (intent.principalChain != context.principalChain) return deny("PRINCIPAL_MISMATCH")
        if (intent.policyEpoch != context.profile.policyEpoch) return deny("STALE_POLICY_EPOCH")

        // Workspace isolation is a hard boundary for every preset. FULL_ACCESS adds device
        // storage, not another Workspace.
        when (val scope = intent.scope) {
            is CredentialScopeRef.Workspace -> {
                if (scope.workspaceId != context.workspaceId) return deny("CROSS_WORKSPACE_DENIED")
            }
            CredentialScopeRef.UserGlobal -> {
                val id = intent.credentialId
                if (id == null || id !in context.allowedGlobalCredentialIds) {
                    return deny("GLOBAL_CREDENTIAL_NOT_BOUND")
                }
            }
        }

        when (intent.operation) {
            CredentialOperation.TOOL_EXECUTION -> Unit
            CredentialOperation.LIST -> {
                // Listing may be narrowed by a query, but it always operates on one scope.
            }
            CredentialOperation.READ_METADATA, CredentialOperation.READ_PLAINTEXT -> {
                if (intent.credentialId.isNullOrBlank()) return deny("CREDENTIAL_ID_REQUIRED")
            }
            CredentialOperation.WRITE -> {
                if (intent.credentialId != null && intent.expectedRevision == null) {
                    return deny("EXPECTED_REVISION_REQUIRED")
                }
                if (intent.credentialId == null && intent.expectedRevision != null) {
                    return deny("CREATE_MUST_NOT_BIND_REVISION")
                }
                if (context.profile.accessEnvelope == AccessEnvelope.READ_ONLY) {
                    return deny("READ_ONLY_WRITE_DENIED")
                }
            }
            CredentialOperation.DELETE -> {
                if (intent.credentialId.isNullOrBlank()) return deny("CREDENTIAL_ID_REQUIRED")
                if (intent.expectedRevision == null) return deny("EXPECTED_REVISION_REQUIRED")
                if (context.profile.accessEnvelope == AccessEnvelope.READ_ONLY) {
                    return deny("READ_ONLY_DELETE_DENIED")
                }
            }
            CredentialOperation.USE -> {
                if (intent.credentialId.isNullOrBlank()) return deny("CREDENTIAL_ID_REQUIRED")
                val target = intent.canonicalTarget ?: return deny("TARGET_REQUIRED")
                if (target.scheme != "https") return deny("HTTPS_REQUIRED")
                // A read-only preset cannot prove that an arbitrary HTTP request has no side
                // effect. P0 refuses it rather than calling GET safe by name.
                if (context.profile.accessEnvelope == AccessEnvelope.READ_ONLY) {
                    return deny("READ_ONLY_NETWORK_DENIED")
                }
            }
        }

        // Credential operations are always approval-bearing. There is no preset that turns them
        // into ordinary low-risk tool calls.
        return when (context.profile.authority) {
            ApprovalAuthority.HUMAN -> PolicyDecision.RequireHuman("HUMAN_APPROVAL_REQUIRED")
            ApprovalAuthority.EXTERNAL_REVIEW_MODEL -> PolicyDecision.RequireReviewer("REVIEW_MODEL_REQUIRED")
            ApprovalAuthority.DETERMINISTIC_POLICY -> deny("SENSITIVE_OPERATION_REQUIRES_APPROVAL")
        }
    }

    private fun deny(code: String): PolicyDecision.Deny = PolicyDecision.Deny(code)
}
