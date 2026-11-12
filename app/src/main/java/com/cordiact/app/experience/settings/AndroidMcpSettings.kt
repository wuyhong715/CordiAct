package com.cordiact.app.experience.settings

import com.cordiact.app.CordiActApplication
import com.cordiact.app.settings.McpCredentialStore
import com.cordiact.app.security.AndroidSystemCredentials
import com.cordiact.core.mcp.*
import com.cordiact.core.plugin.PluginChangeResult
import com.cordiact.core.plugin.PluginState
import com.cordiact.mcp.client.HttpMcpClientFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class AndroidMcpSettings(private val app: CordiActApplication) : McpSettings {
    private val service get() = app.pluginManagerService()
    private val credentials = McpCredentialStore(app)
    private val prefs = app.getSharedPreferences("mcp_settings_revision_v1", 0)
    private fun <T> write(target: String, action: () -> T): T {
        val receipt = com.cordiact.core.settings.SettingsOperationAudit(app.credentialVaultRuntime.database).run(target, { prefs.getLong("revision", 0) }) {
            val value = action()
            check(prefs.edit().putLong("revision", prefs.getLong("revision", 0) + 1).commit()) { "MCP_REVISION_WRITE_FAILED" }
            value
        }
        check(receipt.completionRecorded) { "MCP_SAVED_AUDIT_INCOMPLETE_REFRESH_BEFORE_RETRY" }
        return receipt.value
    }
    override suspend fun list(): List<McpSummary> = withContext(Dispatchers.IO) {
        service.inventory().filter { it.spec.id.startsWith("$MCP_PLUGIN_ID.") }.map { entry ->
            val config = McpServerConfigCodec.decode(entry.spec.config)
            McpSummary(config.serverName, config.url, entry.state == PluginState.ENABLED, entry.state.name, entry.publishedToolIds.size,
                AndroidSystemCredentials.get(app).has("mcp", config.serverName))
        }
    }
    override suspend fun reconnect(name: String): McpReconnectOutcome = withContext(Dispatchers.IO) {
        val entry = service.inventory().find { it.spec.id == "$MCP_PLUGIN_ID.$name" }
            ?: return@withContext McpReconnectOutcome.Failed(McpReconnectFailure.UNKNOWN_SERVER)
        val config = try {
            McpServerConfigCodec.decode(entry.spec.config).also { config(it.serverName, it.url) }
        } catch (_: Exception) { return@withContext McpReconnectOutcome.Failed(McpReconnectFailure.INVALID_CONFIG) }
        val credential = try { credentials.readForExecution(config.serverName) }
        catch (_: Exception) { return@withContext McpReconnectOutcome.Failed(McpReconnectFailure.CREDENTIAL_UNREADABLE) }
        reconnectMcpTools(
            enabled = { service.inventory().find { it.spec.id == entry.spec.id }?.state == PluginState.ENABLED },
            loadTools = {
                val connection = HttpMcpClientFactory.connect(config, credential)
                try { connection.listTools() } finally { connection.close() }
            },
            restart = {
                write("mcp.reconnect") {
                    check(service.setEnabled(entry.spec.id, false).application == PluginChangeResult.Application.APPLIED)
                    check(service.setEnabled(entry.spec.id, true).application == PluginChangeResult.Application.APPLIED)
                }
            },
        )
    }

    private fun config(name: String, url: String) = McpServerConfig(name.trim(), url.trim()).also {
        val uri = java.net.URI(it.url)
        require(uri.rawUserInfo == null && uri.fragment == null) { "MCP_URL_INVALID" }
        require(uri.scheme == "https" || uri.scheme == "http" && uri.host in setOf("localhost", "127.0.0.1", "::1", "[::1]"))
    }
    private fun credential(header: String?, secret: String?): McpCredential? {
        if (header.isNullOrBlank() && secret.isNullOrBlank()) return null
        require(!header.isNullOrBlank() && !secret.isNullOrBlank())
        McpCredentialStore.validateHeader(header.trim())
        require(!secret.contains('\r') && !secret.contains('\n'))
        return McpCredential(header.trim(), secret.trim())
    }
    override suspend fun probe(name: String, url: String, header: String?, secret: String?): List<McpToolDescriptor> = withContext(Dispatchers.IO) {
        val connection = HttpMcpClientFactory.connect(config(name, url), credential(header, secret))
        try { connection.listTools() } finally { connection.close() }
    }
    override suspend fun install(name: String, url: String, header: String?, secret: String?) = withContext(Dispatchers.IO) {
        val config = config(name, url); val credential = credential(header, secret)
        check(service.inventory().none { it.spec.id == "$MCP_PLUGIN_ID.${config.serverName}" }) { "MCP_ALREADY_EXISTS" }
        // Repeat probe at commit, so form changes cannot reuse a successful stale preview.
        val connection = HttpMcpClientFactory.connect(config, credential)
        try { connection.listTools() } finally { connection.close() }
        write("mcp.install") {
            credentials.set(config.serverName, credential)
            val result = service.install(com.cordiact.core.mcp.mcpPluginSpec(config))
            check(result.application == PluginChangeResult.Application.APPLIED) { "MCP_INSTALL_FAILED" }
        }
        Unit
    }
    override suspend fun updateAuthentication(name: String, header: String?, secret: String?): List<com.cordiact.core.mcp.McpToolDescriptor> = withContext(Dispatchers.IO) {
        val id = "$MCP_PLUGIN_ID.$name"
        val entry = service.inventory().find { it.spec.id == id } ?: error("MCP_NOT_FOUND")
        val server = McpServerConfigCodec.decode(entry.spec.config)
        val replacement = credential(header, secret)
        // Validate the replacement before touching either the vault or the active transport.
        val connection = HttpMcpClientFactory.connect(server, replacement)
        val tools = try { connection.listTools() } finally { connection.close() }
        val previous = credentials.readForExecution(name)
        val wasEnabled = entry.state == PluginState.ENABLED
        write("mcp.authentication") {
            if (wasEnabled) check(service.setEnabled(id, false).application == PluginChangeResult.Application.APPLIED) { "MCP_DISABLE_FAILED" }
            try {
                credentials.set(name, replacement)
                if (wasEnabled) check(service.setEnabled(id, true).application == PluginChangeResult.Application.APPLIED) { "MCP_RECONNECT_FAILED" }
            } catch (failure: Exception) {
                // On failure restore the old vault value and best-effort old connection. Do not report success.
                runCatching { credentials.set(name, previous) }
                if (wasEnabled) runCatching { service.setEnabled(id, true) }
                if (failure is kotlinx.coroutines.CancellationException) throw failure
                throw IllegalStateException("MCP_AUTH_UPDATE_FAILED")
            }
        }
        tools
    }
    override suspend fun setEnabled(name: String, enabled: Boolean) = withContext(Dispatchers.IO) {
        write("mcp.enabled") { check(service.setEnabled("$MCP_PLUGIN_ID.$name", enabled).application == PluginChangeResult.Application.APPLIED) { "MCP_STATE_CHANGE_FAILED" } }
        Unit
    }
    override suspend fun remove(name: String) = withContext(Dispatchers.IO) {
        write("mcp.remove") { check(service.remove("$MCP_PLUGIN_ID.$name").application == PluginChangeResult.Application.APPLIED) { "MCP_REMOVE_FAILED" } }
        Unit
    }
}
