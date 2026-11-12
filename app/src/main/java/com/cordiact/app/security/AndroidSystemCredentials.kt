package com.cordiact.app.security

import android.content.Context
import android.util.Base64
import com.cordiact.core.vault.LegacyCredentialSource
import com.cordiact.core.vault.SystemCredentialStore
import java.security.KeyStore
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Single host credential path. SQLite and AES-GCM are shared with the vault, never with tools. */
class AndroidSystemCredentials private constructor(context: Context) {
    private val appContext = context.applicationContext
    private val database by lazy { AndroidSecurityDatabase(appContext, AndroidAuditSigner()) }
    private val store by lazy { SystemCredentialStore(database, AndroidVaultCrypto(appContext)) }

    @Synchronized fun has(kind: String, providerId: String): Boolean =
        database.readSystemCredential("$kind:$providerId") != null || legacy(kind, providerId).hasValue()

    @Synchronized fun metadata() = database.systemCredentialMetadata()
    @Synchronized fun clearExpected(kind: String, providerId: String, expectedRevision: Long) {
        require(kind in setOf("model", "search", "ssh", "mcp"))
        check(revision(kind, providerId) == expectedRevision) { "CREDENTIAL_REVISION_CONFLICT" }
        check(has(kind, providerId)) { "CREDENTIAL_NOT_FOUND" }
        set(kind, providerId, null)
    }
    fun revision(kind: String, providerId: String) = database.systemCredentialRevision("$kind:$providerId")

    @Synchronized fun read(kind: String, providerId: String): String? {
        val bytes = store.read("$kind:$providerId", legacy(kind, providerId)) ?: return null
        return try { database.markSystemCredentialUsed("$kind:$providerId"); bytes.toString(Charsets.UTF_8) } finally { bytes.fill(0) }
    }

    @Synchronized fun set(kind: String, providerId: String, value: String?) {
        val normalized = value?.trim()?.takeIf(String::isNotEmpty)
        if (normalized == null) {
            store.delete("$kind:$providerId", legacy(kind, providerId))
        } else {
            require(normalized.length <= 16 * 1024) { "API key is too long" }
            val bytes = normalized.toByteArray(Charsets.UTF_8)
            try { store.write("$kind:$providerId", bytes, legacy(kind, providerId)) }
            finally { bytes.fill(0) }
        }
    }

    private interface LegacySource : LegacyCredentialSource { fun hasValue(): Boolean }

    private fun legacy(kind: String, providerId: String): LegacySource {
        val digest = MessageDigest.getInstance("SHA-256").digest(providerId.toByteArray(Charsets.UTF_8))
            .take(16).joinToString("") { "%02x".format(it.toInt() and 0xff) }
        val search = kind == "search"
        require(search || kind == "model")
        val deepSeek = !search && providerId == "deepseek"
        val preferences = appContext.getSharedPreferences(if (search) "cordiact.search.credentials" else "model_credentials", Context.MODE_PRIVATE)
        val preferenceKey = when {
            search -> "search.$providerId"
            deepSeek -> "deepseek_api_key_ciphertext"
            else -> "provider_${digest}_ciphertext"
        }
        val alias = when {
            search -> "cordiact.search.keys"
            deepSeek -> "cordiact.deepseek.api-key.v1"
            else -> "cordiact.provider.$digest.v1"
        }
        return object : LegacySource {
            override fun hasValue() = preferences.contains(preferenceKey)
            override fun read(): ByteArray? {
                val encoded = preferences.getString(preferenceKey, null) ?: return null
                return try {
                    // Migration must never generate a missing key or hide a decryption failure.
                    val key = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.getKey(alias, null) as? SecretKey
                        ?: error("LEGACY_CREDENTIAL_KEY_MISSING")
                    val packed = Base64.decode(encoded, Base64.NO_WRAP)
                    require(packed.size > 12)
                    val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                    cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, packed.copyOfRange(0, 12)))
                    cipher.doFinal(packed.copyOfRange(12, packed.size))
                } catch (failure: Exception) { throw IllegalStateException("LEGACY_CREDENTIAL_MIGRATION_FAILED", failure) }
            }
            override fun clear() {
                if (preferences.contains(preferenceKey)) {
                    check(preferences.edit().remove(preferenceKey).commit()) { "LEGACY_CREDENTIAL_CLEANUP_FAILED" }
                }
                // Old aliases are deliberately retained: deleting unrelated Keystore data is not migration.
            }
        }
    }

    companion object {
        @Volatile private var instance: AndroidSystemCredentials? = null
        fun get(context: Context): AndroidSystemCredentials = instance ?: synchronized(this) {
            instance ?: AndroidSystemCredentials(context.applicationContext).also { instance = it }
        }
    }
}
