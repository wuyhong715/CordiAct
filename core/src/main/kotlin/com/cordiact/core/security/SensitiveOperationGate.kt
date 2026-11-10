package com.cordiact.core.security

import java.util.UUID

/**
 * The single mandatory entry point for sensitive operations.
 *
 * Every credential read/write/delete/use must pass through [run]. The gate:
 * 1. writes a REQUESTED audit event before anything else,
 * 2. asks the deterministic policy engine for a decision,
 * 3. obtains a human or independent-reviewer decision when required,
 * 4. issues and atomically consumes a one-shot grant,
 * 5. writes EXECUTION_STARTED before invoking [operation],
 * 6. writes a terminal result.
 *
 * If the audit sink is unavailable the operation is refused. If the post-execution audit write
 * fails, [AuditUnavailableException.operationExecuted] is true and the caller must not retry
 * blindly.
 */
class SensitiveOperationGate(
    private val policy: SecurityPolicyEngine = SecurityPolicyEngine(),
    private val approvals: SecurityApprovalCoordinator,
    private val audit: AuditLog,
    private val clock: SecurityClock = SecurityClock.system(),
    private val isContextCurrent: (SecurityContext) -> Boolean = { true },
    private val gateVersion: String = "0.1.0",
) {
    fun <T> run(
        context: SecurityContext,
        intent: SensitiveIntent,
        toolId: String = "credential-vault",
        transactional: Boolean = true,
        operation: () -> T,
    ): T {
        val correlationId = intent.digest()
        val attemptId = UUID.randomUUID().toString()

        append(
            context,
            intent,
            toolId,
            AuditEventDraft(
                category = AuditCategory.CREDENTIAL,
                phase = AuditPhase.REQUESTED,
                outcome = AuditOutcome.PENDING,
                reasonCode = "REQUESTED",
                correlationId = correlationId,
                attemptId = attemptId,
            ),
        )

        checkCurrent(context)
        val decision = policy.evaluate(context, intent)
        append(
            context,
            intent,
            toolId,
            AuditEventDraft(
                category = AuditCategory.SECURITY,
                phase = AuditPhase.POLICY_EVALUATED,
                outcome = when (decision) {
                    is PolicyDecision.Deny -> AuditOutcome.DENIED
                    else -> AuditOutcome.PENDING
                },
                reasonCode = decision.reasonCode,
                correlationId = correlationId,
                attemptId = attemptId,
            ),
        )

        var approvalId: String? = null
        when (decision) {
            is PolicyDecision.Deny -> throw SecurityDeniedException(decision.reasonCode)

            is PolicyDecision.RequireHuman -> {
                approvalId = UUID.randomUUID().toString()
                val result = requestHuman(context, intent, toolId, correlationId, attemptId, approvalId)
                requireAllowed(result, "HUMAN")
            }

            is PolicyDecision.RequireReviewer -> {
                val reviewer = context.profile.reviewer
                    ?: throw SecurityDeniedException("REVIEWER_NOT_CONFIGURED")
                approvalId = UUID.randomUUID().toString()
                val reviewResult = requestReviewer(
                    context = context,
                    intent = intent,
                    toolId = toolId,
                    correlationId = correlationId,
                    attemptId = attemptId,
                    approvalId = approvalId,
                    reviewer = reviewer,
                )
                if (reviewResult.decision == SecurityApprovalDecision.NEEDS_HUMAN) {
                    if (reviewer.fallbackPolicy != ReviewerFallbackPolicy.ASK_USER) {
                        throw SecurityDeniedException("REVIEW_NEEDS_HUMAN")
                    }
                    approvalId = UUID.randomUUID().toString()
                    val humanResult = requestHuman(context, intent, toolId, correlationId, attemptId, approvalId)
                    requireAllowed(humanResult, "REVIEW_ESCALATED_HUMAN")
                } else {
                    requireAllowed(reviewResult, "REVIEW_MODEL")
                }
            }

            is PolicyDecision.Allow -> Unit
        }

        checkCurrent(context)
        val grant = approvals.issueGrant(
            context = context,
            intent = intent,
            approvalId = approvalId ?: "DETERMINISTIC_POLICY",
        )
        if (!approvals.consume(context, intent, grant)) {
            append(
                context,
                intent,
                toolId,
                AuditEventDraft(
                    category = AuditCategory.SECURITY,
                    phase = AuditPhase.FAILED,
                    outcome = AuditOutcome.DENIED,
                    reasonCode = "GRANT_CONSUME_FAILED",
                    correlationId = correlationId,
                    approvalId = approvalId,
                    attemptId = attemptId,
                ),
            )
            throw SecurityDeniedException("GRANT_CONSUME_FAILED")
        }

        append(
            context,
            intent,
            toolId,
            AuditEventDraft(
                category = AuditCategory.CREDENTIAL,
                phase = AuditPhase.EXECUTION_STARTED,
                outcome = AuditOutcome.PENDING,
                reasonCode = "EXECUTION_STARTED",
                correlationId = correlationId,
                approvalId = approvalId,
                attemptId = attemptId,
            ),
        )

        val successDraft = AuditEventDraft(
            category = AuditCategory.CREDENTIAL,
            phase = AuditPhase.SUCCEEDED,
            outcome = AuditOutcome.SUCCEEDED,
            reasonCode = "SUCCEEDED",
            correlationId = correlationId,
            approvalId = approvalId,
            attemptId = attemptId,
        )
        val result = try {
            if (transactional && audit is TransactionalAuditLog) {
                audit.transaction {
                    checkCurrent(context)
                    val value = operation()
                    append(context, intent, toolId, successDraft)
                    value
                }
            } else {
                checkCurrent(context)
                val value = operation()
                try {
                    append(context, intent, toolId, successDraft)
                } catch (auditFailure: AuditUnavailableException) {
                    throw AuditUnavailableException(
                        message = "AUDIT_FAILED_AFTER_EXECUTION",
                        operationExecuted = true,
                        cause = auditFailure,
                    )
                }
                value
            }
        } catch (failure: Throwable) {
            try {
                append(
                    context,
                    intent,
                    toolId,
                    AuditEventDraft(
                        category = AuditCategory.CREDENTIAL,
                        phase = AuditPhase.FAILED,
                        outcome = AuditOutcome.FAILED,
                        reasonCode = failureCode(failure),
                        correlationId = correlationId,
                        approvalId = approvalId,
                        attemptId = attemptId,
                    ),
                )
            } catch (auditFailure: Throwable) {
                failure.addSuppressed(auditFailure)
            }
            throw failure
        }
        return result
    }

    private fun requestHuman(
        context: SecurityContext,
        intent: SensitiveIntent,
        toolId: String,
        correlationId: String,
        attemptId: String,
        approvalId: String,
    ): SecurityApprovalResult {
        append(
            context,
            intent,
            toolId,
            AuditEventDraft(
                category = AuditCategory.APPROVAL,
                phase = AuditPhase.APPROVAL_ASKED,
                outcome = AuditOutcome.PENDING,
                reasonCode = "HUMAN_APPROVAL_ASKED",
                correlationId = correlationId,
                approvalId = approvalId,
                attemptId = attemptId,
            ),
        )
        val result = approvals.requestHuman(context, intent, approvalId)
        appendApprovalDecided(
            context, intent, toolId, correlationId, attemptId, approvalId, result,
            decidedBy = ApprovalAuthority.HUMAN,
        )
        return result
    }

    private fun requestReviewer(
        context: SecurityContext,
        intent: SensitiveIntent,
        toolId: String,
        correlationId: String,
        attemptId: String,
        approvalId: String,
        reviewer: ReviewerConfig,
    ): SecurityApprovalResult {
        append(
            context,
            intent,
            toolId,
            AuditEventDraft(
                category = AuditCategory.APPROVAL,
                phase = AuditPhase.APPROVAL_ASKED,
                outcome = AuditOutcome.PENDING,
                reasonCode = "REVIEW_MODEL_ASKED",
                correlationId = correlationId,
                approvalId = approvalId,
                attemptId = attemptId,
                reviewerId = reviewer.reviewerId,
                reviewerConfigRevision = reviewer.revision,
                reviewRequestId = approvalId,
            ),
        )
        val result = approvals.requestReviewer(context, intent, reviewer, approvalId)
        appendApprovalDecided(
            context, intent, toolId, correlationId, attemptId, approvalId, result,
            decidedBy = ApprovalAuthority.EXTERNAL_REVIEW_MODEL,
        )
        return result
    }

    private fun appendApprovalDecided(
        context: SecurityContext,
        intent: SensitiveIntent,
        toolId: String,
        correlationId: String,
        attemptId: String,
        approvalId: String,
        result: SecurityApprovalResult,
        decidedBy: ApprovalAuthority,
    ) {
        append(
            context,
            intent,
            toolId,
            AuditEventDraft(
                category = AuditCategory.APPROVAL,
                phase = AuditPhase.APPROVAL_DECIDED,
                outcome = when (result.decision) {
                    SecurityApprovalDecision.ALLOW -> AuditOutcome.ALLOWED
                    SecurityApprovalDecision.DENY -> AuditOutcome.DENIED
                    SecurityApprovalDecision.NEEDS_HUMAN -> AuditOutcome.PENDING
                },
                reasonCode = result.reasonCode,
                correlationId = correlationId,
                approvalId = approvalId,
                attemptId = attemptId,
                approvalAuthority = decidedBy,
                reviewerId = context.profile.reviewer?.reviewerId,
                reviewerConfigRevision = context.profile.reviewer?.revision,
                reviewRequestId = if (decidedBy == ApprovalAuthority.EXTERNAL_REVIEW_MODEL) approvalId else null,
            ),
        )
    }

    private fun requireAllowed(result: SecurityApprovalResult, source: String) {
        if (result.decision != SecurityApprovalDecision.ALLOW) {
            throw SecurityDeniedException("${source}_${result.reasonCode}")
        }
    }

    private fun checkCurrent(context: SecurityContext) {
        if (!isContextCurrent(context)) throw SecurityDeniedException("SECURITY_PROFILE_CHANGED")
    }

    private fun append(context: SecurityContext, intent: SensitiveIntent, toolId: String, draft: AuditEventDraft) {
        try {
            audit.append(
                draft.copy(
                    principalChain = context.principalChain,
                    workspaceId = context.workspaceId,
                    sessionId = context.sessionId,
                    runId = context.runId,
                    turnId = context.turnId,
                    toolCallId = context.toolCallId,
                    toolId = draft.toolId ?: toolId,
                    policyVersion = draft.policyVersion ?: gateVersion,
                    policyEpoch = draft.policyEpoch ?: context.profile.policyEpoch,
                    permissionPreset = draft.permissionPreset ?: context.profile.preset,
                    baseEnvelope = draft.baseEnvelope ?: context.profile.accessEnvelope,
                    approvalAuthority = draft.approvalAuthority ?: context.profile.authority,
                    credentialOpaqueId = draft.credentialOpaqueId ?: intent.credentialId,
                    credentialRevision = draft.credentialRevision ?: intent.expectedRevision,
                    targetDescriptorSanitized = draft.targetDescriptorSanitized ?: intent.canonicalTarget?.canonical(),
                    receiverDescriptorSanitized = draft.receiverDescriptorSanitized ?: intent.effectiveReceiver?.sanitized(),
                ),
            )
        } catch (failure: Throwable) {
            throw AuditUnavailableException("AUDIT_APPEND_FAILED", cause = failure)
        }
    }

    private fun failureCode(failure: Throwable): String = when (failure) {
        is SecurityDeniedException -> failure.reasonCode
        is AuditUnavailableException -> "AUDIT_UNAVAILABLE_DURING_EXECUTION"
        else -> failure::class.simpleName ?: "EXECUTION_FAILED"
    }
}
