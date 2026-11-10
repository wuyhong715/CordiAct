package com.cordiact.core.security

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SecurityCoreTest {
    private val audit = InMemoryAuditLog(HmacAuditSigner("0123456789abcdef".toByteArray()))

    private fun context(
        profile: EffectiveSecurityProfile,
        workspaceId: String = "ws-1",
        globalIds: Set<String> = emptySet(),
    ) = SecurityContext(
        workspaceId = workspaceId,
        sessionId = "session-1",
        runId = "run-1",
        turnId = "turn-1",
        toolCallId = "call-1",
        principalChain = listOf(Principal("main", PrincipalType.MAIN_AGENT)),
        profile = profile,
        modelProviderId = "provider",
        modelEndpointHost = "api.example.com",
        modelId = "model",
        allowedGlobalCredentialIds = globalIds,
    )

    private fun intent(
        context: SecurityContext,
        operation: CredentialOperation = CredentialOperation.READ_METADATA,
        credentialId: String? = "cred-1",
        scope: CredentialScopeRef = CredentialScopeRef.Workspace(context.workspaceId),
        target: TargetDescriptor? = null,
        expectedRevision: Long? = null,
    ) = SensitiveIntent(
        workspaceId = context.workspaceId,
        operation = operation,
        scope = scope,
        credentialId = credentialId,
        expectedRevision = expectedRevision,
        principalChain = context.principalChain,
        sessionId = context.sessionId,
        runId = context.runId,
        turnId = context.turnId,
        toolCallId = context.toolCallId,
        canonicalTarget = target,
        payloadCommitment = "commitment",
        policyEpoch = context.profile.policyEpoch,
        declaredPurpose = "test",
    )

    @Test
    fun `workspace mismatch is denied`() {
        val context = context(EffectiveSecurityProfile.workspaceOnly("ws-1"))
        val request = intent(context).copy(workspaceId = "ws-2")
        val decision = SecurityPolicyEngine().evaluate(context, request)
        assertTrue(decision is PolicyDecision.Deny)
        assertEquals("WORKSPACE_MISMATCH", decision.reasonCode)
    }

    @Test
    fun `global credential requires an explicit binding`() {
        val context = context(EffectiveSecurityProfile.workspaceOnly("ws-1"))
        val request = intent(
            context,
            scope = CredentialScopeRef.UserGlobal,
        )
        val decision = SecurityPolicyEngine().evaluate(context, request)
        assertTrue(decision is PolicyDecision.Deny)
        assertEquals("GLOBAL_CREDENTIAL_NOT_BOUND", decision.reasonCode)
    }

    @Test
    fun `read only preset refuses credential writes`() {
        val context = context(EffectiveSecurityProfile.readOnly("ws-1"))
        val request = intent(context, operation = CredentialOperation.WRITE, credentialId = null)
        val decision = SecurityPolicyEngine().evaluate(context, request)
        assertTrue(decision is PolicyDecision.Deny)
        assertEquals("READ_ONLY_WRITE_DENIED", decision.reasonCode)
    }

    @Test
    fun `normal presets require human approval for credentials`() {
        val context = context(EffectiveSecurityProfile.fullAccess("ws-1"))
        val decision = SecurityPolicyEngine().evaluate(context, intent(context))
        assertTrue(decision is PolicyDecision.RequireHuman)
    }

    @Test
    fun `auto preset routes to the reviewer`() {
        val reviewer = ReviewerConfig(
            reviewerId = "reviewer",
            revision = 1,
            providerId = "review-provider",
            modelId = "review-model",
            endpointIdentity = "review.example.com",
        )
        val context = context(
            EffectiveSecurityProfile.autoApproval("ws-1", AccessEnvelope.WORKSPACE_ONLY, reviewer),
        )
        val decision = SecurityPolicyEngine().evaluate(context, intent(context))
        assertTrue(decision is PolicyDecision.RequireReviewer)
    }

    @Test
    fun `one shot grant is consumed once`() {
        val context = context(EffectiveSecurityProfile.workspaceOnly("ws-1"))
        val request = intent(context)
        val coordinator = SecurityApprovalCoordinator(
            humanChannel = HumanApprovalChannel {
                SecurityApprovalResult(SecurityApprovalDecision.ALLOW, "HUMAN_ALLOWED")
            },
        )
        val grant = coordinator.issueGrant(context, request, "approval-1")
        assertTrue(coordinator.consume(context, request, grant))
        assertFalse(coordinator.consume(context, request, grant))
    }

    @Test
    fun `reviewer output with wrong request id is rejected`() {
        val reviewer = ReviewerConfig(
            reviewerId = "reviewer",
            revision = 1,
            providerId = "review-provider",
            modelId = "review-model",
            endpointIdentity = "review.example.com",
        )
        val context = context(
            EffectiveSecurityProfile.autoApproval("ws-1", AccessEnvelope.WORKSPACE_ONLY, reviewer),
        )
        val request = intent(context)
        val coordinator = SecurityApprovalCoordinator(
            humanChannel = HumanApprovalChannel {
                SecurityApprovalResult(SecurityApprovalDecision.DENY, "UNUSED")
            },
            reviewerClient = ReviewerClient { snapshot ->
                ReviewerOutput(
                    requestId = "forged",
                    intentDigest = snapshot.intentDigest,
                    decision = SecurityApprovalDecision.ALLOW,
                    risk = ReviewerRisk.LOW,
                    reasonCode = "OK",
                )
            },
        )
        val result = coordinator.requestReviewer(context, request, reviewer, "approval-1")
        assertEquals(SecurityApprovalDecision.DENY, result.decision)
        assertEquals("REVIEW_REQUEST_MISMATCH", result.reasonCode)
    }

    @Test
    fun `audit chain verifies and detects tampering`() {
        val log = InMemoryAuditLog(HmacAuditSigner("0123456789abcdef".toByteArray()))
        val draft = AuditEventDraft(
            category = AuditCategory.SECURITY,
            phase = AuditPhase.POLICY_EVALUATED,
            outcome = AuditOutcome.DENIED,
            reasonCode = "TEST",
            correlationId = "corr",
            workspaceId = "ws-1",
        )
        log.append(draft)
        log.append(draft.copy(correlationId = "corr-2"))
        assertTrue(log.verify().valid)
    }

    @Test
    fun `gate refuses when audit sink is unavailable`() {
        val context = context(EffectiveSecurityProfile.workspaceOnly("ws-1"))
        val request = intent(context, operation = CredentialOperation.LIST, credentialId = null)
        val failingAudit = object : AuditLog {
            override fun append(draft: AuditEventDraft): AuditEvent = throw IllegalStateException("disk full")
            override fun query(filter: AuditFilter): List<AuditEvent> = emptyList()
            override fun verify(): AuditVerification = AuditVerification(false, 0)
        }
        val gate = SensitiveOperationGate(
            approvals = SecurityApprovalCoordinator(
                humanChannel = HumanApprovalChannel {
                    SecurityApprovalResult(SecurityApprovalDecision.ALLOW, "OK")
                },
            ),
            audit = failingAudit,
        )
        var executed = false
        assertFailsWith<AuditUnavailableException> {
            gate.run(context, request) { executed = true; "ok" }
        }
        assertFalse(executed)
    }

    @Test
    fun `grant expires with clock`() {
        var now = Instant.parse("2026-11-09T00:00:00Z")
        val clock = SecurityClock { now }
        val context = context(EffectiveSecurityProfile.workspaceOnly("ws-1"))
        val request = intent(context)
        val coordinator = SecurityApprovalCoordinator(
            humanChannel = HumanApprovalChannel {
                SecurityApprovalResult(SecurityApprovalDecision.ALLOW, "OK")
            },
            clock = clock,
            grantTtlMillis = 1_000,
        )
        val grant = coordinator.issueGrant(context, request, "approval-1")
        now = now.plusSeconds(2)
        assertFalse(coordinator.consume(context, request, grant))
    }
}
