package com.cordiact.core.tool

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
) {
    /** The grant covering [targetWorkspaceId], or null when it is not readable this turn. */
    fun scopeFor(targetWorkspaceId: String): GrantedScope? =
        grantedScopes.firstOrNull { it.workspaceId == targetWorkspaceId }

    /** True when this turn may read from [targetWorkspaceId] at all. */
    fun canRead(targetWorkspaceId: String): Boolean =
        targetWorkspaceId == workspaceId || scopeFor(targetWorkspaceId) != null
}

data class ToolDefinition(
    val descriptor: ToolDescriptor,
    val executor: ToolExecutor,
)

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
}

fun interface ToolCapabilityGate {
    fun grantsAll(workspaceId: String, capabilityIds: Set<String>): Boolean
}

interface ToolDefinitionRegistry {
    fun register(definition: ToolDefinition)
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

    /** Attach the per-request visibility gate for an already registered tool. */
    fun registerVisibility(toolId: String, gate: ToolVisibilityGate)

    /** The gate for [toolId], or null when the tool is always visible. */
    fun visibility(toolId: String): ToolVisibilityGate?
}

class InMemoryToolDefinitionRegistry : ToolDefinitionRegistry {
    private val definitions = linkedMapOf<String, ToolDefinition>()
    private val visibilityGates = mutableMapOf<String, ToolVisibilityGate>()

    @Synchronized
    override fun register(definition: ToolDefinition) {
        val descriptor = snapshotDescriptor(definition.descriptor)
        validateDescriptor(descriptor)
        validateExecutionDeclaration(descriptor)
        require(definitions.putIfAbsent(descriptor.id, ToolDefinition(descriptor, definition.executor)) == null) {
            "Tool '${descriptor.id}' is already registered"
        }
    }

    @Synchronized
    override fun get(toolId: String): ToolDefinition? = definitions[toolId]?.let(::snapshotDefinition)

    @Synchronized
    override fun unregister(toolId: String): Boolean {
        visibilityGates.remove(toolId)
        return definitions.remove(toolId) != null
    }

    @Synchronized
    override fun all(): List<ToolDefinition> = definitions.values.map(::snapshotDefinition)

    @Synchronized
    override fun registerVisibility(toolId: String, gate: ToolVisibilityGate) {
        require(definitions.containsKey(toolId)) { "Tool '$toolId' is not registered" }
        visibilityGates[toolId] = gate
    }

