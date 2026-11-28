package com.cordiact.core.conversation

import com.cordiact.core.attachment.MessagePart
import com.cordiact.core.attachment.MessageParts

import com.cordiact.core.plugin.ContextManagedPlugin
import com.cordiact.core.plugin.ContextPlugin
import com.cordiact.core.plugin.ManagedPlugin
import com.cordiact.core.plugin.PluginContext
import com.cordiact.core.plugin.PluginKernel
import com.cordiact.core.plugin.ServiceKey
import com.cordiact.core.plugin.PluginEventKey
import com.cordiact.core.plugin.PluginSpec
import com.cordiact.core.plugin.PluginTarget
import com.cordiact.core.tool.ToolHistoryReceipt
import com.cordiact.core.workspace.WorkspaceSessionResolver
import java.util.UUID

/** Common typed model identity. Provider-specific configuration stays inside the adapter plugin. */
data class ModelId(val providerId: String, val modelId: String)

/** Post-turn notification for trusted host observers. The payload is the session ID. */
val CONVERSATION_TURN_COMPLETED = PluginEventKey("conversation/turn-completed", String::class.java)

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
    /** What this model declares it accepts; empty means "nothing adjustable / no attachments". */
    val capabilities: ModelCapabilities = ModelCapabilities.NONE,
)

/**
 * User-facing capability declaration of one model. Only declared values may be offered by a UI;
 * AUTO is never listed because it is expressed by omitting the parameter.
 */
data class ModelCapabilities(
    val reasoningEfforts: Set<ReasoningEffort> = emptySet(),
    val responseSpeeds: Set<ResponseSpeed> = emptySet(),
    val attachments: com.cordiact.core.attachment.AttachmentInputCapability =
        com.cordiact.core.attachment.AttachmentInputCapability.UNSUPPORTED,
) {
    init {
        require(ReasoningEffort.AUTO !in reasoningEfforts) { "AUTO is implicit and must not be declared" }
        require(ResponseSpeed.AUTO !in responseSpeeds) { "AUTO is implicit and must not be declared" }
    }

    companion object {
        val NONE = ModelCapabilities()
    }
}

enum class MessageRole { SYSTEM, USER, ASSISTANT, TOOL }

/**
 * One tool the model may call.
 *
 * Providers use only the public name, description and schema. A host may attach opaque
 * registration identity so dispatch can reject a model response from a retired tool catalog.
 */
data class ModelToolSpec(
    val name: String,
    val description: String,
    /** JSON Schema object as a string; providers want it as-is. */
    val parametersJson: String,
    /** Local metadata only; deliberately absent from provider payloads and persisted messages. */
    @Transient val registration: com.cordiact.core.tool.ToolRegistration? = null,
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
    /** Opaque Gemini continuation data; other protocols must not send it. */
    val geminiCallId: String? = null,
    val geminiThoughtSignature: String? = null,
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
    /**
     * Ordered parts for a multimodal message; empty for every text-only message.
     *
     * When non-empty this is the only semantic source: [content] is then a text projection kept
     * for search, previews and old readers. Providers must encode [parts], never [content] alone.
     */
    val parts: List<MessagePart> = emptyList(),
    /** Host-only execution status; null for legacy dispatchers or non-tool messages. */
    val toolSuccess: Boolean? = null,
    /** One dispatch only; the tool loop strips these host fields before the next model round. */
    @Transient val toolHistoryReceipt: ToolHistoryReceipt? = null,
    /** Suppress the preview of a sensitive result without altering its model transport. */
    @Transient val suppressToolHistorySummary: Boolean = false,
) {
    init {
        require(role != MessageRole.TOOL || toolCallId != null) {
            "A tool result must carry the toolCallId it answers"
        }
    }
}

/** OpenAI-compatible reasoning effort. AUTO explicitly delegates to the model/server. */
enum class ReasoningEffort {
    NONE,
    AUTO,
    MINIMAL,
    LOW,
    MEDIUM,
    HIGH,
    XHIGH,
    MAX,
}

