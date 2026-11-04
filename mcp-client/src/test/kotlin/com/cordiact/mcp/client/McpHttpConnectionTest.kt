package com.cordiact.mcp.client

import com.cordiact.core.mcp.McpConfigException
import com.cordiact.core.mcp.McpCredential
import com.cordiact.core.mcp.McpProtocolException
import com.cordiact.core.mcp.McpServerConfig
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.io.File
import java.net.InetSocketAddress
import java.net.ServerSocket
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Drives the transport against a real socket.
 *
 * `com.sun.net.httpserver` ships with the JDK, so this stays a plain JVM test with no test
 * server dependency, and it catches the things a mocked client cannot: header echo, SSE
 * framing, and status handling.
 */
class McpHttpConnectionTest {

    private var server: HttpServer? = null

    @AfterTest
    fun tearDown() {
        server?.stop(0)
        server = null
    }

    @Test
    fun handshakeListsToolsAndCallsThem() {
        val seenSessionIds = mutableListOf<String?>()
        val base = serve { exchange ->
            seenSessionIds += exchange.requestHeaders.getFirst("Mcp-Session-Id")
            when (methodOf(exchange)) {
                "initialize" -> {
                    exchange.responseHeaders.add("Mcp-Session-Id", "session-1")
                    reply(exchange, """{"jsonrpc":"2.0","id":${idOf(exchange)},"result":{"protocolVersion":"2025-06-18","capabilities":{"tools":{}},"serverInfo":{"name":"fake","version":"9.9"},"instructions":"be brief"}}""")
                }
                "notifications/initialized" -> {
                    exchange.sendResponseHeaders(202, -1)
                    exchange.close()
                }
                "tools/list" -> reply(
                    exchange,
                    """{"jsonrpc":"2.0","id":${idOf(exchange)},"result":{"tools":[{"name":"echo","description":"Echo","inputSchema":{"type":"object","properties":{"text":{"type":"string"}}}}]}}""",
                )
                "tools/call" -> reply(
                    exchange,
                    """{"jsonrpc":"2.0","id":${idOf(exchange)},"result":{"content":[{"type":"text","text":"hello"}]}}""",
                )
                else -> error("unexpected method ${methodOf(exchange)}")
            }
        }

        val connection = HttpMcpClientFactory.connect(config(base), credential = null)
        val info = connection.initialize()
        assertEquals("2025-06-18", info.protocolVersion)
        assertEquals("fake", info.serverName)
        assertEquals("be brief", info.instructions)

        val tools = connection.listTools()
        assertEquals(1, tools.size)
        assertEquals("echo", tools[0].name)
        assertTrue(tools[0].inputSchemaJson.contains("\"text\""))

        val result = connection.callTool("echo", """{"text":"hi"}""", 5_000)
        assertEquals("hello", result.text)
        assertTrue(!result.isError)

        // The session id from initialize must ride every later request, or the server treats
        // each call as a fresh uninitialized session.
        assertTrue(seenSessionIds.drop(1).all { it == "session-1" })
    }

    @Test
    fun anSseFramedResponseIsDecodedToo() {
        val base = serve { exchange ->
            when (methodOf(exchange)) {
                "initialize" -> reply(exchange, """{"jsonrpc":"2.0","id":${idOf(exchange)},"result":{"protocolVersion":"2025-06-18"}}""")
                "notifications/initialized" -> { exchange.sendResponseHeaders(202, -1); exchange.close() }
                "tools/list" -> {
                    val body = """{"jsonrpc":"2.0","id":${idOf(exchange)},"result":{"tools":[{"name":"ping"}]}}"""
                    val payload = "event: message\ndata: $body\n\n".toByteArray()
                    exchange.responseHeaders.add("Content-Type", "text/event-stream")
                    exchange.sendResponseHeaders(200, payload.size.toLong())
                    exchange.responseBody.use { it.write(payload) }
                }
                else -> error("unexpected method ${methodOf(exchange)}")
            }
        }

        val connection = HttpMcpClientFactory.connect(config(base), credential = null)
        connection.initialize()
        assertEquals(listOf("ping"), connection.listTools().map { it.name })
    }

