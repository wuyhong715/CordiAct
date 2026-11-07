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
    /**
     * Total context window size in tokens, if known.
     *
     * When null, callers fall back to the provider default lookup table or conservative limits.
     */
    val contextWindowTokens: Int? = null,
)

enum class MessageRole { SYSTEM, USER, ASSISTANT, TOOL }

/**
 * One tool the model may call.
 *
 * Deliberately mirrors [ToolDescriptor] rather than reusing it: the model contract must not
 * depend on the tool manager, or a provider adapter would gain the ability to dispatch. This is
 * a request shape, not a capability.
 */
data class ModelToolSpec(
    val name: String,
    val description: String,
    /** JSON Schema object as a string; providers want it as-is. */
    val parametersJson: String,
)

/**
 * A tool call the model asked for.
 *
 * [argumentsJson] is kept as raw JSON rather than a parsed map so each protocol can relay what
 * the model actually sent; parsing happens once, at dispatch, where failures can be reported
 * back to the model instead of aborting the turn.
 */
data class ModelToolCall(
    val id: String,
    val name: String,
    val argumentsJson: String,
)

data class ModelMessage(
    val role: MessageRole,
    val content: String,
    /** Set on an assistant message that requested tools. */
    val toolCalls: List<ModelToolCall> = emptyList(),
    /** Set on a TOOL message, linking the result to the call it answers. */
    val toolCallId: String? = null,
    /**
     * The tool's name, set on a TOOL message.
     *
     * Carried because Gemini matches results to calls by name rather than id; a protocol that
     * only knows ids can ignore it.
     */
    val toolName: String? = null,
    /**
     * Host-only evidence produced by a tool call, as JSON.
     *
     * Never sent to a model: providers serialise role/content/toolCalls only. The session
     * layer persists it so the UI can show what a turn actually looked at (for example web
     * sources) after a restart.
     */
    val metadataJson: String? = null,
) {
    init {
        require(role != MessageRole.TOOL || toolCallId != null) {
            "A tool result must carry the toolCallId it answers"
        }
    }
}

/** Provider-neutral sampling parameters. Null means "not specified at this layer". */
data class GenerationParameters(
    val temperature: Double? = null,
    val maxOutputTokens: Int? = null,
    val topP: Double? = null,
) {
    init {
        require(temperature == null || temperature in 0.0..MAX_TEMPERATURE) {
            "temperature must be within 0..$MAX_TEMPERATURE"
        }
        require(topP == null || topP in 0.0..1.0) { "topP must be within 0..1" }
        require(maxOutputTokens == null || maxOutputTokens in 1..MAX_OUTPUT_TOKENS) {
            "maxOutputTokens must be within 1..$MAX_OUTPUT_TOKENS"
        }
    }

    /** Per-request values win; nulls fall through to the provider defaults. */
    fun mergedWith(overrides: GenerationParameters?): GenerationParameters =
        if (overrides == null) this else GenerationParameters(
            temperature = overrides.temperature ?: temperature,
            maxOutputTokens = overrides.maxOutputTokens ?: maxOutputTokens,
            topP = overrides.topP ?: topP,
        )

    companion object {
        const val MAX_TEMPERATURE = 2.0
        const val MAX_OUTPUT_TOKENS = 1_000_000
        val NONE = GenerationParameters()
    }
}

data class ModelGenerationRequest(
    val sessionId: String,
    val model: ModelId,
    val messages: List<ModelMessage>,
    /** Per-turn overrides; the provider's stored defaults fill any null field. */
    val parameters: GenerationParameters? = null,
    /** Tools the model may call this turn. Empty means a plain text completion. */
    val tools: List<ModelToolSpec> = emptyList(),
)

sealed interface ModelEvent {
    data class Delta(val text: String) : ModelEvent

    /**
     * The model finished asking for tools instead of answering.
     *
     * Emitted once per response that contains tool calls, before [Completed]. Carrying them in
     * an event rather than on [Completed] keeps the streaming contract unchanged for callers
     * that only care about text.
     */
    data class ToolCallsRequested(val calls: List<ModelToolCall>) : ModelEvent
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

/**
 * What a Session is for.
 *
 * A delegated agent is an ordinary Session with its own transcript, its own turn and its own
 * tools; only [USER] sessions belong in the Workspace's conversation list. Keeping them in the
 * same table rather than a parallel one means the model-facing id, the history query and the
 * deletion cascade are all the ones that already exist.
 */
enum class SessionKind { USER, SUBAGENT, ORCHESTRATION }

data class ConversationSession(
    val id: String,
    val workspaceId: String,
    val title: String,
    val createdAtMillis: Long,
    val updatedAtMillis: Long,
    /** The Session that delegated this one, or null for a root conversation. */
    val parentSessionId: String? = null,
    val kind: SessionKind = SessionKind.USER,
    /** 3-5 word display label for a delegated Session; null for a root conversation. */
    val agentLabel: String? = null,
    /**
     * Delegation depth, 0 for a root conversation.
     *
     * Persisted rather than derived by walking parents: the depth limit is checked on every
     * delegation, and a walk would put the whole ancestor chain on that path.
     */
    val depth: Int = 0,
)

data class ConversationMessage(
    val id: String,
    val sessionId: String,
    val role: MessageRole,
    val content: String,
    val createdAtMillis: Long,
    /** Host-only evidence for this message, as JSON; null for ordinary text turns. */
    val metadataJson: String? = null,
)

/** Storage port; the app implementation is durable SQLite and scoped by session/workspace. */
interface ConversationSessionStore {
    /**
     * Creates one Session.
     *
     * The delegation fields default to a root conversation, so every existing caller keeps its
     * meaning; only a subagent runtime passes them.
     */
    fun create(
        id: String,
        workspaceId: String,
        title: String,
        nowMillis: Long,
        parentSessionId: String? = null,
        kind: SessionKind = SessionKind.USER,
        agentLabel: String? = null,
        depth: Int = 0,
    ): ConversationSession

