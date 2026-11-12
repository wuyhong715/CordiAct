package com.cordiact.core.mcp

import com.cordiact.core.plugin.PluginSpec
import com.cordiact.core.plugin.PluginTarget

const val MCP_PLUGIN_ID = "core.mcp"
const val MCP_PLUGIN_VERSION = "0.1.0"

/**
 * One MCP server this app connects to over Streamable HTTP.
 *
 * Deliberately a data class, not a JSON blob: the lifecycle manager persists the whole
 * [PluginSpec.config] as text, but everything that decides behaviour is parsed and validated
 * here first. Credentials are *not* part of it — they travel separately so a config dump can
 * never leak a token.
 *
 * There is no stdio transport: Android cannot spawn child processes, so a locally hosted
 * server is reached over HTTP on loopback instead.
 */
data class McpServerConfig(
    /** Stable namespace for this server's tool ids. `[A-Za-z0-9_-]{1,32}`. */
    val serverName: String,
    /** MCP endpoint. `http` is accepted only for loopback hosts. */
    val url: String,
    /** Extra headers (no credentials; those are injected at connect time). */
    val headers: Map<String, String> = emptyMap(),
    /** Per-call budget for `tools/list` and `tools/call`. */
    val toolCallTimeoutMs: Long = DEFAULT_TOOL_CALL_TIMEOUT_MS,
    /** Budget for the connection and `initialize` handshake. */
    val connectTimeoutMs: Long = DEFAULT_CONNECT_TIMEOUT_MS,
    /** Whether a dropped connection is retried, and how. */
    val reconnect: McpReconnectPolicy = McpReconnectPolicy(),
) {
    companion object {
        const val DEFAULT_TOOL_CALL_TIMEOUT_MS = 60_000L
        const val DEFAULT_CONNECT_TIMEOUT_MS = 15_000L
        const val MAX_TOOL_CALL_TIMEOUT_MS = 300_000L
        const val MAX_CONNECT_TIMEOUT_MS = 120_000L

        private val SERVER_NAME = Regex("[A-Za-z0-9_-]{1,32}")

        /**
         * Validate a config before it is persisted or dialed.
         *
         * @throws McpConfigException naming the one field that is wrong.
         */
        fun validated(config: McpServerConfig): McpServerConfig {
            if (!SERVER_NAME.matches(config.serverName)) {
                throw McpConfigException("serverName must match ${SERVER_NAME.pattern}")
            }
            val uri = runCatching { java.net.URI(config.url) }.getOrNull()
                ?: throw McpConfigException("url is not a valid URI")
            if (uri.scheme != "https" && uri.scheme != "http") {
                throw McpConfigException("url must be http or https")
            }
            if (uri.scheme == "http" && !isLoopback(uri.host)) {
                throw McpConfigException("plain http is allowed only for a loopback host")
            }
            if (uri.rawUserInfo != null || uri.fragment != null || uri.rawQuery != null) throw McpConfigException("credentials, query and fragment are not permitted in MCP URLs; use credential headers")
            if (uri.host.isNullOrBlank()) throw McpConfigException("url has no host")
            if (config.toolCallTimeoutMs !in 1..MAX_TOOL_CALL_TIMEOUT_MS) {
                throw McpConfigException("toolCallTimeoutMs must be within 1..$MAX_TOOL_CALL_TIMEOUT_MS")
            }
            if (config.connectTimeoutMs !in 1..MAX_CONNECT_TIMEOUT_MS) {
                throw McpConfigException("connectTimeoutMs must be within 1..$MAX_CONNECT_TIMEOUT_MS")
            }
            if (config.headers.keys.any { it.isBlank() }) {
                throw McpConfigException("header names must not be blank")
            }
            config.reconnect.validated()
            return config
        }

        private fun isLoopback(host: String?): Boolean = when (host) {
            null -> false
            "localhost", "127.0.0.1", "::1", "[::1]" -> true
            else -> false
        }
    }
}

