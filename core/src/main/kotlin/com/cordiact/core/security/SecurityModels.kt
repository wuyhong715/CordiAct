package com.cordiact.core.security

import java.net.IDN
import java.net.URI
import java.security.MessageDigest
import java.time.Clock
import java.time.Instant
import java.util.Locale
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** User-visible permission presets. Internally this is split into an access envelope and an approval authority. */
enum class PermissionPreset {
    READ_ONLY,
    WORKSPACE_ONLY,
    FULL_ACCESS,
    AUTO_APPROVAL,
}

/** What the current task may touch. This is independent from who approves an operation. */
enum class AccessEnvelope {
    READ_ONLY,
    WORKSPACE_ONLY,
    WORKSPACE_AND_DEVICE_STORAGE,
}

/** Who is allowed to answer a sensitive-operation approval request. */
enum class ApprovalAuthority {
    HUMAN,
    EXTERNAL_REVIEW_MODEL,
    DETERMINISTIC_POLICY,
}

enum class ReviewerFallbackPolicy {
    STOP,
    ASK_USER,
}

/**
 * Configuration for the independent reviewer used by the AUTO_APPROVAL preset.
 *
 * The reviewer is a separate model identity from the executing agent. It is configured by the
 * user in a trusted settings surface and is never addressable through a tool argument.
 */
data class ReviewerConfig(
    val reviewerId: String,
    val revision: Long,
    val providerId: String,
    val modelId: String,
    val endpointIdentity: String,
    val policyTemplateVersion: String = "credential-review-v1",
    val timeoutMillis: Long = 30_000L,
    val maxInputTokens: Int = 4_000,
    val maxOutputTokens: Int = 512,
    val maxCallsPerRun: Int = 50,
    val costBudgetMicros: Long? = null,
    val fallbackPolicy: ReviewerFallbackPolicy = ReviewerFallbackPolicy.STOP,
    val credentialRevision: Long = 0,
) {
    init {
        require(reviewerId.isNotBlank()) { "reviewerId must not be blank" }
        require(revision >= 0) { "reviewer revision must not be negative" }
        require(providerId.isNotBlank()) { "reviewer providerId must not be blank" }
        require(modelId.isNotBlank()) { "reviewer modelId must not be blank" }
        require(endpointIdentity.isNotBlank()) { "reviewer endpointIdentity must not be blank" }
        require(timeoutMillis in 1_000L..300_000L) { "reviewer timeout out of range" }
    }
}

/**
 * The immutable security profile attached to one invocation by the host.
 *
 * A tool never supplies this object. The host resolves the workspace, preset, envelope, policy
 * epoch and reviewer configuration before the model can call a sensitive operation.
 */
