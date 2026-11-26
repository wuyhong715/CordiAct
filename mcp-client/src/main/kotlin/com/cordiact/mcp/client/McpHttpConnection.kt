package com.cordiact.mcp.client

import com.cordiact.core.mcp.McpConfigException
import com.cordiact.core.mcp.McpConnection
import com.cordiact.core.mcp.McpCredential
import com.cordiact.core.mcp.McpProtocolException
import com.cordiact.core.mcp.McpServerConfig
import com.cordiact.core.mcp.McpServerInfo
import com.cordiact.core.mcp.McpToolCallResult
import com.cordiact.core.mcp.McpToolDescriptor
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicLong

/**
 * One MCP server over the Streamable HTTP transport.
 *
 * Only what this client needs is implemented: `initialize`, `tools/list`, `tools/call`, and
 * the `Mcp-Session-Id` echo. Notifications, resources, prompts and sampling are out of scope
 * for the first slice; the bridge ignores anything it did not ask for.
 *
 * Bodies are JSON-RPC. A server may answer a request either with a plain JSON body or with an
 * SSE stream carrying `data:` frames; both are accepted because real servers do both
 * depending on version.
 */
internal class McpHttpConnection(
    private val config: McpServerConfig,
    private val credential: McpCredential?,
) : McpConnection {

    private data class Session(val id: String?, val protocol: String)

    private val requestIds = AtomicLong(0)
    private val stateLock = Any()
    private var session: Session? = null
    private var initializing = false
    @Volatile override var isRetired: Boolean = false
        private set

    override fun initialize(): McpServerInfo {
        synchronized(stateLock) {
            checkLive()
            if (initializing || session != null) throw McpProtocolException("MCP connection cannot be initialized twice")
            initializing = true
        }
        try {
            val response = request(
                method = "initialize",
                params = buildString {
                    append("{\"protocolVersion\":").append(JsonWrite.string(SUPPORTED_PROTOCOL_VERSION))
                    append(",\"capabilities\":{\"roots\":{\"listChanged\":false}}")
                    append(",\"clientInfo\":{\"name\":").append(JsonWrite.string(CLIENT_NAME))
                    append(",\"version\":").append(JsonWrite.string(CLIENT_VERSION)).append("}}")
                },
                timeoutMs = config.connectTimeoutMs,
                selected = null,
            )
            val result = response.envelope.field("result")?.asObject()
                ?: throw McpProtocolException("initialize returned no result")
            val protocol = result.field("protocolVersion")?.asString()
                ?: throw McpProtocolException("initialize returned no protocolVersion")
            val selected = Session(response.sessionId, protocol)
            notify("notifications/initialized", selected)
            synchronized(stateLock) {
                checkLive()
                session = selected
            }
            val serverInfo = result.field("serverInfo")
            return McpServerInfo(
                protocolVersion = protocol,
                serverName = serverInfo?.field("name")?.let { runCatching { it.asString() }.getOrNull() },
                serverVersion = serverInfo?.field("version")?.let { runCatching { it.asString() }.getOrNull() },
                instructions = result.field("instructions")?.let { runCatching { it.asString() }.getOrNull() }
                    ?.take(MAX_INSTRUCTION_CHARS),
            )
        } catch (failure: Exception) {
            close()
            throw failure
        } finally {
            synchronized(stateLock) { initializing = false }
        }
    }

    override fun listTools(timeoutMs: Long): List<McpToolDescriptor> {
        val selected = currentSession()
        val tools = mutableListOf<McpToolDescriptor>()
        var cursor: String? = null
        // Paginate, but bound the loop: a server that always returns a cursor must not hang a
        // plugin enable forever.
        repeat(MAX_PAGES) {
            val paramsJson = cursor?.let { "{\"cursor\":${JsonWrite.string(it)}}" } ?: "{}"
            val response = request("tools/list", paramsJson, timeoutMs, selected)
            val result = response.envelope.field("result")?.asObject()
                ?: throw McpProtocolException("tools/list returned no result")
            val page = result.field("tools")?.asArray().orEmpty()
            page.forEach { entry ->
                val tool = entry.asObject()
                val name = tool.field("name")?.asString()
                    ?: throw McpProtocolException("a tool entry has no name")
                tools += McpToolDescriptor(
                    name = name,
                    description = tool.field("description")?.let { runCatching { it.asString() }.getOrNull() }
                        ?.take(MAX_DESCRIPTION_CHARS).orEmpty(),
                    inputSchemaJson = tool.field("inputSchema")?.encode() ?: EMPTY_SCHEMA,
                )
            }
            cursor = result.field("nextCursor")?.let { runCatching { it.asString() }.getOrNull() }
            if (cursor == null) return tools
        }
        throw McpProtocolException("tools/list did not finish within $MAX_PAGES pages")
    }

    override fun callTool(name: String, argumentsJson: String, timeoutMs: Long): McpToolCallResult {
        val params = buildString {
            append("{\"name\":").append(JsonWrite.string(name))
            append(",\"arguments\":").append(argumentsJson.ifBlank { "{}" })
            append('}')
        }
        val response = request("tools/call", params, timeoutMs, currentSession())
        val result = response.envelope.field("result")?.asObject()
            ?: throw McpProtocolException("tools/call returned no result")
        val isError = result.field("isError")?.let { runCatching { it.asBoolean("isError") }.getOrNull() } ?: false
        return McpToolCallResult(text = flattenContent(result.field("content")), isError = isError)
    }

    override fun close() = synchronized(stateLock) {
        // Local retirement only. Already-admitted HTTP requests can still finish; no DELETE or replay.
        isRetired = true
        session = null
    }

    private fun checkLive() {
        if (isRetired) throw McpProtocolException("MCP connection is retired; open a new connection explicitly")
    }

    private fun currentSession(): Session = synchronized(stateLock) {
        checkLive()
        session ?: throw McpProtocolException("MCP connection is not initialized")
    }

    private fun flattenContent(content: JsonValue?): String {
        val items = content?.let { runCatching { it.asArray() }.getOrNull() } ?: return ""
        return items.mapNotNull { item ->
            val block = runCatching { item.asObject() }.getOrNull() ?: return@mapNotNull null
            when (block.field("type")?.let { runCatching { it.asString() }.getOrNull() }) {
                "text" -> block.field("text")?.let { runCatching { it.asString() }.getOrNull() }
                "image" -> "[image " + (block.field("mimeType")?.let { runCatching { it.asString() }.getOrNull() } ?: "unknown") + "]"
                "resource" -> block.field("resource")?.encode()?.take(MAX_RESOURCE_CHARS)
                else -> block.encode().take(MAX_RESOURCE_CHARS)
            }
        }.joinToString("\n")
    }

    private fun notify(method: String, selected: Session) {
        // Preserve compatibility with servers that do not implement the notification endpoint.
        runCatching { post(method, params = null, id = null, timeoutMs = config.connectTimeoutMs, selected = selected) }
    }

    private data class HttpReply(val body: String, val sessionId: String?)
    private data class RpcReply(val envelope: JsonValue.Obj, val sessionId: String?)

    /**
     * Send one request and return the JSON-RPC envelope.
     *
     * @throws McpProtocolException when the server answers with a JSON-RPC error, an
     *   unexpected id, or a body this client cannot parse.
     */
    private fun request(method: String, params: String, timeoutMs: Long, selected: Session?): RpcReply {
        val id = requestIds.incrementAndGet()
        val response = post(method, params, id, timeoutMs, selected)
        val envelope = JsonParser(response.body).parseDocument().asObject()
        val error = envelope.field("error")
        if (error != null) {
            val obj = runCatching { error.asObject() }.getOrNull()
            val message = obj?.field("message")?.let { runCatching { it.asString() }.getOrNull() } ?: "unknown error"
            val code = (obj?.field("code") as? JsonValue.Num)?.value?.toInt()
            throw McpProtocolException("$method failed: $message" + (code?.let { " (code $it)" } ?: ""))
        }
        val returnedId = (envelope.field("id") as? JsonValue.Num)?.value?.toLong()
        if (returnedId != null && returnedId != id) {
            throw McpProtocolException("$method answered request id $returnedId while $id was outstanding")
        }
        checkLive()
        return RpcReply(envelope, response.sessionId)
    }

    private fun post(method: String, params: String?, id: Long?, timeoutMs: Long, selected: Session?): HttpReply {
        checkLive()
        val payload = buildString {
            append("{\"jsonrpc\":\"2.0\",\"method\":").append(JsonWrite.string(method))
            if (params != null) append(",\"params\":").append(params)
            if (id != null) append(",\"id\":").append(id)
            append('}')
        }
        val bytes = payload.toByteArray(Charsets.UTF_8)
        val connection = (URL(config.url).openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = false
            requestMethod = "POST"
            connectTimeout = config.connectTimeoutMs.toInt()
            readTimeout = timeoutMs.toInt()
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Accept", "application/json, text/event-stream")
            setFixedLengthStreamingMode(bytes.size)
            config.headers.forEach { (key, value) -> setRequestProperty(key, value) }
            credential?.let { setRequestProperty(it.headerName, it.value) }
            setRequestProperty("MCP-Protocol-Version", selected?.protocol ?: SUPPORTED_PROTOCOL_VERSION)
            selected?.id?.let { setRequestProperty("Mcp-Session-Id", it) }
        }
        return try {
            checkLive()
            connection.outputStream.use { it.write(bytes) }
            val status = connection.responseCode
            val returnedSession = connection.getHeaderField("Mcp-Session-Id")
            when {
                status == 404 && selected?.id != null -> {
                    close()
                    throw McpProtocolException("MCP session expired (HTTP 404); reconnect explicitly")
                }
                status in 200..299 -> {
                    if (selected != null && returnedSession != null && returnedSession != selected.id) {
                        close()
                        throw McpProtocolException("MCP session identity changed; reconnect explicitly")
                    }
                    if (returnedSession != null && (returnedSession.length !in 1..MAX_SESSION_ID_CHARS ||
                            returnedSession.any { it !in '!'..'~' })) {
                        close()
                        throw McpProtocolException("Invalid MCP session identity")
                    }
                    val body = readBody(connection.inputStream)
                    checkLive()
                    HttpReply(body, returnedSession)
                }
                status == 401 || status == 403 ->
                    throw McpConfigException("MCP server '${config.serverName}' rejected the credentials (HTTP $status)")
                else -> {
                    val errorStream = connection.errorStream
                    val detail = if (errorStream == null) "" else readBody(errorStream)
                    throw McpProtocolException("MCP server '${config.serverName}' answered HTTP $status: ${detail.take(300)}")
                }
            }
        } catch (failure: IOException) {
            throw McpProtocolException("MCP server '${config.serverName}' is unreachable: ${failure.message}", failure)
        } finally {
            connection.disconnect()
        }
    }

    /** Read a response that is either plain JSON or an SSE stream carrying `data:` frames. */
    private fun readBody(stream: InputStream?): String {
        if (stream == null) return ""
        val text = stream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        if (!text.startsWith("event:") && !text.startsWith("data:") && !text.contains("\ndata:")) return text
        val data = StringBuilder()
        text.lineSequence().forEach { line ->
            if (line.startsWith("data: ")) data.append(line.removePrefix("data: ")).append('\n')
            else if (line == "data:") data.append('\n')
        }
        return data.toString().trim().ifEmpty { text }
    }

    private fun managedHeader(name: String): Boolean =
        name.equals("Mcp-Session-Id", ignoreCase = true) || name.equals("MCP-Protocol-Version", ignoreCase = true)

    init {
        if (config.headers.keys.any(::managedHeader) || credential?.headerName?.let(::managedHeader) == true) {
            throw McpConfigException("MCP session and protocol headers are managed by the connection")
        }
    }

    private companion object {
        const val MAX_SESSION_ID_CHARS = 1024
        const val CLIENT_NAME = "cordiact"
        const val CLIENT_VERSION = "0.1.0"
        const val SUPPORTED_PROTOCOL_VERSION = "2025-06-18"
        const val MAX_PAGES = 20
        const val MAX_INSTRUCTION_CHARS = 32_768
        const val MAX_DESCRIPTION_CHARS = 2_000
        const val MAX_RESOURCE_CHARS = 8_000
        const val EMPTY_SCHEMA = "{\"type\":\"object\",\"properties\":{}}"
    }
}

/** Default factory: validates the config, then opens one HTTP connection per [connect]. */
object HttpMcpClientFactory : com.cordiact.core.mcp.McpClientFactory {
    override fun connect(config: McpServerConfig, credential: McpCredential?): McpConnection =
        McpHttpConnection(McpServerConfig.validated(config), credential)
}