/** Processing-speed request, independent of reasoning effort. */
enum class ResponseSpeed {
    AUTO,
    FAST,
    ULTRAFAST,
}

/** Provider-neutral generation parameters. Null means "not specified at this layer". */
data class GenerationParameters(
    val temperature: Double? = null,
    val maxOutputTokens: Int? = null,
    val topP: Double? = null,
    val reasoningEffort: ReasoningEffort? = null,
    val responseSpeed: ResponseSpeed? = null,
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
            reasoningEffort = overrides.reasoningEffort ?: reasoningEffort,
            responseSpeed = overrides.responseSpeed ?: responseSpeed,
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
    /**
     * Workspace the turn belongs to; required only when a message carries attachment parts.
     *
     * Attachment reads are scoped by Workspace, so a request without this context must fail closed
     * instead of guessing a scope from the session id.
     */
    val workspaceId: String? = null,
    /** Budget fallback for protocols requiring a maximum; does not override model defaults. */
    val outputBudgetTokens: Int? = null,
)

/** Durable attribution of a host-observed tool result, never an authorization or a payload claim. */
data class ToolHistoryProvenance(
    val ownerPluginId: String,
    val toolId: String,
    val source: ToolHistorySource,
    val format: ToolHistoryFormat,
    val packageSha256: String? = null,
)

enum class ToolHistorySource(val wireValue: String) {
    HOST_REGISTRATION("host-registration"),
}

enum class ToolHistoryFormat(val wireValue: String) {
    /** The existing bounded text preview; it is not a complete or trustworthy JSON document. */
    BOUNDED_SUMMARY_V1("bounded-summary-v1"),
}

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
    data class Completed(
        val inputTokens: Long? = null,
        val outputTokens: Long? = null,
        val outputLimitReached: Boolean = false,
        val toolLimitReached: Boolean = false,
        val finishReason: String? = null,
    ) : ModelEvent
    data class Failed(
        val code: String,
        val message: String,
        val retryable: Boolean = false,
        val providerCode: String? = null,
        val retryAfterSeconds: Long? = null,
    ) : ModelEvent

    /** DeepSeek-R1 / reasoning model chain-of-thought streaming chunk. */
    data class Thinking(val text: String) : ModelEvent

    /** Emitted before a tool begins executing so UI can render a progress card immediately. */
    data class ToolCallStarted(
        val callId: String,
        val toolName: String,
        val argumentsJson: String,
    ) : ModelEvent

    /** Emitted after a tool finishes execution. */
    data class ToolCallCompleted(
        val callId: String,
        val toolName: String,
        val success: Boolean,
        val durationMillis: Long,
        val summary: String? = null,
        /** Set by the host loop after a successful, precisely bound non-sensitive dispatch. */
        val provenance: ToolHistoryProvenance? = null,
    ) : ModelEvent

    /** Emitted when context compaction triggers, notifying UI of memory reduction. */
    data class ContextCompacted(
        val savedTokens: Long,
        val summary: String,
    ) : ModelEvent
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
    val archivedAtMillis: Long? = null,
    val pinnedAtMillis: Long? = null,
    val titleManuallySet: Boolean = false,
)

const val AGENT_PRESET_SYSTEM_METADATA = "{\"cordiactAgentPresetSystem\":true}"

data class ConversationMessage(
    val id: String,
    val sessionId: String,
    val role: MessageRole,
    val content: String,
    val createdAtMillis: Long,
    /** Host-only evidence for this message, as JSON; null for ordinary text turns. */
    val metadataJson: String? = null,
    /**
     * Ordered parts of the message. Empty means "text only", in which case [content] is complete.
     *
     * A message with attachment parts also carries a text projection in [content] so that search,
     * notifications and pre-migration readers keep working.
     */
    val parts: List<MessagePart> = emptyList(),
)

/** Storage port; the app implementation is durable SQLite and scoped by session/workspace. */
interface ConversationSessionStore {
    fun updateFirstUserTitleFromOriginal(sessionId: String, originalText: String) {}


