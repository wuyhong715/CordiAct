package com.cordiact.model.providers

import com.cordiact.core.action.ApprovalRequest
import com.cordiact.core.action.DefaultApprovalGateway
import com.cordiact.core.conversation.*
import com.cordiact.core.plugin.AuthoredPluginForeground
import com.cordiact.core.tool.ToolInvocationContext
import com.cordiact.core.toolloop.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.*

class ToolTurnLifecycleTest {
    private class Sessions : SessionManagerService {
        val stored = mutableListOf<ConversationMessage>()
        override fun createChildSession(workspaceId: String, parentSessionId: String, label: String, kind: SessionKind, depth: Int) = error("unused")
        override fun createSession(workspaceId: String, title: String) = ConversationSession("s", workspaceId, title, 0, 0)
        override fun getSession(sessionId: String) = ConversationSession(sessionId, "ws", "test", 0, 0)
        override fun listSessions(workspaceId: String) = emptyList<ConversationSession>()
        override fun listChildSessions(parentSessionId: String) = emptyList<ConversationSession>()
        override fun messages(sessionId: String) = stored.toList()
        override fun appendMessage(sessionId: String, role: MessageRole, content: String, metadataJson: String?): ConversationMessage =
            ConversationMessage("m${stored.size}", sessionId, role, content, stored.size.toLong(), metadataJson).also { stored += it }
    }
    private val spec = listOf(ModelToolSpec("read", "read", "{}"))
    private fun loop() = ToolLoopPlugin().apply { onEnable() }
    private fun toolResult(call: ModelToolCall) = ModelMessage(MessageRole.TOOL, "ok", toolCallId = call.id, toolName = call.name)

    @Test fun `completed is emitted once after tools and persistence with summed usage`() {
        val sessions = Sessions()
        val events = mutableListOf<ModelEvent>()
        var rounds = 0
        val adapter = object : ModelAdapterService {
            override fun models() = emptyList<ModelDescriptor>()
            override fun generate(request: ModelGenerationRequest, onEvent: (ModelEvent) -> Unit): ModelCall {
                rounds++
                if (rounds == 1) {
                    onEvent(ModelEvent.ToolCallsRequested(listOf(ModelToolCall("c", "read", "{}"))))
                    onEvent(ModelEvent.Completed(inputTokens = 10, outputTokens = 2))
                } else { onEvent(ModelEvent.Delta("answer")); onEvent(ModelEvent.Completed(inputTokens = 20, outputTokens = 3)) }
                return ModelCall {}
            }
        }
        val service = ConversationTurnService(sessions, adapter)
        val foreground = AuthoredPluginForeground().apply { resume(Any()); select("ws", "s") }
        val authoring = requireNotNull(foreground.openTurn("ws", "s"))
        service.submitWithTools("s", ModelId("p", "m"), "prompt", spec, loop(), ToolDispatcher { _, call -> toolResult(call) },
            ToolInvocationContext("ws", sessionId = "s", authoredPluginTurn = authoring)) { event ->
            if (event is ModelEvent.Completed) {
                assertEquals("answer", sessions.stored.last().content)
                assertFalse(authoring.isOpen)
                val trace = com.google.gson.JsonParser.parseString(sessions.stored.last().metadataJson).asJsonObject.getAsJsonObject("trace")
                assertEquals(30L, trace.get("inputTokens").asLong)
                assertEquals("read", trace.getAsJsonArray("tools").single().asJsonObject.get("name").asString)
            }
            events += event
        }
        assertEquals(2, rounds)
        assertEquals(1, events.filterIsInstance<ModelEvent.Completed>().size)
        assertEquals(30L, events.filterIsInstance<ModelEvent.Completed>().single().inputTokens)
        assertEquals(5L, events.filterIsInstance<ModelEvent.Completed>().single().outputTokens)
        assertTrue(events.indexOfFirst { it is ModelEvent.ToolCallCompleted } < events.indexOfFirst { it is ModelEvent.Completed })
        assertFalse(service.isTurnActive("s"))
    }

    @Test fun `both turn paths keep registration identity in the dispatcher and out of the provider request`() {
        val registry = com.cordiact.core.tool.InMemoryToolDefinitionRegistry()
        fun register(name: String) {
            registry.register(com.cordiact.core.tool.ToolDefinition(
                com.cordiact.core.tool.ToolDescriptor(name, name, name, com.cordiact.core.tool.ToolInputSchema(), "fixture"),
            ) { _, _ -> error("direct execution should not be needed") })
        }
        register("fixture.read")
        register("fixture.parent")
        val registration = requireNotNull(registry.get("fixture.read")?.registration)
        val parentRegistration = requireNotNull(registry.get("fixture.parent")?.registration)
        val specs = listOf(ModelToolSpec("fixture.read", "read", "{}", registration))
        val inherited = ToolInvocationContext("ws", sessionId = "s", toolRegistrations = mapOf("fixture.parent" to parentRegistration))
        for (delegated in listOf(false, true)) {
            val requests = mutableListOf<ModelGenerationRequest>()
            val bindings = mutableListOf<Map<String, com.cordiact.core.tool.ToolRegistration>>()
            val adapter = object : ModelAdapterService {
                override fun models() = emptyList<ModelDescriptor>()
                override fun generate(request: ModelGenerationRequest, onEvent: (ModelEvent) -> Unit): ModelCall {
                    requests += request
                    if (requests.size == 1) onEvent(ModelEvent.ToolCallsRequested(listOf(ModelToolCall("c", "fixture.read", "{}"))))
                    else onEvent(ModelEvent.Delta("finished"))
                    onEvent(ModelEvent.Completed())
                    return ModelCall {}
                }
            }
            val service = ConversationTurnService(Sessions(), adapter)
            val dispatcher = ToolDispatcher { context, call -> bindings += context.toolRegistrations; toolResult(call) }
            if (delegated) {
                assertIs<ToolLoopOutcome.Completed>(service.runTurnToCompletion("s", ModelId("p", "m"), "prompt", specs, loop(), dispatcher, inherited))
            } else {
                val events = mutableListOf<ModelEvent>()
                service.submitWithTools("s", ModelId("p", "m"), "prompt", specs, loop(), dispatcher, inherited, onEvent = events::add)
                assertEquals(1, events.filterIsInstance<ModelEvent.Completed>().size)
            }
            assertEquals(2, requests.size)
            assertEquals(listOf(ModelToolSpec("fixture.read", "read", "{}")), requests.first().tools)
            assertEquals(listOf(mapOf("fixture.read" to registration)), bindings)
        }
    }

