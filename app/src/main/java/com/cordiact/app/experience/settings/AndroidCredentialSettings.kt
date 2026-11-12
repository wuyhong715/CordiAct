package com.cordiact.app.experience.settings

import com.cordiact.app.CordiActApplication
import com.cordiact.app.security.AndroidSystemCredentials
import com.cordiact.core.settings.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Only metadata crosses the UI boundary; no plaintext read API. */
class AndroidCredentialSettings(private val app: CordiActApplication) {
    private val store = AndroidSystemCredentials.get(app)
    private val manager = CredentialSettings(object : ManagedCredentials {
        override fun list(): List<ManagedCredentialMeta> {
            val persisted = store.metadata().mapNotNull { record ->
                val kind = record.id.substringBefore(':')
                val owner = record.id.substringAfter(':', "")
                if (kind !in setOf("model", "search", "ssh", "mcp") || owner.isBlank()) null else
                    ManagedCredentialMeta(kind, owner, record.revision, record.updatedAtMillis, record.lastUsedAtMillis)
            }
            // Legacy entries have no trustworthy timestamps; never decrypt to discover metadata.
            val candidates = app.modelProviders.providers().map { "model" to it.providerId } +
                listOf("tavily", "brave", "exa", "bocha", "deepseek").map { "search" to it } +
                runCatching { com.cordiact.app.ssh.SshHostStore(app).list().map { "ssh" to it.name } }.getOrDefault(emptyList()) +
                runCatching { app.pluginManagerService().inventory().mapNotNull { entry ->
                    entry.spec.id.removePrefix("core.mcp.").takeIf { entry.spec.id.startsWith("core.mcp.") }?.let { "mcp" to it }
                } }.getOrDefault(emptyList())
            return (persisted + candidates.mapNotNull { (kind, owner) ->
                if (persisted.any { it.namespace == kind && it.owner == owner } || !store.has(kind, owner)) null
                else ManagedCredentialMeta(kind, owner, store.revision(kind, owner))
            }).sortedWith(compareBy({ it.namespace }, { it.owner }))
        }
        override fun clear(expected: ManagedCredentialMeta) =
            store.clearExpected(expected.namespace, expected.owner, expected.revision)
    }, app.credentialVaultRuntime.database)
    suspend fun list() = withContext(Dispatchers.IO) { manager.list() }
    suspend fun clear(expected: ManagedCredentialMeta) = withContext(Dispatchers.IO) { manager.clear(expected) }
}
