package com.cordiact.core.vault

import com.cordiact.core.security.TargetDescriptor
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.time.Instant
import java.util.UUID

enum class CredentialType {
    API_KEY,
    BEARER_TOKEN,
    COOKIE_JAR,
    SSH_KEY,
    GENERIC_SECRET,
}

sealed interface CredentialScope {
    data class Workspace(val workspaceId: String) : CredentialScope {
        init {
            require(workspaceId.isNotBlank()) { "Workspace scope id must not be blank" }
        }
    }

    data object UserGlobal : CredentialScope
}

data class CredentialMetadata(
    val label: String,
    val notes: String = "",
    val accountHint: String = "",
    val targetConstraints: Map<String, String> = emptyMap(),
) {
    init {
        require(label.isNotBlank()) { "credential label must not be blank" }
        require(label.length <= 200) { "credential label is too long" }
        require(notes.length <= 2_000) { "credential notes are too long" }
        require(accountHint.length <= 200) { "accountHint is too long" }
    }
}

data class Cookie(
    val name: String,
    val value: String,
    val domain: String,
    val hostOnly: Boolean = true,
    val path: String = "/",
    val expiresAt: Instant? = null,
    val secure: Boolean = true,
    val httpOnly: Boolean = true,
    val sameSite: String = "Lax",
) {
    init {
        require(name.isNotBlank()) { "cookie name must not be blank" }
        require(domain.isNotBlank()) { "cookie domain must not be blank" }
        require(path.startsWith("/")) { "cookie path must start with /" }
    }
}

sealed interface CredentialPayload {
    data class Secret(val bytes: ByteArray) : CredentialPayload

    data class CookieJar(val cookies: List<Cookie>) : CredentialPayload

    data class SshKey(val privateKey: ByteArray, val passphrase: ByteArray? = null) : CredentialPayload
}

/** Binary codec, so labels and payload bytes are never implicitly converted through String. */
object CredentialPayloadCodec {
    fun encode(payload: CredentialPayload): ByteArray {
        val out = ByteArrayOutputStream()
        DataOutputStream(out).use { data ->
            when (payload) {
                is CredentialPayload.Secret -> {
                    data.writeByte(0)
                    data.writeBytesWithLimit(payload.bytes, MAX_SECRET_BYTES)
                }
                is CredentialPayload.CookieJar -> {
                    data.writeByte(1)
                    data.writeInt(payload.cookies.size)
                    payload.cookies.forEach { cookie ->
                        data.writeString(cookie.name)
                        data.writeString(cookie.value)
                        data.writeString(cookie.domain)
                        data.writeBoolean(cookie.hostOnly)
                        data.writeString(cookie.path)
                        data.writeNullableString(cookie.expiresAt?.toString())
                        data.writeBoolean(cookie.secure)
                        data.writeBoolean(cookie.httpOnly)
                        data.writeString(cookie.sameSite)
                    }
                }
                is CredentialPayload.SshKey -> {
                    data.writeByte(2)
                    data.writeBytesWithLimit(payload.privateKey, MAX_SECRET_BYTES)
                    data.writeBoolean(payload.passphrase != null)
                    payload.passphrase?.let { data.writeBytesWithLimit(it, MAX_SECRET_BYTES) }
                }
            }
        }
        return out.toByteArray()
    }

    fun decode(type: CredentialType, bytes: ByteArray): CredentialPayload {
        DataInputStream(ByteArrayInputStream(bytes)).use { data ->
            val tag = data.readUnsignedByte()
            val expected = when (type) {
                CredentialType.API_KEY, CredentialType.BEARER_TOKEN, CredentialType.GENERIC_SECRET -> 0
                CredentialType.COOKIE_JAR -> 1
                CredentialType.SSH_KEY -> 2
            }
            require(tag == expected) { "credential payload type mismatch" }
            return when (tag) {
                0 -> CredentialPayload.Secret(data.readBytesWithLimit(MAX_SECRET_BYTES))
                1 -> {
                    val count = data.readInt()
                    require(count in 0..1_000) { "too many cookies" }
                    val cookies = (0 until count).map {
                        Cookie(
                            name = data.readString(),
                            value = data.readString(),
                            domain = data.readString(),
                            hostOnly = data.readBoolean(),
                            path = data.readString(),
                            expiresAt = data.readNullableString()?.let(Instant::parse),
                            secure = data.readBoolean(),
                            httpOnly = data.readBoolean(),
                            sameSite = data.readString(),
                        )
                    }
                    CredentialPayload.CookieJar(cookies)
                }
                2 -> {
                    val privateKey = data.readBytesWithLimit(MAX_SECRET_BYTES)
                    val passphrase = if (data.readBoolean()) data.readBytesWithLimit(MAX_SECRET_BYTES) else null
                    CredentialPayload.SshKey(privateKey, passphrase)
                }
                else -> throw IllegalArgumentException("unknown credential payload tag: $tag")
            }
        }
    }

