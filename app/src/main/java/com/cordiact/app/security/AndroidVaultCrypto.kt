package com.cordiact.app.security

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import com.cordiact.core.vault.EncryptedValue
import com.cordiact.core.vault.VaultCrypto
import com.cordiact.core.vault.VaultCryptoException
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Android Keystore-backed AES-256-GCM implementation.
 *
 * The key is non-exportable. Every encryption uses a platform-generated random IV; AAD is supplied
 * by the vault domain and binds the ciphertext to its credential id, scope, revision and type.
 */
class AndroidVaultCrypto(
    context: Context,
    private val keyAlias: String = DEFAULT_KEY_ALIAS,
    override var currentKeyVersion: Int = 1,
) : VaultCrypto {
    private val appContext = context.applicationContext

    override fun encrypt(plaintext: ByteArray, aad: ByteArray, keyVersion: Int): EncryptedValue {
        return try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, secretKey(keyVersion))
            cipher.updateAAD(aad)
            val ciphertext = cipher.doFinal(plaintext)
            EncryptedValue(nonce = cipher.iv.copyOf(), ciphertext = ciphertext)
        } catch (failure: Throwable) {
            throw VaultCryptoException("VAULT_ENCRYPT_FAILED", failure)
        }
    }

    override fun decrypt(value: EncryptedValue, aad: ByteArray, keyVersion: Int): ByteArray {
        return try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(
                Cipher.DECRYPT_MODE,
                secretKey(keyVersion),
                GCMParameterSpec(TAG_BITS, value.nonce),
            )
            cipher.updateAAD(aad)
            cipher.doFinal(value.ciphertext)
        } catch (failure: KeyPermanentlyInvalidatedException) {
            throw VaultCryptoException("VAULT_KEY_INVALIDATED", failure)
        } catch (failure: Throwable) {
            throw VaultCryptoException("VAULT_DECRYPT_FAILED", failure)
        }
    }

    /** Destroys one key version. Only the trusted management UI may call this after explicit consent. */
    fun destroyKey(keyVersion: Int = currentKeyVersion) {
        val store = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        val alias = aliasForVersion(keyVersion)
        if (store.containsAlias(alias)) store.deleteEntry(alias)
    }

    private fun secretKey(keyVersion: Int): SecretKey {
        val alias = aliasForVersion(keyVersion)
        val store = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                alias,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setRandomizedEncryptionRequired(true)
                .build(),
        )
        return generator.generateKey()
    }

    private fun aliasForVersion(version: Int): String {
        if (version == 1) return keyAlias
        val versioned = keyAlias.replace(Regex("v\\d+$"), "v$version")
        return if (versioned == keyAlias) "$keyAlias.v$version" else versioned
    }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val TAG_BITS = 128
        const val DEFAULT_KEY_ALIAS = "cordiact.vault.master.v1"
    }
}
