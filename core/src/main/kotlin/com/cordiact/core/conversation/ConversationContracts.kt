package com.cordiact.core.conversation

import com.cordiact.core.plugin.ManagedPlugin
import com.cordiact.core.plugin.PluginSpec
import com.cordiact.core.plugin.PluginTarget
import com.cordiact.core.workspace.WorkspaceSessionResolver
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/** Common typed model identity. Provider-specific configuration stays inside the adapter plugin. */
data class ModelId(val providerId: String, val modelId: String)

data class ModelDescriptor(
    val id: ModelId,
    val displayName: String,
    val supportsStreaming: Boolean = true,
)

enum class MessageRole { SYSTEM, USER, ASSISTANT, TOOL }

data class ModelMessage(val role: MessageRole, val content: String)

data class ModelGenerationRequest(
    val sessionId: String,
    val model: ModelId,
    val messages: List<ModelMessage>,
    val temperature: Double? = null,
    val maxOutputTokens: Int? = null,
)

sealed interface ModelEvent {
    data class Delta(val text: String) : ModelEvent
    data class Completed(val inputTokens: Long? = null, val outputTokens: Long? = null) : ModelEvent
    data class Failed(val code: String, val message: String, val retryable: Boolean = false) : ModelEvent
}

fun interface ModelCall { fun cancel() }

/** Provider integration contract; concrete providers are separate plugins/adapters. */
interface ModelBackend {
    val providerId: String
    fun models(): List<ModelDescriptor>
    fun generate(request: ModelGenerationRequest, onEvent: (ModelEvent) -> Unit): ModelCall
}

interface ModelAdapterService {
    fun models(): List<ModelDescriptor>
    fun generate(request: ModelGenerationRequest, onEvent: (ModelEvent) -> Unit): ModelCall
}

data class ConversationSession(
    val id: String,
    val workspaceId: String,
    val title: String,
    val createdAtMillis: Long,
    val updatedAtMillis: Long,
)

data class ConversationMessage(
    val id: String,
    val sessionId: String,
    val role: MessageRole,
    val content: String,
    val createdAtMillis: Long,
)

/** Storage port; the app implementation is durable SQLite and scoped by session/workspace. */
interface ConversationSessionStore {
    fun create(id: String, workspaceId: String, title: String, nowMillis: Long): ConversationSession
    fun get(sessionId: String): ConversationSession?
    fun list(workspaceId: String): List<ConversationSession>
    fun append(sessionId: String, role: MessageRole, content: String, nowMillis: Long): ConversationMessage
    fun messages(sessionId: String): List<ConversationMessage>
}

interface SessionManagerService {
    fun createSession(workspaceId: String, title: String = "New conversation"): ConversationSession
    fun getSession(sessionId: String): ConversationSession?
    fun listSessions(workspaceId: String): List<ConversationSession>
    fun appendMessage(sessionId: String, role: MessageRole, content: String): ConversationMessage
    fun messages(sessionId: String): List<ConversationMessage>
}

const val MODEL_ADAPTER_PLUGIN_ID = "core.model-adapter"
const val SESSION_MANAGER_PLUGIN_ID = "core.session-manager"
const val CORE_CONVERSATION_PLUGIN_VERSION = "0.1.0"

fun modelAdapterPluginSpec() = PluginSpec(
    id = MODEL_ADAPTER_PLUGIN_ID,
    version = CORE_CONVERSATION_PLUGIN_VERSION,
    target = PluginTarget.ANDROID_NATIVE,
)

fun sessionManagerPluginSpec() = PluginSpec(
    id = SESSION_MANAGER_PLUGIN_ID,
    version = CORE_CONVERSATION_PLUGIN_VERSION,
    target = PluginTarget.ANDROID_NATIVE,
    dependencies = setOf(com.cordiact.core.workspace.WORKSPACE_MANAGER_PLUGIN_ID),
)

/** Lifecycle-managed, protected system plugin. Provider plugins register backends through the host. */
class ModelAdapterPlugin : ManagedPlugin, ModelAdapterService {
    override val spec = modelAdapterPluginSpec()
    private data class OwnedBackend(val ownerPluginId: String, val backend: ModelBackend)
    private val backends = linkedMapOf<String, OwnedBackend>()
    private val activeCalls = linkedMapOf<String, Pair<String, ModelCall>>()
    @Volatile private var enabled = false

    @Synchronized
    fun attachBackend(ownerPluginId: String, backend: ModelBackend) {
        check(enabled) { "Model Adapter plugin is not enabled" }
        require(ownerPluginId.isNotBlank()) { "Backend owner plugin id is required" }
        require(backend.providerId.isNotBlank()) { "Provider id is required" }
        require(backend.providerId.matches(Regex("[a-z0-9][a-z0-9._-]{1,127}"))) { "Invalid provider id" }
        val descriptors = backend.models()
        require(descriptors.all { it.id.providerId == backend.providerId && it.id.modelId.isNotBlank() }) {
            "Backend '${backend.providerId}' returned invalid model descriptors"
        }
        val previous = backends[backend.providerId]
        check(previous == null || (previous.ownerPluginId == ownerPluginId && previous.backend === backend)) {
            "A backend for provider '${backend.providerId}' is already attached"
        }
        backends[backend.providerId] = OwnedBackend(ownerPluginId, backend)
    }

