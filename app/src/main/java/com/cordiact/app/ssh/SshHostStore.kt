package com.cordiact.app.ssh

import android.content.Context
import com.cordiact.app.security.AndroidSystemCredentials
import com.cordiact.core.execution.SshHostConfig
import com.cordiact.core.execution.SshHostSummary
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID

/** Only execution paths can read secrets. The settings UI consumes list() metadata. */
interface SshCredentialStore {
    fun read(owner: String): String?
    fun write(owner: String, value: String?)
}

class SshHostStore(
    private val storageFile: File,
    private val credentials: SshCredentialStore,
    private val legacyFile: File? = null,
) {
    constructor(context: Context) : this(
        File(context.filesDir, "ssh_hosts_v2.json"),
        object : SshCredentialStore {
            private val vault = AndroidSystemCredentials.get(context)
            override fun read(owner: String) = vault.read("ssh", owner)
            override fun write(owner: String, value: String?) = vault.set("ssh", owner, value)
        }, File(context.filesDir, "ssh_hosts.json"),
    )
    // Test-only file construction fails closed on saving unless a credential backend is supplied.
    constructor(storageFile: File) : this(storageFile, object : SshCredentialStore {
        override fun read(owner: String): String? = error("SSH_CREDENTIAL_BACKEND_REQUIRED")
        override fun write(owner: String, value: String?) { error("SSH_CREDENTIAL_BACKEND_REQUIRED") }
    })

    private data class Profile(val summary: SshHostSummary, val credentialOwner: String)
    private var profiles: Map<String, Profile>? = null
    private var currentRevision = 0L
    @Synchronized fun revision(): Long { load(); return currentRevision }
    @Synchronized fun pin(name: String, fingerprint: String) {
        require(fingerprint.startsWith("SHA256:"))
        val current = load(); val profile = current[name] ?: error("SSH_HOST_MISSING")
        val next = current + (name to profile.copy(summary = profile.summary.copy(pinnedFingerprint = fingerprint)))
        persist(next); profiles = next
    }

    @Synchronized fun save(config: SshHostConfig) {
        require(config.pinnedFingerprint?.startsWith("SHA256:") == true) { "SSH_SHA256_PIN_REQUIRED" }
        val current = load()
        val owner = UUID.randomUUID().toString()
        val value = secret(config)
        credentials.write(owner, value)
        check(credentials.read(owner) == value) { "SSH_CREDENTIAL_VERIFY_FAILED" }
        val next = current + (config.name to Profile(config.toSummary(), owner))
        persist(next)
        profiles = next
        current[config.name]?.let { credentials.write(it.credentialOwner, null) }
    }
    @Synchronized fun get(name: String): SshHostConfig? {
        val profile = load()[name] ?: return null
        check(!profile.summary.pinnedFingerprint.isNullOrBlank()) { "SSH_PIN_REQUIRED" }
        val value = credentials.read(profile.credentialOwner) ?: error("SSH_CREDENTIAL_MISSING")
        val auth = JSONObject(value)
        return profile.summary.let { s -> SshHostConfig(s.name, s.host, s.port, s.user,
            auth.nullable("password"), auth.nullable("privateKey"), auth.nullable("passphrase"), s.pinnedFingerprint) }
    }
    @Synchronized fun list(): List<SshHostSummary> = load().values.map { it.summary }.sortedBy { it.name }
    @Synchronized fun delete(name: String): Boolean {
        val current = load()
        val profile = current[name] ?: return false
        val next = current - name
        persist(next)
        profiles = next
        credentials.write(profile.credentialOwner, null)
        return true
    }

    private fun load(): Map<String, Profile> {
        profiles?.let { return it }
        // Deliberately lazy: a damaged file surfaces an ERROR in settings, not an app startup crash.
        val result = if (storageFile.exists()) readProfiles() else if (legacyFile?.exists() == true) migrate() else emptyMap()
        if (storageFile.exists() && legacyFile?.exists() == true) {
            // A crash after committing metadata is recoverable only after credentials are verified.
            result.values.forEach { check(credentials.read(it.credentialOwner) != null) { "SSH_MIGRATION_INCOMPLETE" } }
            check(legacyFile.delete()) { "SSH_LEGACY_DELETE_FAILED" }
        }
        profiles = result
        return result
    }
    private fun readProfiles(): Map<String, Profile> {
        val root = JSONObject(storageFile.readText())
        check(root.getInt("version") == 2) { "SSH_PROFILE_VERSION_INVALID" }
        currentRevision = root.optLong("revision", 0)
        val rows = root.getJSONArray("hosts")
        val result = linkedMapOf<String, Profile>()
        for (i in 0 until rows.length()) {
            val row = rows.getJSONObject(i)
            check(!row.has("password") && !row.has("privateKey") && !row.has("passphrase")) { "SSH_PROFILE_CONTAINS_SECRET" }
            val summary = SshHostSummary(row.getString("name"), row.getString("host"), row.getInt("port"), row.getString("user"), row.getString("authType"), row.nullable("pinnedFingerprint"))
            check(summary.name.isNotBlank() && summary.host.isNotBlank() && summary.user.isNotBlank() && summary.port in 1..65535)
            val owner = row.getString("credentialOwner")
            check(owner.isNotBlank() && !result.containsKey(summary.name)) { "SSH_PROFILE_INVALID" }
            result[summary.name] = Profile(summary, owner)
        }
        return result
    }
    private fun migrate(): Map<String, Profile> {
        val rows = JSONArray(checkNotNull(legacyFile).readText())
        val configs = (0 until rows.length()).map { i -> rows.getJSONObject(i).let { r ->
            SshHostConfig(r.getString("name"), r.getString("host"), r.optInt("port", 22), r.getString("user"),
                r.nullable("password"), r.nullable("privateKey"), r.nullable("passphrase"), r.nullable("pinnedFingerprint"))
        } }
        check(configs.map { it.name }.distinct().size == configs.size) { "SSH_DUPLICATE_HOST" }
        val result = configs.associate { config ->
            val owner = UUID.randomUUID().toString()
            val value = secret(config)
            credentials.write(owner, value)
            check(credentials.read(owner) == value) { "SSH_MIGRATION_VERIFY_FAILED" }
            config.name to Profile(config.toSummary(), owner)
        }
        persist(result)
        check(legacyFile.delete()) { "SSH_LEGACY_DELETE_FAILED" }
        return result
    }
    private fun persist(values: Map<String, Profile>) {
        storageFile.parentFile?.mkdirs()
        val rows = JSONArray()
        values.values.sortedBy { it.summary.name }.forEach { profile -> rows.put(JSONObject().apply {
            val s = profile.summary
            put("name", s.name); put("host", s.host); put("port", s.port); put("user", s.user)
            put("authType", s.authType); put("pinnedFingerprint", s.pinnedFingerprint ?: JSONObject.NULL)
            put("credentialOwner", profile.credentialOwner)
        }) }
        val tmp = File(storageFile.parentFile, storageFile.name + ".tmp")
        java.io.FileOutputStream(tmp).use { out ->
            out.write(JSONObject().put("version", 2).put("revision", currentRevision + 1).put("hosts", rows).toString().toByteArray()); out.fd.sync()
        }
        Files.move(tmp.toPath(), storageFile.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        currentRevision += 1
    }
    private fun secret(config: SshHostConfig) = JSONObject().apply {
        put("password", config.password ?: JSONObject.NULL)
        put("privateKey", config.privateKey ?: JSONObject.NULL)
        put("passphrase", config.passphrase ?: JSONObject.NULL)
    }.toString()
    private fun JSONObject.nullable(key: String): String? = if (isNull(key)) null else getString(key).takeIf { it.isNotBlank() }
}