    @Test fun `failure in a provider round cannot become successful empty completion`() {
        val events = mutableListOf<ModelEvent>()
        val adapter = object : ModelAdapterService {
            override fun models() = emptyList<ModelDescriptor>()
            override fun generate(request: ModelGenerationRequest, onEvent: (ModelEvent) -> Unit): ModelCall {
                onEvent(ModelEvent.Failed("offline", "disconnected")); return ModelCall {}
            }
        }
        val service = ConversationTurnService(Sessions(), adapter)
        service.submitWithTools("s", ModelId("p", "m"), "prompt", spec, loop(), ToolDispatcher { _, call -> toolResult(call) }, ToolInvocationContext("ws"), onEvent = events::add)
        assertEquals(1, events.filterIsInstance<ModelEvent.Failed>().size)
        assertTrue(events.none { it is ModelEvent.Completed })
        assertFalse(service.isTurnActive("s"))
    }

    @Test fun `cancel exact controller while dispatcher awaits human approval without a provider round`() {
        val gate = DefaultApprovalGateway()
        val foreground = AuthoredPluginForeground().apply { resume(Any()); select("ws", "s") }
        val authoring = requireNotNull(foreground.openTurn("ws", "s"))
        val entered = CountDownLatch(1)
        val controller = java.util.concurrent.atomic.AtomicReference<ModelCall>()
        val events = java.util.Collections.synchronizedList(mutableListOf<ModelEvent>())
        val adapter = object : ModelAdapterService {
            override fun models() = emptyList<ModelDescriptor>()
            override fun generate(request: ModelGenerationRequest, onEvent: (ModelEvent) -> Unit): ModelCall {
                onEvent(ModelEvent.ToolCallsRequested(listOf(ModelToolCall("c", "read", "{}"))))
                onEvent(ModelEvent.Completed())
                return ModelCall { }
            }
        }
        val service = ConversationTurnService(Sessions(), adapter)
        val executor = Executors.newSingleThreadExecutor()
        try {
            val result = executor.submit {
                service.submitWithTools("s", ModelId("p", "m"), "prompt", spec, loop(), ToolDispatcher { context, call ->
                    assertSame(authoring, context.authoredPluginTurn)
                    entered.countDown()
                    val decision = gate.requestApproval(ApprovalRequest("once", call.name, "Approval", isCurrent = { authoring.isOpen }))
                    assertFalse(decision.approved)
                    toolResult(call)
                }, ToolInvocationContext("ws", sessionId = "s", authoredPluginTurn = authoring),
                    onModelCallStarted = controller::set, onEvent = events::add)
            }
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            controller.get().cancel()
            assertFalse(authoring.isOpen)
            // Trigger a liveness observation even if the request registered just before cancellation.
            assertTrue(gate.pendingRequests().isEmpty())
            result.get(2, TimeUnit.SECONDS)
            assertEquals("turn_cancelled", events.filterIsInstance<ModelEvent.Failed>().single().code)
            assertFalse(service.isTurnActive("s"))
        } finally { executor.shutdownNow() }
    }

    @Test fun `cancel during in flight tool round releases wait and rejects later events`() {
        val entered = CountDownLatch(1)
        val providerCancelled = AtomicBoolean(false)
        val events = java.util.Collections.synchronizedList(mutableListOf<ModelEvent>())
        val adapter = object : ModelAdapterService {
            override fun models() = emptyList<ModelDescriptor>()
            override fun generate(request: ModelGenerationRequest, onEvent: (ModelEvent) -> Unit): ModelCall {
                entered.countDown()
                return ModelCall { providerCancelled.set(true); onEvent(ModelEvent.Delta("late")) }
            }
        }
        val service = ConversationTurnService(Sessions(), adapter)
        val executor = Executors.newSingleThreadExecutor()
        try {
            val result = executor.submit {
                service.submitWithTools("s", ModelId("p", "m"), "prompt", spec, loop(), ToolDispatcher { _, call -> toolResult(call) }, ToolInvocationContext("ws"), onEvent = events::add)
            }
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            assertTrue(service.cancelTurn("s"))
            result.get(2, TimeUnit.SECONDS)
            assertTrue(providerCancelled.get())
            assertEquals("turn_cancelled", events.filterIsInstance<ModelEvent.Failed>().single().code)
            assertTrue(events.none { it is ModelEvent.Delta || it is ModelEvent.Completed })
            assertFalse(service.isTurnActive("s"))
        } finally { executor.shutdownNow() }
    }
}
