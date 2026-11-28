package com.cordiact.core.conversation

import com.cordiact.core.plugin.ContextPlugin
import com.cordiact.core.plugin.PluginContext
import com.cordiact.core.plugin.PluginInstanceId
import com.cordiact.core.plugin.PluginInstanceState
import com.cordiact.core.plugin.PluginKernel
import com.cordiact.core.workspace.Workspace
import com.cordiact.core.workspace.WorkspaceSessionResolver
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ConversationPluginsTest {
    @Test
    fun contextBackendWaitsForAdapterAndWithdrawsAnInFlightGeneration() {
        val kernel = PluginKernel()
        val adapter = ModelAdapterPlugin(kernel)
        val owner = PluginInstanceId("provider.test")
        val events = mutableListOf<ModelEvent>()
        var lateCancel = false
        val backend = object : ModelBackend {
            override val providerId = "test-provider"
            override fun models() = listOf(ModelDescriptor(request().model, "Test"))
            override fun generate(request: ModelGenerationRequest, onEvent: (ModelEvent) -> Unit): ModelCall {
                kernel.disable(owner)
                onEvent(ModelEvent.Delta("stale output"))
                onEvent(ModelEvent.Completed())
                return ModelCall { lateCancel = true }
            }
        }
        kernel.register(owner, object : ContextPlugin {
            override val requiredServices = setOf(MODEL_BACKEND_CONTRIBUTIONS)
            override fun start(context: PluginContext) {
                context.service(MODEL_BACKEND_CONTRIBUTIONS).register(context, backend)
            }
        })
        kernel.enable(owner)
        assertEquals(PluginInstanceState.WAITING, kernel.status(owner).state)
        adapter.onEnable()
        assertEquals(backend.models(), adapter.models())
        adapter.generate(request(), events::add)
        assertTrue(lateCancel)
        assertEquals(listOf("provider_revoked"), events.map { (it as ModelEvent.Failed).code })
        assertTrue(adapter.models().isEmpty())
        val replacement = FakeBackend(backend.providerId)
        adapter.attachBackend("provider.replacement", replacement)
        events.clear()
        adapter.generate(request(), events::add)
        assertEquals(listOf(ModelEvent.Delta("Hello"), ModelEvent.Completed(2, 1)), events)
    }

    @Test
    fun staleBackendHandleCannotRemoveReplacement() {
        val adapter = ModelAdapterPlugin().apply { onEnable() }
        val first = adapter.attachBackend("provider.test", FakeBackend("test-provider"))
        adapter.detachBackend("provider.test", "test-provider")
        val replacement = FakeBackend("test-provider")
        adapter.attachBackend("provider.test", replacement)
        first.close()
        assertEquals(listOf(replacement.descriptor), adapter.models())
    }
    @Test
    fun modelAdapterRoutesGenerationAndEnforcesBackendOwnership() {
        val plugin = ModelAdapterPlugin()
        val backend = FakeBackend("test-provider")
        plugin.onEnable()
        plugin.attachBackend("provider.test", backend)
        assertEquals(listOf(backend.descriptor), plugin.models())

        val events = mutableListOf<ModelEvent>()
        plugin.generate(request(), events::add)
        assertEquals(listOf(ModelEvent.Delta("Hello"), ModelEvent.Completed(2, 1)), events)
        assertFailsWith<IllegalStateException> { plugin.detachBackend("untrusted.plugin", "test-provider") }
        plugin.detachBackend("provider.test", "test-provider")
        assertTrue(plugin.models().isEmpty())
    }

    @Test
    fun disablingModelAdapterCancelsInFlightProviderCalls() {
        val backend = HoldingBackend()
        val plugin = ModelAdapterPlugin()
        plugin.onEnable()
        plugin.attachBackend("provider.test", backend)
        plugin.generate(request()) {}
        assertFalse(backend.cancelled)
        plugin.onDisable()
        assertTrue(backend.cancelled)
    }

    @Test
    fun modelAdapterFailsClosedWhenDisabledOrModelMissing() {
        val plugin = ModelAdapterPlugin()
        val disabled = mutableListOf<ModelEvent>()
        plugin.generate(request(), disabled::add)
        assertEquals("plugin_disabled", (disabled.single() as ModelEvent.Failed).code)

        plugin.onEnable()
        plugin.attachBackend("provider.test", FakeBackend("test-provider"))
        val missing = mutableListOf<ModelEvent>()
        plugin.generate(request().copy(model = ModelId("test-provider", "missing")), missing::add)
        assertEquals("model_unavailable", (missing.single() as ModelEvent.Failed).code)
        plugin.onDisable()
        assertFailsWith<IllegalStateException> { plugin.models() }
    }

    @Test
    fun sessionManagerUsesStableGeneratedIdAndWorkspaceScopedStore() {
        val store = FakeSessionStore()
        val plugin = SessionManagerPlugin(
            store,
            WorkspaceSessionResolver { id -> if (id == "workspace-1") Workspace(id, "Test", 1L, 1L) else null },
            clockMillis = { 100L },
            idFactory = { "session-1" },
        )
        assertFailsWith<IllegalStateException> { plugin.listSessions("workspace-a") }
        plugin.onEnable()
        assertFailsWith<IllegalArgumentException> { plugin.createSession("missing", "No workspace") }
        val created = plugin.createSession("workspace-1", "  Notes  ")
        assertEquals("session-1", created.id)
        assertEquals("Notes", created.title)
        plugin.appendMessage(created.id, MessageRole.USER, "Hi")
        assertEquals(1, plugin.messages(created.id).size)
        assertEquals(listOf(created), plugin.listSessions("workspace-1"))
        assertFailsWith<IllegalArgumentException> { plugin.listSessions("workspace-b") }
        plugin.onDisable()
        assertFailsWith<IllegalStateException> { plugin.messages(created.id) }
    }

    @Test
    fun archivedSessionCleanupUsesPersistedOwnerAndRetriesBeforeRestore() {
        val store = FakeSessionStore()
        val seen = mutableListOf<Pair<String, String>>()
        var cleanupFails = true
        val plugin = SessionManagerPlugin(
            store,
            WorkspaceSessionResolver { id -> Workspace(id, "Test", 1L, 1L) },
            clockMillis = { 100L },
            idFactory = { "session-1" },
            invalidateSessionResources = { archived ->
                assertEquals(100L, store.get(archived.id)?.archivedAtMillis)
                seen += archived.workspaceId to archived.id
                check(!cleanupFails) { "cleanup unavailable" }
            },
        )
        plugin.onEnable()
        val created = plugin.createSession("workspace-1", "Test")
        plugin.restoreSession(created.id) // no invalidation of an already-open session
        assertTrue(seen.isEmpty())
        assertFailsWith<SessionArchiveCleanupException> { plugin.archiveSession(created.id) }
        assertEquals(100L, plugin.getSession(created.id)?.archivedAtMillis)
        assertFailsWith<IllegalStateException> { plugin.restoreSession(created.id) }
        assertEquals(100L, plugin.getSession(created.id)?.archivedAtMillis)
        cleanupFails = false
        assertEquals(null, plugin.restoreSession(created.id).archivedAtMillis)
        assertEquals(List(3) { "workspace-1" to "session-1" }, seen)
    }

    private fun request() = ModelGenerationRequest(
        sessionId = "session-1",
        model = ModelId("test-provider", "echo-v1"),
        messages = listOf(ModelMessage(MessageRole.USER, "Hello")),
    )

    private class FakeBackend(override val providerId: String) : ModelBackend {
        val descriptor = ModelDescriptor(ModelId(providerId, "echo-v1"), "Echo v1")
        override fun models() = listOf(descriptor)
        override fun generate(request: ModelGenerationRequest, onEvent: (ModelEvent) -> Unit): ModelCall {
            onEvent(ModelEvent.Delta("Hello"))
            onEvent(ModelEvent.Completed(inputTokens = 2, outputTokens = 1))
            return ModelCall {}
        }
    }

    private class HoldingBackend : ModelBackend {
        override val providerId = "test-provider"
        var cancelled = false
        override fun models() = listOf(ModelDescriptor(ModelId(providerId, "echo-v1"), "Echo"))
        override fun generate(request: ModelGenerationRequest, onEvent: (ModelEvent) -> Unit) = ModelCall { cancelled = true }
    }

    private class FakeSessionStore : ConversationSessionStore {
        private val sessions = linkedMapOf<String, ConversationSession>()
        private val messages = mutableMapOf<String, MutableList<ConversationMessage>>()
        private var nextMessage = 0
        override fun create(
            id: String,
            workspaceId: String,
            title: String,
            nowMillis: Long,
            parentSessionId: String?,
            kind: SessionKind,
            agentLabel: String?,
            depth: Int,
        ): ConversationSession {
            val value = ConversationSession(id, workspaceId, title, nowMillis, nowMillis, parentSessionId, kind, agentLabel, depth)
            sessions[id] = value
            return value
        }
        override fun get(sessionId: String) = sessions[sessionId]
        override fun archiveSession(sessionId: String, nowMillis: Long): ConversationSession =
            sessions.getValue(sessionId).let { it.copy(archivedAtMillis = it.archivedAtMillis ?: nowMillis) }
                .also { sessions[sessionId] = it }
        override fun restoreSession(sessionId: String): ConversationSession =
            sessions.getValue(sessionId).copy(archivedAtMillis = null).also { sessions[sessionId] = it }
        override fun list(workspaceId: String) =
            sessions.values.filter { it.workspaceId == workspaceId && it.kind == SessionKind.USER }
        override fun listChildren(parentSessionId: String) =
            sessions.values.filter { it.parentSessionId == parentSessionId }
        override fun append(
            sessionId: String,
            role: MessageRole,
            content: String,
            nowMillis: Long,
            metadataJson: String?,
        ): ConversationMessage {
            check(sessionId in sessions)
            val message = ConversationMessage("message-${++nextMessage}", sessionId, role, content, nowMillis, metadataJson)
            messages.getOrPut(sessionId) { mutableListOf() }.add(message)
            return message
        }
        override fun messages(sessionId: String) = messages[sessionId].orEmpty().toList()
    }

}