    fun renameSession(sessionId: String, title: String): ConversationSession = throw UnsupportedOperationException("Session rename unavailable")
    fun archiveSession(sessionId: String, nowMillis: Long): ConversationSession = throw UnsupportedOperationException("Session archive unavailable")
    fun restoreSession(sessionId: String): ConversationSession = throw UnsupportedOperationException("Session restore unavailable")
    fun setSessionPinned(sessionId: String, pinned: Boolean, nowMillis: Long): ConversationSession = throw UnsupportedOperationException("Session pin unavailable")

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

    /**
     * Appends a message and its ordered parts in one transaction.
     *
     * The default refuses instead of silently dropping [parts]: a store that has not implemented
     * structured parts must not pretend it saved an attachment message.
     */
    fun appendWithParts(
        sessionId: String,
        role: MessageRole,
        content: String,
        parts: List<MessagePart>,
        nowMillis: Long,
        metadataJson: String? = null,
    ): ConversationMessage = throw UnsupportedOperationException(
        "This ConversationSessionStore cannot persist message parts",
    )

    fun messages(sessionId: String): List<ConversationMessage>
}

interface SessionManagerService {
    fun updateFirstUserTitleFromOriginal(sessionId: String, originalText: String) {}

    fun renameSession(sessionId: String, title: String): ConversationSession = throw UnsupportedOperationException("Session management unavailable")
    fun archiveSession(sessionId: String): ConversationSession = throw UnsupportedOperationException("Session management unavailable")
    fun restoreSession(sessionId: String): ConversationSession = throw UnsupportedOperationException("Session management unavailable")
    fun setSessionPinned(sessionId: String, pinned: Boolean): ConversationSession = throw UnsupportedOperationException("Session management unavailable")

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

    /** Appends a message with ordered parts; see [ConversationSessionStore.appendWithParts]. */
    fun appendMessageWithParts(
        sessionId: String,
        role: MessageRole,
        content: String,
        parts: List<MessagePart>,
        metadataJson: String? = null,
    ): ConversationMessage = throw UnsupportedOperationException(
        "This SessionManagerService cannot persist message parts",
    )

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

/** Trusted ContextPlugin API for publishing a concrete model implementation. */
fun interface ModelBackendContributions {
    fun register(context: PluginContext, backend: ModelBackend): AutoCloseable
}

val MODEL_BACKEND_CONTRIBUTIONS =
    ServiceKey("conversation.model-backends", ModelBackendContributions::class.java)

/** Lifecycle-managed, protected system plugin. Provider plugins register backends through the host. */
class ModelAdapterPlugin(kernel: PluginKernel? = null) : ManagedPlugin, ModelAdapterService {
    /** Optional local accounting observer. Must not throw or block model delivery. */
    @Volatile var usageObserver: ((ModelUsage) -> Unit)? = null
    override val spec = modelAdapterPluginSpec()
    private class OwnedBackend(
        val ownerPluginId: String, val providerId: String, val backend: ModelBackend, val active: () -> Boolean,
    )
    private val backends = linkedMapOf<String, OwnedBackend>()
    private val activeCalls = linkedMapOf<String, PendingCall>()
    private var enabled = false

    private val bridge = kernel?.let {
        ContextManagedPlugin(spec, it, object : ContextPlugin {
            override fun start(context: PluginContext) {
                context.provide(MODEL_BACKEND_CONTRIBUTIONS, ModelBackendContributions { owner, backend ->
                    check(context.isContributionActive()) { "Model backend registry is no longer active" }
                    require(owner.id.workspaceId == null) { "Native model backends are registered at application scope" }
                    owner.scope.requireOpen()
                    val binding = addBackend(owner.id.pluginId, backend, owner::isContributionActive)
                    try {
                        owner.effect { withdrawBackend(binding) }
                    } catch (failure: Exception) {
                        withdrawBackend(binding)
                        throw failure
                    }
                })
            }
        })
    }

