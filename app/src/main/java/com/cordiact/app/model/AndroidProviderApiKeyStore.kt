package com.cordiact.app.model

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.cordiact.model.providers.ApiKeyProvider
import java.security.KeyStore
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Stores one API key per provider, encrypted with a non-exportable Android Keystore key. */
class AndroidProviderApiKeyStore(context: Context) {
    private val preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    fun provider(providerId: String): ApiKeyProvider {
        require(providerId.matches(Regex("[a-z0-9][a-z0-9._-]{1,127}"))) { "Invalid provider id" }
        return ApiKeyProvider { read(providerId) }
    }

    @Synchronized
    fun read(providerId: String): String? {
        val payload = preferences.getString(preferenceKey(providerId), null) ?: return null
        return try {
            val packed = Base64.decode(payload, Base64.NO_WRAP)
            require(packed.size > IV_LENGTH)
            val iv = packed.copyOfRange(0, IV_LENGTH)
            val encrypted = packed.copyOfRange(IV_LENGTH, packed.size)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, secretKey(keyAlias(providerId)), GCMParameterSpec(TAG_BITS, iv))
            cipher.doFinal(encrypted).toString(Charsets.UTF_8)
        } catch (_: Exception) {
            throw IllegalStateException("Stored provider API key cannot be decrypted")
        }
    }

    @Synchronized
    fun clear(providerId: String) {
        require(providerId.matches(Regex("[a-z0-9][a-z0-9._-]{1,127}"))) { "Invalid provider id" }
        check(preferences.edit().remove(preferenceKey(providerId)).commit()) { "Could not clear provider API key" }
        if (providerId != DEEPSEEK_PROVIDER_ID) {
            val store = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
            if (store.containsAlias(keyAlias(providerId))) store.deleteEntry(keyAlias(providerId))
        }
    }

    @Synchronized
    fun set(providerId: String, value: String?) {
        require(providerId.matches(Regex("[a-z0-9][a-z0-9._-]{1,127}"))) { "Invalid provider id" }
        val prefKey = preferenceKey(providerId)
        val normalized = value?.trim()?.takeIf(String::isNotEmpty)
        if (normalized == null) {
            clear(providerId)
            return
        }
        require(normalized.length <= MAX_API_KEY_CHARS) { "Provider API key is too long" }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey(keyAlias(providerId)))
        val encrypted = cipher.iv + cipher.doFinal(normalized.toByteArray(Charsets.UTF_8))
        check(preferences.edit().putString(prefKey, Base64.encodeToString(encrypted, Base64.NO_WRAP)).commit()) {
            "Could not save provider API key"
        }
    }

    private fun secretKey(alias: String): SecretKey {
        val store = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build(),
        )
        return generator.generateKey()
    }

    private fun preferenceKey(providerId: String): String = when (providerId) {
        DEEPSEEK_PROVIDER_ID -> LEGACY_DEEPSEEK_PREFERENCE_KEY
        else -> "provider_${digest(providerId)}_ciphertext"
    }

    private fun keyAlias(providerId: String): String = when (providerId) {
        DEEPSEEK_PROVIDER_ID -> LEGACY_DEEPSEEK_KEY_ALIAS
        else -> "cordiact.provider.${digest(providerId)}.v1"
    }

    private fun digest(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8)).take(16).joinToString("") { "%02x".format(it.toInt() and 0xff) }

    private companion object {
        const val MAX_API_KEY_CHARS = 16 * 1024
        const val PREFERENCES = "model_credentials"
        const val DEEPSEEK_PROVIDER_ID = "deepseek"
        const val LEGACY_DEEPSEEK_PREFERENCE_KEY = "deepseek_api_key_ciphertext"
        const val LEGACY_DEEPSEEK_KEY_ALIAS = "cordiact.deepseek.api-key.v1"
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_LENGTH = 12
        const val TAG_BITS = 128
    }
}
