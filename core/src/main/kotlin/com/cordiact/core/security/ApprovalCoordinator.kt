package com.cordiact.core.security

import java.time.Instant
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

enum class SecurityApprovalDecision {
    ALLOW,
    DENY,
    NEEDS_HUMAN,
}

data class SecurityApprovalResult(
    val decision: SecurityApprovalDecision,
    val reasonCode: String,
    val reasonSummary: String = "",
) {
    init {
        require(reasonCode.length <= 96) { "approval reasonCode is too long" }
        require(reasonSummary.length <= 280) { "approval reasonSummary is too long" }
    }
}

enum class ApprovalKind {
    HUMAN,
    REVIEW_MODEL,
}

data class PendingSecurityApproval(
    val requestId: String,
    val intent: SensitiveIntent,
    val kind: ApprovalKind,
    val createdAt: Instant,
    val expiresAt: Instant,
)

/**
 * Trusted host UI implements this. Tool JSON, model text and MCP clients are not allowed to
 * implement or invoke it.
 */
fun interface HumanApprovalChannel {
    fun request(ticket: PendingSecurityApproval): SecurityApprovalResult
}

/**
 * A thread-safe queue for foreground approval. The UI can display [pendingRequests] and answer
 * exactly once through [respond]. Late, duplicate and unknown request ids are rejected.
 */
class QueueingHumanApprovalChannel(
    private val clock: SecurityClock = SecurityClock.system(),
    private val timeoutMillis: Long = 120_000L,
) : HumanApprovalChannel {
    private data class Pending(
        val ticket: PendingSecurityApproval,
        val future: CompletableFuture<SecurityApprovalResult>,
    )

    private val lock = Any()
    private val pending = LinkedHashMap<String, Pending>()

    fun pendingRequests(): List<PendingSecurityApproval> = synchronized(lock) {
        pending.values.map { it.ticket }
    }

    /** Called only by the trusted approval UI. Returns true when this answer won the race. */
    fun respond(requestId: String, result: SecurityApprovalResult): Boolean {
        val entry = synchronized(lock) {
            val found = pending[requestId] ?: return false
            pending.remove(requestId)
            found
        }
        return entry.future.complete(result)
    }

    fun cancel(requestId: String): Boolean =
        respond(requestId, SecurityApprovalResult(SecurityApprovalDecision.DENY, "APPROVAL_CANCELLED"))

    override fun request(ticket: PendingSecurityApproval): SecurityApprovalResult {
        val future = CompletableFuture<SecurityApprovalResult>()
        synchronized(lock) {
            require(!pending.containsKey(ticket.requestId)) { "duplicate approval request id" }
            pending[ticket.requestId] = Pending(ticket, future)
        }
        return try {
            future.get(timeoutMillis, TimeUnit.MILLISECONDS)
        } catch (_: java.util.concurrent.TimeoutException) {
            synchronized(lock) { pending.remove(ticket.requestId) }
            SecurityApprovalResult(SecurityApprovalDecision.DENY, "APPROVAL_TIMEOUT")
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            synchronized(lock) { pending.remove(ticket.requestId) }
            SecurityApprovalResult(SecurityApprovalDecision.DENY, "APPROVAL_INTERRUPTED")
        } catch (_: Exception) {
            synchronized(lock) { pending.remove(ticket.requestId) }
            SecurityApprovalResult(SecurityApprovalDecision.DENY, "APPROVAL_UNAVAILABLE")
        }
    }
}

/**
 * The exact, secret-free view that may be sent to an independent reviewer. It deliberately has
 * no field for a credential value, cookie, header, private key or full chat history.
 */