    @Synchronized
    fun detachBackend(ownerPluginId: String, providerId: String) {
        // Provider ownership is expected to be verified by the plugin host before this call.
        val binding = backends[providerId] ?: return
        check(binding.ownerPluginId == ownerPluginId) { "Plugin '$ownerPluginId' does not own provider '$providerId'" }
        backends.remove(providerId)
        cancelCallsForProvider(providerId)
    }

    override fun onEnable() { enabled = true }

    override fun onDisable() {
        enabled = false
        val calls = synchronized(this) {
            backends.clear()
            activeCalls.values.map { it.second }.also { activeCalls.clear() }
        }
        calls.forEach { runCatching { it.cancel() } }
    }

    override fun onUninstall() = onDisable()

    @Synchronized
    override fun models(): List<ModelDescriptor> {
        check(enabled) { "Model Adapter plugin is not enabled" }
        return backends.values.flatMap { it.backend.models() }.distinctBy { it.id }
    }

    override fun generate(request: ModelGenerationRequest, onEvent: (ModelEvent) -> Unit): ModelCall {
        val backend = synchronized(this) {
            if (!enabled) {
                onEvent(ModelEvent.Failed("plugin_disabled", "Model Adapter is not enabled"))
                return ModelCall {}
            }
            backends[request.model.providerId]?.backend
        }
        if (backend == null) {
            onEvent(ModelEvent.Failed("provider_unavailable", "No adapter is available for '${request.model.providerId}'"))
            return ModelCall {}
        }
        if (backend.models().none { it.id == request.model }) {
            onEvent(ModelEvent.Failed("model_unavailable", "Model '${request.model.modelId}' is not available"))
            return ModelCall {}
        }
        val callId = UUID.randomUUID().toString()
        val terminal = AtomicBoolean(false)
        return try {
            val delegate = backend.generate(request) { event ->
                if (!terminal.get()) {
                    onEvent(event)
                    if (event is ModelEvent.Completed || event is ModelEvent.Failed) {
                        terminal.set(true)
                        synchronized(this) { activeCalls.remove(callId) }
                    }
                }
            }
            val tracked = ModelCall {
                if (terminal.compareAndSet(false, true)) delegate.cancel()
                synchronized(this) { activeCalls.remove(callId) }
            }
            synchronized(this) {
                if (enabled && !terminal.get()) activeCalls[callId] = request.model.providerId to tracked
                else if (!terminal.get()) tracked.cancel()
            }
            tracked
        } catch (failure: Exception) {
            if (terminal.compareAndSet(false, true)) {
                onEvent(ModelEvent.Failed("provider_error", failure.message ?: "Model request failed", retryable = true))
            }
            ModelCall {}
        }
    }

    private fun cancelCallsForProvider(providerId: String) {
        val calls = synchronized(this) {
            activeCalls.filterValues { it.first == providerId }.keys.toList().mapNotNull { activeCalls.remove(it)?.second }
        }
        calls.forEach { runCatching { it.cancel() } }
    }
}

/** Lifecycle-managed system plugin for durable workspace-scoped conversation history. */
class SessionManagerPlugin(
    private val store: ConversationSessionStore,
    private val workspaces: WorkspaceSessionResolver,
    private val clockMillis: () -> Long = System::currentTimeMillis,
    private val idFactory: () -> String = { java.util.UUID.randomUUID().toString() },
) : ManagedPlugin, SessionManagerService {
    override val spec = sessionManagerPluginSpec()
    @Volatile private var enabled = false

    override fun onEnable() { enabled = true }
    override fun onDisable() { enabled = false }
    override fun onUninstall() { enabled = false }

    override fun createSession(workspaceId: String, title: String): ConversationSession {
        requireEnabled()
        require(workspaceId.isNotBlank()) { "Workspace id must not be blank" }
        requireNotNull(workspaces.findSessionWorkspace(workspaceId)) { "Workspace does not exist or is archived" }
        val normalizedTitle = title.trim().ifBlank { "New conversation" }.take(160)
        val id = idFactory()
        require(id.isNotBlank()) { "Session id factory returned a blank id" }
        return store.create(id, workspaceId, normalizedTitle, clockMillis())
    }

    override fun getSession(sessionId: String): ConversationSession? {
        requireEnabled(); return store.get(sessionId)
    }

    override fun listSessions(workspaceId: String): List<ConversationSession> {
        requireEnabled()
        require(workspaceId.isNotBlank()) { "Workspace id must not be blank" }
        requireNotNull(workspaces.findSessionWorkspace(workspaceId)) { "Workspace does not exist or is archived" }
        return store.list(workspaceId)
    }

    override fun appendMessage(sessionId: String, role: MessageRole, content: String): ConversationMessage {
        requireEnabled()
        require(sessionId.isNotBlank())
        require(content.isNotBlank())
        return store.append(sessionId, role, content, clockMillis())
    }

    override fun messages(sessionId: String): List<ConversationMessage> {
        requireEnabled(); return store.messages(sessionId)
    }

    private fun requireEnabled() = check(enabled) { "Session Manager plugin is not enabled" }
}
