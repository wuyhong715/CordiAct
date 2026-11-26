package com.cordiact.core.tool

import com.cordiact.core.conversation.ToolHistoryFormat
import com.cordiact.core.conversation.ToolHistoryProvenance
import com.cordiact.core.conversation.ToolHistorySource
import com.cordiact.core.plugin.ManagedPlugin
import com.cordiact.core.plugin.PluginSpec
import com.cordiact.core.plugin.PluginState
import com.cordiact.core.plugin.PluginTarget
import com.cordiact.core.security.SensitivePayload
import com.cordiact.core.workspace.WorkspaceManagerService
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

const val TOOL_MANAGER_PLUGIN_ID = "core.tool-manager"
const val TOOL_MANAGER_VERSION = "0.1.0"
const val TOOL_MANAGER_CAPABILITY = "core.tools.manage"

fun toolManagerPluginSpec() = PluginSpec(
    id = TOOL_MANAGER_PLUGIN_ID,
    version = TOOL_MANAGER_VERSION,
    target = PluginTarget.ANDROID_NATIVE,
    dependencies = setOf("core.workspace-manager"),
)

enum class ToolValueType { STRING, NUMBER, INTEGER, BOOLEAN, ARRAY, OBJECT }

/** JSON-like primitive input values. Object parsing and schema validation belong to the runtime adapter. */
sealed interface ToolValue {
    data class StringValue(val value: String) : ToolValue
    data class NumberValue(val value: Double) : ToolValue
    data class BooleanValue(val value: Boolean) : ToolValue
    data object NullValue : ToolValue
    data class ArrayValue(val values: List<ToolValue>) : ToolValue
    data class ObjectValue(val values: Map<String, ToolValue>) : ToolValue
}

data class ToolArguments(val values: Map<String, ToolValue> = emptyMap())

/**
 * One tool result.
 *
 * [values] is what the model sees. [metadata] is host-only structured data (for
 * example web sources) that must not be rendered into the prompt; the session
 * store persists it for UI replay.
 */
data class ToolResult(
    val values: Map<String, ToolValue> = emptyMap(),
    val metadata: Map<String, ToolValue> = emptyMap(),
    /**
     * Host-only sensitive result for an explicitly approved raw read. Normal tool serializers must
     * never render this field into the prompt, history, trace or audit. A model adapter that has no
     * approved sensitive transport must refuse to consume it instead of falling back to String.
     */
    val sensitivePayload: SensitivePayload? = null,
    /** Issued (or cleared) by the manager, not copied from an executor's result. */
    @Transient val historyReceipt: ToolHistoryReceipt? = null,
)

/**
 * One declared input parameter.
 *
 * The optional fields are part of the model-facing JSON Schema; they are not
 * cosmetic. Without enum values and array item types a model has to guess
 * accepted values, which makes tools like web search call incorrectly.
 */
data class ToolParameter(
    val name: String,
    val type: ToolValueType,
    val required: Boolean = false,
    val description: String? = null,
    val enumValues: List<String> = emptyList(),
    val itemType: ToolValueType? = null,
    val minimum: Double? = null,
    val maximum: Double? = null,
    val minItems: Int? = null,
    val maxItems: Int? = null,
    val maxLength: Int? = null,
    /** True for a value that must be scrubbed from traces before execution and never audited. */
    val sensitive: Boolean = false,
)

/**
 * Per-request context used to decide whether a tool is offered at all.
 *
 * It deliberately carries neutral identifiers only, so `core.tool` does not
 * depend on a search or model module.
 */
data class ToolActivationContext(
    val workspaceId: String,
    val modelProviderId: String? = null,
    val modelId: String? = null,
    val sessionId: String? = null,
)

/** Host-side gate evaluated when a turn's tool list is assembled. */
fun interface ToolVisibilityGate {
    fun visible(context: ToolActivationContext): Boolean
}

data class ToolInputSchema(val parameters: List<ToolParameter> = emptyList(), val allowAdditional: Boolean = false)

data class ToolDescriptor(
    val id: String,
    val name: String,
    val description: String,
    val inputSchema: ToolInputSchema,
    val ownerPluginId: String,
    val defaultEnabled: Boolean = false,
    val requiredCapabilities: Set<String> = emptySet(),
    /** Host-enforced execution budget; null means no host timeout. */
    val timeoutMs: Long? = null,
    /** True when calls may run alongside other tools without sharing state. */
    val concurrencySafe: Boolean = false,
    /**
     * A complete JSON Schema object used verbatim instead of [inputSchema].
     *
     * Foreign tools (MCP) carry nested schemas that [ToolInputSchema] cannot express;
     * flattening them would silently drop `oneOf`, `$ref` and nested properties, so
     * their authors pass the schema through untouched. When non-null the model-facing
     * declaration uses this string and [inputSchema] is ignored for rendering.
     */
    val rawInputSchemaJson: String? = null,
    /**
     * Declares every executable action of this tool (shell, SSH, Termux,
     * outbound file transfer). ToolManagerPlugin denies calls for tools that
     * declare execution metadata without a matching action, and never lets a
     * command-bearing tool bypass the human approval gate. Null means the tool
     * cannot reach an execution backend.
     */
    val execution: ToolExecutionMetadata? = null,
)

data class ToolAvailability(
    val descriptor: ToolDescriptor,
    val enabledForWorkspace: Boolean,
    val ownerEnabled: Boolean,
    val capabilitiesGranted: Boolean,
) {
    val dispatchable: Boolean get() = enabledForWorkspace && ownerEnabled && capabilitiesGranted
}

