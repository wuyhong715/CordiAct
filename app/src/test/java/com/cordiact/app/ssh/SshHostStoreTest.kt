package com.cordiact.app.ssh

import com.cordiact.core.execution.SshHostConfig
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files
import java.io.File

class SshHostStoreTest {
    private class Vault : SshCredentialStore {
        val values = mutableMapOf<String, String>()
        var fail = false
        override fun read(owner: String) = values[owner]
        override fun write(owner: String, value: String?) {
            check(!fail) { "VAULT_UNAVAILABLE" }
            if (value == null) values.remove(owner) else values[owner] = value
        }
    }
    private fun config(pin: String? = "SHA256:verified") = SshHostConfig("test", "example.test", user = "tester", password = "secret-password", pinnedFingerprint = pin)
    @Test fun persistsMetadataOnlyAndReadsCredentialsAfterReopen() {
        val file = File(Files.createTempDirectory("ssh-test").toFile(), "hosts.json")
        val vault = Vault()
        SshHostStore(file, vault).save(config())
        assertFalse(file.readText().contains("secret-password"))
        assertFalse(file.readText().contains("password\":"))
        assertEquals("secret-password", SshHostStore(file, vault).get("test")!!.password)
        assertEquals(1, SshHostStore(file, vault).list().size)
    }
    @Test fun legacyMigrationWritesAndVerifiesVaultThenDeletesSource() {
        val dir = Files.createTempDirectory("ssh-migration").toFile()
        val old = File(dir, "old.json").apply { writeText("""[{"name":"test","host":"example.test","port":22,"user":"tester","password":"secret-password"}]""") }
        val next = File(dir, "new.json"); val vault = Vault()
        val store = SshHostStore(next, vault, old)
        assertTrue(old.exists()) // Constructor must not migrate at app startup.
        assertEquals(1, store.list().size)
        assertFalse(old.exists())
        assertFalse(next.readText().contains("secret-password"))
        assertThrows(IllegalStateException::class.java) { store.get("test") } // unpinned legacy entry is quarantined
    }
    @Test fun failedVaultMigrationPreservesOriginalFile() {
        val dir = Files.createTempDirectory("ssh-failure").toFile()
        val old = File(dir, "old.json").apply { writeText("""[{"name":"test","host":"example.test","user":"tester","password":"secret-password"}]""") }
        val next = File(dir, "new.json"); val vault = Vault().apply { fail = true }
        assertThrows(IllegalStateException::class.java) { SshHostStore(next, vault, old).list() }
        assertTrue(old.exists()); assertFalse(next.exists())
    }
    @Test fun corruptionNeverLooksLikeEmptyInventory() {
        val file = File(Files.createTempDirectory("ssh-corrupt").toFile(), "hosts.json").apply { writeText("broken") }
        assertThrows(Exception::class.java) { SshHostStore(file, Vault()).list() }
        assertEquals("broken", file.readText())
    }
    @Test fun saveRequiresPinAndReplacementRemovesPreviousCredential() {
        val file = File(Files.createTempDirectory("ssh-pin").toFile(), "hosts.json"); val vault = Vault(); val store = SshHostStore(file, vault)
        assertThrows(IllegalArgumentException::class.java) { store.save(config(null)) }
        assertTrue(vault.values.isEmpty())
        assertThrows(IllegalArgumentException::class.java) { store.save(config("aa:bb:cc")) }
        store.save(config()); store.save(config().copy(password = "replacement"))
        assertEquals(1, vault.values.size)
        assertEquals("replacement", store.get("test")!!.password)
        assertTrue(store.delete("test")); assertTrue(vault.values.isEmpty())
    }
    @Test fun repinningLegacyHostChangesOnlyMetadataAndIncrementsRevision() {
        val file = File(Files.createTempDirectory("ssh-repin").toFile(), "hosts.json"); val vault = Vault(); val store = SshHostStore(file, vault)
        store.save(config()); val previous = store.revision(); val before = vault.values.toMap()
        store.pin("test", "SHA256:new-pin")
        assertEquals(previous + 1, store.revision())
        assertEquals(before, vault.values)
        assertEquals("SHA256:new-pin", store.get("test")!!.pinnedFingerprint)
        assertEquals(previous + 1, SshHostStore(file, vault).revision())
    }
    @Test fun corruptLegacySourceNeverCreatesReplacementOrDeletesOriginal() {
        val dir = Files.createTempDirectory("ssh-old-corrupt").toFile(); val old = File(dir, "old.json").apply { writeText("not-json") }; val next = File(dir, "new.json")
        assertThrows(Exception::class.java) { SshHostStore(next, Vault(), old).list() }
        assertTrue(old.exists()); assertFalse(next.exists())
    }
    @Test fun crashAfterMetadataCommitDeletesOldFileOnlyAfterVaultVerification() {
        val dir = Files.createTempDirectory("ssh-recovery").toFile(); val next = File(dir, "new.json"); val vault = Vault()
        SshHostStore(next, vault).save(config())
        val old = File(dir, "old.json").apply { writeText("legacy-copy") }
        val unavailable = object : SshCredentialStore {
            override fun read(owner: String): String? = null
            override fun write(owner: String, value: String?) = Unit
        }
        assertThrows(IllegalStateException::class.java) { SshHostStore(next, unavailable, old).list() }
        assertTrue(old.exists())
        assertEquals(1, SshHostStore(next, vault, old).list().size)
        assertFalse(old.exists())
    }
    @Test fun metadataReadNeverDecryptsCredential() {
        val file = File(Files.createTempDirectory("ssh-meta").toFile(), "hosts.json"); val vault = Vault()
        SshHostStore(file, vault).save(config())
        val noRead = object : SshCredentialStore {
            override fun read(owner: String): String? = error("MUST_NOT_DECRYPT")
            override fun write(owner: String, value: String?) = Unit
        }
        assertEquals(1, SshHostStore(file, noRead).list().size)
    }
}
