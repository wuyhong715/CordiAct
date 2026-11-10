package com.cordiact.core.vault

import com.cordiact.core.security.CredentialOperation
import com.cordiact.core.security.CredentialScopeRef
import com.cordiact.core.security.IntentCommitment
import com.cordiact.core.security.ReceiverDescriptor
import com.cordiact.core.security.SecurityContext
import com.cordiact.core.security.SensitiveIntent
import com.cordiact.core.security.SensitiveOperationGate
import com.cordiact.core.security.SensitivePayload
import com.cordiact.core.security.TargetDescriptor

class ControlledUseUnavailableException(message: String) : RuntimeException(message)

class VaultCorruptedException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

/**
 * Credential domain service. Every public method builds an immutable [SensitiveIntent] and calls
 * the host [SensitiveOperationGate] before touching plaintext or the encrypted repository.
 *
 * The service never returns a raw value through a plain String. Callers that need plaintext get a
 * [SensitivePayload] that they must consume immediately and close.
 */
class CredentialVaultService(
    private val repository: CredentialRepository,
    private val crypto: VaultCrypto,
    private val gate: SensitiveOperationGate,
    private val commitment: IntentCommitment,
    private val clock: com.cordiact.core.security.SecurityClock = com.cordiact.core.security.SecurityClock.system(),
    private val idGenerator: () -> String = ::newCredentialId,
    private val httpExecutor: ControlledHttpExecutor? = null,
) {
    fun list(
        context: SecurityContext,
        query: String? = null,
        type: CredentialType? = null,
        limit: Int = 20,
        global: Boolean = false,
        purpose: String = "List credential metadata",
    ): List<CredentialSummary> {
        require(limit in 1..200) { "limit out of range" }
        val scope = scopeFor(context, global)
        val intent = intent(
            context = context,
            operation = CredentialOperation.LIST,
            scope = scope,
            declaredPurpose = purpose,
            payloadCommitment = commitment.commit(
                "list",
                context.workspaceId,
                scopeKey(scope),
                query ?: "",
                type?.name ?: "",
                limit.toString(),
            ),
            sanitizedArguments = buildMap {
                query?.let { put("query", it.take(100)) }
                type?.let { put("type", it.name) }
                put("limit", limit.toString())
            },
        )
        return gate.run(context, intent) {
            repository.list(scope)
                .asSequence()
                .filter { type == null || it.type == type }
                .filter { record ->
                    if (query.isNullOrBlank()) true else {
                        val metadata = decryptMetadata(record)
                        metadata.label.contains(query, ignoreCase = true) ||
                            metadata.accountHint.contains(query, ignoreCase = true)
                    }
                }
                .take(limit)
                .map { record -> summaryOf(record, decryptMetadata(record)) }
                .toList()
        }
    }

    fun readMetadata(
        context: SecurityContext,
        credentialId: String,
        global: Boolean = false,
        purpose: String = "Read credential metadata",
    ): CredentialDetails {
        val scope = scopeFor(context, global)
        val intent = intent(
            context = context,
            operation = CredentialOperation.READ_METADATA,
            scope = scope,
            credentialId = credentialId,
            declaredPurpose = purpose,
            payloadCommitment = commitment.commit("read_metadata", context.workspaceId, scopeKey(scope), credentialId),
            sanitizedArguments = mapOf("credentialId" to credentialId),
        )
        return gate.run(context, intent) {
            val record = requireRecord(credentialId, scope)
            val metadata = decryptMetadata(record)
            CredentialDetails(summaryOf(record, metadata), metadata.notes)
        }
    }

    fun readPlaintext(
        context: SecurityContext,
        credentialId: String,
        global: Boolean = false,
        purpose: String = "Read credential plaintext",
    ): SensitivePayload {
        val scope = scopeFor(context, global)
        val approvedRecord = requireRecord(credentialId, scope)
        val intent = intent(
            context = context,
            operation = CredentialOperation.READ_PLAINTEXT,
            scope = scope,
            credentialId = credentialId,
            expectedRevision = approvedRecord.revision,
            declaredPurpose = purpose,
            payloadCommitment = commitment.commit("read_plaintext", context.workspaceId, scopeKey(scope), credentialId),
            sanitizedArguments = mapOf("credentialId" to credentialId),
        )
        return gate.run(context, intent) {
            val record = requireRecord(credentialId, scope)
            check(record.revision == intent.expectedRevision) { "STALE_REVISION" }
            val payload = decryptPayload(record)
            SensitivePayload.of(plaintextBytes(payload))
        }
    }

    fun write(
        context: SecurityContext,
        request: CredentialWriteRequest,
        global: Boolean = false,
        purpose: String? = null,
    ): CredentialSummary {
        val scope = scopeFor(context, global)
        validatePayloadType(request.type, request.payload)
        val intent = intent(
            context = context,
            operation = CredentialOperation.WRITE,
            scope = scope,
            credentialId = request.credentialId,
            expectedRevision = request.expectedRevision,
            declaredPurpose = purpose ?: if (request.credentialId == null) "Create credential" else "Update credential",
            payloadCommitment = commitment.commitBytes(
                CredentialMetadataCodec.encode(request.metadata) + CredentialPayloadCodec.encode(request.payload),
            ),
            sanitizedArguments = buildMap {
                request.credentialId?.let { put("credentialId", it) }
                put("type", request.type.name)
                put("label", request.metadata.label)
            },
            payloadShape = CredentialPayloadCodec.shapeOf(request.payload),
        )
        return gate.run(context, intent) {
            if (request.credentialId == null) {
                if (request.expectedRevision != null) throw IllegalArgumentException("create must not bind a revision")
                createRecord(scope, request)
            } else {
                updateRecord(scope, request)
            }
        }
    }

    fun delete(
        context: SecurityContext,
        credentialId: String,
        expectedRevision: Long,
        global: Boolean = false,
        purpose: String = "Delete credential",
    ): Boolean {
        val scope = scopeFor(context, global)
        val intent = intent(
            context = context,
            operation = CredentialOperation.DELETE,
            scope = scope,
            credentialId = credentialId,
            expectedRevision = expectedRevision,
            declaredPurpose = purpose,
            payloadCommitment = commitment.commit(
                "delete",
                context.workspaceId,
                scopeKey(scope),
                credentialId,
                expectedRevision.toString(),
            ),
            sanitizedArguments = mapOf("credentialId" to credentialId, "expectedRevision" to expectedRevision.toString()),
        )
        return gate.run(context, intent) {
            val record = repository.find(credentialId) ?: return@run false
            if (record.scope != scope) return@run false
            repository.delete(credentialId, expectedRevision)
        }
    }

    fun use(
        context: SecurityContext,
        request: CredentialUseRequest,
        global: Boolean = false,
        purpose: String = "Use credential through the HTTPS adapter",
    ): CredentialUseResult {
        require(request.adapterId == "https_request") { "unsupported credential adapter: ${request.adapterId}" }
        val executor = httpExecutor ?: throw ControlledUseUnavailableException("HTTPS controlled-use adapter is not configured")
        val scope = scopeFor(context, global)
        val approvedRecord = requireRecord(request.credentialId, scope)
        val intent = intent(
            context = context,
            operation = CredentialOperation.USE,
            scope = scope,
            credentialId = request.credentialId,
            expectedRevision = approvedRecord.revision,
            declaredPurpose = purpose,
            canonicalTarget = request.target,
            payloadCommitment = commitment.commit(
                "use",
                context.workspaceId,
                scopeKey(scope),
                request.credentialId,
                request.target.canonical(),
                request.adapterId,
                request.authMode.name,
                request.headerName ?: "",
                request.body?.let { commitment.commitBytes(it.toByteArray(Charsets.UTF_8)) } ?: "",
            ),
            sanitizedArguments = buildMap {
                put("credentialId", request.credentialId)
                put("adapter", request.adapterId)
                put("target", request.target.canonical())
                put("authMode", request.authMode.name)
                request.headerName?.let { put("headerName", it) }
            },
            payloadShape = "controlled-use",
        )
        // A network send is not rollbackable; record EXECUTION_STARTED before it and let the
        // terminal audit failure surface as UNKNOWN instead of pretending the request was undone.
        return gate.run(context, intent, transactional = false) {
            val record = requireRecord(request.credentialId, scope)
            check(record.revision == intent.expectedRevision) { "STALE_REVISION" }
            val payload = decryptPayload(record)
            executor.execute(payload, request.copy(credentialType = record.type))
        }
    }

    private fun createRecord(scope: CredentialScope, request: CredentialWriteRequest): CredentialSummary {
        val id = idGenerator()
        val now = clock.now()
        val revision = 1L
        val schemaVersion = StoredCredential.CURRENT_SCHEMA_VERSION
        val keyVersion = crypto.currentKeyVersion
        val metadata = crypto.encrypt(
            CredentialMetadataCodec.encode(request.metadata),
            CredentialAad.build(id, scope, schemaVersion, keyVersion, revision, request.type),
            keyVersion,
        )
        val payload = crypto.encrypt(
            CredentialPayloadCodec.encode(request.payload),
            CredentialAad.build(id, scope, schemaVersion, keyVersion, revision, request.type),
            keyVersion,
        )
        val record = StoredCredential(
            id = id,
            scope = scope,
            type = request.type,
            metadata = metadata,
            payload = payload,
            revision = revision,
            schemaVersion = schemaVersion,
            keyVersion = keyVersion,
            createdAt = now,
            updatedAt = now,
            expiresAt = request.expiresAt,
        )
        repository.insert(record)
        return summaryOf(record, request.metadata)
    }

    private fun updateRecord(scope: CredentialScope, request: CredentialWriteRequest): CredentialSummary {
        val id = request.credentialId ?: error("update requires credentialId")
        val existing = requireRecord(id, scope)
        val expected = request.expectedRevision ?: throw IllegalArgumentException("update requires expectedRevision")
        if (existing.revision != expected) throw StaleRevisionException(expected, existing.revision)
        val nextRevision = existing.revision + 1
        val metadata = crypto.encrypt(
            CredentialMetadataCodec.encode(request.metadata),
            CredentialAad.build(id, scope, existing.schemaVersion, existing.keyVersion, nextRevision, request.type),
            existing.keyVersion,
        )
        val payload = crypto.encrypt(
            CredentialPayloadCodec.encode(request.payload),
            CredentialAad.build(id, scope, existing.schemaVersion, existing.keyVersion, nextRevision, request.type),
            existing.keyVersion,
        )
        val updated = existing.copy(
            type = request.type,
            metadata = metadata,
            payload = payload,
            revision = nextRevision,
            updatedAt = clock.now(),
            expiresAt = request.expiresAt,
        )
        repository.update(updated, expected)
        return summaryOf(updated, request.metadata)
    }

    private fun requireRecord(id: String, scope: CredentialScope): StoredCredential {
        val record = repository.find(id) ?: throw CredentialNotFoundException(id)
        if (record.scope != scope) throw CredentialNotFoundException(id)
        return record
    }

    private fun decryptMetadata(record: StoredCredential): CredentialMetadata = try {
        val aad = CredentialAad.build(
            record.id,
            record.scope,
            record.schemaVersion,
            record.keyVersion,
            record.revision,
            record.type,
        )
        CredentialMetadataCodec.decode(crypto.decrypt(record.metadata, aad, record.keyVersion))
    } catch (failure: Throwable) {
        throw VaultCorruptedException("credential metadata could not be decrypted", failure)
    }

    private fun decryptPayload(record: StoredCredential): CredentialPayload = try {
        val aad = CredentialAad.build(
            record.id,
            record.scope,
            record.schemaVersion,
            record.keyVersion,
            record.revision,
            record.type,
        )
        CredentialPayloadCodec.decode(record.type, crypto.decrypt(record.payload, aad, record.keyVersion))
    } catch (failure: Throwable) {
        throw VaultCorruptedException("credential payload could not be decrypted", failure)
    }

    private fun summaryOf(record: StoredCredential, metadata: CredentialMetadata): CredentialSummary =
        CredentialSummary(
            id = record.id,
            scope = record.scope,
            type = record.type,
            label = metadata.label,
            accountHint = metadata.accountHint,
            targetConstraints = metadata.targetConstraints,
            revision = record.revision,
            expiresAt = record.expiresAt,
            disabled = record.disabledAt != null,
        )

    private fun validatePayloadType(type: CredentialType, payload: CredentialPayload) {
        val valid = when (type) {
            CredentialType.API_KEY, CredentialType.BEARER_TOKEN, CredentialType.GENERIC_SECRET ->
                payload is CredentialPayload.Secret
            CredentialType.COOKIE_JAR -> payload is CredentialPayload.CookieJar
            CredentialType.SSH_KEY -> payload is CredentialPayload.SshKey
        }
        require(valid) { "payload does not match credential type $type" }
    }

    private fun plaintextBytes(payload: CredentialPayload): ByteArray = when (payload) {
        is CredentialPayload.Secret -> payload.bytes.copyOf()
        is CredentialPayload.CookieJar -> payload.cookies
            .joinToString("; ") { "${it.name}=${it.value}" }
            .toByteArray(Charsets.UTF_8)
        is CredentialPayload.SshKey -> payload.privateKey.copyOf()
    }

    private fun scopeFor(context: SecurityContext, global: Boolean): CredentialScope =
        if (global) CredentialScope.UserGlobal else CredentialScope.Workspace(context.workspaceId)

    private fun scopeKey(scope: CredentialScope): String = when (scope) {
        is CredentialScope.Workspace -> "workspace:${scope.workspaceId}"
        CredentialScope.UserGlobal -> "global"
    }

    private fun intent(
        context: SecurityContext,
        operation: CredentialOperation,
        scope: CredentialScope,
        credentialId: String? = null,
        expectedRevision: Long? = null,
        canonicalTarget: TargetDescriptor? = null,
        payloadCommitment: String,
        declaredPurpose: String,
        sanitizedArguments: Map<String, String>,
        payloadShape: String? = null,
    ): SensitiveIntent = SensitiveIntent(
        workspaceId = context.workspaceId,
        operation = operation,
        scope = when (scope) {
            is CredentialScope.Workspace -> CredentialScopeRef.Workspace(scope.workspaceId)
            CredentialScope.UserGlobal -> CredentialScopeRef.UserGlobal
        },
        credentialId = credentialId,
        expectedRevision = expectedRevision,
        principalChain = context.principalChain,
        sessionId = context.sessionId,
        runId = context.runId,
        turnId = context.turnId,
        toolCallId = context.toolCallId,
        effectiveReceiver = receiverOf(context),
        canonicalTarget = canonicalTarget,
        payloadCommitment = payloadCommitment,
        policyEpoch = context.profile.policyEpoch,
        declaredPurpose = declaredPurpose,
        sanitizedArguments = sanitizedArguments,
        payloadShape = payloadShape,
        issuedAt = clock.now(),
        expiresAt = clock.now().plusSeconds(120),
    )

    private fun receiverOf(context: SecurityContext): ReceiverDescriptor? {
        val provider = context.modelProviderId ?: return null
        val model = context.modelId ?: return null
        return ReceiverDescriptor(provider, context.modelEndpointHost ?: "configured-endpoint", model)
    }
}

