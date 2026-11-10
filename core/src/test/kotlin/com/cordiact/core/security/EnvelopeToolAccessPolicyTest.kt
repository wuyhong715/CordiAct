package com.cordiact.core.security

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class EnvelopeToolAccessPolicyTest {
    private fun context(profile: EffectiveSecurityProfile) = SecurityContext(
        workspaceId = "ws-1",
        sessionId = "session-1",
        runId = "run-1",
        turnId = "turn-1",
        toolCallId = "call-1",
        principalChain = listOf(Principal("main")),
        profile = profile,
    )

    private fun policy(effect: ToolEffectClass) = EnvelopeToolAccessPolicy { _, _ -> effect }

    @Test
    fun `read only refuses writes`() {
        val decision = policy(ToolEffectClass.WORKSPACE_WRITE)
            .evaluate(context(EffectiveSecurityProfile.readOnly("ws-1")), "workspace.files.write_text", "core.file")
        assertTrue(decision is ToolAccessDecision.Deny)
        assertEquals("READ_ONLY_WRITE_DENIED", (decision as ToolAccessDecision.Deny).reasonCode)
    }

    @Test
    fun `workspace only refuses device storage`() {
        val decision = policy(ToolEffectClass.DEVICE_READ)
            .evaluate(context(EffectiveSecurityProfile.workspaceOnly("ws-1")), "screen_list", "core.automation")
        assertTrue(decision is ToolAccessDecision.Deny)
    }

    @Test
    fun `full access allows device and command but refuses root`() {
        val ctx = context(EffectiveSecurityProfile.fullAccess("ws-1"))
        assertTrue(policy(ToolEffectClass.DEVICE_WRITE).evaluate(ctx, "screen_tap", "core.automation") is ToolAccessDecision.Allow)
        assertTrue(policy(ToolEffectClass.COMMAND).evaluate(ctx, "termux_run_command", "core.termux") is ToolAccessDecision.Allow)
        assertTrue(policy(ToolEffectClass.ROOT).evaluate(ctx, "priv_root", "core.privilege") is ToolAccessDecision.Deny)
    }

    @Test
    fun `auto approval routes ordinary tools to the reviewer`() {
        val decision = policy(ToolEffectClass.WORKSPACE_READ)
            .evaluate(context(EffectiveSecurityProfile.autoApproval("ws-1", EffectiveSecurityProfile.readOnly("ws-1").accessEnvelope, reviewer())), "workspace.files.read_text", "core.file")
        assertTrue(decision is ToolAccessDecision.RequireReview)
    }

    @Test
    fun `auto approval does not recursively review credential tools`() {
        val decision = policy(ToolEffectClass.CREDENTIAL)
            .evaluate(context(EffectiveSecurityProfile.autoApproval("ws-1", EffectiveSecurityProfile.workspaceOnly("ws-1").accessEnvelope, reviewer())), "credential_read", CREDENTIAL_VAULT_PLUGIN_ID)
        assertTrue(decision is ToolAccessDecision.Allow)
    }

    @Test
    fun `unknown effects fail closed`() {
        val decision = policy(ToolEffectClass.UNKNOWN)
            .evaluate(context(EffectiveSecurityProfile.fullAccess("ws-1")), "third_party_tool", "third.party")
        assertTrue(decision is ToolAccessDecision.Deny)
        assertEquals("UNCLASSIFIED_TOOL_DENIED", (decision as ToolAccessDecision.Deny).reasonCode)
    }

    private fun reviewer() = ReviewerConfig(
        reviewerId = "reviewer-1", revision = 1, providerId = "p-1", modelId = "m-1",
        endpointIdentity = "https://example.test", policyTemplateVersion = "v1", timeoutMillis = 1_000,
        maxInputTokens = 1_000, maxOutputTokens = 100, maxCallsPerRun = 10,
    )

    @Test
    fun `credential tools are left to the sensitive gate`() {
        assertTrue(
            policy(ToolEffectClass.CREDENTIAL)
                .evaluate(context(EffectiveSecurityProfile.readOnly("ws-1")), "credential_read", CREDENTIAL_VAULT_PLUGIN_ID) is ToolAccessDecision.Allow,
        )
    }
}