data class ReviewSnapshot(
    val requestId: String,
    val intentDigest: String,
    val profileRevision: Long,
    val policyEpoch: Long,
    val actorChain: List<Principal>,
    val workspaceIdentity: String,
    val runId: String,
    val callId: String,
    val declaredUserGoalWithSource: String,
    val currentEnvelope: AccessEnvelope,
    val normalizedOperation: CredentialOperation,
    val actualTarget: String?,
    val effectCategory: String,
    val sanitizedArguments: Map<String, String>,
    val payloadShape: String?,
    val credentialTypeAndRevision: String?,
    val reviewerProviderId: String? = null,
    val reviewerModelId: String? = null,
    val reviewerEndpointIdentity: String? = null,
    val reviewerConfigRevision: Long? = null,
    val reviewerCredentialRevision: Long = 0,
    val reviewerTimeoutMillis: Long = 30_000L,
    val recentRelevantDecisionsAndToolFacts: List<String> = emptyList(),
    val deterministicPolicyFindings: List<String> = emptyList(),
)

enum class ReviewerRisk {
    LOW,
    MEDIUM,
    HIGH,
}

data class ReviewerOutput(
    val requestId: String,
    val intentDigest: String,
    val decision: SecurityApprovalDecision,
    val risk: ReviewerRisk,
    val reasonCode: String,
    val reasonSummary: String = "",
)

fun interface ReviewerClient {
    fun review(snapshot: ReviewSnapshot): ReviewerOutput
}

/**
 * Owns approval routing, one-shot grant issuance and consumption. It is intentionally separate
 * from the audit sink: an approval is not a grant until the host has issued and consumed a grant.
 */
