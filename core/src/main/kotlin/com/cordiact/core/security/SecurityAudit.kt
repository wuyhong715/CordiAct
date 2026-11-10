package com.cordiact.core.security

import java.time.Instant
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

enum class AuditCategory {
    CREDENTIAL,
    APPROVAL,
    SECURITY,
}

enum class AuditPhase {
    REQUESTED,
    POLICY_EVALUATED,
    APPROVAL_ASKED,
    APPROVAL_DECIDED,
    EXECUTION_STARTED,
    SUCCEEDED,
    FAILED,
    UNKNOWN,
}

enum class AuditOutcome {
    PENDING,
    ALLOWED,
    DENIED,
    CANCELLED,
    EXPIRED,
    SUCCEEDED,
    FAILED,
    UNKNOWN,
}

/**
 * Secret-free audit event content. Free text is intentionally absent: only bounded reason codes
 * and sanitized descriptors may be stored.
 */
data class AuditEventDraft(
    val category: AuditCategory,
    val phase: AuditPhase,
    val outcome: AuditOutcome = AuditOutcome.PENDING,
    val reasonCode: String = "",
    val correlationId: String,
    val invocationId: String? = null,
    val approvalId: String? = null,
    val attemptId: String? = null,
    val principalChain: List<Principal> = emptyList(),
    val workspaceId: String = "",
    val sessionId: String? = null,
    val runId: String? = null,
    val turnId: String? = null,
    val toolCallId: String? = null,
    val toolId: String? = null,
    val policyVersion: String? = null,
    val policyEpoch: Long? = null,
    val permissionPreset: PermissionPreset? = null,
    val baseEnvelope: AccessEnvelope? = null,
    val approvalAuthority: ApprovalAuthority? = null,
    val reviewerId: String? = null,
    val reviewerConfigRevision: Long? = null,
    val reviewRequestId: String? = null,
    val credentialOpaqueId: String? = null,
    val credentialRevision: Long? = null,
    val targetDescriptorSanitized: String? = null,
    val receiverDescriptorSanitized: String? = null,
) {
    init {
        require(correlationId.isNotBlank()) { "correlationId must not be blank" }
        require(reasonCode.length <= 96) { "reasonCode is too long" }
    }
}

data class AuditEvent(
    val eventId: String,
    val sequence: Long,
    val occurredAt: Instant,
    val bootInstance: String,
    val draft: AuditEventDraft,
    val prevMac: String?,
    val eventMac: String,
) {
    fun canonicalPayload(): String = buildString {
        append("v1").append('\u001f')
        append(eventId).append('\u001f')
        append(sequence).append('\u001f')
        append(occurredAt).append('\u001f')
        append(bootInstance).append('\u001f')
        append(draft.category.name).append('\u001f')
        append(draft.phase.name).append('\u001f')
        append(draft.outcome.name).append('\u001f')
        append(draft.reasonCode).append('\u001f')
        append(draft.correlationId).append('\u001f')
        append(draft.invocationId ?: "").append('\u001f')
        append(draft.approvalId ?: "").append('\u001f')
        append(draft.attemptId ?: "").append('\u001f')
        append(draft.principalChain.joinToString(",") { "${it.type.name}:${it.id}" }).append('\u001f')
        append(draft.workspaceId).append('\u001f')
        append(draft.sessionId ?: "").append('\u001f')
        append(draft.runId ?: "").append('\u001f')
        append(draft.turnId ?: "").append('\u001f')
        append(draft.toolCallId ?: "").append('\u001f')
        append(draft.toolId ?: "").append('\u001f')
        append(draft.policyVersion ?: "").append('\u001f')
        append(draft.policyEpoch?.toString() ?: "").append('\u001f')
        append(draft.permissionPreset?.name ?: "").append('\u001f')
        append(draft.baseEnvelope?.name ?: "").append('\u001f')
        append(draft.approvalAuthority?.name ?: "").append('\u001f')
        append(draft.reviewerId ?: "").append('\u001f')
        append(draft.reviewerConfigRevision?.toString() ?: "").append('\u001f')
        append(draft.reviewRequestId ?: "").append('\u001f')
        append(draft.credentialOpaqueId ?: "").append('\u001f')
        append(draft.credentialRevision?.toString() ?: "").append('\u001f')
        append(draft.targetDescriptorSanitized ?: "").append('\u001f')
        append(draft.receiverDescriptorSanitized ?: "")
    }
}

