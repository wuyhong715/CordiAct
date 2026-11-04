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

    private val requestIds = AtomicLong(0)
    private var sessionId: String? = null
    private var negotiatedProtocol: String? = null
    private var closed = false

    override fun initialize(): McpServerInfo {
        val response = request(
            method = "initialize",
            params = buildString {
                append("{\"protocolVersion\":").append(JsonWrite.string(SUPPORTED_PROTOCOL_VERSION))
                append(",\"capabilities\":{\"roots\":{\"listChanged\":false}}")
                append(",\"clientInfo\":{\"name\":").append(JsonWrite.string(CLIENT_NAME))
                append(",\"version\":").append(JsonWrite.string(CLIENT_VERSION)).append("}}")
            },
            timeoutMs = config.connectTimeoutMs,
        )
        val result = response.field("result")?.asObject()
            ?: throw McpProtocolException("initialize returned no result")
        val protocol = result.field("protocolVersion")?.asString()
            ?: throw McpProtocolException("initialize returned no protocolVersion")
        negotiatedProtocol = protocol
        val serverInfo = result.field("serverInfo")
        notify("notifications/initialized")
        return McpServerInfo(
            protocolVersion = protocol,
            serverName = serverInfo?.field("name")?.let { runCatching { it.asString() }.getOrNull() },
            serverVersion = serverInfo?.field("version")?.let { runCatching { it.asString() }.getOrNull() },
            instructions = result.field("instructions")?.let { runCatching { it.asString() }.getOrNull() }
                ?.take(MAX_INSTRUCTION_CHARS),
        )
    }

    override fun listTools(timeoutMs: Long): List<McpToolDescriptor> {
        val tools = mutableListOf<McpToolDescriptor>()
        var cursor: String? = null
        // Paginate, but bound the loop: a server that always returns a cursor must not hang a
        // plugin enable forever.
        repeat(MAX_PAGES) {
            val paramsJson = cursor?.let { "{\"cursor\":${JsonWrite.string(it)}}" } ?: "{}"
            val response = request("tools/list", paramsJson, timeoutMs)
            val result = response.field("result")?.asObject()
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
        val response = request("tools/call", params, timeoutMs)
        val result = response.field("result")?.asObject()
            ?: throw McpProtocolException("tools/call returned no result")
        val isError = result.field("isError")?.let { runCatching { it.asBoolean("isError") }.getOrNull() } ?: false
        return McpToolCallResult(text = flattenContent(result.field("content")), isError = isError)
    }

    override fun close() {
        if (closed) return
        closed = true
        // Streamable HTTP has no long-lived socket here, so there is nothing to tear down beyond
        // forgetting the session. A DELETE is optional per spec and servers may reject it, so it
        // is deliberately not attempted.
        sessionId = null
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

    private fun notify(method: String) {
        // Notifications carry no id and expect no body; a failure here must not abort the
        // handshake, because servers differ on whether they answer 202 or 404.
        runCatching { post(method, params = null, id = null, timeoutMs = config.connectTimeoutMs) }
    }

    /**
     * Send one request and return the JSON-RPC envelope.
     *
     * @throws McpProtocolException when the server answers with a JSON-RPC error, an
     *   unexpected id, or a body this client cannot parse.
     */
    private fun request(method: String, params: String, timeoutMs: Long): JsonValue.Obj {
        val id = requestIds.incrementAndGet()
        val body = post(method, params, id, timeoutMs)
        val envelope = JsonParser(body).parseDocument().asObject()
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
        return envelope
    }

    private fun post(method: String, params: String?, id: Long?, timeoutMs: Long): String {
        check(!closed) { "MCP connection '${config.serverName}' is closed" }
        val payload = buildString {
            append("{\"jsonrpc\":\"2.0\",\"method\":").append(JsonWrite.string(method))
            if (params != null) append(",\"params\":").append(params)
            if (id != null) append(",\"id\":").append(id)
            append('}')
        }
        val connection = (URL(config.url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = config.connectTimeoutMs.toInt()
            readTimeout = timeoutMs.toInt()
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Accept", "application/json, text/event-stream")
            setRequestProperty("MCP-Protocol-Version", negotiatedProtocol ?: SUPPORTED_PROTOCOL_VERSION)
            sessionId?.let { setRequestProperty("Mcp-Session-Id", it) }
            config.headers.forEach { (key, value) -> setRequestProperty(key, value) }
            credential?.let { setRequestProperty(it.headerName, it.value) }
        }
        return try {
            connection.outputStream.use { it.write(payload.toByteArray(Charsets.UTF_8)) }
            val status = connection.responseCode
            val returnedSession = connection.getHeaderField("Mcp-Session-Id")
            if (returnedSession != null) sessionId = returnedSession
            when {
                status in 200..299 -> readBody(connection.inputStream)
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

    private companion object {
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
