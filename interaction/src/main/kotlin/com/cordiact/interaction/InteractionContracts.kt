package com.cordiact.interaction

import com.cordiact.core.conversation.GenerationParameters
import com.cordiact.core.conversation.ModelCall
import com.cordiact.core.conversation.ModelEvent
import com.cordiact.core.conversation.ModelId
import com.cordiact.core.plugin.ManagedPlugin
import com.cordiact.core.plugin.PluginSpec
import com.cordiact.core.plugin.PluginTarget
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

const val INTERACTION_PLUGIN_ID = "core.interaction"
const val INTERACTION_PLUGIN_VERSION = "0.1.0"

fun interactionPluginSpec() = PluginSpec(
    id = INTERACTION_PLUGIN_ID,
    version = INTERACTION_PLUGIN_VERSION,
    target = PluginTarget.ANDROID_NATIVE,
    dependencies = setOf(
        "core.workspace-manager",
        "core.file-manager",
        "core.tool-manager",
        "core.model-adapter",
        "core.session-manager",
    ),
)

enum class MentionType {
    AGENT, WORKSPACE, PROJECT, FOLDER, FILE, SKILL, MCP, PLUGIN, OTHER,

    /**
     * A Workspace's memory subtree.
     *
     * A separate type from [FILE] on purpose: memory is data that may be stale and is injected
     * as such, whereas a file is addressed individually. Collapsing the two would let a
     * cross-Workspace memory grant be satisfied by any file.
     */
    MEMORY,
}
enum class InteractionTokenKind { COMMAND, MENTION, PRONOUN, FILL }
enum class TokenStatus { VALID, INVALID, INCOMPLETE, CONFIRMATION_REQUIRED }

/** Context is captured from the active persisted Session, never inferred from message text. */
data class InteractionContext(val sessionId: String, val workspaceId: String) {
    init {
        require(sessionId.isNotBlank())
        require(workspaceId.isNotBlank())
    }
}

data class MentionDescriptor(
    val id: String,
    val type: MentionType,
    val label: String,
    val workspaceId: String,
    val parentId: String? = null,
    val relativePath: String? = null,
    /**
     * The subtree this reference authorizes, as a Workspace-relative prefix.
     *
     * Null means the whole Workspace (`@workspace`); `"memory/"` means only memory
     * (`@workspace：记忆`). The grant is the reference path itself rather than a separate
     * capability flag, so what the user picked and what they authorized cannot drift apart.
     */
    val authorizedPrefix: String? = null,
    val requiredCapabilities: Set<String> = emptySet(),
) {
    init {
        require(id.isNotBlank() && label.isNotBlank() && workspaceId.isNotBlank())
        require(authorizedPrefix == null || !authorizedPrefix.startsWith("/")) {
            "Authorized prefix must be Workspace-relative: $authorizedPrefix"
        }
    }

    /** True when [path] falls inside this reference's granted subtree. */
    fun authorizes(path: String): Boolean {
        val prefix = authorizedPrefix ?: return true
        return path.startsWith(prefix) || path == prefix.trimEnd('/')
    }
}

/** Implementations must return only entities visible in this persisted Workspace. */
interface MentionDirectory {
    fun children(context: InteractionContext, parentId: String? = null): List<MentionDescriptor>
    fun find(context: InteractionContext, id: String): MentionDescriptor?
}

data class MentionReference(
    val entities: List<MentionDescriptor>,
    val start: Int,
    val endExclusive: Int,
) {
    init {
        require(entities.isNotEmpty())
        require(endExclusive > start)
        require(entities.size <= 2)
    }

    val leaf: MentionDescriptor get() = entities.last()
}

data class InteractionToken(
    val kind: InteractionTokenKind,
    val start: Int,
    val endExclusive: Int,
    val status: TokenStatus,
    val value: String? = null,
)

data class InteractionDiagnostic(
    val code: String,
    val message: String,
    val start: Int,
    val endExclusive: Int,
)

data class CommandDescriptor(
    val id: String,
    val name: String,
    val ownerPluginId: String,
    val aliases: Set<String> = emptySet(),
    val allowedMentionTypes: Set<MentionType> = emptySet(),
    val requiredCapabilities: Set<String> = emptySet(),
    val requiresMention: Boolean = false,
    val allowsEmptyBody: Boolean = false,
    val executor: InteractionCommandExecutor,
) {
    init {
        require(id.isNotBlank() && name.isNotBlank() && ownerPluginId.isNotBlank())
        require((aliases + name).all { it.isNotBlank() && !it.startsWith('/') })
    }
}

fun interface InteractionCommandExecutor {
    fun execute(
        envelope: InteractionEnvelope,
        modelId: ModelId,
        submitter: InteractionModelSubmitter,
        onEvent: (ModelEvent) -> Unit,
    ): CommandExecutionResult
}

