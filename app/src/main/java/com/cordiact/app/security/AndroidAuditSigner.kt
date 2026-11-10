package com.cordiact.app.security

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import com.cordiact.core.security.AuditSigner
import java.security.KeyStore
import javax.crypto.KeyGenerator
import javax.crypto.Mac
import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec

/**
 * Audit-chain HMAC backed by a separate Android Keystore key. The key is not the vault encryption
 * key: deleting or rotating one must not silently validate the other.
 */
class AndroidAuditSigner(
    private val keyAlias: String = "cordiact.vault.audit.v1",
) : AuditSigner {
    override fun sign(previousMac: String?, payload: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(secretKey())
        mac.update(previousMac?.toByteArray(Charsets.UTF_8) ?: ByteArray(0))
        mac.update(payload.toByteArray(Charsets.UTF_8))
        return mac.doFinal().joinToString("") { "%02x".format(it) }
    }

    private fun secretKey(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(keyAlias, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_HMAC_SHA256, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(
                keyAlias,
                KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY,
            ).build(),
        )
        return generator.generateKey()
    }
}

/** Non-Android fallback used only by unit tests that do not have a Keystore. */
class InMemoryAuditSigner(key: ByteArray) : AuditSigner {
    private val keyCopy = key.copyOf()

    override fun sign(previousMac: String?, payload: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(keyCopy, "HmacSHA256"))
        mac.update(previousMac?.toByteArray(Charsets.UTF_8) ?: ByteArray(0))
        mac.update(payload.toByteArray(Charsets.UTF_8))
        return mac.doFinal().joinToString("") { "%02x".format(it) }
    }
}
