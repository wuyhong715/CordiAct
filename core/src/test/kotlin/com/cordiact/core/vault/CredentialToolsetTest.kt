package com.cordiact.core.vault

import com.cordiact.core.security.EffectiveSecurityProfile
import com.cordiact.core.security.HumanApprovalChannel
import com.cordiact.core.security.IntentCommitment
import com.cordiact.core.security.Principal
import com.cordiact.core.security.SecurityApprovalCoordinator
import com.cordiact.core.security.SecurityApprovalDecision
import com.cordiact.core.security.SecurityApprovalResult
import com.cordiact.core.security.SecurityContext
import com.cordiact.core.security.SensitiveOperationGate
import com.cordiact.core.security.TestAuditSupport
import com.cordiact.core.tool.InMemoryToolDefinitionRegistry
import com.cordiact.core.tool.ToolArguments
import com.cordiact.core.tool.ToolInvocationContext
import com.cordiact.core.tool.ToolValue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class CredentialToolsetTest {
    private val audit = TestAuditSupport.log()
    private val commitment = IntentCommitment("0123456789abcdef".toByteArray())

    private fun fixture(): Pair<CredentialToolset, CredentialVaultService> {
        val approvals = SecurityApprovalCoordinator(
            humanChannel = HumanApprovalChannel {
                SecurityApprovalResult(SecurityApprovalDecision.ALLOW, "HUMAN_ALLOWED")
            },
        )
        val gate = SensitiveOperationGate(approvals = approvals, audit = audit)
        val service = CredentialVaultService(
            repository = InMemoryCredentialRepository(),
            crypto = TestVaultCrypto(),
            gate = gate,
            commitment = commitment,
        )
        val resolver = SecurityContextResolver {
            SecurityContext(
                workspaceId = "ws-1",
                sessionId = "session-1",
                runId = "run-1",
                turnId = "turn-1",
                toolCallId = "call-1",
                principalChain = listOf(Principal("main")),
                profile = EffectiveSecurityProfile.workspaceOnly("ws-1"),
            )
        }
        return CredentialToolset(service, resolver) to service
    }

    @Test
    fun `registers the five credential tools`() {
        val (toolset, _) = fixture()
        val registry = InMemoryToolDefinitionRegistry()
        toolset.register(registry)
        assertEquals(
            setOf(
                CredentialToolIds.LIST,
                CredentialToolIds.READ,
                CredentialToolIds.WRITE,
                CredentialToolIds.DELETE,
                CredentialToolIds.USE,
            ),
            registry.all().map { it.descriptor.id }.toSet(),
        )
    }

    @Test
    fun `plaintext read is delivered as a sensitive payload not a tool value`() {
        val (toolset, service) = fixture()
        val context = SecurityContext(
            workspaceId = "ws-1",
            sessionId = "session-1",
            runId = "run-1",
            turnId = "turn-1",
            toolCallId = "call-1",
            principalChain = listOf(Principal("main")),
            profile = EffectiveSecurityProfile.workspaceOnly("ws-1"),
        )
        val summary = service.write(
            context,
            CredentialWriteRequest(
                type = CredentialType.BEARER_TOKEN,
                metadata = CredentialMetadata("Token"),
                payload = CredentialPayload.Secret("sk-toolset-secret".toByteArray()),
            ),
        )
        val read = toolset.definitions().single { it.descriptor.id == CredentialToolIds.READ }
        val result = read.executor.invoke(
            ToolInvocationContext(workspaceId = "ws-1"),
            ToolArguments(
                mapOf(
                    "credentialId" to ToolValue.StringValue(summary.id),
                    "mode" to ToolValue.StringValue("plaintext"),
                    "purpose" to ToolValue.StringValue("test"),
                ),
            ),
        )
        val payload = assertNotNull(result.sensitivePayload)
        assertEquals("sk-toolset-secret", payload.use { String(it) })
        assertTrue(result.values.values.none { it is ToolValue.StringValue && it.value.contains("sk-toolset-secret") })
        payload.close()
    }
}