/**
 * A Workspace the current turn may read from, and how much of it.
 *
 * `@` is an authorization, not an attachment: it grants a range and the model then decides
 * what to read through tools. Nothing is pre-loaded, so the grant has to travel with the tool
 * call rather than being applied once when the turn starts.
 */
data class GrantedScope(
    val workspaceId: String,
    /** Workspace-relative subtree, or null for the whole Workspace. */
    val prefix: String? = null,
) {
    init {
        require(workspaceId.isNotBlank()) { "Granted Workspace must not be blank" }
        require(prefix == null || !prefix.startsWith("/")) { "Scope prefix must be relative: $prefix" }
    }
}

data class ToolInvocationContext(
    /** The Workspace the session belongs to. Always readable. */
    val workspaceId: String,
    val requestId: String? = null,
    /**
     * The Session that issued this call, when the host knows it.
     *
     * Added for delegation: a subagent tool must address its own calling Session to read its
     * depth and to attach a child to it. Null means "no Session context", in which case tools
     * that require one refuse rather than guess.
     */
    val sessionId: String? = null,
    /**
     * Additional Workspaces this turn may read.
     *
     * Empty for an ordinary turn. Populated from `@` references, and only with what the
     * authorization step approved. Tools that do not read files ignore it.
     */
    val grantedScopes: List<GrantedScope> = emptyList(),
    /** The model route this turn runs on, when known. */
    val modelProviderId: String? = null,
    val modelId: String? = null,
    /** The user text that triggered this turn, when known. */
    val requestText: String? = null,
    /** Host-issued security context for sensitive tools; never populated from tool JSON. */
    val securityContext: com.cordiact.core.security.SecurityContext? = null,
    /** Optional event callback to emit real-time tool lifecycle events to the caller/UI. */
    val onEvent: ((com.cordiact.core.conversation.ModelEvent) -> Unit)? = null,
    /** Identities from the actual turn's tool catalog. Never populated from model arguments. */
    @Transient val toolRegistrations: Map<String, ToolRegistration> = emptyMap(),
    /** Set by the host only for ordinary turns started from the foreground composer. */
    @Transient val allowsPluginAuthoring: Boolean = false,
    /** Never serialized, delegated, or populated from model-supplied tool arguments. */
    @Transient val authoredPluginTurn: com.cordiact.core.plugin.AuthoredPluginTurn? = null,
    /** One host loop dispatch; deliberately separate from the model's call ID. */
    @Transient internal val historyInvocation: ToolHistoryInvocation? = null,
) {
    /** The grant covering [targetWorkspaceId], or null when it is not readable this turn. */
    fun scopeFor(targetWorkspaceId: String): GrantedScope? =
        grantedScopes.firstOrNull { it.workspaceId == targetWorkspaceId }

    /** True when this turn may read from [targetWorkspaceId] at all. */
    fun canRead(targetWorkspaceId: String): Boolean =
        targetWorkspaceId == workspaceId || scopeFor(targetWorkspaceId) != null
}

/** Host-only identity of one published definition. Never serialize it to a model or a log. */
class ToolRegistration internal constructor(
    internal val scope: ToolContributionScope,
    internal val hostEffect: com.cordiact.core.security.ToolEffectClass? = null,
    internal val hasSensitiveArguments: Boolean,
)

/** Identity of one dispatch, even if a model reuses the same public call ID. */
class ToolHistoryInvocation internal constructor()

/** Opaque manager receipt. Neither a model call nor tool-result JSON can mint or replay one. */
class ToolHistoryReceipt internal constructor(
    internal val invocation: ToolHistoryInvocation,
    internal val registration: ToolRegistration,
    internal val provenance: ToolHistoryProvenance,
)

/** Explicit publication scope, separate from @ grants and other data-read permissions. */
internal data class ToolContributionScope(val workspaceId: String? = null, val sessionId: String? = null) {
    fun includes(workspace: String, session: String?): Boolean =
        (workspaceId == null || workspaceId == workspace) && (sessionId == null || sessionId == session)

    fun overlaps(other: ToolContributionScope): Boolean =
        (workspaceId == null || other.workspaceId == null || workspaceId == other.workspaceId) &&
            (sessionId == null || other.sessionId == null || sessionId == other.sessionId)

    val global: Boolean get() = workspaceId == null
}

@ExposedCopyVisibility // Preserve existing host adapters that copy a definition to wrap its executor.
data class ToolDefinition internal constructor(
    val descriptor: ToolDescriptor,
    val executor: ToolExecutor,
    @Transient val registration: ToolRegistration?,
) {
    constructor(descriptor: ToolDescriptor, executor: ToolExecutor) : this(descriptor, executor, null)
}

fun interface ToolExecutor {
    fun invoke(context: ToolInvocationContext, arguments: ToolArguments): ToolResult
}

fun interface ToolPreferenceStore {
    fun isEnabled(workspaceId: String, toolId: String): Boolean?
}

interface MutableToolPreferenceStore : ToolPreferenceStore {
    fun setEnabled(workspaceId: String, toolId: String, enabled: Boolean?)
}

fun interface ToolPluginStateLookup {
    fun state(pluginId: String): PluginState?

    /** Only registrations already verified by the manager can select an exact scoped owner. */
    fun scopedState(pluginId: String, workspaceId: String, sessionId: String?): PluginState? = state(pluginId)
}

