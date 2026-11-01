package com.cordiact.model.deepseek

import com.cordiact.core.conversation.ConversationMessage
import com.cordiact.core.conversation.ConversationSession
import com.cordiact.core.conversation.MessageRole
import com.cordiact.core.conversation.ModelAdapterService
import com.cordiact.core.conversation.ModelCall
import com.cordiact.core.conversation.ModelDescriptor
import com.cordiact.core.conversation.ModelEvent
import com.cordiact.core.conversation.ModelGenerationRequest
import com.cordiact.core.conversation.ModelId
import com.cordiact.core.conversation.SessionManagerService
import com.google.gson.JsonParser
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class DeepSeekBackendTest {
    @Test
    fun streamsCompletionThroughAdapterAndPersistsConversationTurn() {
        var requestBody = ""
        var authorization = ""
        val server = server { exchange ->
            authorization = exchange.requestHeaders.getFirst("Authorization").orEmpty()
            requestBody = exchange.requestBody.bufferedReader(StandardCharsets.UTF_8).use { it.readText() }
            val body = listOf(
                "data: {\"choices\":[{\"delta\":{\"content\":\"Hello\"},\"index\":0}]}\n\n",
                "data: {\"choices\":[{\"delta\":{\"content\":\" world\"},\"index\":0}]}\n\n",
                "data: {\"choices\":[],\"usage\":{\"prompt_tokens\":4,\"completion_tokens\":2}}\n\n",
                "data: [DONE]\n\n",
            ).joinToString("")
            exchange.responseHeaders.add("Content-Type", "text/event-stream; charset=utf-8")
            exchange.sendResponseHeaders(200, 0)
            exchange.responseBody.use { it.write(body.toByteArray(StandardCharsets.UTF_8)) }
        }
        val backend = DeepSeekBackend(
            credentials = ApiKeyProvider { "secret-test-key" },
            config = DeepSeekBackendConfig.forLoopbackTest(endpoint = "http://127.0.0.1:${server.address.port}/chat/completions"),
        )
        val adapter = AdapterHarness(backend)
        val sessions = SessionHarness()
        val session = sessions.createSession("workspace-1")
        val turns = ConversationTurnService(sessions, adapter)
        val events = Collections.synchronizedList(mutableListOf<ModelEvent>())
        val completed = CountDownLatch(1)
        try {
            turns.submit(session.id, ModelId("deepseek", "deepseek-flash"), "Hi") { event ->
                events += event
                if (event is ModelEvent.Completed || event is ModelEvent.Failed) completed.countDown()
            }
            assertTrue(completed.await(5, TimeUnit.SECONDS), "model stream did not terminate")
            assertEquals("Bearer secret-test-key", authorization)
            assertEquals("deepseek-flash", JsonParser.parseString(requestBody).asJsonObject.get("model").asString)
            assertTrue(JsonParser.parseString(requestBody).asJsonObject.get("stream").asBoolean)
            assertEquals("Hi", sessions.messages(session.id).first().content)
            assertEquals("Hello world", sessions.messages(session.id).last().content)
            assertEquals(listOf("Hello", " world"), events.filterIsInstance<ModelEvent.Delta>().map { it.text })
            assertEquals(ModelEvent.Completed(4, 2), events.last())
            assertEquals("deepseek-flash", adapter.models().single().id.modelId)
        } finally {
            backend.close()
            server.stop(0)
        }
    }

    @Test
    fun missingKeyFailsWithoutOpeningNetworkRequest() {
        val backend = DeepSeekBackend(ApiKeyProvider { null })
        val events = mutableListOf<ModelEvent>()
        try {
            backend.generate(request(), events::add)
            assertEquals("missing_credentials", assertIs<ModelEvent.Failed>(events.single()).code)
        } finally {
            backend.close()
        }
    }

    @Test
    fun rejectsNonTlsNonLoopbackEndpoint() {
        try {
            DeepSeekBackendConfig(endpoint = "http://example.invalid/chat/completions")
            throw AssertionError("plain HTTP endpoints must be rejected")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message.orEmpty().contains("HTTPS"))
        }
    }

    @Test
    fun incompleteSseDoesNotReportCompleted() {
        val server = server { exchange ->
            exchange.responseHeaders.add("Content-Type", "text/event-stream")
            val body = "data: {\"choices\":[{\"delta\":{\"content\":\"partial\"}}]}\n\n"
            exchange.sendResponseHeaders(200, body.length.toLong())
            exchange.responseBody.use { it.write(body.toByteArray(StandardCharsets.UTF_8)) }
        }
        val backend = DeepSeekBackend(
            ApiKeyProvider { "test" },
            DeepSeekBackendConfig.forLoopbackTest(endpoint = "http://127.0.0.1:${server.address.port}/chat/completions"),
        )
        val terminal = CountDownLatch(1)
        val events = Collections.synchronizedList(mutableListOf<ModelEvent>())
        try {
            backend.generate(request(), events::add).also { }
            // Wait until the worker has delivered its terminal error.
            waitFor { events.any { it is ModelEvent.Failed || it is ModelEvent.Completed } }
            terminal.countDown()
            assertTrue(terminal.await(1, TimeUnit.SECONDS))
            assertFalse(events.any { it is ModelEvent.Completed })
            assertEquals("incomplete_stream", events.filterIsInstance<ModelEvent.Failed>().single().code)
        } finally {
            backend.close()
            server.stop(0)
        }
    }

    @Test
    fun providerPluginRegistersAndDetachesItsBackend() {
        val adapter = com.cordiact.core.conversation.ModelAdapterPlugin()
        adapter.onEnable()
        val backend = DeepSeekBackend(ApiKeyProvider { "test" })
        val plugin = DeepSeekBackendPlugin(adapter, backend)
        try {
            plugin.onEnable()
            assertEquals(listOf("deepseek-flash"), adapter.models().map { it.id.modelId })
            plugin.onDisable()
            assertTrue(adapter.models().isEmpty())
        } finally {
            plugin.onUninstall()
            adapter.onDisable()
        }
    }

    @Test
    fun concurrentTurnForSameSessionIsRejected() {
        val sessions = SessionHarness()
        val session = sessions.createSession("workspace-1")
        val adapter = HoldingAdapter()
        val turns = ConversationTurnService(sessions, adapter)
        val first = turns.submit(session.id, ModelId("deepseek", "deepseek-flash"), "first") {}
        try {
            try {
                turns.submit(session.id, ModelId("deepseek", "deepseek-flash"), "second") {}
                throw AssertionError("a session may not run overlapping turns")
            } catch (expected: IllegalStateException) {
                assertTrue(expected.message.orEmpty().contains("already active"))
            }
        } finally {
            first.cancel()
        }
    }

    private fun request() = ModelGenerationRequest(
        sessionId = "session-1",
        model = ModelId("deepseek", "deepseek-flash"),
        messages = listOf(com.cordiact.core.conversation.ModelMessage(MessageRole.USER, "hello")),
    )

    private fun server(handler: (com.sun.net.httpserver.HttpExchange) -> Unit): HttpServer =
        HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/chat/completions", handler)
            start()
        }

    private fun waitFor(condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (!condition() && System.nanoTime() < deadline) Thread.sleep(5)
        assertTrue(condition(), "condition was not reached in time")
    }

    private class AdapterHarness(private val backend: DeepSeekBackend) : ModelAdapterService {
        override fun models(): List<ModelDescriptor> = backend.models()
        override fun generate(request: ModelGenerationRequest, onEvent: (ModelEvent) -> Unit): ModelCall {
            assertEquals("deepseek", request.model.providerId)
            return backend.generate(request, onEvent)
        }
    }

    private class HoldingAdapter : ModelAdapterService {
        override fun models(): List<ModelDescriptor> = emptyList()
        override fun generate(request: ModelGenerationRequest, onEvent: (ModelEvent) -> Unit): ModelCall = ModelCall {}
    }

    private class SessionHarness : SessionManagerService {
        private val sessions = linkedMapOf<String, ConversationSession>()
        private val messages = linkedMapOf<String, MutableList<ConversationMessage>>()
        override fun createSession(workspaceId: String, title: String): ConversationSession {
            val session = ConversationSession("session-${sessions.size + 1}", workspaceId, title, 1L, 1L)
            sessions[session.id] = session
            messages[session.id] = mutableListOf()
            return session
        }
        override fun getSession(sessionId: String): ConversationSession? = sessions[sessionId]
        override fun listSessions(workspaceId: String): List<ConversationSession> = sessions.values.filter { it.workspaceId == workspaceId }
        override fun appendMessage(sessionId: String, role: MessageRole, content: String): ConversationMessage {
            val item = ConversationMessage("message-${messages[sessionId]!!.size + 1}", sessionId, role, content, 1L)
            messages[sessionId]!!.add(item)
            return item
        }
        override fun messages(sessionId: String): List<ConversationMessage> = messages[sessionId].orEmpty().toList()
    }
}