data class CommandExecutionResult(
    val completed: Boolean,
    val message: String? = null,
    val modelCall: ModelCall? = null,
)

enum class InteractionTaskState { RUNNING, COMPLETED, FAILED, CANCELLED }

data class InteractionTask(
    val id: String,
    val commandId: String,
    val sessionId: String,
    val workspaceId: String,
    val startedAtMillis: Long,
    val state: InteractionTaskState,
    val message: String? = null,
)

interface InteractionTaskTracker {
    fun start(command: CommandDescriptor, context: InteractionContext): InteractionTask
    fun finish(taskId: String, state: InteractionTaskState, message: String? = null)
    fun cancel(taskId: String)
    fun active(): List<InteractionTask>
}

class InMemoryInteractionTaskTracker(
    private val clockMillis: () -> Long = System::currentTimeMillis,
) : InteractionTaskTracker {
    private val tasks = ConcurrentHashMap<String, InteractionTask>()

    override fun start(command: CommandDescriptor, context: InteractionContext): InteractionTask {
        val task = InteractionTask(UUID.randomUUID().toString(), command.id, context.sessionId, context.workspaceId,
            clockMillis(), InteractionTaskState.RUNNING)
        tasks[task.id] = task
        return task
    }

    override fun finish(taskId: String, state: InteractionTaskState, message: String?) {
        require(state != InteractionTaskState.RUNNING) { "Task terminal state is required" }
        tasks.computeIfPresent(taskId) { _, current ->
            if (current.state == InteractionTaskState.RUNNING) current.copy(state = state, message = message) else current
        }
    }

    override fun cancel(taskId: String) {
        finish(taskId, InteractionTaskState.CANCELLED, "Cancelled by user")
    }

    override fun active(): List<InteractionTask> = tasks.values
        .filter { it.state == InteractionTaskState.RUNNING }
        .sortedBy { it.startedAtMillis }
}

/** Registry mutations are owner-scoped; disabling an owner immediately blocks its commands. */
class InteractionCommandRegistry {
    private val commands = ConcurrentHashMap<String, CommandDescriptor>()
    private val enabledOwners = ConcurrentHashMap.newKeySet<String>()

    @Synchronized
    fun register(descriptor: CommandDescriptor) {
        val names = descriptor.aliases + descriptor.name
        require(names.size == descriptor.aliases.size + 1) { "Command name duplicates an alias" }
        require(names.none { name -> commands.containsKey(name) }) { "Command name is already registered" }
        names.forEach { commands[it] = descriptor }
    }

    @Synchronized
    fun unregisterOwner(ownerPluginId: String) {
        commands.entries.removeIf { it.value.ownerPluginId == ownerPluginId }
        enabledOwners.remove(ownerPluginId)
    }

    fun setOwnerEnabled(ownerPluginId: String, enabled: Boolean) {
        if (enabled) enabledOwners.add(ownerPluginId) else enabledOwners.remove(ownerPluginId)
    }

    fun find(name: String): CommandDescriptor? = commands[name]?.takeIf { it.ownerPluginId in enabledOwners }

    fun candidates(): List<CommandDescriptor> = commands.values.distinctBy { it.id }
        .filter { it.ownerPluginId in enabledOwners }
        .sortedBy { it.name }
}

/** The only object passed to an interaction command after the preflight gate succeeds. */
data class InteractionEnvelope(
    val context: InteractionContext,
    val originalText: String,
    val submittedText: String,
    val commandInput: String,
    val command: CommandDescriptor?,
    val mentions: List<MentionReference>,
    val pronounBindings: Map<String, String>,
    val requiredCapabilities: Set<String>,
)

/** Host-owned adapter to the existing ConversationTurnService; model choice stays provider-neutral. */
fun interface InteractionModelSubmitter {
    /**
     * [parameters] carries this turn's sampling overrides; null means "use the provider defaults".
     * Kept as the single abstract method so SAM conversion stays unambiguous.
     */
    fun submit(
        envelope: InteractionEnvelope,
        modelId: ModelId,
        parameters: GenerationParameters?,
        onEvent: (ModelEvent) -> Unit,
    ): ModelCall
}

data class PreparedInteraction(
    val envelope: InteractionEnvelope?,
    val tokens: List<InteractionToken>,
    val diagnostics: List<InteractionDiagnostic>,
) {
    val valid: Boolean get() = envelope != null && diagnostics.isEmpty()
}

fun interface InteractionAuthorization {
    fun authorize(context: InteractionContext, command: CommandDescriptor?, targets: List<MentionDescriptor>): AuthorizationDecision
}

data class AuthorizationDecision(val allowed: Boolean, val requiresConfirmation: Boolean = false, val reason: String? = null)