class SecurityApprovalCoordinator(
    private val humanChannel: HumanApprovalChannel,
    private val reviewerClient: ReviewerClient? = null,
    private val clock: SecurityClock = SecurityClock.system(),
    private val grantTtlMillis: Long = 30_000L,
) {
    private val grants = ConcurrentHashMap<String, OneShotGrant>()

    fun requestHuman(context: SecurityContext, intent: SensitiveIntent, approvalId: String): SecurityApprovalResult {
        val ticket = PendingSecurityApproval(
            requestId = approvalId,
            intent = intent,
            kind = ApprovalKind.HUMAN,
            createdAt = clock.now(),
            expiresAt = clock.now().plusMillis(120_000L),
        )
        return humanChannel.request(ticket)
    }

    fun requestReviewer(
        context: SecurityContext,
        intent: SensitiveIntent,
        reviewer: ReviewerConfig,
        approvalId: String,
        credentialTypeAndRevision: String? = null,
    ): SecurityApprovalResult {
        val client = reviewerClient
            ?: return SecurityApprovalResult(SecurityApprovalDecision.DENY, "REVIEWER_NOT_CONFIGURED")
        val snapshot = ReviewSnapshot(
            requestId = approvalId,
            intentDigest = intent.digest(),
            profileRevision = context.profile.profileRevision,
            policyEpoch = context.profile.policyEpoch,
            actorChain = context.principalChain,
            workspaceIdentity = context.workspaceId,
            runId = context.runId,
            callId = context.toolCallId,
            declaredUserGoalWithSource = intent.declaredPurpose,
            currentEnvelope = context.profile.accessEnvelope,
            normalizedOperation = intent.operation,
            actualTarget = intent.canonicalTarget?.canonical(),
            effectCategory = effectCategory(intent.operation),
            sanitizedArguments = intent.sanitizedArguments,
            payloadShape = intent.payloadShape,
            credentialTypeAndRevision = credentialTypeAndRevision,
            reviewerProviderId = reviewer.providerId,
            reviewerModelId = reviewer.modelId,
            reviewerEndpointIdentity = reviewer.endpointIdentity,
            reviewerConfigRevision = reviewer.revision,
            reviewerCredentialRevision = reviewer.credentialRevision,
            reviewerTimeoutMillis = reviewer.timeoutMillis,
            deterministicPolicyFindings = listOf("credential_operation_requires_approval"),
        )
        val output = try {
            client.review(snapshot)
        } catch (_: Throwable) {
            return if (reviewer.fallbackPolicy == ReviewerFallbackPolicy.ASK_USER) {
                SecurityApprovalResult(SecurityApprovalDecision.NEEDS_HUMAN, "REVIEW_UNAVAILABLE")
            } else {
                SecurityApprovalResult(SecurityApprovalDecision.DENY, "REVIEW_UNAVAILABLE")
            }
        }
        val validationError = validateReviewerOutput(output, approvalId, intent.digest())
        if (validationError != null) {
            return SecurityApprovalResult(SecurityApprovalDecision.DENY, validationError)
        }
        return SecurityApprovalResult(
            decision = output.decision,
            reasonCode = output.reasonCode.ifBlank { "REVIEW_${output.decision.name}" },
            reasonSummary = output.reasonSummary,
        )
    }

    fun issueGrant(
        context: SecurityContext,
        intent: SensitiveIntent,
        approvalId: String,
        reviewer: ReviewerConfig? = context.profile.reviewer,
    ): OneShotGrant {
        val now = clock.now()
        val grant = OneShotGrant(
            grantId = UUID.randomUUID().toString(),
            requestId = approvalId,
            intentDigest = intent.digest(),
            workspaceId = context.workspaceId,
            profileRevision = context.profile.profileRevision,
            policyEpoch = context.profile.policyEpoch,
            reviewerConfigRevision = reviewer?.revision,
            reviewerCredentialRevision = reviewer?.credentialRevision,
            nonce = UUID.randomUUID().toString(),
            issuedAt = now,
            expiresAt = now.plusMillis(grantTtlMillis),
        )
        grants[grant.grantId] = grant
        return grant
    }

    /** Atomic remove-and-check: duplicate or late consumers see false. */
    fun consume(context: SecurityContext, intent: SensitiveIntent, grant: OneShotGrant): Boolean {
        val stored = grants.remove(grant.grantId) ?: return false
        return stored.intentDigest == intent.digest() &&
            stored.workspaceId == context.workspaceId &&
            stored.profileRevision == context.profile.profileRevision &&
            stored.policyEpoch == context.profile.policyEpoch &&
            stored.reviewerConfigRevision == context.profile.reviewer?.revision &&
            stored.reviewerCredentialRevision == context.profile.reviewer?.credentialRevision &&
            stored.expiresAt.isAfter(clock.now()) &&
            grant.intentDigest == stored.intentDigest
    }

    fun revokeAll() {
        grants.clear()
    }

    fun revokeWorkspace(workspaceId: String) {
        grants.entries.removeIf { it.value.workspaceId == workspaceId }
    }

    private fun validateReviewerOutput(
        output: ReviewerOutput,
        expectedRequestId: String,
        expectedDigest: String,
    ): String? {
        if (output.requestId != expectedRequestId) return "REVIEW_REQUEST_MISMATCH"
        if (output.intentDigest != expectedDigest) return "REVIEW_INTENT_MISMATCH"
        if (output.reasonCode.isBlank() || output.reasonCode.length > 96) return "REVIEW_INVALID_REASON"
        if (output.reasonSummary.length > 280) return "REVIEW_INVALID_SUMMARY"
        return null
    }

    private fun effectCategory(operation: CredentialOperation): String = when (operation) {
        CredentialOperation.TOOL_EXECUTION -> "tool-execution"
        CredentialOperation.LIST -> "credential_metadata_list"
        CredentialOperation.READ_METADATA -> "credential_metadata_read"
        CredentialOperation.READ_PLAINTEXT -> "credential_plaintext_read"
        CredentialOperation.WRITE -> "credential_write"
        CredentialOperation.DELETE -> "credential_delete"
        CredentialOperation.USE -> "credential_network_use"
    }
}

data class OneShotGrant(
    val grantId: String,
    val requestId: String,
    val intentDigest: String,
    val workspaceId: String,
    val profileRevision: Long,
    val policyEpoch: Long,
    val reviewerConfigRevision: Long?,
    val nonce: String,
    val issuedAt: Instant,
    val expiresAt: Instant,
    val reviewerCredentialRevision: Long? = null,
)

class SecurityDeniedException(val reasonCode: String) : RuntimeException("Security operation denied: $reasonCode")
