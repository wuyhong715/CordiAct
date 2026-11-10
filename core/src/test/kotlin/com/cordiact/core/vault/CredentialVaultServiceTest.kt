package com.cordiact.core.vault

import com.cordiact.core.security.AccessEnvelope
import com.cordiact.core.security.ApprovalAuthority
import com.cordiact.core.security.InMemoryAuditLog
import com.cordiact.core.security.IntentCommitment
import com.cordiact.core.security.SecurityApprovalCoordinator
import com.cordiact.core.security.SecurityApprovalDecision
import com.cordiact.core.security.SecurityApprovalResult
import com.cordiact.core.security.SecurityContext
import com.cordiact.core.security.SecurityDeniedException
import com.cordiact.core.security.SensitiveOperationGate
import com.cordiact.core.security.TargetDescriptor
import com.cordiact.core.security.TestAuditSupport
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CredentialVaultServiceTest {
    private val audit = TestAuditSupport.log()
    private val commitment = IntentCommitment("0123456789abcdef".toByteArray())

    private fun service(
        humanDecision: SecurityApprovalDecision = SecurityApprovalDecision.ALLOW,
        repository: CredentialRepository = InMemoryCredentialRepository(),
    ): Pair<CredentialVaultService, CredentialRepository> {
        val approvals = SecurityApprovalCoordinator(
            humanChannel = com.cordiact.core.security.HumanApprovalChannel {
                SecurityApprovalResult(humanDecision, if (humanDecision == SecurityApprovalDecision.ALLOW) "HUMAN_ALLOWED" else "HUMAN_DENIED")
            },
        )
        val gate = SensitiveOperationGate(approvals = approvals, audit = audit)
        return CredentialVaultService(repository, TestVaultCrypto(), gate, commitment) to repository
    }

    private fun context(profile: com.cordiact.core.security.EffectiveSecurityProfile) = SecurityContext(
        workspaceId = profile.workspaceId,
        sessionId = "session-1",
        runId = "run-1",
        turnId = "turn-1",
        toolCallId = "call-1",
        principalChain = listOf(com.cordiact.core.security.Principal("main")),
        profile = profile,
        modelProviderId = "provider",
        modelEndpointHost = "api.example.com",
        modelId = "model",
    )

    @Test
    fun `write list read update delete lifecycle`() {
        val (vault, repository) = service()
        val ctx = context(com.cordiact.core.security.EffectiveSecurityProfile.workspaceOnly("ws-1"))
        val created = vault.write(
            ctx,
            CredentialWriteRequest(
                type = CredentialType.BEARER_TOKEN,
                metadata = CredentialMetadata("Service A", accountHint = "bot"),
                payload = CredentialPayload.Secret("sk-test-1234567890".toByteArray()),
            ),
        )
        assertEquals(1, created.revision)
        assertEquals(1, repository.all().size)

        val listed = vault.list(ctx)
        assertEquals(listOf("Service A"), listed.map { it.label })

        val details = vault.readMetadata(ctx, created.id)
        assertEquals("bot", details.summary.accountHint)

        val plaintext = vault.readPlaintext(ctx, created.id)
        val value = plaintext.use { String(it) }
        assertEquals("sk-test-1234567890", value)
        plaintext.close()

        val updated = vault.write(
            ctx,
            CredentialWriteRequest(
                credentialId = created.id,
                expectedRevision = 1,
                type = CredentialType.BEARER_TOKEN,
                metadata = CredentialMetadata("Service A v2"),
                payload = CredentialPayload.Secret("sk-test-0987654321".toByteArray()),
            ),
        )
        assertEquals(2, updated.revision)

        assertTrue(vault.delete(ctx, created.id, expectedRevision = 2))
        assertFalse(vault.delete(ctx, created.id, expectedRevision = 2))
    }

    @Test
    fun `stale revision refuses the update`() {
        val (vault, _) = service()
        val ctx = context(com.cordiact.core.security.EffectiveSecurityProfile.workspaceOnly("ws-1"))
        val created = vault.write(
            ctx,
            CredentialWriteRequest(
                type = CredentialType.API_KEY,
                metadata = CredentialMetadata("Key"),
                payload = CredentialPayload.Secret("abc".toByteArray()),
            ),
        )
        vault.write(
            ctx,
            CredentialWriteRequest(
                credentialId = created.id,
                expectedRevision = 1,
                type = CredentialType.API_KEY,
                metadata = CredentialMetadata("Key 2"),
                payload = CredentialPayload.Secret("def".toByteArray()),
            ),
        )
        assertFailsWith<StaleRevisionException> {
            vault.write(
                ctx,
                CredentialWriteRequest(
                    credentialId = created.id,
                    expectedRevision = 1,
                    type = CredentialType.API_KEY,
                    metadata = CredentialMetadata("Key 3"),
                    payload = CredentialPayload.Secret("ghi".toByteArray()),
                ),
            )
        }
    }

    @Test
    fun `read only preset refuses writes without asking`() {
        val (vault, repository) = service()
        val ctx = context(com.cordiact.core.security.EffectiveSecurityProfile.readOnly("ws-1"))
        assertFailsWith<SecurityDeniedException> {
            vault.write(
                ctx,
                CredentialWriteRequest(
                    type = CredentialType.API_KEY,
                    metadata = CredentialMetadata("Key"),
                    payload = CredentialPayload.Secret("abc".toByteArray()),
                ),
            )
        }
        assertTrue(repository.all().isEmpty())
    }

    @Test
    fun `denied human approval does not write`() {
        val (vault, repository) = service(humanDecision = SecurityApprovalDecision.DENY)
        val ctx = context(com.cordiact.core.security.EffectiveSecurityProfile.workspaceOnly("ws-1"))
        assertFailsWith<SecurityDeniedException> {
            vault.write(
                ctx,
                CredentialWriteRequest(
                    type = CredentialType.API_KEY,
                    metadata = CredentialMetadata("Key"),
                    payload = CredentialPayload.Secret("abc".toByteArray()),
                ),
            )
        }
        assertTrue(repository.all().isEmpty())
    }

    @Test
    fun `audit never contains the secret value`() {
        val (vault, _) = service()
        val ctx = context(com.cordiact.core.security.EffectiveSecurityProfile.workspaceOnly("ws-1"))
        val secret = "sk-canary-this-must-never-be-audited"
        vault.write(
            ctx,
            CredentialWriteRequest(
                type = CredentialType.BEARER_TOKEN,
                metadata = CredentialMetadata("Canary"),
                payload = CredentialPayload.Secret(secret.toByteArray()),
            ),
        )
        assertFalse(audit.query().any { it.canonicalPayload().contains(secret) })
    }

    @Test
    fun `controlled use binds the exact target`() {
        val repository = InMemoryCredentialRepository()
        val executor = ControlledHttpExecutor { _, request ->
            CredentialUseResult(200, "ok:${request.target.canonical()}")
        }
        val approvals = SecurityApprovalCoordinator(
            humanChannel = com.cordiact.core.security.HumanApprovalChannel {
                SecurityApprovalResult(SecurityApprovalDecision.ALLOW, "HUMAN_ALLOWED")
            },
        )
        val gate = SensitiveOperationGate(approvals = approvals, audit = audit)
        val vault = CredentialVaultService(
            repository,
            TestVaultCrypto(),
            gate,
            commitment,
            httpExecutor = executor,
        )
        val ctx = context(com.cordiact.core.security.EffectiveSecurityProfile.fullAccess("ws-1"))
        val created = vault.write(
            ctx,
            CredentialWriteRequest(
                type = CredentialType.BEARER_TOKEN,
                metadata = CredentialMetadata("API"),
                payload = CredentialPayload.Secret("token".toByteArray()),
            ),
        )
        val result = vault.use(
            ctx,
            CredentialUseRequest(created.id, TargetDescriptor.parseHttps("https://api.example.com/v1/items", "GET")),
        )
        assertEquals(200, result.statusCode)
        assertTrue(result.responseSummary!!.contains("https://api.example.com:443/v1/items"))
    }
}