    fun get(sessionId: String): ConversationSession?

    /** Root conversations only: a Workspace's list must not fill up with delegated Sessions. */
    fun list(workspaceId: String): List<ConversationSession>

    /** Direct children of [parentSessionId], newest last. Empty when there are none. */
    fun listChildren(parentSessionId: String): List<ConversationSession>

    fun append(
        sessionId: String,
        role: MessageRole,
        content: String,
        nowMillis: Long,
        metadataJson: String? = null,
    ): ConversationMessage
    fun messages(sessionId: String): List<ConversationMessage>
}

interface SessionManagerService {
    fun createSession(workspaceId: String, title: String = "New conversation"): ConversationSession

    /**
     * Creates a Session that belongs to a delegated agent.
     *
     * Kept separate from [createSession] so that "a Workspace conversation" and "a child of this
     * conversation" are not distinguished by a nullable argument at every call site: the origin
     * is a required parameter of the path that creates a child.
     */
    fun createChildSession(
        workspaceId: String,
        parentSessionId: String,
        label: String,
        kind: SessionKind,
        depth: Int,
    ): ConversationSession

    fun getSession(sessionId: String): ConversationSession?
    fun listSessions(workspaceId: String): List<ConversationSession>

    /** Direct children of [parentSessionId], newest last. */
    fun listChildSessions(parentSessionId: String): List<ConversationSession>

    fun appendMessage(
        sessionId: String,
        role: MessageRole,
        content: String,
        metadataJson: String? = null,
    ): ConversationMessage
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

    override fun createChildSession(
        workspaceId: String,
        parentSessionId: String,
        label: String,
        kind: SessionKind,
        depth: Int,
    ): ConversationSession {
        requireEnabled()
        require(workspaceId.isNotBlank()) { "Workspace id must not be blank" }
        require(kind != SessionKind.USER) { "A child Session must not be a user conversation" }
        require(depth > 0) { "A child Session must be deeper than a root conversation" }
        val parent = requireNotNull(store.get(parentSessionId)) { "Parent Session does not exist" }
        // The child belongs to the parent's Workspace by construction. Letting the caller pass a
        // different one would be a cross-Workspace delegation with none of the @ authorization.
        require(parent.workspaceId == workspaceId) { "Parent Session belongs to a different Workspace" }
        val normalizedLabel = label.trim().ifBlank { "delegated task" }.take(120)
        val id = idFactory()
        require(id.isNotBlank()) { "Session id factory returned a blank id" }
        return store.create(
            id = id,
            workspaceId = workspaceId,
            title = normalizedLabel,
            nowMillis = clockMillis(),
            parentSessionId = parentSessionId,
            kind = kind,
            agentLabel = normalizedLabel,
            depth = depth,
        )
    }

    override fun listChildSessions(parentSessionId: String): List<ConversationSession> {
        requireEnabled()
        require(parentSessionId.isNotBlank()) { "Parent Session id must not be blank" }
        return store.listChildren(parentSessionId)
    }

    override fun listSessions(workspaceId: String): List<ConversationSession> {
        requireEnabled()
        require(workspaceId.isNotBlank()) { "Workspace id must not be blank" }
        requireNotNull(workspaces.findSessionWorkspace(workspaceId)) { "Workspace does not exist or is archived" }
        return store.list(workspaceId)
    }

    override fun appendMessage(
        sessionId: String,
        role: MessageRole,
        content: String,
        metadataJson: String?,
    ): ConversationMessage {
        requireEnabled()
        require(sessionId.isNotBlank())
        require(content.isNotBlank())
        return store.append(sessionId, role, content, clockMillis(), metadataJson)
    }

    override fun messages(sessionId: String): List<ConversationMessage> {
        requireEnabled(); return store.messages(sessionId)
    }

    private fun requireEnabled() = check(enabled) { "Session Manager plugin is not enabled" }
}

/**
 * A block of text injected into a turn before its history.
 *
 * Kept in the conversation layer rather than in the subagent plugin: from the turn's point of
 * view this is just "something the host wants the model to know at the start", and a background
 * child is only the first thing that produces one.
 */
data class TurnNotice(
    val id: String,
    val heading: String,
    val body: String? = null,
    /**
     * Whether this is something a child chose to say, or the runtime's account of an outcome.
     *
     * Kept because a transcript that merged the two would credit a child with the runtime's words.
     */
    val kind: com.cordiact.core.inbox.InboxItemKind = com.cordiact.core.inbox.InboxItemKind.CHILD_SETTLED,
)

/** Where a turn gets its notices, and where it reports that they were delivered. */
interface TurnNoticeSource {
    /** Notices for [sessionId], oldest first. */
    fun pending(sessionId: String): List<TurnNotice>

    /**
     * Called once the turn's first model request has been issued.
     *
     * Deliberately not called when the turn fails to start: a notice that was never put in front
     * of the model must stay pending, or the parent silently loses the child's outcome.
     */
    fun acknowledge(sessionId: String, ids: List<String>)
}
