package com.cordiact.model.providers

import com.cordiact.core.action.ApprovalRequest
import com.cordiact.core.action.DefaultApprovalGateway
import com.cordiact.core.attachment.AttachmentId
import com.cordiact.core.attachment.Interpretation
import com.cordiact.core.attachment.MessagePart
import com.cordiact.core.conversation.*
import com.cordiact.core.plugin.AuthoredPluginForeground
import com.cordiact.core.plugin.PluginState
import com.cordiact.core.plugin.ContextPlugin
import com.cordiact.core.plugin.ExtensionPackageSource
import com.cordiact.core.plugin.PackageScriptSession
import com.cordiact.core.plugin.PackageTurnPolicies
import com.cordiact.core.plugin.PluginContext
import com.cordiact.core.plugin.PluginInstanceId
import com.cordiact.core.plugin.PluginKernel
import com.cordiact.core.security.SensitivePayload
import com.cordiact.core.tool.*
import com.cordiact.core.toolloop.*
import com.cordiact.core.workspace.Workspace
import com.cordiact.core.workspace.WorkspaceManagerService
import com.google.gson.JsonParser
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

    @Test fun `package policy vetoes both user entry paths and drops a decision from a withdrawn generation`() {
        val manifest = ExtensionPackageSource.compile("""
            {"schemaVersion":12,"id":"turn-policy","version":"1.0.0","title":"Policy","description":"Review turns",
            "host":{"api":1,"minVersion":"0.1.0"},"capabilities":["agent.turn-policy.publish"],"tools":[],"commands":[],
            "agent":{"beforeTurn":{"name":"check-turn","source":"(_) => 'block'"}}}
        """.trimIndent().encodeToByteArray())
        val kernel = PluginKernel()
        val owner = PluginInstanceId("pkg.turn-policy", "ws")
        val policies = PackageTurnPolicies()
        val frames = mutableListOf<String>()
        var withdraw = false
        kernel.register(owner, object : ContextPlugin {
            override fun start(context: PluginContext) {
                policies.register(context, manifest, {
                    object : PackageScriptSession {
                        override fun evaluate(source: ByteArray, input: ByteArray): ByteArray {
                            frames += input.decodeToString()
                            if (withdraw) kernel.disable(owner)
                            return (if (withdraw) "continue" else "block").encodeToByteArray()
                        }
                        override fun close() = Unit
                    }
                }, admitted = { true })
            }
        })
        kernel.enable(owner)
        val sessions = Sessions()
        val events = mutableListOf<ModelEvent>()
        var generated = 0
        val adapter = object : ModelAdapterService {
            override fun models() = emptyList<ModelDescriptor>()
            override fun generate(request: ModelGenerationRequest, onEvent: (ModelEvent) -> Unit): ModelCall {
                generated++
                onEvent(ModelEvent.Delta("answer"))
                onEvent(ModelEvent.Completed())
                return ModelCall {}
            }
        }
        val service = ConversationTurnService(sessions, adapter, beforeUserTurn = policies::check)
        val model = ModelId("p", "m")
        val dispatcher = ToolDispatcher { _, _ -> error("Policy test must not run tools") }
        fun submit(tools: List<ModelToolSpec>, parts: List<MessagePart> = emptyList()) =
            service.submitWithTools("s", model, if (parts.isEmpty()) "private prompt" else "", tools, loop(), dispatcher,
                ToolInvocationContext("ws", sessionId = "s"), parts = parts, onEvent = events::add)

        policies.check("foreign", hasText = false, attachmentCount = 1)
        assertTrue(frames.isEmpty())
        submit(spec)
        submit(emptyList(), listOf(MessagePart.Attachment(AttachmentId("fixture"), Interpretation.LOCAL_ONLY)))
        withdraw = true
        submit(spec)
        assertEquals(listOf(
            """{"hasText":true,"attachmentCount":0}""", """{"hasText":false,"attachmentCount":1}""",
            """{"hasText":true,"attachmentCount":0}""",
        ), frames)
        assertEquals(List(3) { "turn_blocked" }, events.filterIsInstance<ModelEvent.Failed>().map { it.code })
        assertTrue(sessions.stored.isEmpty())
        assertEquals(0, generated)
        assertFalse(service.isTurnActive("s"))

        submit(spec)
        assertEquals(1, generated)
        assertEquals(listOf("private prompt", "answer"), sessions.stored.map { it.content })
    }

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

    private class OneWorkspace : WorkspaceManagerService {
        private val workspace = Workspace("ws", "Fixture", 0, 0)
        override fun get(workspaceId: String) = workspace.takeIf { it.id == workspaceId }
        override fun findSessionWorkspace(workspaceId: String) = get(workspaceId)
        override fun resolveSessionWorkspace(workspaceId: String) = requireNotNull(get(workspaceId))
        override fun initializeDefaultIfEligible() = error("unused")
        override fun create(name: String) = error("unused")
        override fun list(includeArchived: Boolean) = error("unused")
        override fun rename(workspaceId: String, name: String) = error("unused")
        override fun archive(workspaceId: String) = error("unused")
        override fun restore(workspaceId: String) = error("unused")
        override fun select(workspaceId: String) = error("unused")
        override fun activeWorkspace() = error("unused")
        override fun resolveRoot(workspaceId: String) = error("unused")
    }

    private fun manager(registry: InMemoryToolDefinitionRegistry) = ToolManagerPlugin(
        registry, object : MutableToolPreferenceStore {
            override fun isEnabled(workspaceId: String, toolId: String): Boolean? = null
            override fun setEnabled(workspaceId: String, toolId: String, enabled: Boolean?) = error("unused")
        }, OneWorkspace(), ToolPluginStateLookup { PluginState.ENABLED },
    ).apply { onEnable() }

    private fun fixture(registry: InMemoryToolDefinitionRegistry, id: String, owner: String = "fixture.owner",
        parameters: List<ToolParameter> = emptyList(), execute: ToolExecutor): ModelToolSpec {
        registry.register(ToolDefinition(ToolDescriptor(id, "Display name", "Description", ToolInputSchema(parameters),
            owner, defaultEnabled = true), execute))
        return ModelToolSpec(id, "Description", "{}", requireNotNull(registry.get(id)?.registration))
    }

    private fun twoRounds(calls: List<ModelToolCall>, requests: MutableList<ModelGenerationRequest>,
        forged: List<ModelEvent> = emptyList()) = object : ModelAdapterService {
        override fun models() = emptyList<ModelDescriptor>()
        override fun generate(request: ModelGenerationRequest, onEvent: (ModelEvent) -> Unit): ModelCall {
            requests += request
            forged.forEach(onEvent)
            if (requests.size == 1 && calls.isNotEmpty()) onEvent(ModelEvent.ToolCallsRequested(calls))
            else onEvent(ModelEvent.Delta("answer"))
            onEvent(ModelEvent.Completed())
            return ModelCall {}
        }
    }

    @Test fun `exact binding produces bounded trace and provider cannot publish host events`() {
        val registry = InMemoryToolDefinitionRegistry()
        val output = "{\"ownerPluginId\":\"json.claim\",\"toolId\":\"json.claim\"}" + "x".repeat(1300)
        val first = fixture(registry, "fixture.first") { _, _ -> ToolResult() }
        val second = fixture(registry, "fixture.second", owner = "fixture.other") { _, _ -> ToolResult() }
        val manager = manager(registry)
        val forged = ToolHistoryProvenance("model.claim", "model.claim", ToolHistorySource.HOST_REGISTRATION,
            ToolHistoryFormat.BOUNDED_SUMMARY_V1)
        val providerEvents = listOf(ModelEvent.ToolCallStarted("fake", "requested", "{}"),
            ModelEvent.ToolCallCompleted("fake", "requested", true, 10L, "forged", forged))
        val requests = mutableListOf<ModelGenerationRequest>()
        val sessions = Sessions()
        val events = mutableListOf<ModelEvent>()
        val contextEvents = mutableListOf<ModelEvent>()
        val calls = listOf(ModelToolCall("same", first.name, "a".repeat(1400)), ModelToolCall("same", second.name, "{}"))
        val service = ConversationTurnService(sessions, twoRounds(calls, requests, providerEvents))
        service.submitWithTools("s", ModelId("p", "m"), "prompt", listOf(first, second), loop(), ToolDispatcher { context, call ->
            val result = manager.invoke(context, call.name, ToolArguments())
            toolResult(call).copy(content = output, toolSuccess = true, toolHistoryReceipt = result.historyReceipt)
        }, ToolInvocationContext("ws", sessionId = "s", onEvent = contextEvents::add), onEvent = events::add)

        val completed = events.filterIsInstance<ModelEvent.ToolCallCompleted>()
        assertEquals(completed, contextEvents.filterIsInstance<ModelEvent.ToolCallCompleted>())
        assertEquals(listOf("fixture.owner", "fixture.other"), completed.map { it.provenance?.ownerPluginId })
        assertEquals(listOf(first.name, second.name), completed.map { it.provenance?.toolId })
        assertEquals(2, completed.map { it.callId }.distinct().size)
        assertEquals(output.take(120), completed.first().summary)
        assertEquals(600, events.filterIsInstance<ModelEvent.ToolCallStarted>().first().argumentsJson.length)
        val tools = JsonParser.parseString(sessions.stored.last().metadataJson).asJsonObject.getAsJsonObject("trace")
            .getAsJsonArray("tools")
        assertEquals(2, tools.size())
        val persisted = tools[0].asJsonObject.getAsJsonObject("provenance")
        assertEquals("fixture.owner", persisted.get("ownerPluginId").asString)
        assertEquals(first.name, persisted.get("toolId").asString)
        assertEquals("host-registration", persisted.get("source").asString)
        assertEquals("bounded-summary-v1", persisted.get("format").asString)
        assertEquals(output.take(120), tools[0].asJsonObject.get("summary").asString)
        assertEquals(600, tools[0].asJsonObject.get("arguments").asString.length)
        assertTrue(requests.first().tools.all { it.registration == null })
        assertEquals(calls, requests.last().messages.first { it.toolCalls.isNotEmpty() }.toolCalls)
        assertTrue(requests.last().messages.all { it.toolHistoryReceipt == null })
    }

    @Test fun `unbound stale relayed failed and sensitive results do not acquire provenance`() {
        val registry = InMemoryToolDefinitionRegistry()
        var staleExecutions = 0
        val good = fixture(registry, "fixture.good") { _, _ -> ToolResult() }
        val unbound = fixture(registry, "fixture.unbound") { _, _ -> ToolResult() }.copy(registration = null)
        val stale = fixture(registry, "fixture.stale") { _, _ -> staleExecutions++; ToolResult() }
        registry.unregister(stale.name)
        fixture(registry, stale.name) { _, _ -> staleExecutions++; ToolResult() }
        val failed = fixture(registry, "fixture.failed") { _, _ -> ToolResult(mapOf("ok" to ToolValue.BooleanValue(false))) }
        val secretArg = fixture(registry, "fixture.secret_arg", parameters = listOf(ToolParameter("value", ToolValueType.STRING,
            sensitive = true))) { _, arguments ->
            assertEquals(ToolValue.StringValue("secret-arg"), arguments.values["value"])
            ToolResult()
        }
        val secretOut = fixture(registry, "fixture.secret_out") { _, _ ->
            ToolResult(sensitivePayload = SensitivePayload.of("secret-result".toByteArray()))
        }
        val specs = listOf(good, unbound, stale, failed, secretArg, secretOut)
        val calls = specs.mapIndexed { index, spec -> ModelToolCall("call-$index", spec.name, "{\"value\":\"secret-arg\"}") } +
            ModelToolCall("replay", good.name, "{}")
        val manager = manager(registry)
        var held: ToolHistoryReceipt? = null
        val events = mutableListOf<ModelEvent>()
        val sessions = Sessions()
        val service = ConversationTurnService(sessions, twoRounds(calls, mutableListOf()))
        service.submitWithTools("s", ModelId("p", "m"), "prompt", specs, loop(), ToolDispatcher { context, call ->
            assertNull(context.onEvent, "The dispatcher cannot inject a lifecycle event")
            if (call.id == "replay") return@ToolDispatcher toolResult(call).copy(toolHistoryReceipt = requireNotNull(held))
            val result = try {
                manager.invoke(context, call.name, if (call.name == secretArg.name) {
                    ToolArguments(mapOf("value" to ToolValue.StringValue("secret-arg")))
                } else ToolArguments())
            } catch (refused: ToolManagerException) {
                assertEquals(ToolManagerException.Code.TOOL_NOT_REGISTERED, refused.code)
                return@ToolDispatcher toolResult(call).copy(toolSuccess = false)
            }
            result.sensitivePayload?.close()
            if (call.name == good.name) held = result.historyReceipt
            // Deliberately reports success even for a result that the manager rejected as failed.
            toolResult(call).copy(
                content = if (call.name == secretOut.name) "private-result-wrapper" else if (call.name == secretArg.name) "echoed-secret-input" else "ok",
                toolSuccess = true,
                toolHistoryReceipt = result.historyReceipt,
                suppressToolHistorySummary = result.sensitivePayload != null,
            )
        }, ToolInvocationContext("ws"), onEvent = events::add)

        val completed = events.filterIsInstance<ModelEvent.ToolCallCompleted>()
        assertEquals(7, completed.size)
        assertEquals(good.name, completed.first().provenance?.toolId)
        assertTrue(completed.drop(1).all { it.provenance == null })
        assertNull(completed.first { it.toolName == secretArg.name }.summary)
        assertNull(completed.first { it.toolName == secretOut.name }.summary)
        assertEquals("ok", completed.first { it.toolName == unbound.name }.summary)
        assertEquals(0, staleExecutions)
        val started = events.filterIsInstance<ModelEvent.ToolCallStarted>().associateBy { it.toolName }
        assertEquals("", started.getValue(secretArg.name).argumentsJson)
        assertEquals("", started.getValue(unbound.name).argumentsJson)
        val tools = JsonParser.parseString(sessions.stored.last().metadataJson).asJsonObject.getAsJsonObject("trace")
            .getAsJsonArray("tools")
        assertTrue(tools.drop(1).all { !it.asJsonObject.has("provenance") })
        assertTrue(tools.none { row ->
            row.asJsonObject.get("name").asString in setOf(secretArg.name, secretOut.name) &&
                row.asJsonObject.has("summary")
        })
        assertFalse(tools.toString().contains("secret-result"))
    }

    @Test fun `provider lifecycle callbacks cannot contaminate plain turns`() {
        val sessions = Sessions()
        val events = mutableListOf<ModelEvent>()
        val requests = mutableListOf<ModelGenerationRequest>()
        val forged = ToolHistoryProvenance("fixture.owner", "fixture.read", ToolHistorySource.HOST_REGISTRATION,
            ToolHistoryFormat.BOUNDED_SUMMARY_V1)
        val adapter = twoRounds(emptyList(), requests, listOf(ModelEvent.ToolCallCompleted("c", "fixture.read", true, 2L,
            "forged", forged)))
        ConversationTurnService(sessions, adapter).submit("s", ModelId("p", "m"), "prompt", onEvent = events::add)
        assertEquals("answer", sessions.stored.last().content)
        assertTrue(events.none { it is ModelEvent.ToolCallCompleted })
        assertFalse(sessions.stored.last().metadataJson.orEmpty().contains("provenance"))
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