fun interface ToolCapabilityGate {
    fun grantsAll(workspaceId: String, capabilityIds: Set<String>): Boolean

    /** Owner-aware hook; legacy gates retain their existing capability-only behavior. */
    fun grantsTool(workspaceId: String, descriptor: ToolDescriptor): Boolean =
        grantsAll(workspaceId, descriptor.requiredCapabilities)
}

/** One global definition and its optional per-turn policy, installed together by the host. */
data class GlobalToolPublication(val definition: ToolDefinition, val visibility: ToolVisibilityGate? = null)

interface ToolDefinitionRegistry {
    fun register(definition: ToolDefinition)

    /**
     * Publish the entire global batch or nothing. The returned handle only withdraws these exact
     * registrations, even if a legacy caller removed one and registered a replacement by ID.
     * The default fails closed for registries that cannot provide the atomic contract.
     */
    fun publishGlobalBatch(entries: List<GlobalToolPublication>): AutoCloseable =
        throw UnsupportedOperationException("Atomic global tool publication is not supported")
    fun get(toolId: String): ToolDefinition?

    /**
     * Remove one definition and its visibility gate.
     *
     * Foreign tool bridges (MCP) replace a whole generation at a time; without this the
     * previous generation could never be retired and a disconnected server would keep
     * offering tools that no longer answer.
     *
     * @return true when the tool was registered and is now gone.
     */
    fun unregister(toolId: String): Boolean

    fun all(): List<ToolDefinition>

    /** Legacy registries have only global definitions. */
    fun getIn(toolId: String, workspaceId: String, sessionId: String?): ToolDefinition? = get(toolId)
    fun allIn(workspaceId: String, sessionId: String?): List<ToolDefinition> = all()

    /** Attach the per-request visibility gate for an already registered tool. */
    fun registerVisibility(toolId: String, gate: ToolVisibilityGate)

    /** The gate for [toolId], or null when the tool is always visible. */
    fun visibility(toolId: String): ToolVisibilityGate?
}

class InMemoryToolDefinitionRegistry : ToolDefinitionRegistry {
    private data class Binding(val definition: ToolDefinition, val active: () -> Boolean)
    private val globalScope = ToolContributionScope()
    private val definitions = linkedMapOf<String, MutableMap<ToolContributionScope, Binding>>()
    private val visibilityGates = mutableMapOf<String, ToolVisibilityGate>()

    override fun register(definition: ToolDefinition) {
        publish(definition, globalScope) { true }
    }

    @Synchronized override fun publishGlobalBatch(entries: List<GlobalToolPublication>): AutoCloseable {
        val staged = linkedMapOf<String, Pair<ToolDefinition, ToolVisibilityGate?>>()
        for ((definition, visibility) in entries) {
            val bound = bind(definition, globalScope, hostEffect = null)
            val id = bound.descriptor.id
            require(id !in staged) { "Duplicate tool '$id' in global publication" }
            require(definitions[id].orEmpty().keys.none(globalScope::overlaps)) {
                "Tool '$id' is already registered in an overlapping scope"
            }
            staged[id] = bound to visibility
        }
        for ((id, entry) in staged) {
            val (bound, visibility) = entry
            definitions.getOrPut(id) { linkedMapOf() }[globalScope] = Binding(bound) { true }
            if (visibility != null) visibilityGates[id] = visibility
        }
        val tokens = staged.mapValues { (_, entry) -> checkNotNull(entry.first.registration) }
        return AutoCloseable { withdrawGlobalBatch(tokens) }
    }

    @Synchronized private fun withdrawGlobalBatch(tokens: Map<String, ToolRegistration>) {
        tokens.forEach { (id, token) -> withdraw(id, globalScope, token) }
    }

    /** The activation reader must be lock-free and must never invoke plugin code. */
    @Synchronized internal fun publish(
        definition: ToolDefinition,
        scope: ToolContributionScope,
        hostEffect: com.cordiact.core.security.ToolEffectClass? = null,
        active: () -> Boolean,
    ): AutoCloseable {
        val bound = bind(definition, scope, hostEffect)
        val id = bound.descriptor.id
        val bindings = definitions.getOrPut(id) { linkedMapOf() }
        require(bindings.keys.none(scope::overlaps)) { "Tool '$id' is already registered in an overlapping scope" }
        bindings[scope] = Binding(bound, active)
        val token = checkNotNull(bound.registration)
        return AutoCloseable { withdraw(id, scope, token) }
    }

    private fun bind(
        definition: ToolDefinition,
        scope: ToolContributionScope,
        hostEffect: com.cordiact.core.security.ToolEffectClass?,
    ): ToolDefinition {
        val descriptor = snapshotDescriptor(definition.descriptor)
        validateDescriptor(descriptor)
        validateExecutionDeclaration(descriptor)
        val token = ToolRegistration(scope, hostEffect, descriptor.inputSchema.parameters.any(ToolParameter::sensitive))
        return ToolDefinition(descriptor, definition.executor, token)
    }

    @Synchronized private fun withdraw(toolId: String, scope: ToolContributionScope, token: ToolRegistration) {
        if (definitions[toolId]?.get(scope)?.definition?.registration === token) remove(toolId, scope)
    }