data class EffectiveSecurityProfile(
    val preset: PermissionPreset,
    val accessEnvelope: AccessEnvelope,
    val authority: ApprovalAuthority,
    val workspaceId: String,
    val profileRevision: Long,
    val policyEpoch: Long,
    val reviewer: ReviewerConfig? = null,
) {
    init {
        require(workspaceId.isNotBlank()) { "workspaceId must not be blank" }
        require(profileRevision >= 0) { "profileRevision must not be negative" }
        require(policyEpoch >= 0) { "policyEpoch must not be negative" }
        when (preset) {
            PermissionPreset.READ_ONLY -> {
                require(accessEnvelope == AccessEnvelope.READ_ONLY) { "READ_ONLY must use the READ_ONLY envelope" }
                require(authority == ApprovalAuthority.HUMAN) { "READ_ONLY cannot delegate approval to a model" }
                require(reviewer == null) { "READ_ONLY must not carry a reviewer" }
            }
            PermissionPreset.WORKSPACE_ONLY -> {
                require(accessEnvelope == AccessEnvelope.WORKSPACE_ONLY) { "WORKSPACE_ONLY must use the WORKSPACE_ONLY envelope" }
                require(authority == ApprovalAuthority.HUMAN) { "WORKSPACE_ONLY cannot delegate approval to a model" }
                require(reviewer == null) { "WORKSPACE_ONLY must not carry a reviewer" }
            }
            PermissionPreset.FULL_ACCESS -> {
                require(accessEnvelope == AccessEnvelope.WORKSPACE_AND_DEVICE_STORAGE) {
                    "FULL_ACCESS must use the WORKSPACE_AND_DEVICE_STORAGE envelope"
                }
                require(authority == ApprovalAuthority.HUMAN) { "FULL_ACCESS cannot delegate approval to a model" }
                require(reviewer == null) { "FULL_ACCESS must not carry a reviewer" }
            }
            PermissionPreset.AUTO_APPROVAL -> {
                require(authority == ApprovalAuthority.EXTERNAL_REVIEW_MODEL) {
                    "AUTO_APPROVAL must use the external reviewer authority"
                }
                require(reviewer != null) { "AUTO_APPROVAL requires a configured reviewer" }
            }
        }
    }

    companion object {
        fun readOnly(workspaceId: String, profileRevision: Long = 1, policyEpoch: Long = 1) =
            EffectiveSecurityProfile(
                preset = PermissionPreset.READ_ONLY,
                accessEnvelope = AccessEnvelope.READ_ONLY,
                authority = ApprovalAuthority.HUMAN,
                workspaceId = workspaceId,
                profileRevision = profileRevision,
                policyEpoch = policyEpoch,
            )

        fun workspaceOnly(workspaceId: String, profileRevision: Long = 1, policyEpoch: Long = 1) =
            EffectiveSecurityProfile(
                preset = PermissionPreset.WORKSPACE_ONLY,
                accessEnvelope = AccessEnvelope.WORKSPACE_ONLY,
                authority = ApprovalAuthority.HUMAN,
                workspaceId = workspaceId,
                profileRevision = profileRevision,
                policyEpoch = policyEpoch,
            )

        fun fullAccess(workspaceId: String, profileRevision: Long = 1, policyEpoch: Long = 1) =
            EffectiveSecurityProfile(
                preset = PermissionPreset.FULL_ACCESS,
                accessEnvelope = AccessEnvelope.WORKSPACE_AND_DEVICE_STORAGE,
                authority = ApprovalAuthority.HUMAN,
                workspaceId = workspaceId,
                profileRevision = profileRevision,
                policyEpoch = policyEpoch,
            )

        fun autoApproval(
            workspaceId: String,
            baseEnvelope: AccessEnvelope,
            reviewer: ReviewerConfig,
            profileRevision: Long = 1,
            policyEpoch: Long = 1,
        ) = EffectiveSecurityProfile(
            preset = PermissionPreset.AUTO_APPROVAL,
            accessEnvelope = baseEnvelope,
            authority = ApprovalAuthority.EXTERNAL_REVIEW_MODEL,
            workspaceId = workspaceId,
            profileRevision = profileRevision,
            policyEpoch = policyEpoch,
            reviewer = reviewer,
        )
    }
}

enum class PrincipalType {
    MAIN_AGENT,
    SUB_AGENT,
    BACKGROUND_JOB,
    MCP_BRIDGE,
    COMMAND,
    USER,
}

data class Principal(val id: String, val type: PrincipalType = PrincipalType.MAIN_AGENT) {
    init {
        require(id.isNotBlank()) { "principal id must not be blank" }
    }
}

enum class InvocationSource {
    MAIN_AGENT,
    SUB_AGENT,
    BACKGROUND_JOB,
    MCP_BRIDGE,
    COMMAND,
}

/**
 * Host-issued context for one tool call. It is constructed from the real session/run/turn, not
 * from tool JSON. [allowedGlobalCredentialIds] is an explicit local sharing binding; a profile or
 * a reviewer can never populate it.
 */
