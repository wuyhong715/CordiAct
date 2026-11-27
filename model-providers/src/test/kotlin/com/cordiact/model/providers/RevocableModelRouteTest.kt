package com.cordiact.model.providers

import com.cordiact.core.conversation.*
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class RevocableModelRouteTest {
    private val request = ModelGenerationRequest("session", ModelId("wsroute_test", "approved"),
        listOf(ModelMessage(MessageRole.USER, "hello")), workspaceId = "alpha")

    @Test
    fun `accepted model streams through the existing protocol backend`() {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val auth = CompletableFuture<String>()
        server.createContext("/v1/chat/completions") { exchange ->
            auth.complete(exchange.requestHeaders.getFirst("Authorization"))
            exchange.requestBody.use { it.readBytes() }
            val data = ("data: {\"choices\":[{\"delta\":{\"content\":\"hello\"},\"finish_reason\":\"stop\"}]}\n\n" +
                "data: [DONE]\n\n").toByteArray()
            exchange.responseHeaders.set("Content-Type", "text/event-stream")
            exchange.sendResponseHeaders(200, data.size.toLong())
            exchange.responseBody.use { it.write(data) }
        }
        server.start()
        try {
            val config = ModelProviderConfig.forLoopbackTest("wsroute_test", "Test", ModelProtocol.OPENAI_CHAT_COMPLETIONS,
                "http://127.0.0.1:${server.address.port}/v1/chat/completions", listOf("approved"))
            ConfigurableModelBackend(config, ApiKeyProvider { "temporary-test-key" }).use { delegate ->
                RevocableModelRoute(delegate, { true }, { it.workspaceId == "alpha" && it.model.modelId == "approved" }).use { route ->
                    val delta = CompletableFuture<String>()
                    val finished = CompletableFuture<ModelEvent>()
                    route.generate(request) { event -> when (event) {
                        is ModelEvent.Delta -> delta.complete(event.text)
                        is ModelEvent.Completed, is ModelEvent.Failed -> finished.complete(event)
                        else -> Unit
                    } }
                    assertEquals("Bearer temporary-test-key", auth.get(5, TimeUnit.SECONDS))
                    assertEquals("hello", delta.get(5, TimeUnit.SECONDS))
                    assertIs<ModelEvent.Completed>(finished.get(5, TimeUnit.SECONDS))
                }
            }
        } finally { server.stop(0) }
    }

    @Test
    fun `revocation ends the active stream and blocks late data and new calls`() {
        lateinit var receive: (ModelEvent) -> Unit
        var deliveries = 0
        var cancelled = 0
        val backend = object : ModelBackend {
            override val providerId = request.model.providerId
            override fun models() = listOf(ModelDescriptor(request.model, "Approved", true))
            override fun generate(request: ModelGenerationRequest, onEvent: (ModelEvent) -> Unit): ModelCall {
                deliveries++
                receive = onEvent
                return ModelCall { cancelled++ }
            }
        }
        val route = RevocableModelRoute(backend, { true }, { it.workspaceId == "alpha" && it.model.modelId == "approved" })
        val denied = mutableListOf<ModelEvent>()
        route.generate(request.copy(workspaceId = "beta"), denied::add)
        route.generate(request.copy(model = ModelId(backend.providerId, "unapproved")), denied::add)
        assertEquals(0, deliveries)
        assertEquals(listOf("route_revoked", "route_revoked"), denied.map { assertIs<ModelEvent.Failed>(it).code })
        val events = mutableListOf<ModelEvent>()
        route.generate(request, events::add)
        receive(ModelEvent.Delta("allowed"))
        route.close()
        receive(ModelEvent.Delta("late"))
        assertEquals(1, cancelled)
        assertEquals(2, events.size)
        assertEquals(ModelEvent.Delta("allowed"), events.first())
        assertEquals("route_revoked", assertIs<ModelEvent.Failed>(events.last()).code)
        route.generate(request, denied::add)
        assertEquals(1, deliveries)
    }
}