    @Test
    fun aJsonRpcErrorSurfacesTheServerMessage() {
        val base = serve { exchange ->
            when (methodOf(exchange)) {
                "initialize" -> reply(exchange, """{"jsonrpc":"2.0","id":${idOf(exchange)},"result":{"protocolVersion":"2025-06-18"}}""")
                "notifications/initialized" -> { exchange.sendResponseHeaders(202, -1); exchange.close() }
                "tools/list" -> reply(
                    exchange,
                    """{"jsonrpc":"2.0","id":${idOf(exchange)},"error":{"code":-32601,"message":"tools are disabled"}}""",
                )
                else -> error("unexpected method ${methodOf(exchange)}")
            }
        }

        val connection = HttpMcpClientFactory.connect(config(base), credential = null)
        connection.initialize()
        val failure = assertFailsWith<McpProtocolException> { connection.listTools() }
        assertTrue(failure.message!!.contains("tools are disabled"))
        assertTrue(failure.message!!.contains("-32601"))
    }

    @Test
    fun aRejectedCredentialIsAConfigProblemNotAProtocolOne() {
        val base = serve { exchange ->
            exchange.sendResponseHeaders(401, -1)
            exchange.close()
        }
        val connection = HttpMcpClientFactory.connect(config(base), McpCredential("Authorization", "Bearer wrong"))
        assertFailsWith<McpConfigException> { connection.initialize() }
    }

    @Test
    fun aToolLevelErrorIsReportedAsDataNotAnException() {
        val base = serve { exchange ->
            when (methodOf(exchange)) {
                "initialize" -> reply(exchange, """{"jsonrpc":"2.0","id":${idOf(exchange)},"result":{"protocolVersion":"2025-06-18"}}""")
                "notifications/initialized" -> { exchange.sendResponseHeaders(202, -1); exchange.close() }
                "tools/call" -> reply(
                    exchange,
                    """{"jsonrpc":"2.0","id":${idOf(exchange)},"result":{"isError":true,"content":[{"type":"text","text":"bad input"}]}}""",
                )
                else -> error("unexpected method ${methodOf(exchange)}")
            }
        }
        val connection = HttpMcpClientFactory.connect(config(base), credential = null)
        connection.initialize()
        val result = connection.callTool("echo", "{}", 5_000)
        assertTrue(result.isError)
        assertEquals("bad input", result.text)
    }

    private fun config(base: String) = McpServerConfig(
        serverName = "fake",
        url = "$base/mcp",
        toolCallTimeoutMs = 5_000,
        connectTimeoutMs = 5_000,
    )

    private fun serve(handler: (HttpExchange) -> Unit): String {
        val socket = ServerSocket(0)
        val port = socket.localPort
        socket.close()
        val httpServer = HttpServer.create(InetSocketAddress("127.0.0.1", port), 0)
        httpServer.createContext("/mcp") { exchange -> runCatching { handler(exchange) } }
        httpServer.executor = null
        httpServer.start()
        server = httpServer
        return "http://127.0.0.1:$port"
    }

    private fun methodOf(exchange: HttpExchange): String {
        val body = exchange.requestBody.readBytes().toString(Charsets.UTF_8)
        bodyOf[exchange] = body
        return Regex("\"method\"\\s*:\\s*\"([^\"]+)\"").find(body)?.groupValues?.get(1)
            ?: throw IllegalStateException("no method in $body")
    }

    private fun idOf(exchange: HttpExchange): String =
        Regex("\"id\"\\s*:\\s*(-?\\d+)").find(bodyOf[exchange].orEmpty())?.groupValues?.get(1) ?: "null"

    private fun reply(exchange: HttpExchange, json: String) {
        val bytes = json.toByteArray()
        exchange.responseHeaders.add("Content-Type", "application/json")
        exchange.sendResponseHeaders(200, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
    }

    private val bodyOf = HashMap<HttpExchange, String>()
}