/** A deliberately tiny in-memory crypto implementation for core tests only. */
class TestVaultCrypto(
    private val key: ByteArray = ByteArray(32) { 7 },
    override val currentKeyVersion: Int = 1,
) : VaultCrypto {
    override fun encrypt(plaintext: ByteArray, aad: ByteArray, keyVersion: Int): EncryptedValue {
        val nonce = ByteArray(12) { (it + plaintext.size + aad.size + keyVersion).toByte() }
        val cipher = plaintext.copyOf()
        val versionKey = key.copyOf().also { bytes -> bytes[0] = (bytes[0].toInt() xor keyVersion).toByte() }
        cipher.indices.forEach { index -> cipher[index] = (cipher[index].toInt() xor versionKey[index % versionKey.size].toInt()).toByte() }
        return EncryptedValue(nonce, cipher)
    }

    override fun decrypt(value: EncryptedValue, aad: ByteArray, keyVersion: Int): ByteArray {
        val plain = value.ciphertext.copyOf()
        val versionKey = key.copyOf().also { bytes -> bytes[0] = (bytes[0].toInt() xor keyVersion).toByte() }
        plain.indices.forEach { index -> plain[index] = (plain[index].toInt() xor versionKey[index % versionKey.size].toInt()).toByte() }
        return plain
    }
}