/** Retry policy for a connection that was established once and then dropped. */
data class McpReconnectPolicy(
    val enabled: Boolean = true,
    val initialDelayMs: Long = 1_000,
    val maxDelayMs: Long = 30_000,
    val maxAttempts: Int = 5,
) {
    fun validated(): McpReconnectPolicy {
        if (initialDelayMs !in 1..MAX_DELAY_MS) {
            throw McpConfigException("initialDelayMs must be within 1..$MAX_DELAY_MS")
        }
        if (maxDelayMs !in initialDelayMs..MAX_DELAY_MS) {
            throw McpConfigException("maxDelayMs must be within initialDelayMs..$MAX_DELAY_MS")
        }
        if (maxAttempts !in 1..MAX_ATTEMPTS) {
            throw McpConfigException("maxAttempts must be within 1..$MAX_ATTEMPTS")
        }
        return this
    }

    companion object {
        const val MAX_DELAY_MS = 3_600_000L
        const val MAX_ATTEMPTS = 100
    }
}

/** A credential injected at connect time; never serialized into the plugin config. */
data class McpCredential(val headerName: String, val value: String)

/** What the server reported during `initialize`. */
data class McpServerInfo(
    val protocolVersion: String,
    val serverName: String? = null,
    val serverVersion: String? = null,
    /** Free-form instructions the server wants the model to see, already size-capped. */
    val instructions: String? = null,
)

/**
 * One tool as the server described it.
 *
 * [inputSchemaJson] is the server's JSON Schema object verbatim. It is kept as a string
 * because `core` carries no JSON parser on purpose; the bridge hands it to
 * `ToolDescriptor.rawInputSchemaJson` unchanged.
 */
data class McpToolDescriptor(
    val name: String,
    val description: String = "",
    val inputSchemaJson: String = "{\"type\":\"object\",\"properties\":{}}",
)

/** One `tools/call` outcome, already reduced to what a tool result needs. */
data class McpToolCallResult(
    val text: String,
    /** True when the server marked the call as a failure (a tool error, not a transport error). */
    val isError: Boolean = false,
)

/**
 * A live connection to one server.
 *
 * Implementations own transports and must make [close] idempotent; the bridge disposes a
 * connection generation during a failed swap.
 */
interface McpConnection {
    fun initialize(): McpServerInfo
    fun listTools(timeoutMs: Long = McpServerConfig.DEFAULT_TOOL_CALL_TIMEOUT_MS): List<McpToolDescriptor>
    fun callTool(name: String, argumentsJson: String, timeoutMs: Long): McpToolCallResult
    fun close()
}

/** Opens connections. Separate from [McpConnection] so tests can supply a fake server. */
fun interface McpClientFactory {
    fun connect(config: McpServerConfig, credential: McpCredential?): McpConnection
}

/** Why a config or a connection attempt was rejected. */
class McpConfigException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

/** A transport or protocol failure; [serverName] is filled in by the bridge. */
class McpProtocolException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

/**
 * Map one server tool to a CordiAct tool id.
 *
 * MCP allows names that the tool registry does not (`-`, uppercase, leading digits), and a
 * server may exceed the length budget, so the id is normalized rather than rejected. The
 * mapping is not reversible: callers must keep the raw name alongside it.
 */
fun mcpToolId(serverName: String, rawToolName: String): String {
    val joined = "$MCP_PLUGIN_ID.$serverName.$rawToolName".lowercase()
    // The `core.mcp.` prefix guarantees a leading letter, so only the body needs normalizing.
    val normalized = joined.map { char ->
        when (char) {
            in 'a'..'z', in '0'..'9', '.', '_', '-' -> char
            else -> '_'
        }
    }.joinToString("")
    return if (normalized.length <= MAX_TOOL_ID_LENGTH) normalized else normalized.take(MAX_TOOL_ID_LENGTH - 9) + "_" + shortHash(joined)
}

/** The plugin spec a server becomes, so it flows through the ordinary lifecycle. */
fun mcpPluginSpec(config: McpServerConfig, version: String = MCP_PLUGIN_VERSION): PluginSpec = PluginSpec(
    id = "$MCP_PLUGIN_ID.${config.serverName.lowercase()}",
    version = version,
    target = PluginTarget.ANDROID_NATIVE,
    config = McpServerConfigCodec.encode(config),
)

private fun shortHash(value: String): String {
    val digest = java.security.MessageDigest.getInstance("SHA-256").digest(value.toByteArray())
    return digest.take(4).joinToString("") { "%02x".format(it) }
}

private const val MAX_TOOL_ID_LENGTH = 128