    @Synchronized
    override fun visibility(toolId: String): ToolVisibilityGate? = visibilityGates[toolId]

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
) : ManagedPlugin, ToolManagerService {
    override val spec: PluginSpec = toolManagerPluginSpec()
    @Volatile private var enabled = false
    @Volatile private var generation = 0L
    private val dispatchLock = Any()

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
        return registry.all().map { definition -> availability(workspaceId, definition.descriptor) }
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
    ): List<ToolDescriptor> = list(workspaceId)
        .filter(ToolAvailability::dispatchable)
        .map(ToolAvailability::descriptor)
        .filter { descriptor ->
            activation == null || registry.visibility(descriptor.id)?.visible(activation) != false
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
        val definition = registry.get(toolId) ?: throw failure(
            ToolManagerException.Code.TOOL_NOT_REGISTERED,
            "Tool is not registered",
        )
        val descriptor = definition.descriptor
        if (pluginStates.state(descriptor.ownerPluginId) != PluginState.ENABLED) {
            throw failure(ToolManagerException.Code.OWNER_PLUGIN_UNAVAILABLE, "Tool owner plugin is unavailable")
        }
        if (!capabilities.grantsAll(context.workspaceId, descriptor.requiredCapabilities)) {
            throw failure(ToolManagerException.Code.CAPABILITY_DENIED, "Required capability is not granted")
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
            when (val access = accessPolicy.evaluate(securityContext, descriptor.id, descriptor.ownerPluginId)) {
                is com.cordiact.core.security.ToolAccessDecision.Deny -> throw failure(
                    ToolManagerException.Code.APPROVAL_DENIED,
                    access.reasonCode,
                )
                com.cordiact.core.security.ToolAccessDecision.Allow -> Unit
                com.cordiact.core.security.ToolAccessDecision.RequireReview -> requiresReview = true
            }
        }

        // Human authorization is enforced here, after schema/capability checks and
        // before the real executor. Every caller (model, sub-agent, tool-to-tool,
        // SSH/Termux background start) goes through this single path.
        when (val authorization = executionPolicy.authorize(descriptor, arguments)) {
            is ToolExecutionAuthorization.Denied -> throw failure(
                ToolManagerException.Code.APPROVAL_DENIED,
                authorization.reason,
            )
            is ToolExecutionAuthorization.Allowed, is ToolExecutionAuthorization.Approved -> Unit
        }
        // The tool may have been re-registered while the approval card was open;
        // never execute a different definition than the one that was approved.
        if (descriptor.execution != null && registry.get(toolId)?.descriptor != descriptor) {
            throw failure(
                ToolManagerException.Code.APPROVAL_DENIED,
                "Tool definition changed during approval; execution refused",
            )
        }

        // Snapshot the authorization state, then execute OUTSIDE dispatchLock. Network tools
        // must never hold a lock shared by every Workspace; the generation check below discards
        // a result whose authorization changed while it was in flight.
        val generationAtStart = synchronized(dispatchLock) {
            checkManagerLocallyEnabled()
            if (!isEnabled(context.workspaceId, descriptor)) {
                throw failure(ToolManagerException.Code.TOOL_DISABLED, "Tool is disabled for this workspace")
            }
            generation
        }

        val execute = {
            synchronized(dispatchLock) {
                checkManagerLocallyEnabled()
                if (generation != generationAtStart || !isEnabled(context.workspaceId, descriptor)) {
                    throw failure(ToolManagerException.Code.TOOL_DISABLED, "Tool authorization changed during approval")
                }
            }
            if (registry.get(toolId).let { it == null || it.descriptor != definition.descriptor || it.executor !== definition.executor }) {
                throw failure(ToolManagerException.Code.APPROVAL_DENIED, "Tool definition changed during approval")
            }
            if (descriptor.concurrencySafe) {
            executeWithTimeout(descriptor.timeoutMs) { definition.executor.invoke(context, arguments) }
        } else {
            synchronized(serializedExecution) {
                executeWithTimeout(descriptor.timeoutMs) { definition.executor.invoke(context, arguments) }
            }
        }

        }
        val result = if (requiresReview) {
            val reviewer = autoReview ?: throw failure(ToolManagerException.Code.APPROVAL_DENIED, "AUTO_REVIEW_UNAVAILABLE")
            reviewer(requireNotNull(securityContext), descriptor.id, arguments, execute)
        } else execute()

        // Revalidate after execution: a disabled manager, revoked preference, unloaded owner
        // plugin or archived Workspace makes the result stale, so it is discarded fail-closed.
        synchronized(dispatchLock) {
            checkManagerLocallyEnabled()
            if (generation != generationAtStart) {
                throw failure(ToolManagerException.Code.TOOL_DISABLED, "Tool authorization changed during execution")
            }
            if (!isEnabled(context.workspaceId, descriptor)) {
                throw failure(ToolManagerException.Code.TOOL_DISABLED, "Tool is disabled for this workspace")
            }
        }
        if (pluginStates.state(descriptor.ownerPluginId) != PluginState.ENABLED) {
            throw failure(ToolManagerException.Code.OWNER_PLUGIN_UNAVAILABLE, "Tool owner plugin is unavailable")
        }
        validateWorkspace(context.workspaceId)
        return result
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

    private fun availability(workspaceId: String, descriptor: ToolDescriptor): ToolAvailability = ToolAvailability(
        descriptor = descriptor,
        enabledForWorkspace = isEnabled(workspaceId, descriptor),
        ownerEnabled = pluginStates.state(descriptor.ownerPluginId) == PluginState.ENABLED,
        capabilitiesGranted = capabilities.grantsAll(workspaceId, descriptor.requiredCapabilities),
    )

    private fun isEnabled(workspaceId: String, descriptor: ToolDescriptor): Boolean =
        preferences.isEnabled(workspaceId, descriptor.id) ?: descriptor.defaultEnabled

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
