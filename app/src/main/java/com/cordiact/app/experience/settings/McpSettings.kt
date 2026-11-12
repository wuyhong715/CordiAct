package com.cordiact.app.experience.settings

import com.cordiact.core.mcp.McpToolDescriptor

/**
 * One tool an installed server currently publishes.
 *
 * [id] is the normalized registry id (what the lifecycle manager reports and what a withdrawn
 * tool disappears from); [name] is the server-side name, which is the only one a user can
 * recognise. Display-only: nothing here is used to call a tool.
 */
data class McpToolEntry(val id: String, val name: String)

data class McpSummary(
    val name: String,
    val url: String,
    val enabled: Boolean,
    val state: String,
    val toolCount: Int,
    val keySet: Boolean,
    /** Tools published right now. Empty while the server is disabled or never started. */
    val tools: List<McpToolEntry> = emptyList(),
)

/**
 * Coarse reason a reconnect did not complete.
 *
 * Deliberately not a message: transport text can quote URLs, headers or server bodies, and the
 * settings screen only ever gets to show one of these labels.
 */
enum class McpReconnectFailure {
    /** No installed server answers to that name (already removed, or a stale card). */
    UNKNOWN_SERVER,
    /** The vault holds a credential for this server but it cannot be read back safely. */
    CREDENTIAL_UNREADABLE,
    /** The stored config does not parse or no longer validates. */
    INVALID_CONFIG,
    /** The server answered 401/403 for the stored credential. */
    REJECTED,
    /** Nothing answered: DNS, TLS, timeout or connection refused. */
    UNREACHABLE,
    /** It answered, but not with a usable MCP handshake or tool list. */
    PROTOCOL,
    /** Anything else. */
    FAILED,
}

/** Outcome of [McpSettings.reconnect]. [Connected] carries a live listing, not a persisted one. */
sealed interface McpReconnectOutcome {
    data class Connected(val tools: List<McpToolDescriptor>) : McpReconnectOutcome
    data class Failed(val reason: McpReconnectFailure) : McpReconnectOutcome
}

interface McpSettings {
    suspend fun list(): List<McpSummary>
    suspend fun probe(name: String, url: String, header: String?, secret: String?): List<McpToolDescriptor>
    suspend fun install(name: String, url: String, header: String?, secret: String?)
    suspend fun updateAuthentication(name: String, header: String?, secret: String?): List<McpToolDescriptor>
    suspend fun setEnabled(name: String, enabled: Boolean)
    suspend fun remove(name: String)

    /** Reconnect the active plugin; for a disabled plugin, probe only and leave it disabled.
     * Credentials stay on the execution path. Only descriptors and safe reason codes leave it.
     */
    suspend fun reconnect(name: String): McpReconnectOutcome
}

/** The IO facade supplies transport callbacks. This policy never enables disabled entries. */
internal fun reconnectMcpTools(
    enabled: () -> Boolean,
    loadTools: () -> List<McpToolDescriptor>,
    restart: () -> Unit,
): McpReconnectOutcome = try {
    val tools = loadTools()
    if (enabled()) restart()
    McpReconnectOutcome.Connected(tools)
} catch (cancelled: java.util.concurrent.CancellationException) {
    throw cancelled
} catch (_: Exception) {
    // Transport messages may contain URLs, headers or reflected secrets. Never expose them.
    McpReconnectOutcome.Failed(McpReconnectFailure.FAILED)
}