data class SecurityContext(
    val workspaceId: String,
    val sessionId: String,
    val runId: String,
    val turnId: String,
    val toolCallId: String,
    val principalChain: List<Principal>,
    val profile: EffectiveSecurityProfile,
    val modelProviderId: String? = null,
    val modelEndpointHost: String? = null,
    val modelId: String? = null,
    val source: InvocationSource = InvocationSource.MAIN_AGENT,
    val allowedGlobalCredentialIds: Set<String> = emptySet(),
    val securitySettingsRevision: Long = 0,
) {
    init {
        require(workspaceId.isNotBlank()) { "workspaceId must not be blank" }
        require(sessionId.isNotBlank()) { "sessionId must not be blank" }
        require(runId.isNotBlank()) { "runId must not be blank" }
        require(turnId.isNotBlank()) { "turnId must not be blank" }
        require(toolCallId.isNotBlank()) { "toolCallId must not be blank" }
        require(principalChain.isNotEmpty()) { "principalChain must not be empty" }
        require(profile.workspaceId == workspaceId) { "profile belongs to another Workspace" }
    }
}

enum class CredentialOperation {
    LIST,
    READ_METADATA,
    READ_PLAINTEXT,
    TOOL_EXECUTION,
    WRITE,
    DELETE,
    USE,
}

sealed interface CredentialScopeRef {
    data class Workspace(val workspaceId: String) : CredentialScopeRef {
        init {
            require(workspaceId.isNotBlank()) { "Workspace scope id must not be blank" }
        }
    }

    data object UserGlobal : CredentialScopeRef
}

/**
 * Canonical network target. Approval binds this exact value; a redirect, scheme downgrade or
 * host/port/path change invalidates the grant.
 */
data class TargetDescriptor(
    val scheme: String,
    val host: String,
    val port: Int,
    val path: String,
    val query: String? = null,
    val method: String,
) {
    init {
        require(scheme == "https") { "P0 controlled use only supports https" }
        require(host.isNotBlank()) { "target host must not be blank" }
        require(port in 1..65_535) { "target port out of range" }
        require(method.isNotBlank()) { "target method must not be blank" }
    }

    fun origin(): String = "$scheme://$host:$port"

    fun canonical(): String = buildString {
        append(scheme.lowercase(Locale.ROOT))
        append("://")
        append(host.lowercase(Locale.ROOT))
        append(':')
        append(port)
        append(if (path.isBlank()) "/" else path)
        query?.takeIf { it.isNotBlank() }?.let { append('?'); append(it) }
        append(' ')
        append(method.uppercase(Locale.ROOT))
    }

    companion object {
        fun parseHttps(url: String, method: String = "GET"): TargetDescriptor {
            val uri = URI(url)
            require(uri.scheme.equals("https", ignoreCase = true)) { "target must be https" }
            require(uri.userInfo == null) { "credentials must not be placed in the URL" }
            val rawHost = uri.host ?: throw IllegalArgumentException("target host is missing")
            val host = IDN.toASCII(rawHost).lowercase(Locale.ROOT)
            val port = if (uri.port == -1) 443 else uri.port
            val path = uri.rawPath?.takeIf { it.isNotBlank() } ?: "/"
            return TargetDescriptor(
                scheme = "https",
                host = host,
                port = port,
                path = path,
                query = uri.rawQuery,
                method = method.uppercase(Locale.ROOT),
            )
        }
    }
}

data class ReceiverDescriptor(
    val providerId: String,
    val endpointHost: String,
    val modelId: String,
) {
    init {
        require(providerId.isNotBlank()) { "providerId must not be blank" }
        require(endpointHost.isNotBlank()) { "endpointHost must not be blank" }
        require(modelId.isNotBlank()) { "modelId must not be blank" }
    }

    fun sanitized(): String = "$providerId/$endpointHost/$modelId"
}

/**
 * Immutable description of one sensitive operation. The host freezes it before approval and
 * re-validates it at the execution point. It never carries the secret value itself.
 */