    private class ScopedBackends(val owns: (String) -> Boolean, val resolve: (ModelGenerationRequest) -> ModelBackend?)
    @Volatile private var scopedBackends: ScopedBackends? = null

    fun setScopedBackendResolver(owns: (String) -> Boolean, resolver: (ModelGenerationRequest) -> ModelBackend?) {
        scopedBackends = ScopedBackends(owns, resolver)
    }

    /** Host-managed providers receive a handle for the exact backend they attached. */
    fun attachBackend(ownerPluginId: String, backend: ModelBackend): AutoCloseable {
        val binding = addBackend(ownerPluginId, backend, active = null)
        return AutoCloseable { withdrawBackend(binding) }
    }

    @Synchronized
    private fun addBackend(ownerPluginId: String, backend: ModelBackend, active: (() -> Boolean)?): OwnedBackend {
        check(enabled) { "Model Adapter plugin is not enabled" }
        require(ownerPluginId.isNotBlank()) { "Backend owner plugin id is required" }
        val providerId = backend.providerId
        require(providerId.matches(Regex("[a-z0-9][a-z0-9._-]{1,127}"))) { "Invalid provider id" }
        val descriptors = backend.models()
        require(descriptors.all { it.id.providerId == providerId && it.id.modelId.isNotBlank() }) {
            "Backend '$providerId' returned invalid model descriptors"
        }
        val previous = backends[providerId]
        if (previous != null) {
            check(active == null && previous.ownerPluginId == ownerPluginId && previous.backend === backend) {
                "A backend for provider '$providerId' is already attached"
            }
            return previous
        }
        return OwnedBackend(ownerPluginId, providerId, backend, active ?: { true }).also { backends[providerId] = it }
    }

    fun detachBackend(ownerPluginId: String, providerId: String) {
        val binding = synchronized(this) {
            backends[providerId]?.also {
                check(it.ownerPluginId == ownerPluginId) { "Plugin '$ownerPluginId' does not own provider '$providerId'" }
            }
        } ?: return
        withdrawBackend(binding)
    }

    private fun withdrawBackend(binding: OwnedBackend) {
        val calls = synchronized(this) {
            if (!backends.remove(binding.providerId, binding)) return
            activeCalls.values.filter { it.binding === binding }
        }
        calls.forEach(PendingCall::retire)
    }

    override fun onRegister() { bridge?.onRegister() }

    override fun onEnable() {
        synchronized(this) { enabled = true }
        try {
            // Recovery rebinds durable system records without calling onRegister again.
            onRegister()
            bridge?.onEnable()
        } catch (failure: Exception) {
            onDisable()
            throw failure
        }
    }

    override fun onDisable() {
        val calls = synchronized(this) {
            enabled = false
            backends.clear()
            activeCalls.values.toList()
        }
        try { bridge?.onDisable() } finally { calls.forEach(PendingCall::retire) }
    }

    override fun onUninstall() {
        try { onDisable() } finally { bridge?.onUninstall() }
    }

    override fun models(): List<ModelDescriptor> {
        val snapshot = synchronized(this) {
            check(enabled) { "Model Adapter plugin is not enabled" }
            backends.values.toList()
        }
        return snapshot.filter { current(it) }.flatMap { binding ->
            binding.backend.models().takeIf { current(binding) }.orEmpty()
        }.distinctBy { it.id }
    }

    private fun current(binding: OwnedBackend?): Boolean = synchronized(this) {
        enabled && (binding == null || (backends[binding.providerId] === binding && binding.active()))
    }

