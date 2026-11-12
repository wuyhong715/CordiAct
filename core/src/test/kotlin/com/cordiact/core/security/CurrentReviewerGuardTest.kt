package com.cordiact.core.security

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class CurrentReviewerGuardTest {
    private fun snapshot() = ReviewSnapshot(
        requestId = "review-1",
        intentDigest = "digest-1",
        profileRevision = 3,
        policyEpoch = 4,
        actorChain = listOf(Principal("main")),
        workspaceIdentity = "ws-1",
        runId = "run-1",
        callId = "call-1",
        declaredUserGoalWithSource = "read a token for the user's API task",
        currentEnvelope = AccessEnvelope.WORKSPACE_ONLY,
        normalizedOperation = CredentialOperation.READ_PLAINTEXT,
        actualTarget = null,
        effectCategory = "credential_plaintext_read",
        sanitizedArguments = mapOf("credentialId" to "cred-1"),
        payloadShape = "secret:10",
        credentialTypeAndRevision = "BEARER_TOKEN:2",
        reviewerProviderId = "review-provider",
        reviewerModelId = "review-model",
        reviewerEndpointIdentity = "review.example.com",
        reviewerConfigRevision = 1,
    )


    private val reviewer = ReviewerConfig("reviewer", 1, "review-provider", "review-model", "review.example.com")
    private fun allow() = ReviewerOutput("review-1", "digest-1", SecurityApprovalDecision.ALLOW, ReviewerRisk.LOW, "OK", "allowed")

    @Test fun unchangedBindingAcceptsDelegateResult() {
        assertEquals(SecurityApprovalDecision.ALLOW,
            CurrentReviewerGuard({ reviewer }, ReviewerClient { allow() }).review(snapshot()).decision)
    }
    @Test fun changedEndpointRejectedBeforeModelCall() {
        var called = false
        val guard = CurrentReviewerGuard({ reviewer.copy(endpointIdentity = "changed.example") }, ReviewerClient { called = true; allow() })
        val failure = assertThrows(IllegalStateException::class.java) { guard.review(snapshot()) }
        assertEquals("REVIEWER_CONFIGURATION_CHANGED", failure.message)
        assertFalse(called)
    }
    @Test fun changedRevisionDuringModelCallRejectsReturnedApproval() {
        var current = reviewer
        val guard = CurrentReviewerGuard({ current }, ReviewerClient { current = reviewer.copy(revision = 2); allow() })
        assertThrows(IllegalStateException::class.java) { guard.review(snapshot()) }
    }
    @Test fun removedReviewerDuringCallRejectsApproval() {
        var current: ReviewerConfig? = reviewer
        val guard = CurrentReviewerGuard({ current }, ReviewerClient { current = null; allow() })
        val failure = assertThrows(IllegalStateException::class.java) { guard.review(snapshot()) }
        assertEquals("REVIEWER_UNAVAILABLE", failure.message)
    }
    @Test fun switchedModelOrProviderCannotReuseSnapshot() {
        listOf(reviewer.copy(providerId = "other"), reviewer.copy(modelId = "other")).forEach { changed ->
            var called = false
            assertThrows(IllegalStateException::class.java) {
                CurrentReviewerGuard({ changed }, ReviewerClient { called = true; allow() }).review(snapshot())
            }
            assertFalse(called)
        }
    }

    @Test fun keyRotationDuringModelCallRejectsOldApproval() {
        var current = reviewer
        val guard = CurrentReviewerGuard({ current }, ReviewerClient {
            current = reviewer.copy(credentialRevision = reviewer.credentialRevision + 1); allow()
        })
        assertThrows(IllegalStateException::class.java) { guard.review(snapshot()) }
    }

    @Test fun keyRevisionBindsOneShotGrantConsumption() {
        val profile = EffectiveSecurityProfile.autoApproval("ws-1", AccessEnvelope.WORKSPACE_ONLY, reviewer)
        val context = SecurityContext("ws-1", "session", "run", "turn", "call", listOf(Principal("main", PrincipalType.MAIN_AGENT)), profile)
        val intent = SensitiveIntent(workspaceId = "ws-1", operation = CredentialOperation.READ_PLAINTEXT,
            scope = CredentialScopeRef.Workspace("ws-1"), principalChain = context.principalChain,
            sessionId = context.sessionId, runId = context.runId, turnId = context.turnId,
            toolCallId = context.toolCallId, payloadCommitment = "payload", policyEpoch = profile.policyEpoch)
        val coordinator = SecurityApprovalCoordinator(HumanApprovalChannel {
            SecurityApprovalResult(SecurityApprovalDecision.ALLOW, "OK")
        })
        val grant = coordinator.issueGrant(context, intent, "approved")
        val rotated = context.copy(profile = EffectiveSecurityProfile.autoApproval("ws-1", AccessEnvelope.WORKSPACE_ONLY, reviewer.copy(credentialRevision = 1)))
        assertFalse(coordinator.consume(rotated, intent, grant))
        assertFalse(coordinator.consume(context, intent, grant))
    }
}