    private fun remove(toolId: String, scope: ToolContributionScope): Boolean {
        val bindings = definitions[toolId] ?: return false
        val removed = bindings.remove(scope) != null
        if (bindings.isEmpty()) definitions.remove(toolId)
        if (scope.global) visibilityGates.remove(toolId)
        return removed
    }

    @Synchronized override fun get(toolId: String): ToolDefinition? =
        definitions[toolId]?.get(globalScope)?.snapshotIfActive()

    @Synchronized override fun unregister(toolId: String): Boolean = remove(toolId, globalScope)

    @Synchronized override fun all(): List<ToolDefinition> =
        definitions.values.mapNotNull { it[globalScope]?.snapshotIfActive() }

    @Synchronized override fun getIn(toolId: String, workspaceId: String, sessionId: String?): ToolDefinition? =
        definitions[toolId]?.inScope(workspaceId, sessionId)

    @Synchronized override fun allIn(workspaceId: String, sessionId: String?): List<ToolDefinition> =
        definitions.values.mapNotNull { it.inScope(workspaceId, sessionId) }

    private fun Map<ToolContributionScope, Binding>.inScope(workspaceId: String, sessionId: String?): ToolDefinition? =
        entries.firstOrNull { it.key.includes(workspaceId, sessionId) }?.value?.snapshotIfActive()

    private fun Binding.snapshotIfActive(): ToolDefinition? = if (active()) snapshotDefinition(definition) else null

    @Synchronized override fun registerVisibility(toolId: String, gate: ToolVisibilityGate) {
        require(definitions[toolId]?.containsKey(globalScope) == true) { "Tool '$toolId' is not registered" }
        visibilityGates[toolId] = gate
    }

    @Synchronized override fun visibility(toolId: String): ToolVisibilityGate? = visibilityGates[toolId]

    private fun validateExecutionDeclaration(descriptor: ToolDescriptor) {
        val parameterNames = descriptor.inputSchema.parameters.map { it.name }.toSet()
        val hasExecutionPayloadParameter = parameterNames.any { it in TOOL_EXECUTION_PARAMETER_NAMES }
        val hasExecutionCapability = descriptor.requiredCapabilities.any { it in TOOL_EXECUTION_CAPABILITIES }
        require(descriptor.execution != null || (!hasExecutionPayloadParameter && !hasExecutionCapability)) {
            "Tool ${descriptor.id} accepts an execution payload or declares an execution capability " +
                "but has no ToolExecutionMetadata; register its actions so ToolManagerPlugin can authorize it"
        }
        descriptor.execution?.actions?.forEach { action ->
            require(action.inputParameter in parameterNames) {
                "Tool ${descriptor.id} action ${action.action} references unknown input parameter ${action.inputParameter}"
            }
            (action.alternativeInputParameters + action.supplementalInputParameters).forEach { name ->
                require(name in parameterNames) {
                    "Tool ${descriptor.id} action ${action.action} references unknown parameter $name"
                }
            }
        }
    }

    private fun snapshotDefinition(definition: ToolDefinition) = ToolDefinition(
        snapshotDescriptor(definition.descriptor),
        definition.executor,
        definition.registration,
    )

    private fun snapshotDescriptor(descriptor: ToolDescriptor) = descriptor.copy(
        inputSchema = descriptor.inputSchema.copy(parameters = descriptor.inputSchema.parameters.toList()),
        requiredCapabilities = descriptor.requiredCapabilities.toSet(),
    )

    private fun validateDescriptor(descriptor: ToolDescriptor) {
        require(TOOL_ID.matches(descriptor.id)) { "Invalid tool id" }
        require(descriptor.name.isNotBlank() && descriptor.name.length <= MAX_NAME_LENGTH) { "Invalid tool name" }
        require(descriptor.description.length <= MAX_DESCRIPTION_LENGTH) { "Tool description is too long" }
        require(descriptor.ownerPluginId.matches(PLUGIN_ID)) { "Invalid tool owner plugin id" }
        require(descriptor.timeoutMs == null || descriptor.timeoutMs in 1..MAX_TIMEOUT_MS) {
            "Tool timeout must be within 1..$MAX_TIMEOUT_MS ms"
        }
        require(descriptor.inputSchema.parameters.map(ToolParameter::name).toSet().size == descriptor.inputSchema.parameters.size) {
            "Tool parameter names must be unique"
        }
        descriptor.inputSchema.parameters.forEach(::validateParameter)
        descriptor.rawInputSchemaJson?.let { raw ->
            require(raw.length <= MAX_RAW_SCHEMA_LENGTH) { "Tool input schema is too long" }
            // Cheap structural check only: core has no JSON parser and must not grow one.
            // A caller that hands over malformed JSON is the foreign adapter's bug, not the model's.
            require(raw.trimStart().startsWith("{") && raw.trimEnd().endsWith("}")) {
                "Tool input schema must be a JSON object"
            }
        }
        require(descriptor.requiredCapabilities.none(String::isBlank)) { "Invalid tool capability id" }
    }