    override fun generate(request: ModelGenerationRequest, onEvent: (ModelEvent) -> Unit): ModelCall {
        fun fail(code: String, message: String): ModelCall {
            onEvent(ModelEvent.Failed(code, message))
            return ModelCall {}
        }
        if (!current(null)) return fail("plugin_disabled", "Model Adapter is not enabled")
        val scope = scopedBackends
        val scoped = scope?.owns?.invoke(request.model.providerId) == true
        val binding = if (scoped) null else synchronized(this) { backends[request.model.providerId] }
        val selected = if (scoped) scope?.resolve?.invoke(request) else binding?.takeIf { current(it) }?.backend
        if (selected == null) {
            return fail("provider_unavailable", "No adapter is available for '${request.model.providerId}'")
        }
        if (selected.models().none { it.id == request.model }) {
            return fail("model_unavailable", "Model '${request.model.modelId}' is not available")
        }
        val pending = PendingCall(binding, request, onEvent)
        val admitted = synchronized(this) {
            if (!current(binding)) false else { activeCalls[pending.id] = pending; true }
        }
        if (!admitted) return fail("provider_unavailable", "Model provider is no longer available")
        try {
            if (current(binding)) pending.attach(selected.generate(request, pending::deliver)) else pending.retire()
        } catch (failure: Exception) {
            pending.deliver(ModelEvent.Failed("provider_error", failure.message ?: "Model request failed", retryable = true))
        }
        return ModelCall(pending::cancel)
    }