fun interface AuditSigner {
    /** [previousMac] is null for the genesis event. */
    fun sign(previousMac: String?, payload: String): String
}

class HmacAuditSigner(key: ByteArray) : AuditSigner {
    private val keyCopy = key.copyOf()

    init {
        require(keyCopy.size >= 16) { "audit HMAC key must be at least 16 bytes" }
    }

    override fun sign(previousMac: String?, payload: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(keyCopy, "HmacSHA256"))
        mac.update(previousMac?.toByteArray(Charsets.UTF_8) ?: ByteArray(0))
        mac.update(payload.toByteArray(Charsets.UTF_8))
        return mac.doFinal().joinToString("") { "%02x".format(it) }
    }
}

data class AuditFilter(
    val workspaceId: String? = null,
    val correlationId: String? = null,
    val category: AuditCategory? = null,
    val phase: AuditPhase? = null,
    val limit: Int = 200,
) {
    init {
        require(limit in 1..10_000) { "limit out of range" }
    }
}

data class AuditVerification(
    val valid: Boolean,
    val checkedEvents: Int,
    val brokenAtSequence: Long? = null,
)

interface AuditLog {
    /** Appends one event. Implementations must fail closed when durability or signing fails. */
    fun append(draft: AuditEventDraft): AuditEvent

    fun query(filter: AuditFilter = AuditFilter()): List<AuditEvent>

    fun verify(): AuditVerification
}

/**
 * Optional transactional sink. When the vault repository and the audit sink share one database,
 * [transaction] lets a local mutation and its success event commit together. A failure rolls the
 * mutation back instead of leaving an unaudited write.
 */
interface TransactionalAuditLog : AuditLog {
    fun <T> transaction(block: () -> T): T
}

class AuditUnavailableException(
    message: String,
    val operationExecuted: Boolean = false,
    cause: Throwable? = null,
) : RuntimeException(message, cause)

/**
 * In-memory reference implementation used by core tests and by hosts that have no durable store
 * yet. Production wiring must replace it with a transactional SQLite sink; the gate only depends
 * on this interface so that replacement cannot change approval semantics.
 */
class InMemoryAuditLog(
    private val signer: AuditSigner,
    private val clock: SecurityClock = SecurityClock.system(),
    private val bootInstance: String = UUID.randomUUID().toString(),
) : TransactionalAuditLog {
    private val lock = Any()
    private val events = ArrayList<AuditEvent>()
    private var nextSequence = 1L

    override fun append(draft: AuditEventDraft): AuditEvent {
        require(draft.workspaceId.isNotBlank()) { "workspaceId must not be blank" }
        synchronized(lock) {
            val eventId = UUID.randomUUID().toString()
            val occurredAt = clock.now()
            val previous = events.lastOrNull()?.eventMac
            val unsigned = AuditEvent(
                eventId = eventId,
                sequence = nextSequence,
                occurredAt = occurredAt,
                bootInstance = bootInstance,
                draft = draft,
                prevMac = previous,
                eventMac = "",
            )
            val mac = try {
                signer.sign(previous, unsigned.canonicalPayload())
            } catch (failure: Throwable) {
                throw AuditUnavailableException("AUDIT_SIGNING_FAILED", cause = failure)
            }
            val event = unsigned.copy(eventMac = mac)
            events += event
            nextSequence += 1
            return event
        }
    }

    override fun <T> transaction(block: () -> T): T = block()

    override fun query(filter: AuditFilter): List<AuditEvent> = synchronized(lock) {
        events.asSequence()
            .filter { filter.workspaceId == null || it.draft.workspaceId == filter.workspaceId }
            .filter { filter.correlationId == null || it.draft.correlationId == filter.correlationId }
            .filter { filter.category == null || it.draft.category == filter.category }
            .filter { filter.phase == null || it.draft.phase == filter.phase }
            .toList()
            .takeLast(filter.limit)
    }

    override fun verify(): AuditVerification = synchronized(lock) {
        var previous: String? = null
        events.forEach { event ->
            val expected = try {
                signer.sign(previous, event.copy(eventMac = "").canonicalPayload())
            } catch (_: Throwable) {
                return AuditVerification(false, events.size, event.sequence)
            }
            if (event.prevMac != previous || event.eventMac != expected) {
                return AuditVerification(false, events.size, event.sequence)
            }
            previous = event.eventMac
        }
        AuditVerification(true, events.size)
    }
}