    private fun validateParameter(parameter: ToolParameter) {
        require(parameter.name.matches(PARAMETER_NAME) && parameter.name.length <= MAX_PARAMETER_NAME_LENGTH) {
            "Invalid tool parameter name"
        }
        require(parameter.description == null || parameter.description.length <= MAX_PARAMETER_DESCRIPTION_LENGTH) {
            "Tool parameter description is too long"
        }
        require(parameter.enumValues.all(String::isNotBlank) && parameter.enumValues.distinct().size == parameter.enumValues.size) {
            "Tool parameter enum values must be non-blank and unique"
        }
        if (parameter.type == ToolValueType.ARRAY) {
            require(parameter.itemType != null && parameter.itemType != ToolValueType.ARRAY && parameter.itemType != ToolValueType.OBJECT) {
                "Array parameter '${parameter.name}' needs a scalar item type"
            }
            require(parameter.enumValues.isEmpty()) { "Array parameter '${parameter.name}' cannot declare enum values" }
        } else {
            require(parameter.itemType == null) { "Only array parameters may declare an item type" }
        }
        if (parameter.enumValues.isNotEmpty()) {
            require(parameter.type == ToolValueType.STRING) { "Only string parameters may declare enum values" }
        }
        if (parameter.minimum != null || parameter.maximum != null) {
            require(parameter.type == ToolValueType.NUMBER || parameter.type == ToolValueType.INTEGER) {
                "Only numeric parameters may declare bounds"
            }
            require(parameter.minimum == null || parameter.maximum == null || parameter.minimum <= parameter.maximum) {
                "Tool parameter bounds are inverted"
            }
        }
        if (parameter.minItems != null || parameter.maxItems != null) {
            require(parameter.type == ToolValueType.ARRAY) { "Only array parameters may declare item counts" }
            require(parameter.minItems == null || parameter.minItems >= 0) { "minItems must be non-negative" }
            require(parameter.maxItems == null || parameter.minItems == null || parameter.minItems <= parameter.maxItems) {
                "Tool parameter item counts are inverted"
            }
        }
        if (parameter.maxLength != null) {
            require(parameter.type == ToolValueType.STRING && parameter.maxLength > 0) { "maxLength applies to strings" }
        }
    }

    private companion object {
        val TOOL_ID = Regex("[a-z][a-z0-9._-]{1,127}")
        val PLUGIN_ID = Regex("[a-z0-9][a-z0-9._-]{1,127}")
        val PARAMETER_NAME = Regex("[A-Za-z_][A-Za-z0-9_]*")
        const val MAX_NAME_LENGTH = 120
        const val MAX_DESCRIPTION_LENGTH = 2_000
        const val MAX_PARAMETER_NAME_LENGTH = 128
        const val MAX_PARAMETER_DESCRIPTION_LENGTH = 1_000
        const val MAX_RAW_SCHEMA_LENGTH = 32_000
        const val MAX_TIMEOUT_MS = 300_000L
    }
}

interface ToolManagerService {
    fun list(workspaceId: String): List<ToolAvailability>
    fun setEnabled(workspaceId: String, toolId: String, enabled: Boolean)

    /**
     * Tools this workspace may offer on a turn.
     *
     * When [activation] is supplied, tools whose visibility gate rejects the
     * request are omitted. Omitting the argument keeps the workspace-wide view.
     */
    fun resolveEnabled(workspaceId: String, activation: ToolActivationContext? = null): List<ToolDescriptor>

    /** Host-only catalog; use these identities when dispatching calls from the advertised turn. */
    fun resolveForTurn(activation: ToolActivationContext): List<ToolDefinition>
    fun invoke(context: ToolInvocationContext, toolId: String, arguments: ToolArguments): ToolResult
}

class ToolManagerException(val code: Code, message: String) : RuntimeException(message) {
    enum class Code {
        WORKSPACE_NOT_FOUND,
        WORKSPACE_ARCHIVED,
        TOOL_MANAGER_DISABLED,
        TOOL_NOT_REGISTERED,
        TOOL_DISABLED,
        OWNER_PLUGIN_UNAVAILABLE,
        CAPABILITY_DENIED,
        INVALID_ARGUMENT,
        TOOL_TIMEOUT,
        APPROVAL_DENIED,
    }
}

