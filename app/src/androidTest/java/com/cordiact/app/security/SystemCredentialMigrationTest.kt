package com.cordiact.app.security

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.content.ContextWrapper
import java.io.File
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.cordiact.app.model.AndroidProviderApiKeyStore
import com.cordiact.app.search.AndroidSearchApiKeyStore
import com.cordiact.core.vault.CredentialScope
import com.cordiact.core.vault.EncryptedValue
import com.cordiact.core.vault.SystemCredentialRecord
import java.security.KeyStore
import java.security.MessageDigest
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Android Keystore/SharedPreferences/SQLite proof; no real key or remote request is used. */
@RunWith(AndroidJUnit4::class)
class SystemCredentialMigrationTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val databaseName = "system-migration-${UUID.randomUUID()}.db"

    @Test fun upgradesV1DatabaseWithoutDeletingCredentialOrAuditTables() {
        val isolated = isolatedContext()
        val path = File(isolated.noBackupFilesDir, "security/vault.db").apply { parentFile!!.mkdirs() }
        val legacy = SQLiteDatabase.openOrCreateDatabase(path, null)
        try {
            legacy.execSQL("CREATE TABLE credentials (marker TEXT)")
            legacy.execSQL("INSERT INTO credentials VALUES ('keep-credential')")
            legacy.execSQL("CREATE TABLE audit_events (marker TEXT)")
            legacy.execSQL("INSERT INTO audit_events VALUES ('keep-audit')")
            legacy.version = 1
        } finally { legacy.close() }
        try {
            val database = AndroidSecurityDatabase(isolated, AuditSignerForTest())
            try {
                val db = database
                assertEquals(3, db.writableDatabase.version)
                db.readableDatabase.rawQuery("SELECT marker FROM credentials", null).use { cursor -> assertTrue(cursor.moveToFirst()); assertEquals("keep-credential", cursor.getString(0)) }
                db.readableDatabase.rawQuery("SELECT marker FROM audit_events", null).use { cursor -> assertTrue(cursor.moveToFirst()); assertEquals("keep-audit", cursor.getString(0)) }
                db.writeSystemCredential(SystemCredentialRecord("model:test", EncryptedValue(ByteArray(12), byteArrayOf(1, 2, 3)), 1))
                assertNotNull(db.readSystemCredential("model:test"))
            } finally { database.close() }
        } finally { isolated.noBackupFilesDir.deleteRecursively() }
    }

    @Test fun modelAndSearchStoresMigrateActualLegacyCiphertextThenUseOnlyVault() {
        val id = "migration-${UUID.randomUUID()}"
        val digest = MessageDigest.getInstance("SHA-256").digest(id.toByteArray()).take(16).joinToString("") { "%02x".format(it.toInt() and 0xff) }
        val modelPreference = context.getSharedPreferences("model_credentials", Context.MODE_PRIVATE)
        val searchPreference = context.getSharedPreferences("cordiact.search.credentials", Context.MODE_PRIVATE)
        val modelAlias = "cordiact.provider.$digest.v1"
        val searchAlias = "cordiact.search.keys"
        val modelKey = "provider_${digest}_ciphertext"
        val searchKey = "search.$id"
        seedLegacy(modelPreference, modelKey, modelAlias, "model-test-key")
        seedLegacy(searchPreference, searchKey, searchAlias, "search-test-key")
        val model = AndroidProviderApiKeyStore(context)
        val search = AndroidSearchApiKeyStore(context)
        try {
            assertEquals("model-test-key", model.provider(id).apiKey())
            assertEquals("search-test-key", search.get(id))
            assertFalse(modelPreference.contains(modelKey)); assertFalse(searchPreference.contains(searchKey))
            assertEquals("model-test-key", AndroidProviderApiKeyStore(context).provider(id).apiKey())
            model.set(id, "replacement")
            assertEquals("replacement", model.provider(id).apiKey())
            assertEquals("search-test-key", search.get(id))
            model.clear(id); search.remove(id)
            assertNull(model.provider(id).apiKey()); assertNull(search.get(id))
        } finally {
            model.clear(id); search.remove(id)
            KeyStore.getInstance("AndroidKeyStore").apply { load(null); deleteEntry(modelAlias) }
        }
    }

    @Test fun metadataRevisionsAreMonotonicAndPreserveLegacyUnknownDates() {
        val isolated = isolatedContext()
        val db = AndroidSecurityDatabase(isolated, AuditSignerForTest())
        try {
            val record = SystemCredentialRecord("model:metadata-test", EncryptedValue(ByteArray(12), byteArrayOf(1)), 1)
            assertEquals(0L, db.systemCredentialRevision(record.id))
            db.writeSystemCredential(record)
            assertEquals(1L, db.systemCredentialRevision(record.id))
            assertNotNull(db.systemCredentialMetadata().single().updatedAtMillis)
            assertNull(db.systemCredentialMetadata().single().lastUsedAtMillis)
            db.markSystemCredentialUsed(record.id)
            assertNotNull(db.systemCredentialMetadata().single().lastUsedAtMillis)
            assertEquals(1L, db.systemCredentialRevision(record.id))
            db.deleteSystemCredential(record.id)
            assertTrue(db.systemCredentialMetadata().isEmpty())
            assertEquals(2L, db.systemCredentialRevision(record.id))
            db.writeSystemCredential(record)
            assertEquals(3L, db.systemCredentialRevision(record.id))
            assertNull(db.systemCredentialMetadata().single().lastUsedAtMillis)
        } finally { db.close(); isolated.noBackupFilesDir.deleteRecursively() }
    }

    @Test fun upgradesV2KeepingCiphertextAndUnknownTimestamps() {
        val isolated = isolatedContext()
        val path = File(isolated.noBackupFilesDir, "security/vault.db").apply { parentFile!!.mkdirs() }
        SQLiteDatabase.openOrCreateDatabase(path, null).use { legacy ->
            legacy.execSQL("CREATE TABLE system_credentials (id TEXT PRIMARY KEY NOT NULL, nonce BLOB NOT NULL, ciphertext BLOB NOT NULL, key_version INTEGER NOT NULL)")
            legacy.execSQL("INSERT INTO system_credentials VALUES ('model:old', X'0102', X'0304', 1)")
            legacy.version = 2
        }
        val db = AndroidSecurityDatabase(isolated, AuditSignerForTest())
        try {
            assertEquals(3, db.writableDatabase.version)
            assertArrayEquals(byteArrayOf(3, 4), db.readSystemCredential("model:old")!!.encrypted.ciphertext)
            val meta = db.systemCredentialMetadata().single()
            assertEquals(0L, meta.revision)
            assertNull(meta.updatedAtMillis)
            assertNull(meta.lastUsedAtMillis)
        } finally { db.close(); isolated.noBackupFilesDir.deleteRecursively() }
    }

    @Test fun systemNamespaceIsNotVisibleToAgentGlobalOrWorkspaceCredentialListing() {
        val isolated = isolatedContext()
        val database = AndroidSecurityDatabase(isolated, AuditSignerForTest())
        try {
            val db = database
            db.writeSystemCredential(SystemCredentialRecord("model:test", EncryptedValue(ByteArray(12), byteArrayOf(1, 2, 3)), 1))
            assertTrue(db.list(CredentialScope.UserGlobal).isEmpty())
            assertTrue(db.list(CredentialScope.Workspace("test-workspace")).isEmpty())
        } finally { database.close(); isolated.noBackupFilesDir.deleteRecursively() }
    }

    private fun seedLegacy(preferences: android.content.SharedPreferences, name: String, alias: String, value: String) {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val key = store.getKey(alias, null) ?: KeyGenerator.getInstance("AES", "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).setKeySize(256).build())
        }.generateKey()
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key)
        check(preferences.edit().putString(name, Base64.encodeToString(cipher.iv + cipher.doFinal(value.toByteArray()), Base64.NO_WRAP)).commit())
    }

    private fun isolatedContext(): Context = object : ContextWrapper(context) {
        private val directory = File(context.cacheDir, databaseName).apply { mkdirs() }
        override fun getApplicationContext(): Context = this
        override fun getNoBackupFilesDir(): File = directory
    }

    private class AuditSignerForTest : com.cordiact.core.security.AuditSigner {
        override fun sign(previousMac: String?, payload: String) = "test-mac"
    }
}
