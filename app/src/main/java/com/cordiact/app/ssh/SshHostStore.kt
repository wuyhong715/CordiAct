package com.cordiact.app.ssh

import android.content.Context
import com.cordiact.core.execution.SshHostConfig
import com.cordiact.core.execution.SshHostSummary
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Thread-safe persistent store for SSH host configurations.
 */
class SshHostStore(
    private val storageFile: File,
) {
    constructor(context: Context) : this(File(context.filesDir, "ssh_hosts.json"))

    private val hosts = ConcurrentHashMap<String, SshHostConfig>()

    init {
        load()
    }

    @Synchronized
    private fun load() {
        if (!storageFile.exists()) return
        try {
            val content = storageFile.readText(Charsets.UTF_8)
            if (content.isBlank()) return
            val jsonArray = JSONArray(content)
            for (i in 0 until jsonArray.length()) {
                val obj = jsonArray.getJSONObject(i)
                val config = SshHostConfig(
                    name = obj.getString("name"),
                    host = obj.getString("host"),
                    port = obj.optInt("port", 22),
                    user = obj.getString("user"),
                    password = obj.optString("password").takeIf { it.isNotEmpty() },
                    privateKey = obj.optString("privateKey").takeIf { it.isNotEmpty() },
                    passphrase = obj.optString("passphrase").takeIf { it.isNotEmpty() },
                    pinnedFingerprint = obj.optString("pinnedFingerprint").takeIf { it.isNotEmpty() },
                )
                hosts[config.name] = config
            }
        } catch (_: Exception) {
            // Fail open on corrupted config, store remains empty
        }
    }

    @Synchronized
    private fun persist() {
        try {
            val jsonArray = JSONArray()
            hosts.values.forEach { config ->
                val obj = JSONObject().apply {
                    put("name", config.name)
                    put("host", config.host)
                    put("port", config.port)
                    put("user", config.user)
                    config.password?.let { put("password", it) }
                    config.privateKey?.let { put("privateKey", it) }
                    config.passphrase?.let { put("passphrase", it) }
                    config.pinnedFingerprint?.let { put("pinnedFingerprint", it) }
                }
                jsonArray.put(obj)
            }
            storageFile.writeText(jsonArray.toString(2), Charsets.UTF_8)
        } catch (_: Exception) {}
    }

    fun save(config: SshHostConfig) {
        hosts[config.name] = config
        persist()
    }

    fun get(name: String): SshHostConfig? = hosts[name]

    fun list(): List<SshHostSummary> = hosts.values.map { it.toSummary() }.sortedBy { it.name }

    fun delete(name: String): Boolean {
        val removed = hosts.remove(name) != null
        if (removed) persist()
        return removed
    }
}
