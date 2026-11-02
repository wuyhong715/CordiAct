package com.cordiact.core.tool

import com.cordiact.core.plugin.ManagedPlugin
import com.cordiact.core.plugin.PluginSpec
import com.cordiact.core.plugin.PluginState
import com.cordiact.core.plugin.PluginTarget
import com.cordiact.core.workspace.WorkspaceManagerService

const val TOOL_MANAGER_PLUGIN_ID = "core.tool-manager"
const val TOOL_MANAGER_VERSION = "0.1.0"
const val TOOL_MANAGER_CAPABILITY = "core.tools.manage"

fun toolManagerPluginSpec() = PluginSpec(
    id = TOOL_MANAGER_PLUGIN_ID,
    version = TOOL_MANAGER_VERSION,
    target = PluginTarget.ANDROID_NATIVE,
    dependencies = setOf("core.workspace-manager"),
)

enum class ToolValueType { STRING, NUMBER, BOOLEAN }

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
data class ToolResult(val values: Map<String, ToolValue> = emptyMap())

data class ToolParameter(val name: String, val type: ToolValueType, val required: Boolean = false)

data class ToolInputSchema(val parameters: List<ToolParameter> = emptyList(), val allowAdditional: Boolean = false)

data class ToolDescriptor(
    val id: String,
    val name: String,
    val description: String,
    val inputSchema: ToolInputSchema,
    val ownerPluginId: String,
    val defaultEnabled: Boolean = false,
    val requiredCapabilities: Set<String> = emptySet(),
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
     * Additional Workspaces this turn may read.
     *
     * Empty for an ordinary turn. Populated from `@` references, and only with what the
     * authorization step approved. Tools that do not read files ignore it.
     */
    val grantedScopes: List<GrantedScope> = emptyList(),
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
    fun all(): List<ToolDefinition>
}

class InMemoryToolDefinitionRegistry : ToolDefinitionRegistry {
    private val definitions = linkedMapOf<String, ToolDefinition>()

    @Synchronized
    override fun register(definition: ToolDefinition) {
        val descriptor = snapshotDescriptor(definition.descriptor)
        validateDescriptor(descriptor)
        require(definitions.putIfAbsent(descriptor.id, ToolDefinition(descriptor, definition.executor)) == null) {
            "Tool '${descriptor.id}' is already registered"
        }
    }

    @Synchronized
    override fun get(toolId: String): ToolDefinition? = definitions[toolId]?.let(::snapshotDefinition)

    @Synchronized
    override fun all(): List<ToolDefinition> = definitions.values.map(::snapshotDefinition)

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
        require(descriptor.inputSchema.parameters.map(ToolParameter::name).toSet().size == descriptor.inputSchema.parameters.size) {
            "Tool parameter names must be unique"
        }
        require(descriptor.inputSchema.parameters.all { parameter ->
            parameter.name.matches(PARAMETER_NAME) && parameter.name.length <= MAX_PARAMETER_NAME_LENGTH
        }) { "Invalid tool parameter name" }
        require(descriptor.requiredCapabilities.none(String::isBlank)) { "Invalid tool capability id" }
    }

    private companion object {
        val TOOL_ID = Regex("[a-z][a-z0-9._-]{1,127}")
        val PLUGIN_ID = Regex("[a-z0-9][a-z0-9._-]{1,127}")
        val PARAMETER_NAME = Regex("[A-Za-z_][A-Za-z0-9_]*")
        const val MAX_NAME_LENGTH = 120
        const val MAX_DESCRIPTION_LENGTH = 2_000
        const val MAX_PARAMETER_NAME_LENGTH = 128
    }
}

interface ToolManagerService {
    fun list(workspaceId: String): List<ToolAvailability>
    fun setEnabled(workspaceId: String, toolId: String, enabled: Boolean)
    fun resolveEnabled(workspaceId: String): List<ToolDescriptor>
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
    }
}

/** Protected system plugin. Every read and dispatch revalidates workspace and current plugin state. */
class ToolManagerPlugin(
    private val registry: ToolDefinitionRegistry,
    private val preferences: MutableToolPreferenceStore,
    private val workspaces: WorkspaceManagerService,
    private val pluginStates: ToolPluginStateLookup,
    private val capabilities: ToolCapabilityGate = ToolCapabilityGate { _, required -> required.isEmpty() },
) : ManagedPlugin, ToolManagerService {
    override val spec: PluginSpec = toolManagerPluginSpec()
    @Volatile private var enabled = false
    private val dispatchLock = Any()

    override fun onEnable() = synchronized(dispatchLock) { enabled = true }
    override fun onDisable() = synchronized(dispatchLock) { enabled = false }

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
    }

    override fun resolveEnabled(workspaceId: String): List<ToolDescriptor> = list(workspaceId)
        .filter(ToolAvailability::dispatchable)
        .map(ToolAvailability::descriptor)

    override fun invoke(context: ToolInvocationContext, toolId: String, arguments: ToolArguments): ToolResult {
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
        return synchronized(dispatchLock) {
            checkManagerLocallyEnabled()
            if (!isEnabled(context.workspaceId, descriptor)) {
                throw failure(ToolManagerException.Code.TOOL_DISABLED, "Tool is disabled for this workspace")
            }
            definition.executor.invoke(context, arguments)
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
            if (value != null && !matchesType(parameter.type, value)) {
                throw failure(ToolManagerException.Code.INVALID_ARGUMENT, "Tool argument has the wrong type")
            }
        }
    }

    private fun matchesType(type: ToolValueType, value: ToolValue): Boolean = when (type) {
        ToolValueType.STRING -> value is ToolValue.StringValue
        ToolValueType.NUMBER -> value is ToolValue.NumberValue && value.value.isFinite()
        ToolValueType.BOOLEAN -> value is ToolValue.BooleanValue
    }

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
