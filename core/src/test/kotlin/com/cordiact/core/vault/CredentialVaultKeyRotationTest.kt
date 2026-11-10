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
import kotlin.test.Test
import kotlin.test.assertEquals

class CredentialVaultKeyRotationTest {
    private val audit = TestAuditSupport.log()
    private val commitment = IntentCommitment("0123456789abcdef".toByteArray())

    private fun service(crypto: VaultCrypto): CredentialVaultService {
        val approvals = SecurityApprovalCoordinator(
            humanChannel = HumanApprovalChannel {
                SecurityApprovalResult(SecurityApprovalDecision.ALLOW, "HUMAN_ALLOWED")
            },
        )
        return CredentialVaultService(
            repository = repository,
            crypto = crypto,
            gate = SensitiveOperationGate(approvals = approvals, audit = audit),
            commitment = commitment,
        )
    }

    private lateinit var repository: InMemoryCredentialRepository

    private fun context() = SecurityContext(
        workspaceId = "ws-1",
        sessionId = "session-1",
        runId = "run-1",
        turnId = "turn-1",
        toolCallId = "call-1",
        principalChain = listOf(Principal("main")),
        profile = EffectiveSecurityProfile.workspaceOnly("ws-1"),
    )

    @Test
    fun `rotation moves old records to the new key version`() {
        repository = InMemoryCredentialRepository()
        val v1 = service(TestVaultCrypto(currentKeyVersion = 1))
        val created = v1.write(
            context(),
            CredentialWriteRequest(
                type = CredentialType.BEARER_TOKEN,
                metadata = CredentialMetadata("Token"),
                payload = CredentialPayload.Secret("rotate-me".toByteArray()),
            ),
        )
        assertEquals(1, repository.find(created.id)!!.keyVersion)

        val v2 = TestVaultCrypto(currentKeyVersion = 2)
        val rotated = CredentialVaultKeyRotation(repository, v2, audit).rotate(
            CredentialScope.Workspace("ws-1"),
            fromVersion = 1,
            toVersion = 2,
        )
        assertEquals(1, rotated)
        val record = repository.find(created.id)!!
        assertEquals(2, record.keyVersion)
        assertEquals(2, record.revision)

        val serviceV2 = service(v2)
        val payload = serviceV2.readPlaintext(context(), created.id)
        assertEquals("rotate-me", payload.use { String(it) })
        payload.close()
    }
}