    fun shapeOf(payload: CredentialPayload): String = when (payload) {
        is CredentialPayload.Secret -> "secret:${payload.bytes.size}"
        is CredentialPayload.CookieJar -> "cookies:${payload.cookies.size}"
        is CredentialPayload.SshKey -> "ssh-key"
    }

    private fun DataOutputStream.writeString(value: String) =
        writeBytesWithLimit(value.toByteArray(Charsets.UTF_8), MAX_METADATA_BYTES)

    private fun DataInputStream.readString(): String = readBytesWithLimit(MAX_METADATA_BYTES).toString(Charsets.UTF_8)

    private fun DataOutputStream.writeNullableString(value: String?) {
        writeBoolean(value != null)
        if (value != null) writeString(value)
    }

    private fun DataInputStream.readNullableString(): String? =
        if (readBoolean()) readString() else null

    private fun DataOutputStream.writeBytesWithLimit(value: ByteArray, limit: Int) {
        require(value.size <= limit) { "credential field too large: ${value.size} > $limit" }
        writeInt(value.size)
        write(value)
    }

    private fun DataInputStream.readBytesWithLimit(limit: Int): ByteArray {
        val size = readInt()
        require(size in 0..limit) { "invalid credential field size: $size" }
        val value = ByteArray(size)
        readFully(value)
        return value
    }

    const val MAX_SECRET_BYTES = 64 * 1024
    const val MAX_METADATA_BYTES = 64 * 1024
}

object CredentialMetadataCodec {
    fun encode(metadata: CredentialMetadata): ByteArray {
        val out = ByteArrayOutputStream()
        DataOutputStream(out).use { data ->
            data.writeString(metadata.label)
            data.writeString(metadata.notes)
            data.writeString(metadata.accountHint)
            data.writeInt(metadata.targetConstraints.size)
            metadata.targetConstraints.toSortedMap().forEach { (key, value) ->
                data.writeString(key)
                data.writeString(value)
            }
        }
        return out.toByteArray()
    }

    fun decode(bytes: ByteArray): CredentialMetadata {
        DataInputStream(ByteArrayInputStream(bytes)).use { data ->
            val label = data.readString()
            val notes = data.readString()
            val accountHint = data.readString()
            val count = data.readInt()
            require(count in 0..256) { "too many target constraints" }
            val constraints = LinkedHashMap<String, String>()
            repeat(count) {
                constraints[data.readString()] = data.readString()
            }
            return CredentialMetadata(label, notes, accountHint, constraints)
        }
    }

    private fun DataOutputStream.writeString(value: String) {
        val bytes = value.toByteArray(Charsets.UTF_8)
        require(bytes.size <= CredentialPayloadCodec.MAX_METADATA_BYTES) { "metadata field too large" }
        writeInt(bytes.size)
        write(bytes)
    }

    private fun DataInputStream.readString(): String {
        val size = readInt()
        require(size in 0..CredentialPayloadCodec.MAX_METADATA_BYTES) { "invalid metadata field size" }
        val value = ByteArray(size)
        readFully(value)
        return value.toString(Charsets.UTF_8)
    }
}

data class EncryptedValue(
    val nonce: ByteArray,
    val ciphertext: ByteArray,
)

interface VaultCrypto {
    /** Version used for new writes. Old versions remain readable during rotation. */
    val currentKeyVersion: Int get() = 1

    fun encrypt(plaintext: ByteArray, aad: ByteArray, keyVersion: Int): EncryptedValue

    fun decrypt(value: EncryptedValue, aad: ByteArray, keyVersion: Int): ByteArray

    fun encrypt(plaintext: ByteArray, aad: ByteArray): EncryptedValue =
        encrypt(plaintext, aad, currentKeyVersion)

    fun decrypt(value: EncryptedValue, aad: ByteArray): ByteArray =
        decrypt(value, aad, currentKeyVersion)
}

class VaultCryptoException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

