package com.cordiact.core.vault

/** Host-only credentials. Not a CredentialScope and never exposed by credential tools or exports. */
interface SystemCredentialRepository {
    fun readSystemCredential(id: String): SystemCredentialRecord?
    fun writeSystemCredential(record: SystemCredentialRecord)
    fun deleteSystemCredential(id: String)
}

data class SystemCredentialRecord(val id: String, val encrypted: EncryptedValue, val keyVersion: Int)

/** Read-only compatibility source. No new credential may ever be written to it. */
interface LegacyCredentialSource {
    fun read(): ByteArray?
    fun clear()
}

/** Same vault database and crypto as user credentials, but a separate host-only namespace. */
class SystemCredentialStore(private val repository: SystemCredentialRepository, private val crypto: VaultCrypto) {
    @Synchronized
    fun read(id: String, legacy: LegacyCredentialSource? = null): ByteArray? {
        validate(id)
        repository.readSystemCredential(id)?.let {
            check(it.id == id) { "SYSTEM_CREDENTIAL_ID_MISMATCH" }
            val plaintext = decrypt(it)
            try { legacy?.clear() } catch (failure: Exception) { plaintext.fill(0); throw failure }
            return plaintext
        }
        val old = legacy?.read() ?: return null
        try {
            write(id, old)
            // Migration is restart-safe: the legacy copy is removed only after decrypt verification.
            legacy.clear()
            return old.copyOf()
        } finally { old.fill(0) }
    }

    @Synchronized
    fun write(id: String, value: ByteArray, legacy: LegacyCredentialSource? = null) {
        validate(id)
        require(value.isNotEmpty() && value.size <= 64 * 1024) { "Invalid system credential size" }
        val version = crypto.currentKeyVersion
        val record = SystemCredentialRecord(id, crypto.encrypt(value, aad(id), version), version)
        repository.writeSystemCredential(record)
        val saved = repository.readSystemCredential(id) ?: error("SYSTEM_CREDENTIAL_WRITE_FAILED")
        check(saved.id == id) { "SYSTEM_CREDENTIAL_ID_MISMATCH" }
        val verified = decrypt(saved)
        try { check(value.contentEquals(verified)) { "SYSTEM_CREDENTIAL_VERIFY_FAILED" } }
        finally { verified.fill(0) }
        legacy?.clear()
    }

    @Synchronized
    fun delete(id: String, legacy: LegacyCredentialSource? = null) {
        validate(id)
        // Clear the source first: a failed delete must never resurrect the legacy credential.
        legacy?.clear()
        repository.deleteSystemCredential(id)
    }

    private fun decrypt(record: SystemCredentialRecord) = crypto.decrypt(record.encrypted, aad(record.id), record.keyVersion)
    private fun aad(id: String) = "cordiact:system-credential:v1:$id".toByteArray(Charsets.UTF_8)
    private fun validate(id: String) { require(id.matches(Regex("(model|search|ssh|mcp):[a-zA-Z0-9._-]{1,128}"))) { "Invalid system credential id" } }
}
