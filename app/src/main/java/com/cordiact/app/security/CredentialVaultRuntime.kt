package com.cordiact.app.security

import android.content.Context
import com.cordiact.core.security.CredentialOperation
import com.cordiact.core.security.CredentialScopeRef
import com.cordiact.core.security.CredentialVaultPlugin
import com.cordiact.core.security.SecurityClock
import com.cordiact.core.security.IntentCommitment
import com.cordiact.core.security.Principal
import com.cordiact.core.security.SecurityApprovalCoordinator
import com.cordiact.core.security.SecurityAuditPlugin
import com.cordiact.core.security.SecurityContext
import com.cordiact.core.security.SensitiveIntent
import com.cordiact.core.security.SensitiveOperationGate
import com.cordiact.core.tool.ToolDefinitionRegistry
import com.cordiact.core.vault.CredentialToolset
import com.cordiact.core.vault.CredentialVaultService
import com.cordiact.core.vault.SecurityContextResolver
import java.security.SecureRandom
import java.util.UUID

/**
 * Android composition root for the credential vault and audit plugin.
 *
 * It is intentionally created once at application scope. The vault database, Keystore crypto and
 * audit HMAC key are all separate dependencies; no caller gets a raw repository or crypto handle
 * through the tool surface.
 */
class CredentialVaultRuntime private constructor(
    context: Context,
    approvalGateway: com.cordiact.core.action.ApprovalGateway,
) {
    val profileStore = SecurityProfileStore(context)
    val sensitivePayloads = com.cordiact.core.security.SensitivePayloadRegistry()
    val auditSigner = AndroidAuditSigner()
    val database = AndroidSecurityDatabase(context.applicationContext, auditSigner)
    private val crypto = AndroidVaultCrypto(context.applicationContext)
    val humanApprovals = ApprovalGatewayHumanChannel(approvalGateway)

    @Volatile
    private var reviewerClient: com.cordiact.core.security.ReviewerClient? = null
    private val reviewerDelegate = com.cordiact.core.security.ReviewerClient { snapshot ->
        reviewerClient?.review(snapshot) ?: throw IllegalStateException("REVIEWER_NOT_CONFIGURED")
    }

    private val approvals = SecurityApprovalCoordinator(
        humanChannel = humanApprovals,
        reviewerClient = reviewerDelegate,
    )

    fun attachReviewerClient(client: com.cordiact.core.security.ReviewerClient) {
        reviewerClient = client
    }

    /** Host-issued context for one turn; tools never construct or widen it themselves. */
    fun newSecurityContext(
        workspaceId: String,
        sessionId: String,
        modelProviderId: String?,
        modelId: String?,
    ): SecurityContext {
        val profile = profileStore.profileFor(workspaceId)
        return SecurityContext(
            workspaceId = workspaceId,
            sessionId = sessionId,
            runId = "run-$sessionId",
            turnId = "turn-$sessionId",
            toolCallId = UUID.randomUUID().toString(),
            principalChain = listOf(Principal("main-agent")),
            profile = profile,
            modelProviderId = modelProviderId,
            modelId = modelId,
        )
    }

    private val operationCommitment = IntentCommitment(ByteArray(32).also(SecureRandom()::nextBytes))

    private val gate = SensitiveOperationGate(
        approvals = approvals,
        audit = database,
        isContextCurrent = { context -> profileStore.profileFor(context.workspaceId) == context.profile },
    )

    private val commitmentKey = ByteArray(32).also { SecureRandom().nextBytes(it) }

    val service = CredentialVaultService(
        repository = database,
        crypto = crypto,
        gate = gate,
        commitment = IntentCommitment(commitmentKey),
        httpExecutor = HttpsCredentialExecutor(),
    )

    val credentialPlugin = CredentialVaultPlugin(service)
    val auditPlugin = SecurityAuditPlugin(database)

    private val resolver = SecurityContextResolver { invocation ->
        val context = invocation.securityContext ?: error("SECURITY_CONTEXT_REQUIRED")
        check(context.workspaceId == invocation.workspaceId) { "WORKSPACE_MISMATCH" }
        context.copy(toolCallId = UUID.randomUUID().toString())
    }

    fun reviewTool(context: SecurityContext, toolId: String, arguments: com.cordiact.core.tool.ToolArguments, execute: () -> com.cordiact.core.tool.ToolResult): com.cordiact.core.tool.ToolResult {
        val now = SecurityClock.system().now()
        val targetWorkspace = (arguments.values["targetWorkspaceId"] as? com.cordiact.core.tool.ToolValue.StringValue)?.value
        check(targetWorkspace == null || targetWorkspace == context.workspaceId) { "CROSS_WORKSPACE_DENIED" }
        val safeArguments = arguments.values.filterKeys {
            it in setOf("path", "directory", "offset", "maxBytes", "expectedRevision", "targetWorkspaceId")
        }.mapValues { (_, value) -> value.toString().take(512) }
        val intent = SensitiveIntent(
            workspaceId = context.workspaceId,
            operation = CredentialOperation.TOOL_EXECUTION,
            scope = CredentialScopeRef.Workspace(context.workspaceId),
            principalChain = context.principalChain,
            sessionId = context.sessionId, runId = context.runId, turnId = context.turnId,
            toolCallId = UUID.randomUUID().toString(),
            payloadCommitment = operationCommitment.commit(toolId, arguments.values.toString()),
            policyEpoch = context.profile.policyEpoch,
            sanitizedArguments = safeArguments + mapOf("toolId" to toolId, "argumentNames" to arguments.values.keys.sorted().joinToString(",")),
            issuedAt = now, expiresAt = now.plusSeconds(120),
        )
        return gate.run(context.copy(toolCallId = intent.toolCallId), intent, toolId = toolId, transactional = false) { execute() }
    }

    val toolset = CredentialToolset(
        service = service,
        contextResolver = resolver,
    )
    val auditToolset = com.cordiact.core.security.SecurityAuditToolset(database, resolver)

    fun registerTools(registry: ToolDefinitionRegistry) {
        toolset.register(registry)
        auditToolset.register(registry)
    }

    /**
     * Trusted management entry point. The old key version is retained so a crash mid-rotation
     * leaves every record readable and rerunnable.
     */
    fun rotateWorkspaceKey(workspaceId: String, toVersion: Int): Int {
        require(workspaceId.isNotBlank()) { "workspaceId must not be blank" }
        val fromVersion = crypto.currentKeyVersion
        require(toVersion > fromVersion) { "toVersion must be greater than the current key version" }
        crypto.currentKeyVersion = toVersion
        return com.cordiact.core.vault.CredentialVaultKeyRotation(
            repository = database,
            crypto = crypto,
            audit = database,
        ).rotate(
            scope = com.cordiact.core.vault.CredentialScope.Workspace(workspaceId),
            fromVersion = fromVersion,
            toVersion = toVersion,
        )
    }

    companion object {
        @Volatile
        private var instance: CredentialVaultRuntime? = null

        fun get(
            context: Context,
            approvalGateway: com.cordiact.core.action.ApprovalGateway,
        ): CredentialVaultRuntime =
            instance ?: synchronized(this) {
                instance ?: CredentialVaultRuntime(context.applicationContext, approvalGateway).also { instance = it }
            }
    }
}