/** Must perform live plugin, capability, session, Workspace and policy checks at dispatch time. */
class InteractionService(
    private val commands: InteractionCommandRegistry,
    private val mentions: MentionDirectory,
    private val fills: FillValueStore,
    private val authorization: InteractionAuthorization,
    modelSubmitter: InteractionModelSubmitter? = null,
    private val taskTracker: InteractionTaskTracker = InMemoryInteractionTaskTracker(),
) {
    private val parser = InteractionParser(commands, mentions, fills)
    private val activeCalls = ConcurrentHashMap<String, ModelCall>()
    private val callLifecycleLock = Any()
    @Volatile private var enabled = false
    @Volatile private var modelSubmitter: InteractionModelSubmitter? = modelSubmitter

    fun setEnabled(value: Boolean) {
        val calls = synchronized(callLifecycleLock) {
            enabled = value
            if (value) emptyMap() else activeCalls.toMap().also { activeCalls.clear() }
        }
        calls.forEach { (taskId, call) ->
            call.cancel()
            taskTracker.cancel(taskId)
        }
    }

    fun attachModelSubmitter(submitter: InteractionModelSubmitter) { modelSubmitter = submitter }

    fun cancelActiveCalls() {
        val calls = synchronized(callLifecycleLock) {
            activeCalls.toMap().also { activeCalls.clear() }
        }
        calls.forEach { (taskId, call) ->
            call.cancel()
            taskTracker.cancel(taskId)
        }
    }

    fun commandCandidates(): List<CommandDescriptor> = if (enabled) commands.candidates() else emptyList()

    fun mentionCandidates(context: InteractionContext, parentId: String? = null): List<MentionDescriptor> {
        check(enabled) { "Interaction plugin is disabled" }
        return mentions.children(context, parentId)
    }

    fun prepare(text: String, context: InteractionContext): PreparedInteraction {
        check(enabled) { "Interaction plugin is disabled" }
        return parser.parse(text, context)
    }

    fun dispatch(
        prepared: PreparedInteraction,
        modelId: ModelId,
        parameters: GenerationParameters? = null,
        onEvent: (ModelEvent) -> Unit,
    ): CommandExecutionResult {
        check(enabled) { "Interaction plugin is disabled" }
        val envelope = requireNotNull(prepared.envelope) { "Interaction has syntax errors" }
        require(prepared.diagnostics.isEmpty()) { "Interaction has syntax errors" }
        val decision = authorization.authorize(envelope.context, envelope.command, envelope.mentions.flatMap { it.entities })
        check(decision.allowed && !decision.requiresConfirmation) {
            decision.reason ?: "Interaction is not authorized"
        }
        val command = requireNotNull(envelope.command) { "No registered behavior command was selected" }
        check(commands.find(command.name) == command) { "Command owner is no longer enabled" }
        val currentDecision = authorization.authorize(envelope.context, command, envelope.mentions.flatMap { it.entities })
        check(currentDecision.allowed && !currentDecision.requiresConfirmation) {
            currentDecision.reason ?: "Authorization was revoked before execution"
        }
        val submitter = requireNotNull(modelSubmitter) { "Model submission is not configured" }
        // Bind this turn's overrides so command executors inherit them without changing their contract.
        val boundSubmitter = InteractionModelSubmitter { envelopeToSubmit, id, overrides, onEvent ->
            submitter.submit(envelopeToSubmit, id, overrides ?: parameters, onEvent)
        }
        val task = taskTracker.start(command, envelope.context)
        val terminal = java.util.concurrent.atomic.AtomicBoolean(false)
        val result = try {
            command.executor.execute(envelope, modelId, boundSubmitter) { event ->
                when (event) {
                    is ModelEvent.Completed -> if (terminal.compareAndSet(false, true)) {
                        synchronized(callLifecycleLock) { activeCalls.remove(task.id) }
                        taskTracker.finish(task.id, InteractionTaskState.COMPLETED)
                    }
                    // Tool rounds are internal to the turn; this layer only tracks completion.
                    is ModelEvent.ToolCallsRequested -> Unit
                    is ModelEvent.Failed -> if (terminal.compareAndSet(false, true)) {
                        synchronized(callLifecycleLock) { activeCalls.remove(task.id) }
                        taskTracker.finish(task.id, InteractionTaskState.FAILED, event.message)
                    }
                    is ModelEvent.Delta -> Unit
                    else -> Unit
                }
                onEvent(event)
            }
        } catch (failure: Exception) {
            if (terminal.compareAndSet(false, true)) {
                taskTracker.finish(task.id, InteractionTaskState.FAILED, failure.message)
            }
            throw failure
        }
        val trackedCall = result.modelCall?.let { delegate ->
            val callId = task.id
            val wrapper = ModelCall {
                val call = synchronized(callLifecycleLock) { activeCalls.remove(callId) }
                call?.cancel()
                taskTracker.cancel(task.id)
            }
            val registration = synchronized(callLifecycleLock) {
                when {
                    terminal.get() -> 0
                    !enabled -> 1
                    else -> {
                        activeCalls[callId] = delegate
                        2
                    }
                }
            }
            when (registration) {
                2 -> wrapper
                1 -> {
                    delegate.cancel()
                    if (terminal.compareAndSet(false, true)) taskTracker.cancel(task.id)
                    null
                }
                else -> {
                    delegate.cancel()
                    null
                }
            }
        }
        if (result.completed && terminal.compareAndSet(false, true)) {
            synchronized(callLifecycleLock) { activeCalls.remove(task.id) }
            taskTracker.finish(task.id, InteractionTaskState.COMPLETED, result.message)
        }
        return result.copy(modelCall = trackedCall)
    }

    /** Ordinary input may use the model but never dispatches a registered behavior command. */
    fun submitOrdinary(
        prepared: PreparedInteraction,
        modelId: ModelId,
        parameters: GenerationParameters? = null,
        onEvent: (ModelEvent) -> Unit,
    ): ModelCall {
        check(enabled) { "Interaction plugin is disabled" }
        val envelope = requireNotNull(prepared.envelope) { "Interaction has syntax errors" }
        require(prepared.diagnostics.isEmpty()) { "Interaction has syntax errors" }
        require(envelope.command == null) { "Behavior commands must be dispatched, not sent as ordinary prompts" }
        val targets = envelope.mentions.flatMap { it.entities }
        val decision = authorization.authorize(envelope.context, null, targets)
        check(decision.allowed && !decision.requiresConfirmation) {
            decision.reason ?: "Ordinary interaction is not authorized"
        }
        val submitter = requireNotNull(modelSubmitter) { "Model submission is not configured" }
        val current = authorization.authorize(envelope.context, null, targets)
        check(current.allowed && !current.requiresConfirmation) {
            current.reason ?: "Authorization was revoked before model submission"
        }
        val callId = "ordinary:${java.util.UUID.randomUUID()}"
        val terminal = java.util.concurrent.atomic.AtomicBoolean(false)
        val delegate = submitter.submit(envelope, modelId, parameters) { event ->
            when (event) {
                is ModelEvent.Completed, is ModelEvent.Failed -> if (terminal.compareAndSet(false, true)) {
                    synchronized(callLifecycleLock) { activeCalls.remove(callId) }
                }
                is ModelEvent.Delta -> Unit
                is ModelEvent.ToolCallsRequested -> Unit
                else -> Unit
            }
            onEvent(event)
        }
        val registered = synchronized(callLifecycleLock) {
            if (terminal.get() || !enabled) {
                false
            } else {
                activeCalls[callId] = delegate
                true
            }
        }
        if (registered) {
            return ModelCall {
                val call = synchronized(callLifecycleLock) { activeCalls.remove(callId) }
                if (call != null && terminal.compareAndSet(false, true)) call.cancel()
            }
        }
        if (!terminal.get()) {
            delegate.cancel()
            terminal.compareAndSet(false, true)
        }
        return ModelCall { }
    }
}