object CredentialAad {
    fun build(
        credentialId: String,
        scope: CredentialScope,
        schemaVersion: Int,
        keyVersion: Int,
        revision: Long,
        type: CredentialType,
    ): ByteArray {
        val scopeKey = when (scope) {
            is CredentialScope.Workspace -> "workspace:${scope.workspaceId}"
            CredentialScope.UserGlobal -> "global"
        }
        return "cordiact.vault|v1|$credentialId|$scopeKey|$schemaVersion|$keyVersion|$revision|${type.name}"
            .toByteArray(Charsets.UTF_8)
    }
}

data class StoredCredential(
    val id: String,
    val scope: CredentialScope,
    val type: CredentialType,
    val metadata: EncryptedValue,
    val payload: EncryptedValue,
    val revision: Long,
    val schemaVersion: Int = CURRENT_SCHEMA_VERSION,
    val keyVersion: Int = 1,
    val createdAt: Instant,
    val updatedAt: Instant,
    val expiresAt: Instant? = null,
    val disabledAt: Instant? = null,
) {
    init {
        require(id.isNotBlank()) { "credential id must not be blank" }
        require(revision >= 1) { "credential revision must be positive" }
        require(schemaVersion >= 1) { "schema version must be positive" }
        require(keyVersion >= 1) { "key version must be positive" }
    }

    companion object {
        const val CURRENT_SCHEMA_VERSION = 1
    }
}

data class CredentialSummary(
    val id: String,
    val scope: CredentialScope,
    val type: CredentialType,
    val label: String,
    val accountHint: String,
    val targetConstraints: Map<String, String>,
    val revision: Long,
    val expiresAt: Instant?,
    val disabled: Boolean,
)

data class CredentialDetails(
    val summary: CredentialSummary,
    val notes: String,
)

interface CredentialRepository {
    fun list(scope: CredentialScope): List<StoredCredential>
    fun find(id: String): StoredCredential?
    fun insert(record: StoredCredential): StoredCredential
    fun update(record: StoredCredential, expectedRevision: Long): StoredCredential
    fun delete(id: String, expectedRevision: Long): Boolean
    fun all(): List<StoredCredential>
}

class StaleRevisionException(expected: Long, actual: Long) :
    RuntimeException("credential revision changed: expected $expected, actual $actual")

class CredentialNotFoundException(id: String) : RuntimeException("credential not found: $id")

class InMemoryCredentialRepository : CredentialRepository {
    private val records = LinkedHashMap<String, StoredCredential>()

    @Synchronized
    override fun list(scope: CredentialScope): List<StoredCredential> =
        records.values.filter { it.scope == scope }

    @Synchronized
    override fun find(id: String): StoredCredential? = records[id]

    @Synchronized
    override fun insert(record: StoredCredential): StoredCredential {
        require(!records.containsKey(record.id)) { "credential already exists: ${record.id}" }
        records[record.id] = record
        return record
    }

    @Synchronized
    override fun update(record: StoredCredential, expectedRevision: Long): StoredCredential {
        val current = records[record.id] ?: throw CredentialNotFoundException(record.id)
        if (current.revision != expectedRevision) {
            throw StaleRevisionException(expectedRevision, current.revision)
        }
        records[record.id] = record
        return record
    }

    @Synchronized
    override fun delete(id: String, expectedRevision: Long): Boolean {
        val current = records[id] ?: return false
        if (current.revision != expectedRevision) {
            throw StaleRevisionException(expectedRevision, current.revision)
        }
        records.remove(id)
        return true
    }

    @Synchronized
    override fun all(): List<StoredCredential> = records.values.toList()
}

data class CredentialWriteRequest(
    val credentialId: String? = null,
    val expectedRevision: Long? = null,
    val type: CredentialType,
    val metadata: CredentialMetadata,
    val payload: CredentialPayload,
    val expiresAt: Instant? = null,
)

enum class CredentialAuthMode {
    AUTO,
    BEARER,
    HEADER,
    COOKIE,
}

data class CredentialUseRequest(
    val credentialId: String,
    val target: TargetDescriptor,
    val adapterId: String = "https_request",
    val authMode: CredentialAuthMode = CredentialAuthMode.AUTO,
    val headerName: String? = null,
    val body: String? = null,
    /** Filled by the vault service from the stored record; never trusted from the model. */
    val credentialType: CredentialType? = null,
)

data class CredentialUseResult(
    val statusCode: Int,
    val responseSummary: String,
    val responseBytes: ByteArray? = null,
)

fun interface ControlledHttpExecutor {
    fun execute(payload: CredentialPayload, request: CredentialUseRequest): CredentialUseResult
}

fun newCredentialId(): String = UUID.randomUUID().toString()
