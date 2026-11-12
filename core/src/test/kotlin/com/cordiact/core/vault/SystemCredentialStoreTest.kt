package com.cordiact.core.vault

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

class SystemCredentialStoreTest {
    private class Repository : SystemCredentialRepository {
        val records = mutableMapOf<String, SystemCredentialRecord>()
        var failWrite = false
        override fun readSystemCredential(id: String) = records[id]
        override fun writeSystemCredential(record: SystemCredentialRecord) {
            check(!failWrite) { "write failed" }; records[record.id] = record
        }
        override fun deleteSystemCredential(id: String) { records.remove(id) }
    }
    private class Crypto : VaultCrypto {
        override var currentKeyVersion = 1
        private val key = SecretKeySpec(ByteArray(32) { 7 }, "AES")
        override fun encrypt(plaintext: ByteArray, aad: ByteArray, keyVersion: Int): EncryptedValue {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key); cipher.updateAAD(aad)
            return EncryptedValue(cipher.iv, cipher.doFinal(plaintext))
        }
        override fun decrypt(value: EncryptedValue, aad: ByteArray, keyVersion: Int): ByteArray {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, value.nonce)); cipher.updateAAD(aad)
            return cipher.doFinal(value.ciphertext)
        }
    }
    private class Legacy(var value: ByteArray? = "old-key".toByteArray()) : LegacyCredentialSource {
        var failClear = false
        override fun read() = value?.copyOf()
        override fun clear() { check(!failClear); value = null }
    }
    private val repository = Repository()
    private val crypto = Crypto()
    private val store = SystemCredentialStore(repository, crypto)

    @Test fun sshNamespaceUsesEncryptedRepository() {
        val secret = "ssh-password".toByteArray()
        store.write("ssh:host-1", secret)
        assertContentEquals(secret, store.read("ssh:host-1"))
        store.delete("ssh:host-1")
        assertNull(store.read("ssh:host-1"))
    }
    @Test fun migratesOnceAndReopensWithNoLegacy() {
        val legacy = Legacy()
        assertContentEquals("old-key".toByteArray(), store.read("model:deepseek", legacy))
        assertNull(legacy.value)
        assertContentEquals("old-key".toByteArray(), SystemCredentialStore(repository, crypto).read("model:deepseek"))
        assertFalse(repository.records.values.first().encrypted.ciphertext.contentEquals("old-key".toByteArray()))
    }

    @Test fun failedWritePreservesLegacy() {
        val legacy = Legacy(); repository.failWrite = true
        assertFailsWith<IllegalStateException> { store.read("model:deepseek", legacy) }
        assertContentEquals("old-key".toByteArray(), legacy.value)
    }

    @Test fun crashAfterCopyResumesCleanupWithoutReplacingNewValue() {
        val legacy = Legacy(); legacy.failClear = true
        assertFailsWith<IllegalStateException> { store.read("search:deepseek", legacy) }
        assertTrue(repository.records.containsKey("search:deepseek"))
        legacy.failClear = false
        assertContentEquals("old-key".toByteArray(), store.read("search:deepseek", legacy))
        assertNull(legacy.value)
        assertEquals(1, repository.records.size)
    }

    @Test fun corruptVaultNeverFallsBackOrDeletesLegacy() {
        val legacy = Legacy()
        store.write("model:p", "new-key".toByteArray())
        repository.records.getValue("model:p").encrypted.ciphertext[0] = (repository.records.getValue("model:p").encrypted.ciphertext[0].toInt() xor 1).toByte()
        assertFailsWith<Exception> { store.read("model:p", legacy) }
        assertContentEquals("old-key".toByteArray(), legacy.value)
    }

    @Test fun modelAndSearchKeysAreSeparateAndAadBound() {
        store.write("model:p", "model-key".toByteArray())
        store.write("search:p", "search-key".toByteArray())
        assertContentEquals("model-key".toByteArray(), store.read("model:p"))
        assertContentEquals("search-key".toByteArray(), store.read("search:p"))
        val record = repository.records.getValue("model:p")
        repository.records["search:p"] = record
        assertFailsWith<Exception> { store.read("search:p") }
        repository.records["search:p"] = record.copy(id = "search:p")
        assertFailsWith<Exception> { store.read("search:p") }
    }

    @Test fun explicitReplacementAndDeletionCannotResurrectOldKey() {
        val legacy = Legacy()
        store.write("model:p", "new-key".toByteArray(), legacy)
        assertNull(legacy.value)
        assertContentEquals("new-key".toByteArray(), store.read("model:p", legacy))
        store.delete("model:p", legacy)
        assertNull(store.read("model:p", legacy))
        assertFailsWith<IllegalArgumentException> { store.write("global:agent", "x".toByteArray()) }
    }
}
