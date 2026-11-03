package com.cordiact.app.model

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.cordiact.model.providers.ApiKeyProvider
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** API key storage encrypted with a non-exportable Android Keystore AES key. */
class AndroidDeepSeekApiKeyStore(context: Context) : ApiKeyProvider {
    private val preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    @Synchronized
    override fun apiKey(): String? {
        val payload = preferences.getString(KEY_CIPHERTEXT, null) ?: return null
        return try {
            val packed = Base64.decode(payload, Base64.NO_WRAP)
            require(packed.size > IV_LENGTH)
            val iv = packed.copyOfRange(0, IV_LENGTH)
            val encrypted = packed.copyOfRange(IV_LENGTH, packed.size)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(TAG_BITS, iv))
            cipher.doFinal(encrypted).toString(Charsets.UTF_8)
        } catch (failure: Exception) {
            throw IllegalStateException("Stored DeepSeek API key cannot be decrypted", failure)
        }
    }

    @Synchronized
    fun setApiKey(value: String?) {
        val normalized = value?.trim()?.takeIf(String::isNotEmpty)
        if (normalized == null) {
            check(preferences.edit().remove(KEY_CIPHERTEXT).commit()) { "Could not clear DeepSeek API key" }
            return
        }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val encrypted = cipher.iv + cipher.doFinal(normalized.toByteArray(Charsets.UTF_8))
        val encoded = Base64.encodeToString(encrypted, Base64.NO_WRAP)
        check(preferences.edit().putString(KEY_CIPHERTEXT, encoded).commit()) { "Could not save DeepSeek API key" }
    }

    private fun secretKey(): SecretKey {
        val store = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (store.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            ).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build(),
        )
        return generator.generateKey()
    }

    private companion object {
        const val PREFERENCES = "model_credentials"
        const val KEY_CIPHERTEXT = "deepseek_api_key_ciphertext"
        const val KEY_ALIAS = "cordiact.deepseek.api-key.v1"
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_LENGTH = 12
        const val TAG_BITS = 128
    }
}