data class SensitiveIntent(
    val workspaceId: String,
    val operation: CredentialOperation,
    val scope: CredentialScopeRef,
    val credentialId: String? = null,
    val expectedRevision: Long? = null,
    val principalChain: List<Principal>,
    val sessionId: String,
    val runId: String,
    val turnId: String,
    val toolCallId: String,
    val effectiveReceiver: ReceiverDescriptor? = null,
    val canonicalTarget: TargetDescriptor? = null,
    val payloadCommitment: String,
    val policyEpoch: Long,
    val declaredPurpose: String = "",
    val sanitizedArguments: Map<String, String> = emptyMap(),
    val payloadShape: String? = null,
    val issuedAt: Instant = Instant.now(),
    val expiresAt: Instant = issuedAt.plusSeconds(120),
) {
    init {
        require(workspaceId.isNotBlank()) { "workspaceId must not be blank" }
        require(principalChain.isNotEmpty()) { "principalChain must not be empty" }
        require(payloadCommitment.isNotBlank()) { "payloadCommitment must not be blank" }
        require(declaredPurpose.length <= 500) { "declaredPurpose is too long" }
        require(expiresAt.isAfter(issuedAt)) { "intent expiry must be after issue time" }
    }

    /** Stable, secret-free digest used for approval and grant binding. */
    fun digest(): String = SecurityDigests.sha256Hex(
        buildString {
            append("v1|")
            append(workspaceId).append('|')
            append(operation.name).append('|')
            when (val value = scope) {
                is CredentialScopeRef.Workspace -> append("workspace:").append(value.workspaceId)
                CredentialScopeRef.UserGlobal -> append("global")
            }
            append('|')
            append(credentialId ?: "").append('|')
            append(expectedRevision?.toString() ?: "").append('|')
            append(principalChain.joinToString(",") { "${it.type.name}:${it.id}" }).append('|')
            append(sessionId).append('|')
            append(runId).append('|')
            append(turnId).append('|')
            append(toolCallId).append('|')
            append(effectiveReceiver?.sanitized() ?: "").append('|')
            append(canonicalTarget?.canonical() ?: "").append('|')
            append(payloadCommitment).append('|')
            append(policyEpoch).append('|')
            append(declaredPurpose).append('|')
            append(sanitizedArguments.toSortedMap().entries.joinToString(",") { "${it.key}=${it.value}" })
        },
    )
}

/** A short-lived plaintext result. Callers must consume it immediately and close it. */
class SensitivePayload private constructor(private val bytes: ByteArray) : AutoCloseable {
    fun <T> use(block: (ByteArray) -> T): T = block(bytes)

    fun copyBytes(): ByteArray = bytes.copyOf()

    override fun close() {
        bytes.fill(0)
    }

    override fun toString(): String = "SensitivePayload(redacted)"

    companion object {
        fun of(value: ByteArray): SensitivePayload = SensitivePayload(value.copyOf())
    }
}

object SecurityDigests {
    fun sha256Hex(value: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
        return digest.toHex()
    }

    fun hmacSha256Hex(key: ByteArray, value: String): String =
        hmacSha256Hex(key, value.toByteArray(Charsets.UTF_8))

    fun hmacSha256Hex(key: ByteArray, value: ByteArray): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(value).toHex()
    }

    private fun ByteArray.toHex(): String = joinToString("") { byte -> "%02x".format(byte) }
}

/** Runtime secret used to bind a sensitive payload without writing a low-entropy hash to audit. */
class IntentCommitment(private val key: ByteArray) {
    init {
        require(key.size >= 16) { "commitment key must be at least 16 bytes" }
    }

    fun commit(vararg fields: String): String =
        SecurityDigests.hmacSha256Hex(key, fields.joinToString("\u001f"))

    fun commitBytes(value: ByteArray): String =
        SecurityDigests.hmacSha256Hex(key, value)
}

/** Clock seam so tests can advance time deterministically. */
fun interface SecurityClock {
    fun now(): Instant

    companion object {
        fun system(): SecurityClock = SecurityClock { Instant.now() }

        fun fixed(clock: Clock): SecurityClock = SecurityClock { clock.instant() }
    }
}
