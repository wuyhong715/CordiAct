package com.cordiact.app.search

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Search provider credentials.
 *
 * Same shape as the model provider key store: AES/GCM with a non-exportable
 * Keystore key, ciphertext in SharedPreferences. A search key is never written
 * to the database, the Workspace, or a log.
 */
class AndroidSearchApiKeyStore(context: Context) {
    private val preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
    private val lock = Any()

    /**
     * Opened lazily: constructing the store must not touch AndroidKeyStore, or a
     * JVM-only unit test that merely wires the catalog would fail on a platform
     * that has no such provider.
     */
    private val keyStore: KeyStore by lazy { KeyStore.getInstance(ANDROID_KEY_STORE).apply { load(null) } }

    fun get(providerId: String): String? = runCatching { read(providerId) }.getOrNull()

    private fun read(providerId: String): String? = synchronized(lock) {
        val encoded = preferences.getString(key(providerId), null) ?: return null
        runCatching {
            val bytes = Base64.decode(encoded, Base64.NO_WRAP)
            if (bytes.size <= IV_LENGTH) return null
            val iv = bytes.copyOfRange(0, IV_LENGTH)
            val cipherText = bytes.copyOfRange(IV_LENGTH, bytes.size)
            val cipher = Cipher.getInstance(TRANSFORMATION).apply {
                init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(TAG_BITS, iv))
            }
            cipher.doFinal(cipherText).toString(Charsets.UTF_8)
        }.getOrNull()
    }

    /** Throws when the platform keystore is unavailable: a silent failure would report success. */
    fun put(providerId: String, apiKey: String) = synchronized(lock) {
        val cipher = Cipher.getInstance(TRANSFORMATION).apply {
            init(Cipher.ENCRYPT_MODE, secretKey())
        }
        val cipherText = cipher.doFinal(apiKey.toByteArray(Charsets.UTF_8))
        val combined = cipher.iv + cipherText
        preferences.edit().putString(key(providerId), Base64.encodeToString(combined, Base64.NO_WRAP)).apply()
    }

    fun remove(providerId: String) = synchronized(lock) {
        preferences.edit().remove(key(providerId)).apply()
    }

    fun has(providerId: String): Boolean = preferences.contains(key(providerId))

    private fun secretKey(): SecretKey {
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEY_STORE)
        generator.init(
            KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build(),
        )
        return generator.generateKey()
    }

    private fun key(providerId: String) = "search.$providerId"

    private companion object {
        const val PREFERENCES = "cordiact.search.credentials"
        const val ANDROID_KEY_STORE = "AndroidKeyStore"
        const val KEY_ALIAS = "cordiact.search.keys"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_LENGTH = 12
        const val TAG_BITS = 128
    }
}