/** Protected system plugin. Every read and dispatch revalidates workspace and current plugin state. */
class ToolManagerPlugin(
    private val registry: ToolDefinitionRegistry,
    private val preferences: MutableToolPreferenceStore,
    private val workspaces: WorkspaceManagerService,
    private val pluginStates: ToolPluginStateLookup,
    private val capabilities: ToolCapabilityGate = ToolCapabilityGate { _, required -> required.isEmpty() },
    private val executionPolicy: ToolExecutionAuthorizationPolicy = ToolExecutionAuthorizationPolicy(null),
    private val accessPolicy: com.cordiact.core.security.EnvelopeToolAccessPolicy? = null,
    private val autoReview: ((com.cordiact.core.security.SecurityContext, String, ToolArguments, () -> ToolResult) -> ToolResult)? = null,
    /** Authoritative host lookup. Without it no session-scoped contribution is visible. */
    private val sessionWorkspace: (String) -> String? = { null },
) : ManagedPlugin, ToolManagerService {
    override val spec: PluginSpec = toolManagerPluginSpec()
    @Volatile private var enabled = false
    @Volatile private var generation = 0L
    private val dispatchLock = Any()
    @Volatile private var workspaceVisibility: (String, ToolDescriptor) -> Boolean = { _, _ -> true }
    fun setWorkspaceVisibilityPolicy(policy: (String, ToolDescriptor) -> Boolean) {
        synchronized(dispatchLock) {
            workspaceVisibility = policy
            generation++
        }
    }

    /**
     * Preserves the old global serialization for tools that did not opt into
     * concurrent execution. Network tools declare `concurrencySafe` and bypass
     * it, so a slow search never blocks a file tool.
     */
    private val serializedExecution = Any()
    private val timeoutExecutor: ExecutorService = Executors.newCachedThreadPool { runnable ->
        Thread(runnable, "cordiact-tool-timeout").apply { isDaemon = true }
    }

    override fun onEnable() {
        synchronized(dispatchLock) {
            enabled = true
            generation++
        }
    }

    override fun onDisable() {
        synchronized(dispatchLock) {
            enabled = false
            generation++
        }
    }

    override fun list(workspaceId: String): List<ToolAvailability> {
        validateWorkspace(workspaceId)
        checkManagerEnabled()
        return registry.all().map { definition -> availability(workspaceId, definition) }
    }

    override fun setEnabled(workspaceId: String, toolId: String, enabled: Boolean) {
        validateWorkspace(workspaceId)
        checkManagerEnabled()
        val definition = registry.get(toolId) ?: throw failure(
            ToolManagerException.Code.TOOL_NOT_REGISTERED,
            "Tool is not registered",
        )
        preferences.setEnabled(
            workspaceId,
            toolId,
            enabled.takeUnless { it == definition.descriptor.defaultEnabled },
        )
        synchronized(dispatchLock) { generation++ }
    }

    override fun resolveEnabled(
        workspaceId: String,
        activation: ToolActivationContext?,
    ): List<ToolDescriptor> = if (activation == null) {
        // Keep legacy Settings and inventory global; scoped preferences are not stored by tool id.
        list(workspaceId).filter(ToolAvailability::dispatchable).map(ToolAvailability::descriptor)
    } else {
        require(activation.workspaceId == workspaceId) { "Tool activation belongs to another workspace" }
        resolveForTurn(activation).map(ToolDefinition::descriptor)
    }

    override fun resolveForTurn(activation: ToolActivationContext): List<ToolDefinition> {
        val workspace = activation.workspaceId
        validateWorkspace(workspace)
        checkManagerEnabled()
        val session = activation.sessionId?.takeIf { sessionWorkspace(it) == workspace }
        val candidates = if (session == null) registry.all() else registry.allIn(workspace, session)
        return candidates.filter { definition ->
            availability(workspace, definition).dispatchable &&
                registry.visibility(definition.descriptor.id)?.visible(activation) != false
        }
    }

    override fun invoke(context: ToolInvocationContext, toolId: String, arguments: ToolArguments): ToolResult =
        invokeFrozen(context, toolId, ToolArguments(arguments.values.mapValues { freezeValue(it.value) }))

    private fun freezeValue(value: ToolValue): ToolValue = when (value) {
        is ToolValue.ObjectValue -> ToolValue.ObjectValue(value.values.mapValues { freezeValue(it.value) })
        is ToolValue.ArrayValue -> ToolValue.ArrayValue(value.values.map(::freezeValue))
        else -> value
    }

    private fun invokeFrozen(context: ToolInvocationContext, toolId: String, arguments: ToolArguments): ToolResult {
        validateWorkspace(context.workspaceId)
        // Every granted scope must still be a live Workspace. A grant captured when the turn
        // started must not survive the Workspace being archived mid-turn.
        for (scope in context.grantedScopes) {
            validateWorkspace(scope.workspaceId)
        }
        checkManagerEnabled()
        val generationAtStart = synchronized(dispatchLock) {
            checkManagerLocallyEnabled()
            generation
        }
        val definition = currentDefinition(context, toolId) ?: throw failure(
            ToolManagerException.Code.TOOL_NOT_REGISTERED, "Tool is not registered for this turn",
        )
        val descriptor = definition.descriptor
        if (!isOwnerEnabled(definition)) {
            throw failure(ToolManagerException.Code.OWNER_PLUGIN_UNAVAILABLE, "Tool owner plugin is unavailable")
        }
        if (!capabilities.grantsTool(context.workspaceId, descriptor)) {
            throw failure(ToolManagerException.Code.CAPABILITY_DENIED, "Required capability is not granted")
        }
        if (!isEnabled(context.workspaceId, definition)) {
            throw failure(ToolManagerException.Code.TOOL_DISABLED, "Tool is disabled for this workspace")
        }
        validateArguments(descriptor.inputSchema, arguments)

        // Envelope check for non-credential tools. Credential tools still run their own
        // SensitiveOperationGate inside the executor; this check prevents a read-only or
        // workspace-only profile from reaching device/command effects through another tool.
        val securityContext = context.securityContext
        if (accessPolicy != null && securityContext == null) {
            throw failure(ToolManagerException.Code.APPROVAL_DENIED, "SECURITY_CONTEXT_REQUIRED")
        }
        var requiresReview = false
        if (accessPolicy != null && securityContext != null) {
            when (val access = accessPolicy.evaluate(
                securityContext, descriptor.id, descriptor.ownerPluginId, definition.registration?.hostEffect,
            )) {
                is com.cordiact.core.security.ToolAccessDecision.Deny -> throw failure(
                    ToolManagerException.Code.APPROVAL_DENIED,
                    access.reasonCode,
                )
                com.cordiact.core.security.ToolAccessDecision.Allow -> Unit
                com.cordiact.core.security.ToolAccessDecision.RequireReview -> requiresReview = true
            }
        }

        fun recheck() {
            checkManagerEnabled()
            validateWorkspace(context.workspaceId)
            if (!isOwnerEnabled(definition)) {
                throw failure(ToolManagerException.Code.OWNER_PLUGIN_UNAVAILABLE, "Tool owner plugin is unavailable")
            }
            if (!capabilities.grantsTool(context.workspaceId, descriptor)) {
                throw failure(ToolManagerException.Code.CAPABILITY_DENIED, "Required capability is not granted")
            }
            if (!isEnabled(context.workspaceId, definition)) {
                throw failure(ToolManagerException.Code.TOOL_DISABLED, "Tool is disabled for this workspace")
            }
            synchronized(dispatchLock) {
                checkManagerLocallyEnabled()
                if (generation != generationAtStart) {
                    throw failure(ToolManagerException.Code.TOOL_DISABLED, "Tool authorization changed")
                }
            }
            val current = currentDefinition(context, toolId)
            if (current == null || current.registration !== definition.registration ||
                current.descriptor != descriptor || current.executor !== definition.executor) {
                throw failure(ToolManagerException.Code.APPROVAL_DENIED, "Tool registration changed")
            }
        }

        // No stale registration should even open an approval card. All execution backends share
        // this check, then recheck inside the serial queue or timeout worker at admission.
        recheck()
        when (val authorization = executionPolicy.authorize(
            descriptor, arguments, com.cordiact.core.action.ApprovalOwner(context.workspaceId, context.sessionId),
        )) {
            is ToolExecutionAuthorization.Denied -> throw failure(
                ToolManagerException.Code.APPROVAL_DENIED, authorization.reason,
            )
            is ToolExecutionAuthorization.Allowed, is ToolExecutionAuthorization.Approved -> Unit
        }
        recheck()
        // An admitted action may have side effects; withdrawal invalidates its publication, not history.
        val invokeExecutor = {
            recheck()
            // The executor receives its authorization context, never the loop's event channel
            // or receipt identity. A nested call is not the tool shown on this trace row.
            definition.executor.invoke(context.copy(onEvent = null, historyInvocation = null), arguments)
        }
        val executedResult = java.util.concurrent.atomic.AtomicReference<ToolResult?>()
        val execute = {
            (if (descriptor.concurrencySafe) executeWithTimeout(descriptor.timeoutMs, invokeExecutor)
            else synchronized(serializedExecution) { executeWithTimeout(descriptor.timeoutMs, invokeExecutor) })
                .also(executedResult::set)
        }
        val result = if (requiresReview) {
            val reviewer = autoReview ?: throw failure(ToolManagerException.Code.APPROVAL_DENIED, "AUTO_REVIEW_UNAVAILABLE")
            reviewer(requireNotNull(securityContext), descriptor.id, arguments, execute)
        } else execute()
        try {
            recheck()
        } catch (refused: Exception) {
            result.sensitivePayload?.close()
            throw refused
        }
        val registration = definition.registration
        val invocation = context.historyInvocation
        val receipt = if (invocation != null && registration != null &&
            registration === context.toolRegistrations[toolId] && result === executedResult.get() &&
            result.sensitivePayload == null && !registration.hasSensitiveArguments &&
            (result.values["ok"] as? ToolValue.BooleanValue)?.value != false && "error" !in result.values
        ) {
            ToolHistoryReceipt(invocation, registration, ToolHistoryProvenance(
                ownerPluginId = descriptor.ownerPluginId,
                toolId = descriptor.id,
                source = ToolHistorySource.HOST_REGISTRATION,
                format = ToolHistoryFormat.BOUNDED_SUMMARY_V1,
            ))
        } else null
        return if (receipt == null && result.historyReceipt == null) result else result.copy(historyReceipt = receipt)
    }

    private fun currentDefinition(context: ToolInvocationContext, toolId: String): ToolDefinition? {
        val binding = context.toolRegistrations[toolId]
        if (binding == null || binding.scope.global) {
            // Legacy host calls can resolve only global definitions. A bound turn cannot silently
            // switch to a replacement, even when the public schema is unchanged.
            return registry.get(toolId)?.takeIf { binding == null || binding === it.registration }
        }
        // @ read grants and caller-provided tool arguments never imply publication scope.
        val workspace = context.workspaceId
        val session = context.sessionId ?: return null
        if (!binding.scope.includes(workspace, session) || sessionWorkspace(session) != workspace) return null
        val security = context.securityContext
        if (security != null && (security.workspaceId != workspace || security.sessionId != session)) return null
        return registry.getIn(toolId, workspace, session)?.takeIf { binding === it.registration }
    }

    private fun executeWithTimeout(timeoutMs: Long?, block: () -> ToolResult): ToolResult {
        if (timeoutMs == null) return block()
        val task = FutureTask(block)
        timeoutExecutor.execute(task)
        return try {
            task.get(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (timeout: TimeoutException) {
            task.cancel(true)
            throw failure(ToolManagerException.Code.TOOL_TIMEOUT, "Tool execution timed out")
        } catch (failure: ExecutionException) {
            when (val cause = failure.cause) {
                is RuntimeException -> throw cause
                else -> throw ToolManagerException(
                    ToolManagerException.Code.INVALID_ARGUMENT,
                    cause?.message ?: "Tool execution failed",
                )
            }
        }
    }

    private fun validateArguments(schema: ToolInputSchema, arguments: ToolArguments) {
        val declared = schema.parameters.associateBy(ToolParameter::name)
        if (!schema.allowAdditional && arguments.values.keys.any { it !in declared }) {
            throw failure(ToolManagerException.Code.INVALID_ARGUMENT, "Tool arguments contain unknown fields")
        }
        schema.parameters.forEach { parameter ->
            val value = arguments.values[parameter.name]
            if (value == null && parameter.required) {
                throw failure(ToolManagerException.Code.INVALID_ARGUMENT, "Required tool argument is missing")
            }
            if (value == null) return@forEach
            if (!matchesType(parameter, value)) {
                throw failure(ToolManagerException.Code.INVALID_ARGUMENT, "Tool argument has the wrong type")
            }
            validateConstraints(parameter, value)
        }
    }

    private fun validateConstraints(parameter: ToolParameter, value: ToolValue) {
        when (value) {
            is ToolValue.StringValue -> {
                if (parameter.enumValues.isNotEmpty() && value.value !in parameter.enumValues) {
                    throw failure(ToolManagerException.Code.INVALID_ARGUMENT, "Tool argument is not an allowed value")
                }
                if (parameter.maxLength != null && value.value.length > parameter.maxLength) {
                    throw failure(ToolManagerException.Code.INVALID_ARGUMENT, "Tool argument is too long")
                }
            }
            is ToolValue.NumberValue -> {
                if (parameter.minimum != null && value.value < parameter.minimum) {
                    throw failure(ToolManagerException.Code.INVALID_ARGUMENT, "Tool argument is below the minimum")
                }
                if (parameter.maximum != null && value.value > parameter.maximum) {
                    throw failure(ToolManagerException.Code.INVALID_ARGUMENT, "Tool argument is above the maximum")
                }
            }
            is ToolValue.ArrayValue -> {
                if (parameter.minItems != null && value.values.size < parameter.minItems) {
                    throw failure(ToolManagerException.Code.INVALID_ARGUMENT, "Tool argument has too few items")
                }
                if (parameter.maxItems != null && value.values.size > parameter.maxItems) {
                    throw failure(ToolManagerException.Code.INVALID_ARGUMENT, "Tool argument has too many items")
                }
            }
            else -> Unit
        }
    }

    private fun matchesType(parameter: ToolParameter, value: ToolValue): Boolean = when (parameter.type) {
        ToolValueType.STRING -> value is ToolValue.StringValue
        ToolValueType.NUMBER -> value is ToolValue.NumberValue && value.value.isFinite()
        ToolValueType.INTEGER -> value is ToolValue.NumberValue &&
            value.value.isFinite() && value.value == Math.floor(value.value)
        ToolValueType.BOOLEAN -> value is ToolValue.BooleanValue
        ToolValueType.ARRAY -> value is ToolValue.ArrayValue &&
            parameter.itemType != null && value.values.all { matchesType(parameter.itemType, it) }
        ToolValueType.OBJECT -> value is ToolValue.ObjectValue
    }

    private fun matchesType(type: ToolValueType, value: ToolValue): Boolean = matchesType(
        ToolParameter(name = "item", type = type),
        value,
    )

    private fun isOwnerEnabled(definition: ToolDefinition): Boolean {
        val owner = definition.descriptor.ownerPluginId
        val scope = definition.registration?.scope
        val workspace = scope?.workspaceId
        val state = if (workspace == null) pluginStates.state(owner) else pluginStates.scopedState(owner, workspace, scope.sessionId)
        return state == PluginState.ENABLED
    }

    private fun availability(workspaceId: String, definition: ToolDefinition): ToolAvailability {
        val descriptor = definition.descriptor
        return ToolAvailability(
            descriptor = descriptor,
            enabledForWorkspace = isEnabled(workspaceId, definition),
            ownerEnabled = isOwnerEnabled(definition),
            capabilitiesGranted = capabilities.grantsTool(workspaceId, descriptor),
        )
    }

    private fun isEnabled(workspaceId: String, definition: ToolDefinition): Boolean {
        val descriptor = definition.descriptor
        if (!workspaceVisibility(workspaceId, descriptor)) return false
        // A stale global preference must not grant a future scoped registration the same id.
        if (definition.registration?.scope?.global == false) return descriptor.defaultEnabled
        return preferences.isEnabled(workspaceId, descriptor.id) ?: descriptor.defaultEnabled
    }

    private fun validateWorkspace(workspaceId: String) {
        try {
            val workspace = workspaces.get(workspaceId)
                ?: throw failure(ToolManagerException.Code.WORKSPACE_NOT_FOUND, "Workspace is unavailable")
            if (workspace.archivedAtMillis != null) {
                throw failure(ToolManagerException.Code.WORKSPACE_ARCHIVED, "Workspace is archived")
            }
            workspaces.resolveSessionWorkspace(workspaceId)
        } catch (failure: ToolManagerException) {
            throw failure
        } catch (failure: Exception) {
            throw ToolManagerException(ToolManagerException.Code.WORKSPACE_NOT_FOUND, "Workspace is unavailable")
        }
    }

    private fun checkManagerEnabled() {
        if (!enabled || pluginStates.state(spec.id) != PluginState.ENABLED) {
            throw failure(ToolManagerException.Code.TOOL_MANAGER_DISABLED, "Tool Manager is unavailable")
        }
    }

    private fun checkManagerLocallyEnabled() {
        if (!enabled) throw failure(ToolManagerException.Code.TOOL_MANAGER_DISABLED, "Tool Manager is unavailable")
    }

    private fun failure(code: ToolManagerException.Code, message: String) = ToolManagerException(code, message)
}