    private inner class PendingCall(
        val binding: OwnedBackend?,
        private val request: ModelGenerationRequest,
        private val onEvent: (ModelEvent) -> Unit,
    ) {
        val id = UUID.randomUUID().toString()
        private val startedAt = System.currentTimeMillis()
        private val observer = usageObserver
        private var terminal = false
        private var cancelOnAttach = false
        private var delegate: ModelCall? = null

        fun attach(call: ModelCall) {
            val cancel = synchronized(this) {
                if (terminal) cancelOnAttach else { delegate = call; false }
            }
            if (cancel) runCatching { call.cancel() }
            if (!current(binding)) retire()
        }

        fun deliver(event: ModelEvent) {
            val cancel = synchronized(this) {
                if (terminal) return
                if (!current(binding)) {
                    finish(ModelUsage.Status.CANCELLED, revokedEvent())
                } else {
                    when (event) {
                        is ModelEvent.Completed -> finish(ModelUsage.Status.COMPLETED, event)
                        is ModelEvent.Failed -> finish(ModelUsage.Status.FAILED, event)
                        else -> onEvent(event)
                    }
                    null
                }
            }
            cancel?.let { runCatching { it.cancel() } }
        }

        fun retire() = stop(revokedEvent())
        fun cancel() = stop(null)

        private fun stop(event: ModelEvent?) {
            val call = synchronized(this) {
                if (terminal) return
                finish(ModelUsage.Status.CANCELLED, event)
            }
            call?.let { runCatching { it.cancel() } }
        }

        private fun finish(status: ModelUsage.Status, event: ModelEvent?): ModelCall? {
            terminal = true
            cancelOnAttach = status == ModelUsage.Status.CANCELLED
            synchronized(this@ModelAdapterPlugin) { activeCalls.remove(id) }
            val call = delegate
            delegate = null
            val completed = event as? ModelEvent.Completed
            if (observer != null) runCatching {
                observer(ModelUsage(id, request.workspaceId, request.sessionId, request.model, startedAt, status,
                    completed?.inputTokens?.takeIf { it >= 0 }, completed?.outputTokens?.takeIf { it >= 0 }))
            }
            event?.let { runCatching { onEvent(it) } }
            return call
        }

        private fun revokedEvent() = ModelEvent.Failed("provider_revoked", "Model provider was withdrawn")
    }
}

/** Archive is persisted, but the host has not confirmed all local resources were withdrawn. */
class SessionArchiveCleanupException(cause: Exception) :
    IllegalStateException("会话已归档，但本地插件清理未完成。恢复前会再次清理。", cause)

/** Lifecycle-managed system plugin for durable workspace-scoped conversation history. */
class SessionManagerPlugin(
    private val store: ConversationSessionStore,
    private val workspaces: WorkspaceSessionResolver,
    private val clockMillis: () -> Long = System::currentTimeMillis,
    private val idFactory: () -> String = { java.util.UUID.randomUUID().toString() },
    /** Withdraw process-local session state without recreating it on restore. Must be retryable. */
    private val invalidateSessionResources: (ConversationSession) -> Unit = {},
) : ManagedPlugin, SessionManagerService {
    override val spec = sessionManagerPluginSpec()
    @Volatile private var enabled = false
    private val archiveLock = Any()

    override fun onEnable() { enabled = true }
    override fun onDisable() { enabled = false }
    override fun onUninstall() { enabled = false }

    private fun requireManagedUserSession(id: String): ConversationSession {
        requireEnabled()
        val session = requireNotNull(store.get(id)) { "会话不存在" }
        require(session.kind == SessionKind.USER) { "只能整理用户会话" }
        requireNotNull(workspaces.findSessionWorkspace(session.workspaceId)) { "工作区不存在或已归档" }
        return session
    }

    override fun updateFirstUserTitleFromOriginal(sessionId: String, originalText: String) {
        requireEnabled()
        store.updateFirstUserTitleFromOriginal(sessionId, originalText)
    }

    override fun renameSession(sessionId: String, title: String): ConversationSession {
        requireManagedUserSession(sessionId)
        return store.renameSession(sessionId, normalizeSessionTitle(title))
    }
    override fun archiveSession(sessionId: String): ConversationSession = synchronized(archiveLock) {
        requireManagedUserSession(sessionId)
        // Persist first: the host can reject new plugin changes as soon as the session is archived.
        val archived = store.archiveSession(sessionId, clockMillis())
        try {
            invalidateSessionResources(archived)
        } catch (failure: Exception) {
            throw SessionArchiveCleanupException(failure)
        }
        archived
    }
    override fun restoreSession(sessionId: String): ConversationSession = synchronized(archiveLock) {
        val session = requireManagedUserSession(sessionId)
        // If previous cleanup failed, refuse to make the session usable again until it succeeds.
        if (session.archivedAtMillis != null) invalidateSessionResources(session)
        store.restoreSession(sessionId)
    }
    override fun setSessionPinned(sessionId: String, pinned: Boolean): ConversationSession {
        requireManagedUserSession(sessionId)
        return store.setSessionPinned(sessionId, pinned, clockMillis())
    }

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

    override fun appendMessageWithParts(
        sessionId: String,
        role: MessageRole,
        content: String,
        parts: List<MessagePart>,
        metadataJson: String?,
    ): ConversationMessage {
        requireEnabled()
        require(sessionId.isNotBlank())
        if (parts.isEmpty()) {
            require(content.isNotBlank())
            return store.append(sessionId, role, content, clockMillis(), metadataJson)
        }
        MessageParts.validate(parts)
        require(role == MessageRole.USER) { "Only user messages may carry attachment parts" }
        return store.appendWithParts(sessionId, role, content, parts, clockMillis(), metadataJson)
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
     * Called once the model successfully completes a request carrying these notices.
     * A failed request leaves them pending so overflow recovery or a later turn can still deliver them.
     */
    fun acknowledge(sessionId: String, ids: List<String>)
}

/** Display-only metadata: never inject these values into model context. */
fun normalizeSessionTitle(title: String): String {
    val normalized = title.trim()
    require(normalized.length in 1..120 && normalized.none { it.isISOControl() || it == '\u2028' || it == '\u2029' }) {
        "标题须为1至120个字符，且不能包含换行或控制字符"
    }
    return normalized
}

fun firstUserSentenceTitle(content: String): String {
    val firstLine = content.trim().lineSequence().firstOrNull().orEmpty()
    val end = firstLine.indexOfFirst { it in "。！？.!?" }
    val sentence = (if (end >= 0) firstLine.substring(0, end + 1) else firstLine)
        .replace(Regex("[\\s\\p{Cc}\\p{Z}]+"), " ").trim()
    val limit = if (sentence.length > 120 && sentence[119].isHighSurrogate()) 119 else 120
    return sentence.take(limit).ifBlank { "附件对话" }
}