interface FillValueStore {
    fun get(key: String): String?
    fun keys(): Set<String>
}

interface MutableFillValueStore : FillValueStore {
    fun set(key: String, value: String)
    fun remove(key: String)
}

class InMemoryFillValueStore(initial: Map<String, String> = emptyMap()) : MutableFillValueStore {
    private val values = ConcurrentHashMap<String, String>()

    init {
        initial.forEach { (key, value) -> set(key, value) }
    }

    @Synchronized
    override fun set(key: String, value: String) {
        require(key.matches(Regex("[A-Za-z0-9_-]{1,64}"))) { "Invalid fill key" }
        values[key] = value
    }

    @Synchronized
    override fun remove(key: String) {
        values.remove(key)
    }

    override fun get(key: String): String? = values[key]
    override fun keys(): Set<String> = values.keys.toSet()
}

/** Protected system plugin which owns the command registry and exposes parser/dispatch services. */
class InteractionPlugin(
    val service: InteractionService,
    val commandRegistry: InteractionCommandRegistry,
) : ManagedPlugin {
    override val spec: PluginSpec = interactionPluginSpec()
    @Volatile private var enabled = false

    override fun onEnable() {
        enabled = true
        service.setEnabled(true)
        commandRegistry.setOwnerEnabled(spec.id, true)
    }

    override fun onDisable() {
        enabled = false
        service.setEnabled(false)
        commandRegistry.setOwnerEnabled(spec.id, false)
    }

    fun isEnabled(): Boolean = enabled
}
