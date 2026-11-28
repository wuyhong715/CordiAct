package com.cordiact.core.plugin

import com.google.gson.Strictness
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import com.cordiact.core.settings.WorkspaceDeclarations
import java.io.IOException
import java.io.StringReader
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest
import java.util.Collections

/** Host API and minimum host version this build accepts for declarative extension packages. */
const val EXTENSION_PACKAGE_HOST_API = 1
const val EXTENSION_PACKAGE_HOST_VERSION = "0.1.0"

/**
 * A capability a package declares before its contributions may be published. The host records the
 * user's consent per exact package version; a version that adds a capability needs a new consent.
 */
enum class ExtensionPackageCapability(val wireName: String) {
    TOOL_PUBLISH("tool.publish"),
    COMMAND_PUBLISH("command.publish"),
    UI_SETTINGS_PUBLISH("ui.settings.publish"),
    UI_SESSION_ACTION_PUBLISH("ui.session-action.publish"),
    UI_WORKBENCH_PANEL_PUBLISH("ui.workbench-panel.publish"),
    TOOL_SCRIPT_EXECUTE("tool.script.execute"),
    AGENT_SKILL_PUBLISH("agent.skill.publish"),
    AGENT_CONTEXT_PUBLISH("agent.context.publish"),
    SEARCH_PROVIDER_PUBLISH("provider.search.publish"),
    MODEL_PROVIDER_PUBLISH("provider.model.publish"),
    AGENT_TOOL_POLICY_PUBLISH("agent.tool-policy.publish"),
    WORKFLOW_PUBLISH("workflow.publish"),
    AGENT_SUBAGENT_PRESET_PUBLISH("agent.subagent-preset.publish"),
    UI_CONVERSATION_PUBLISH("ui.conversation.publish"),
    AGENT_TURN_POLICY_PUBLISH("agent.turn-policy.publish"),
}

/** One host-executed tool. [operation] is null for a fixed-text tool. */
data class ExtensionPackageTool(
    val name: String,
    val description: String,
    val text: String,
    val operation: AuthoredTextOperation? = null,
    val script: String? = null,
) {
    init {
        require(operation == null || text.isEmpty())
        require(script == null || (operation == null && text.isEmpty()))
    }
}

/** One host-executed slash command. It never calls a model or runs package code. */
data class ExtensionPackageCommand(
    val name: String,
    val aliases: Set<String>,
    val description: String,
    val text: String,
)

/** Client contributions rendered by the host from validated declarations. */
data class ExtensionPackageClient(
    val settingsPage: ExtensionPackageSettingsPage? = null,
    val sessionActions: List<ExtensionPackageSessionAction> = emptyList(),
    val workbenchPanel: ExtensionPackageSettingsPage? = null,
    val conversation: ExtensionPackageConversation? = null,
)

enum class ExtensionPackageMessageEvent(val wireName: String) { USER("user.message"), ASSISTANT("assistant.message") }

data class ExtensionPackageConversation(
    val messages: List<ExtensionPackageView<ExtensionPackageMessageEvent>> = emptyList(),
    val toolResults: List<ExtensionPackageView<String>> = emptyList(),
)

data class ExtensionPackageView<T>(val selector: T, val title: String, val lines: List<String>)

data class ExtensionPackageSettingsPage(
    val title: String,
    val description: String,
    val rows: List<ExtensionPackageSettingsRow> = emptyList(),
)

data class ExtensionPackageSettingsRow(val label: String, val value: String)

/** [command] is the primary name of a command from this package, without a leading slash. */
data class ExtensionPackageSessionAction(val id: String, val label: String, val command: String)

data class ExtensionPackageAgent(
    val skills: List<ExtensionPackageSkill> = emptyList(),
    val context: String? = null,
    val beforeTool: List<ExtensionPackageToolPolicy> = emptyList(),
    val subagents: List<ExtensionPackageSubagent> = emptyList(),
    val beforeTurn: ExtensionPackageTurnPolicy? = null,
)

data class ExtensionPackageSubagent(val name: String, val title: String, val instructions: String, val tools: Set<String>)

data class ExtensionPackageSkill(val name: String, val description: String, val body: String)

data class ExtensionPackageToolPolicy(val name: String, val tools: Set<String>, val script: String)
data class ExtensionPackageTurnPolicy(val name: String, val script: String)

data class ExtensionPackageWorkflow(val name: String, val title: String, val steps: List<ExtensionPackageWorkflowStep>)

data class ExtensionPackageWorkflowStep(val title: String, val script: String)

data class ExtensionPackageSearchProvider(val name: String, val title: String, val script: String)

data class ExtensionPackageModelProvider(
    val name: String,
    val title: String,
    val protocol: String,
    val endpoint: String,
    val models: List<String>,
)

/** Immutable, validated package manifest. [sha256] covers the exact source bytes. */
data class ExtensionPackageManifest(
    val schemaVersion: Int,
    val id: String,
    val version: String,
    val title: String,
    val description: String,
    val hostApi: Int,
    val minHostVersion: String,
    val capabilities: Set<ExtensionPackageCapability>,
    val tools: List<ExtensionPackageTool>,
    val commands: List<ExtensionPackageCommand>,
    val sha256: String,
    val sizeBytes: Int,
    val client: ExtensionPackageClient? = null,
    val agent: ExtensionPackageAgent? = null,
    val searchProviders: List<ExtensionPackageSearchProvider> = emptyList(),
    val modelProviders: List<ExtensionPackageModelProvider> = emptyList(),
    val workflows: List<ExtensionPackageWorkflow> = emptyList(),
)


/** The one stable spec projection of a package manifest; hosts and the runtime must agree. */
fun extensionPackageSpec(manifest: ExtensionPackageManifest): PluginSpec = PluginSpec(
    id = "pkg.${manifest.id}",
    version = manifest.version,
    target = if (manifest.tools.any { it.script != null } || manifest.searchProviders.isNotEmpty() ||
        !manifest.agent?.beforeTool.isNullOrEmpty() || manifest.agent?.beforeTurn != null || manifest.workflows.isNotEmpty())
        PluginTarget.ANDROID_ISOLATED_JS else PluginTarget.ANDROID_NATIVE,
    requestedCapabilities = manifest.capabilities.mapTo(linkedSetOf()) { it.wireName },
    config = manifest.sha256,
)

/**
 * Strict, bounded reader for extension packages. It validates declarations and preserves the
 * exact source snapshot for installation. Evaluation belongs to the isolated runtime.
 */
object ExtensionPackageSource {
    const val MAX_SOURCE_BYTES = 32 * 1024
    const val MAX_TOOLS = 4
    const val MAX_COMMANDS = 4
    private const val MAX_SETTINGS_ROWS = 4
    private const val MAX_SESSION_ACTIONS = 2
    private const val MAX_SKILLS = 4
    private const val MAX_CONTEXT_BYTES = 1024
    private val policyToolId = Regex("[a-z][a-z0-9._-]{1,127}")
    const val MAX_TITLE_BYTES = 120
    const val MAX_DESCRIPTION_BYTES = 480
    const val MAX_TEXT_BYTES = 4 * 1024
    private const val HEX = "0123456789abcdef"
    private val slug = Regex("[a-z][a-z0-9-]{1,23}")
    private val version = Regex("[0-9]{1,6}\\.[0-9]{1,6}\\.[0-9]{1,6}")

    /** Workspace-relative draft path. Installation uses approved bytes from host storage. */
    fun path(packageId: String): String {
        validateSlug(packageId, "Invalid package ID")
        return "plugins/packages/$packageId.json"
    }

    /**
     * Compiles one exact source snapshot. Host compatibility is checked against [hostVersion] and
     * [hostApi]; callers must pass the running host's values so an older package is rejected rather
     * than silently downgraded.
     */
    fun compile(
        bytes: ByteArray,
        hostVersion: String = EXTENSION_PACKAGE_HOST_VERSION,
        hostApi: Int = EXTENSION_PACKAGE_HOST_API,
    ): ExtensionPackageManifest {
        require(bytes.size in 1..MAX_SOURCE_BYTES) { "Invalid package source size" }
        val sourceBytes = bytes.copyOf()
        val source = try {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(sourceBytes)).toString()
        } catch (_: CharacterCodingException) {
            throw IllegalArgumentException("Package source must be valid UTF-8")
        }
        require(!source.startsWith('\uFEFF')) { "Package source must not start with a BOM" }
        val manifest = try {
            JsonReader(StringReader(source)).use { reader ->
                reader.strictness = Strictness.STRICT
                readManifest(reader, sourceBytes)
            }
        } catch (_: IOException) {
            throw IllegalArgumentException("Invalid package JSON")
        } catch (_: IllegalStateException) {
            throw IllegalArgumentException("Invalid package JSON")
        } catch (_: NumberFormatException) {
            throw IllegalArgumentException("Invalid package JSON")
        }
        require(manifest.hostApi == hostApi) { "Package requires host API ${manifest.hostApi}" }
        require(compareVersions(manifest.minHostVersion, hostVersion) <= 0) {
            "Package requires host version ${manifest.minHostVersion} or newer"
        }
        return manifest
    }

    /** Numeric `major.minor.patch` comparison; callers validate the format first. */
    fun compareVersions(left: String, right: String): Int {
        val a = left.split('.').map { it.toInt() }
        val b = right.split('.').map { it.toInt() }
        for (index in 0..2) {
            val order = a[index].compareTo(b[index])
            if (order != 0) return order
        }
        return 0
    }

    fun isVersion(value: String): Boolean = version.matches(value)

    private fun readManifest(reader: JsonReader, bytes: ByteArray): ExtensionPackageManifest {
        require(reader.peek() == JsonToken.BEGIN_OBJECT) { "Package source must be a JSON object" }
        reader.beginObject()
        val fields = mutableSetOf<String>()
        var schemaVersion: Int? = null
        var id: String? = null
        var packageVersion: String? = null
        var title: String? = null
        var description: String? = null
        var hostApi: Int? = null
        var minHostVersion: String? = null
        var capabilities: Set<ExtensionPackageCapability>? = null
        var tools: List<ExtensionPackageTool>? = null
        var commands: List<ExtensionPackageCommand>? = null
        var client: ExtensionPackageClient? = null
        var agent: ExtensionPackageAgent? = null
        var providers: PackageProviders? = null
        var workflows = emptyList<ExtensionPackageWorkflow>()
        while (reader.hasNext()) {
            val field = reader.nextName()
            require(fields.add(field)) { "Duplicate package field" }
            when (field) {
                "schemaVersion" -> schemaVersion = readSchemaVersion(reader)
                "id" -> id = reader.readString().also { validateSlug(it, "Invalid package ID") }
                "version" -> packageVersion = reader.readString().also {
                    require(isVersion(it)) { "Invalid package version" }
                }
                "title" -> title = reader.readString().validatedText(MAX_TITLE_BYTES, multiline = false, error = "Invalid package title")
                "description" -> description = reader.readString()
                    .validatedText(MAX_DESCRIPTION_BYTES, multiline = false, error = "Invalid package description")
                "host" -> {
                    val host = readHost(reader)
                    hostApi = host.first
                    minHostVersion = host.second
                }
                "capabilities" -> capabilities = readCapabilities(reader)
                "tools" -> tools = readTools(reader)
                "commands" -> commands = readCommands(reader)
                "client" -> client = readClient(reader)
                "agent" -> agent = readAgent(reader)
                "providers" -> providers = readProviders(reader)
                "workflows" -> workflows = readWorkflows(reader)
                else -> throw IllegalArgumentException("Unknown package field")
            }
        }
        reader.endObject()
        require(reader.peek() == JsonToken.END_DOCUMENT) { "Trailing package source content" }
        val compiledSchema = requireNotNull(schemaVersion) { "Missing package schemaVersion" }
        when (compiledSchema) {
            1 -> require(client == null) { "Unknown package field" }
            2, 3 -> require(client != null) { "Schema version $compiledSchema requires a client declaration" }
        }
        val hasPanel = client?.workbenchPanel != null
        require((!hasPanel || compiledSchema >= 3) && (compiledSchema != 3 || hasPanel)) {
            "A workbench panel requires schema version 3 or later"
        }
        require((agent == null || compiledSchema >= 5) && (compiledSchema != 5 || agent != null)) {
            "Agent contributions require schema version 5 or later"
        }
        val searchProviders = providers?.search.orEmpty()
        val modelProviders = providers?.model.orEmpty()
        require((searchProviders.isEmpty() || compiledSchema >= 6) && (compiledSchema != 6 || searchProviders.isNotEmpty())) {
            "Search provider declarations require schema version 6 or later"
        }
        require((modelProviders.isEmpty() || compiledSchema >= 7) && (compiledSchema != 7 || modelProviders.isNotEmpty())) {
            "Model provider declarations require schema version 7 or later"
        }
        val hasToolPolicy = !agent?.beforeTool.isNullOrEmpty()
        require((!hasToolPolicy || compiledSchema >= 8) && (compiledSchema != 8 || hasToolPolicy)) { "Before-tool policies require schema version 8 or later" }
        require((workflows.isEmpty() || compiledSchema >= 9) && (compiledSchema != 9 || workflows.isNotEmpty())) { "Workflows require schema version 9 or later" }
        val hasSubagents = !agent?.subagents.isNullOrEmpty()
        require((!hasSubagents || compiledSchema >= 10) && (compiledSchema != 10 || hasSubagents)) { "Subagent presets require schema version 10 or later" }
        val conversation = client?.conversation
        require((conversation == null || compiledSchema >= 11) && (compiledSchema != 11 || conversation != null)) {
            "Conversation views require schema version 11 or later"
        }
        val hasTurnPolicy = agent?.beforeTurn != null
        require(hasTurnPolicy == (compiledSchema == 12)) { "Before-turn policy requires schema version 12" }
        require(fields.size == 9 + listOfNotNull(client, agent, providers, workflows.takeIf { it.isNotEmpty() }).size) { "Missing package field" }
        val compiledTools = Collections.unmodifiableList(requireNotNull(tools))
        val compiledCommands = Collections.unmodifiableList(requireNotNull(commands))
        val required = requireNotNull(capabilities)
        val hasScript = compiledTools.any { it.script != null }
        require((!hasScript || compiledSchema >= 4) && (compiledSchema != 4 || hasScript)) {
            "Script tools require schema version 4 or later"
        }
        require(required.contains(ExtensionPackageCapability.TOOL_SCRIPT_EXECUTE) == hasScript) {
            "tool.script.execute must exactly match the script declaration"
        }
        require(required.contains(ExtensionPackageCapability.SEARCH_PROVIDER_PUBLISH) == searchProviders.isNotEmpty()) {
            "provider.search.publish must exactly match the search provider declaration"
        }
        require(required.contains(ExtensionPackageCapability.MODEL_PROVIDER_PUBLISH) == modelProviders.isNotEmpty()) {
            "provider.model.publish must exactly match the model provider declaration"
        }
        if (compiledSchema == 1) {
            require(compiledTools.isNotEmpty() || compiledCommands.isNotEmpty()) {
                "Package must declare at least one contribution"
            }
            require(ExtensionPackageCapability.UI_SETTINGS_PUBLISH !in required &&
                ExtensionPackageCapability.UI_SESSION_ACTION_PUBLISH !in required) {
                "Unknown package capability"
            }
        }
        require(required.contains(ExtensionPackageCapability.TOOL_PUBLISH) == compiledTools.isNotEmpty()) {
            "tool.publish must exactly match the tools declaration"
        }
        require(required.contains(ExtensionPackageCapability.COMMAND_PUBLISH) == compiledCommands.isNotEmpty()) {
            "command.publish must exactly match the commands declaration"
        }
        require(required.contains(ExtensionPackageCapability.UI_WORKBENCH_PANEL_PUBLISH) == (client?.workbenchPanel != null)) {
            "ui.workbench-panel.publish must exactly match the workbenchPanel declaration"
        }
        require(required.contains(ExtensionPackageCapability.UI_CONVERSATION_PUBLISH) == (conversation != null)) {
            "ui.conversation.publish must exactly match the conversation declaration"
        }
        require(conversation?.toolResults.orEmpty().all { view -> compiledTools.any { it.name == view.selector } }) {
            "A tool result card must name a tool declared by its own package"
        }
        require(required.contains(ExtensionPackageCapability.AGENT_SKILL_PUBLISH) == !agent?.skills.isNullOrEmpty()) {
            "agent.skill.publish must exactly match the skills declaration"
        }
        require(required.contains(ExtensionPackageCapability.AGENT_CONTEXT_PUBLISH) == (agent?.context != null)) {
            "agent.context.publish must exactly match the context declaration"
        }
        require(required.contains(ExtensionPackageCapability.AGENT_TOOL_POLICY_PUBLISH) == hasToolPolicy) {
            "agent.tool-policy.publish must exactly match the beforeTool declaration"
        }
        require(required.contains(ExtensionPackageCapability.AGENT_TURN_POLICY_PUBLISH) == hasTurnPolicy) {
            "agent.turn-policy.publish must exactly match the beforeTurn declaration"
        }
        require(required.contains(ExtensionPackageCapability.WORKFLOW_PUBLISH) == workflows.isNotEmpty()) {
            "workflow.publish must exactly match the workflow declaration"
        }
        require(required.contains(ExtensionPackageCapability.AGENT_SUBAGENT_PRESET_PUBLISH) == hasSubagents) {
            "agent.subagent-preset.publish must exactly match the subagent declaration"
        }
        if (client != null) {
            require(required.contains(ExtensionPackageCapability.UI_SETTINGS_PUBLISH) == (client.settingsPage != null)) {
                "ui.settings.publish must exactly match the settingsPage declaration"
            }
            require(required.contains(ExtensionPackageCapability.UI_SESSION_ACTION_PUBLISH) == client.sessionActions.isNotEmpty()) {
                "ui.session-action.publish must exactly match the sessionActions declaration"
            }
            require(client.sessionActions.all { action -> compiledCommands.any { it.name == action.command } }) {
                "Session action must name a primary command from this package"
            }
        }
        return ExtensionPackageManifest(
            schemaVersion = compiledSchema,
            id = requireNotNull(id),
            version = requireNotNull(packageVersion),
            title = requireNotNull(title),
            description = requireNotNull(description),
            hostApi = requireNotNull(hostApi),
            minHostVersion = requireNotNull(minHostVersion),
            capabilities = required,
            tools = compiledTools,
            commands = compiledCommands,
            sha256 = sha256(bytes),
            sizeBytes = bytes.size,
            client = client,
            agent = agent,
            searchProviders = searchProviders,
            modelProviders = modelProviders,
            workflows = workflows,
        )
    }

    private fun readSchemaVersion(reader: JsonReader): Int {
        require(reader.peek() == JsonToken.NUMBER) { "Unsupported package schemaVersion" }
        return when (reader.nextString()) {
            "1" -> 1
            "2" -> 2
            "3" -> 3
            "4" -> 4
            "5" -> 5
            "6" -> 6
            "7" -> 7
            "8" -> 8
            "9" -> 9
            "10" -> 10
            "11" -> 11
            "12" -> 12
            else -> throw IllegalArgumentException("Unsupported package schema version")
        }
    }

    private fun readWorkflows(reader: JsonReader): List<ExtensionPackageWorkflow> {
        val workflows = mutableListOf<ExtensionPackageWorkflow>()
        val names = mutableSetOf<String>()
        reader.beginArray()
        while (reader.hasNext()) {
            require(workflows.size < 4) { "Too many workflows" }
            var name: String? = null
            var title: String? = null
            var steps: List<ExtensionPackageWorkflowStep>? = null
            reader.readClientObject("workflow") { field ->
                when (field) {
                    "name" -> name = reader.readString().also {
                        validateSlug(it, "Invalid workflow name")
                        require(names.add(it)) { "Duplicate workflow name" }
                    }
                    "title" -> title = reader.readClientText(MAX_TITLE_BYTES, "workflow title")
                    "steps" -> steps = readWorkflowSteps(reader)
                    else -> throw IllegalArgumentException("Unknown workflow field")
                }
            }
            workflows += ExtensionPackageWorkflow(requireNotNull(name), requireNotNull(title), requireNotNull(steps))
        }
        reader.endArray()
        require(workflows.isNotEmpty()) { "Workflows must not be empty" }
        return Collections.unmodifiableList(workflows)
    }

    private fun readWorkflowSteps(reader: JsonReader): List<ExtensionPackageWorkflowStep> {
        val steps = mutableListOf<ExtensionPackageWorkflowStep>()
        reader.beginArray()
        while (reader.hasNext()) {
            require(steps.size < 4) { "Too many workflow steps" }
            var title: String? = null
            var source: String? = null
            reader.readClientObject("workflow step") { field ->
                when (field) {
                    "title" -> title = reader.readClientText(MAX_TITLE_BYTES, "workflow step title")
                    "source" -> source = reader.readString().validatedText(PACKAGE_SCRIPT_MAX_SOURCE_BYTES, multiline = true,
                        error = "Invalid workflow step script")
                    else -> throw IllegalArgumentException("Unknown workflow step field")
                }
            }
            steps += ExtensionPackageWorkflowStep(requireNotNull(title), requireNotNull(source))
        }
        reader.endArray()
        require(steps.isNotEmpty()) { "Workflow requires at least one step" }
        return Collections.unmodifiableList(steps)
    }

    private data class PackageProviders(
        val search: List<ExtensionPackageSearchProvider>,
        val model: List<ExtensionPackageModelProvider>,
    )

    private fun readProviders(reader: JsonReader): PackageProviders {
        var search = emptyList<ExtensionPackageSearchProvider>()
        var model = emptyList<ExtensionPackageModelProvider>()
        reader.readClientObject("providers") { field ->
            when (field) {
                "search" -> search = readSearchProviders(reader)
                "model" -> model = readModelProviders(reader)
                else -> throw IllegalArgumentException("Unknown provider type")
            }
        }
        require(search.isNotEmpty() || model.isNotEmpty()) { "A provider package must declare a provider" }
        return PackageProviders(search, model)
    }

    private fun readSearchProviders(reader: JsonReader): List<ExtensionPackageSearchProvider> {
        val search = mutableListOf<ExtensionPackageSearchProvider>()
        val names = mutableSetOf<String>()
        reader.beginArray()
        while (reader.hasNext()) {
            require(search.size < 2) { "Too many search providers" }
            var name: String? = null
            var title: String? = null
            var script: String? = null
            reader.readClientObject("search provider") { property ->
                when (property) {
                    "name" -> name = reader.readString().also {
                        validateSlug(it, "Invalid search provider name")
                        require(names.add(it)) { "Duplicate search provider" }
                    }
                    "title" -> title = reader.readClientText(MAX_TITLE_BYTES, "search provider title")
                    "source" -> script = reader.readString().validatedText(PACKAGE_SCRIPT_MAX_SOURCE_BYTES, multiline = true,
                        error = "Invalid search provider script")
                    else -> throw IllegalArgumentException("Unknown search provider field")
                }
            }
            search += ExtensionPackageSearchProvider(requireNotNull(name), requireNotNull(title), requireNotNull(script))
        }
        reader.endArray()
        require(search.isNotEmpty()) { "Search provider declarations must not be empty" }
        return Collections.unmodifiableList(search)
    }

    private fun readModelProviders(reader: JsonReader): List<ExtensionPackageModelProvider> {
        val providers = mutableListOf<ExtensionPackageModelProvider>()
        val names = mutableSetOf<String>()
        reader.beginArray()
        while (reader.hasNext()) {
            require(providers.size < 2) { "Too many model providers" }
            var name: String? = null
            var title: String? = null
            var protocol: String? = null
            var endpoint: String? = null
            var models: List<String>? = null
            reader.readClientObject("model provider") { property ->
                when (property) {
                    "name" -> name = reader.readString().also {
                        validateSlug(it, "Invalid model provider name")
                        require(names.add(it)) { "Duplicate model provider" }
                    }
                    "title" -> title = reader.readClientText(MAX_TITLE_BYTES, "model provider title")
                    "protocol" -> protocol = reader.readString().also {
                        require(it != "OPENAI_IMAGES" && it in WorkspaceDeclarations.protocols(WorkspaceDeclarations.Kind.PROVIDER))
                    }
                    "endpoint" -> endpoint = reader.readString().also { require(WorkspaceDeclarations.validEndpoint(it)) }
                    "models" -> models = readModelIds(reader)
                    else -> throw IllegalArgumentException("Unknown model provider field")
                }
            }
            providers += ExtensionPackageModelProvider(requireNotNull(name), requireNotNull(title), requireNotNull(protocol),
                requireNotNull(endpoint), requireNotNull(models))
        }
        reader.endArray()
        require(providers.isNotEmpty()) { "Model provider declarations must not be empty" }
        return Collections.unmodifiableList(providers)
    }

    private fun readModelIds(reader: JsonReader): List<String> {
        val models = linkedSetOf<String>()
        reader.beginArray()
        while (reader.hasNext()) {
            val id = reader.readClientText(256, "model ID")
            require(id == id.trim() && models.size < 32 && models.add(id)) { "Invalid or duplicate model ID" }
        }
        reader.endArray()
        require(models.isNotEmpty()) { "Model provider requires at least one model" }
        return Collections.unmodifiableList(models.toList())
    }

    private fun readAgent(reader: JsonReader): ExtensionPackageAgent {
        var skills: List<ExtensionPackageSkill> = emptyList()
        var context: String? = null
        var beforeTool: List<ExtensionPackageToolPolicy> = emptyList()
        var subagents: List<ExtensionPackageSubagent> = emptyList()
        var beforeTurn: ExtensionPackageTurnPolicy? = null
        reader.readClientObject("agent") { field ->
            when (field) {
                "skills" -> skills = readSkills(reader)
                "context" -> context = reader.readString().validatedText(MAX_CONTEXT_BYTES, multiline = true, error = "Invalid agent context")
                "beforeTool" -> beforeTool = readToolPolicies(reader)
                "subagents" -> subagents = readSubagents(reader)
                "beforeTurn" -> beforeTurn = readTurnPolicy(reader)
                else -> throw IllegalArgumentException("Unknown package agent field")
            }
        }
        require(skills.isNotEmpty() || context != null || beforeTool.isNotEmpty() || subagents.isNotEmpty() || beforeTurn != null) {
            "Package agent must declare a contribution"
        }
        return ExtensionPackageAgent(skills, context, beforeTool, subagents, beforeTurn)
    }

    private fun readTurnPolicy(reader: JsonReader): ExtensionPackageTurnPolicy {
        var name: String? = null
        var script: String? = null
        reader.readClientObject("before-turn policy") { field ->
            when (field) {
                "name" -> name = reader.readString().also { validateSlug(it, "Invalid turn policy name") }
                "source" -> script = reader.readString().validatedText(
                    PACKAGE_SCRIPT_MAX_SOURCE_BYTES, multiline = true, error = "Invalid turn policy script",
                )
                else -> throw IllegalArgumentException("Unknown before-turn policy field")
            }
        }
        return ExtensionPackageTurnPolicy(requireNotNull(name), requireNotNull(script))
    }

    private fun readSubagents(reader: JsonReader): List<ExtensionPackageSubagent> {
        val presets = mutableListOf<ExtensionPackageSubagent>()
        val names = mutableSetOf<String>()
        reader.beginArray()
        while (reader.hasNext()) {
            require(presets.size < 4) { "Too many subagent presets" }
            var name: String? = null
            var title: String? = null
            var instructions: String? = null
            var tools: Set<String>? = null
            reader.readClientObject("subagent preset") { field ->
                when (field) {
                    "name" -> name = reader.readString().also {
                        validateSlug(it, "Invalid subagent preset name")
                        require(names.add(it)) { "Duplicate subagent preset name" }
                    }
                    "title" -> title = reader.readClientText(MAX_TITLE_BYTES, "subagent preset title")
                    "instructions" -> instructions = reader.readString().validatedText(MAX_TEXT_BYTES, multiline = true, error = "Invalid subagent instructions")
                    "tools" -> tools = readExactToolIds(reader)
                    else -> throw IllegalArgumentException("Unknown subagent preset field")
                }
            }
            presets += ExtensionPackageSubagent(requireNotNull(name), requireNotNull(title), requireNotNull(instructions), requireNotNull(tools))
        }
        reader.endArray()
        require(presets.isNotEmpty()) { "Subagent presets must not be empty" }
        return Collections.unmodifiableList(presets)
    }

    private fun readExactToolIds(reader: JsonReader): Set<String> {
        val ids = linkedSetOf<String>()
        reader.beginArray()
        while (reader.hasNext()) {
            val id = reader.readString()
            require(policyToolId.matches(id) && ids.size < 16 && ids.add(id)) { "Invalid or duplicate tool ID" }
        }
        reader.endArray()
        return Collections.unmodifiableSet(ids)
    }

    private fun readToolPolicies(reader: JsonReader): List<ExtensionPackageToolPolicy> {
        val policies = mutableListOf<ExtensionPackageToolPolicy>()
        val names = mutableSetOf<String>()
        reader.beginArray()
        while (reader.hasNext()) {
            require(policies.size < 4) { "Too many before-tool policies" }
            var name: String? = null
            var tools: Set<String>? = null
            var script: String? = null
            reader.readClientObject("before-tool policy") { property ->
                when (property) {
                    "name" -> name = reader.readString().also {
                        validateSlug(it, "Invalid tool policy name")
                        require(names.add(it)) { "Duplicate tool policy name" }
                    }
                    "tools" -> tools = readExactToolIds(reader).also { require(it.isNotEmpty()) { "Before-tool policy requires a tool ID" } }
                    "source" -> script = reader.readString().validatedText(PACKAGE_SCRIPT_MAX_SOURCE_BYTES, multiline = true,
                        error = "Invalid tool policy script")
                    else -> throw IllegalArgumentException("Unknown before-tool policy field")
                }
            }
            policies += ExtensionPackageToolPolicy(requireNotNull(name), requireNotNull(tools), requireNotNull(script))
        }
        reader.endArray()
        require(policies.isNotEmpty()) { "Before-tool policies must not be empty" }
        return Collections.unmodifiableList(policies)
    }

    private fun readSkills(reader: JsonReader): List<ExtensionPackageSkill> {
        val skills = mutableListOf<ExtensionPackageSkill>()
        val names = mutableSetOf<String>()
        reader.beginArray()
        while (reader.hasNext()) {
            require(skills.size < MAX_SKILLS) { "Too many package skills" }
            var name: String? = null
            var description: String? = null
            var body: String? = null
            reader.readClientObject("skill") { field ->
                when (field) {
                    "name" -> name = reader.readString().also {
                        validateSlug(it, "Invalid skill name")
                        require(names.add(it)) { "Duplicate skill name" }
                    }
                    "description" -> description = reader.readClientText(MAX_DESCRIPTION_BYTES, "skill description")
                    "body" -> body = reader.readString().validatedText(MAX_TEXT_BYTES, multiline = true, error = "Invalid skill body")
                    else -> throw IllegalArgumentException("Unknown package skill field")
                }
            }
            skills += ExtensionPackageSkill(requireNotNull(name), requireNotNull(description), requireNotNull(body))
        }
        reader.endArray()
        require(skills.isNotEmpty()) { "Package skills must not be empty" }
        return Collections.unmodifiableList(skills)
    }

    private fun readClient(reader: JsonReader): ExtensionPackageClient {
        var settingsPage: ExtensionPackageSettingsPage? = null
        var sessionActions: List<ExtensionPackageSessionAction> = emptyList()
        var workbenchPanel: ExtensionPackageSettingsPage? = null
        var conversation: ExtensionPackageConversation? = null
        reader.readClientObject("client") { field ->
            when (field) {
                "settingsPage" -> settingsPage = readSettingsPage(reader)
                "sessionActions" -> sessionActions = readSessionActions(reader)
                "workbenchPanel" -> workbenchPanel = readSettingsPage(reader, "workbench panel")
                "conversation" -> conversation = readConversation(reader)
                else -> throw IllegalArgumentException("Unknown package client field")
            }
        }
        require(settingsPage != null || sessionActions.isNotEmpty() || workbenchPanel != null || conversation != null) {
            "Package client must declare a contribution"
        }
        return ExtensionPackageClient(settingsPage, sessionActions, workbenchPanel, conversation)
    }

    private fun readConversation(reader: JsonReader): ExtensionPackageConversation {
        var messages = emptyList<ExtensionPackageView<ExtensionPackageMessageEvent>>()
        var toolResults = emptyList<ExtensionPackageView<String>>()
        reader.readClientObject("conversation") { field ->
            when (field) {
                "messages" -> messages = readViews(reader, "event", 2) { wire ->
                    requireNotNull(ExtensionPackageMessageEvent.entries.find { it.wireName == wire }) { "Unknown conversation message event" }
                }
                "toolResults" -> toolResults = readViews(reader, "tool", MAX_TOOLS) { name ->
                    name.also { validateSlug(it, "Invalid tool-result name") }
                }
                else -> throw IllegalArgumentException("Unknown conversation field")
            }
        }
        require(messages.isNotEmpty() || toolResults.isNotEmpty()) { "Conversation extension cannot be empty" }
        return ExtensionPackageConversation(messages, toolResults)
    }

    private fun <T : Any> readViews(reader: JsonReader, selectorField: String, limit: Int, selector: (String) -> T): List<ExtensionPackageView<T>> {
        val views = mutableListOf<ExtensionPackageView<T>>()
        val selectors = mutableSetOf<T>()
        reader.beginArray()
        while (reader.hasNext()) {
            require(views.size < limit) { "Too many conversation views" }
            var match: T? = null
            var title: String? = null
            var lines: List<String>? = null
            reader.readClientObject("conversation view") { field ->
                when (field) {
                    selectorField -> match = selector(reader.readString()).also { require(selectors.add(it)) { "Duplicate conversation selector" } }
                    "title" -> title = reader.readClientText(80, "view title")
                    "lines" -> {
                        val values = mutableListOf<String>()
                        reader.beginArray()
                        while (reader.hasNext()) {
                            require(values.size < 4) { "Too many conversation view lines" }
                            values += reader.readClientText(160, "view line")
                        }
                        reader.endArray()
                        require(values.isNotEmpty()) { "Conversation view requires at least one line" }
                        lines = Collections.unmodifiableList(values)
                    }
                    else -> throw IllegalArgumentException("Unknown conversation view field")
                }
            }
            views += ExtensionPackageView(requireNotNull(match), requireNotNull(title), requireNotNull(lines))
        }
        reader.endArray()
        require(views.isNotEmpty()) { "Conversation views must not be empty" }
        return Collections.unmodifiableList(views)
    }

    private fun readSettingsPage(reader: JsonReader, name: String = "settings page"): ExtensionPackageSettingsPage {
        var title: String? = null
        var description: String? = null
        var rows: List<ExtensionPackageSettingsRow> = emptyList()
        reader.readClientObject(name) { field ->
            when (field) {
                "title" -> title = reader.readClientText(MAX_TITLE_BYTES, "$name title")
                "description" -> description = reader.readClientText(MAX_DESCRIPTION_BYTES, "$name description")
                "rows" -> {
                    require(reader.peek() == JsonToken.BEGIN_ARRAY) { "Package settings rows must be an array" }
                    reader.beginArray()
                    val values = mutableListOf<ExtensionPackageSettingsRow>()
                    while (reader.hasNext()) {
                        require(values.size < MAX_SETTINGS_ROWS) { "Too many package settings rows" }
                        values += readSettingsRow(reader)
                    }
                    reader.endArray()
                    rows = Collections.unmodifiableList(values)
                }
                else -> throw IllegalArgumentException("Unknown package $name field")
            }
        }
        return ExtensionPackageSettingsPage(
            title = requireNotNull(title) { "Missing package $name title" },
            description = requireNotNull(description) { "Missing package $name description" },
            rows = rows,
        )
    }

    private fun readSettingsRow(reader: JsonReader): ExtensionPackageSettingsRow {
        var label: String? = null
        var value: String? = null
        reader.readClientObject("settings row") { field ->
            when (field) {
                "label" -> label = reader.readClientText(MAX_TITLE_BYTES, "settings row label")
                "value" -> value = reader.readClientText(MAX_DESCRIPTION_BYTES, "settings row value")
                else -> throw IllegalArgumentException("Unknown package settings row field")
            }
        }
        return ExtensionPackageSettingsRow(
            label = requireNotNull(label) { "Missing package settings row label" },
            value = requireNotNull(value) { "Missing package settings row value" },
        )
    }

    private fun readSessionActions(reader: JsonReader): List<ExtensionPackageSessionAction> {
        require(reader.peek() == JsonToken.BEGIN_ARRAY) { "Package session actions must be an array" }
        reader.beginArray()
        val actions = mutableListOf<ExtensionPackageSessionAction>()
        val ids = mutableSetOf<String>()
        while (reader.hasNext()) {
            require(actions.size < MAX_SESSION_ACTIONS) { "Too many package session actions" }
            val action = readSessionAction(reader)
            require(ids.add(action.id)) { "Duplicate package session action id" }
            actions += action
        }
        reader.endArray()
        require(actions.isNotEmpty()) { "Package session actions must not be empty" }
        return Collections.unmodifiableList(actions)
    }

    private fun readSessionAction(reader: JsonReader): ExtensionPackageSessionAction {
        var id: String? = null
        var label: String? = null
        var command: String? = null
        reader.readClientObject("session action") { field ->
            when (field) {
                "id" -> id = reader.readString().also { validateSlug(it, "Invalid session action id") }
                "label" -> label = reader.readClientText(MAX_TITLE_BYTES, "session action label")
                "command" -> command = reader.readString().also { validateSlug(it, "Invalid session action command") }
                else -> throw IllegalArgumentException("Unknown package session action field")
            }
        }
        return ExtensionPackageSessionAction(
            id = requireNotNull(id) { "Missing package session action id" },
            label = requireNotNull(label) { "Missing package session action label" },
            command = requireNotNull(command) { "Missing package session action command" },
        )
    }

    private inline fun JsonReader.readClientObject(name: String, readField: (String) -> Unit) {
        require(peek() == JsonToken.BEGIN_OBJECT) { "Package $name must be an object" }
        beginObject()
        val fields = mutableSetOf<String>()
        while (hasNext()) {
            val field = nextName()
            require(fields.add(field)) { "Duplicate package $name field" }
            readField(field)
        }
        endObject()
    }

    private fun JsonReader.readClientText(maxBytes: Int, name: String): String =
        readString().validatedText(maxBytes, multiline = false, error = "Invalid package $name")

    private fun readHost(reader: JsonReader): Pair<Int, String> {
        require(reader.peek() == JsonToken.BEGIN_OBJECT) { "Package host must be an object" }
        reader.beginObject()
        val fields = mutableSetOf<String>()
        var api: Int? = null
        var minVersion: String? = null
        while (reader.hasNext()) {
            val field = reader.nextName()
            require(fields.add(field)) { "Duplicate package host field" }
            when (field) {
                "api" -> {
                    require(reader.peek() == JsonToken.NUMBER) { "Invalid package host API" }
                    api = reader.nextString().toIntOrNull()
                    require(api != null) { "Invalid package host API" }
                }
                "minVersion" -> minVersion = reader.readString().also {
                    require(isVersion(it)) { "Invalid package host version" }
                }
                else -> throw IllegalArgumentException("Unknown package host field")
            }
        }
        reader.endObject()
        require(fields.size == 2) { "Missing package host field" }
        return requireNotNull(api) to requireNotNull(minVersion)
    }

    private fun readCapabilities(reader: JsonReader): Set<ExtensionPackageCapability> {
        require(reader.peek() == JsonToken.BEGIN_ARRAY) { "Package capabilities must be an array" }
        reader.beginArray()
        val values = linkedSetOf<ExtensionPackageCapability>()
        while (reader.hasNext()) {
            val wire = reader.readString()
            val capability = ExtensionPackageCapability.entries.find { it.wireName == wire }
                ?: throw IllegalArgumentException("Unknown package capability")
            require(values.add(capability)) { "Duplicate package capability" }
        }
        reader.endArray()
        return Collections.unmodifiableSet(values)
    }

    private fun readTools(reader: JsonReader): List<ExtensionPackageTool> {
        require(reader.peek() == JsonToken.BEGIN_ARRAY) { "Package tools must be an array" }
        reader.beginArray()
        val tools = mutableListOf<ExtensionPackageTool>()
        val names = mutableSetOf<String>()
        while (reader.hasNext()) {
            require(tools.size < MAX_TOOLS) { "Too many package tools" }
            val tool = readTool(reader)
            require(names.add(tool.name)) { "Duplicate package tool name" }
            tools += tool
        }
        reader.endArray()
        return tools
    }

    private fun readTool(reader: JsonReader): ExtensionPackageTool {
        require(reader.peek() == JsonToken.BEGIN_OBJECT) { "Package tool must be an object" }
        reader.beginObject()
        val fields = mutableSetOf<String>()
        var name: String? = null
        var description: String? = null
        var text: String? = null
        var kind: String? = null
        var operation: String? = null
        var script: String? = null
        while (reader.hasNext()) {
            val field = reader.nextName()
            require(fields.add(field)) { "Duplicate package tool field" }
            when (field) {
                "name" -> name = reader.readString().also { validateSlug(it, "Invalid tool name") }
                "description" -> description = reader.readString()
                    .validatedText(MAX_DESCRIPTION_BYTES, multiline = false, error = "Invalid tool description")
                "text" -> text = reader.readString()
                    .validatedText(MAX_TEXT_BYTES, multiline = true, error = "Invalid tool text")
                "kind" -> kind = reader.readString()
                "operation" -> operation = reader.readString()
                "source" -> script = reader.readString()
                    .validatedText(PACKAGE_SCRIPT_MAX_SOURCE_BYTES, multiline = true, error = "Invalid package script source")
                else -> throw IllegalArgumentException("Unknown package tool field")
            }
        }
        reader.endObject()
        return when (requireNotNull(kind) { "Missing package tool kind" }) {
            "static_text" -> {
                require(operation == null && script == null) { "static_text accepts only text" }
                ExtensionPackageTool(
                    name = requireNotNull(name) { "Missing package tool field" },
                    description = requireNotNull(description) { "Missing package tool field" },
                    text = requireNotNull(text) { "static_text requires text" },
                )
            }
            "text_transform" -> {
                require(text == null && script == null) { "text_transform accepts only operation" }
                val action = ExtensionPackageTransform.find(operation)
                    ?: throw IllegalArgumentException("Unknown text transform operation")
                ExtensionPackageTool(
                    name = requireNotNull(name) { "Missing package tool field" },
                    description = requireNotNull(description) { "Missing package tool field" },
                    text = "",
                    operation = action,
                )
            }
            "text_script" -> {
                require(text == null && operation == null) { "text_script accepts only source" }
                ExtensionPackageTool(
                    name = requireNotNull(name) { "Missing package tool field" },
                    description = requireNotNull(description) { "Missing package tool field" },
                    text = "",
                    script = requireNotNull(script) { "text_script requires source" },
                )
            }
            else -> throw IllegalArgumentException("Unknown package tool kind")
        }
    }

    private fun readCommands(reader: JsonReader): List<ExtensionPackageCommand> {
        require(reader.peek() == JsonToken.BEGIN_ARRAY) { "Package commands must be an array" }
        reader.beginArray()
        val commands = mutableListOf<ExtensionPackageCommand>()
        val names = mutableSetOf<String>()
        while (reader.hasNext()) {
            require(commands.size < MAX_COMMANDS) { "Too many package commands" }
            val command = readCommand(reader)
            for (name in command.aliases + command.name) {
                require(names.add(name)) { "Duplicate package command name or alias" }
            }
            commands += command
        }
        reader.endArray()
        return commands
    }

    private fun readCommand(reader: JsonReader): ExtensionPackageCommand {
        require(reader.peek() == JsonToken.BEGIN_OBJECT) { "Package command must be an object" }
        reader.beginObject()
        val fields = mutableSetOf<String>()
        var name: String? = null
        var aliases: Set<String> = emptySet()
        var description: String? = null
        var text: String? = null
        while (reader.hasNext()) {
            val field = reader.nextName()
            require(fields.add(field)) { "Duplicate package command field" }
            when (field) {
                "name" -> name = reader.readString().also { validateSlug(it, "Invalid command name") }
                "aliases" -> aliases = readAliases(reader)
                "description" -> description = reader.readString()
                    .validatedText(MAX_DESCRIPTION_BYTES, multiline = false, error = "Invalid command description")
                "text" -> text = reader.readString()
                    .validatedText(MAX_TEXT_BYTES, multiline = true, error = "Invalid command text")
                else -> throw IllegalArgumentException("Unknown package command field")
            }
        }
        reader.endObject()
        require(fields.size == 4) { "Missing package command field" }
        val commandName = requireNotNull(name) { "Missing package command field" }
        require(commandName !in aliases) { "Command alias duplicates its name" }
        return ExtensionPackageCommand(
            name = commandName,
            aliases = aliases,
            description = requireNotNull(description) { "Missing package command field" },
            text = requireNotNull(text) { "Missing package command field" },
        )
    }

    private fun readAliases(reader: JsonReader): Set<String> {
        require(reader.peek() == JsonToken.BEGIN_ARRAY) { "Package command aliases must be an array" }
        reader.beginArray()
        val aliases = linkedSetOf<String>()
        while (reader.hasNext()) {
            require(aliases.size < 4) { "Too many command aliases" }
            val alias = reader.readString().also { validateSlug(it, "Invalid command alias") }
            require(aliases.add(alias)) { "Duplicate command alias" }
        }
        reader.endArray()
        return Collections.unmodifiableSet(aliases)
    }

    private fun JsonReader.readString(): String {
        require(peek() == JsonToken.STRING) { "Package field must be a JSON string" }
        return nextString()
    }

    private fun validateSlug(value: String, error: String) = require(slug.matches(value)) { error }

    private fun String.validatedText(maxBytes: Int, multiline: Boolean, error: String): String {
        require(isNotBlank() && length <= maxBytes) { error }
        var offset = 0
        while (offset < length) {
            val codePoint = codePointAt(offset)
            when (Character.getType(codePoint)) {
                Character.CONTROL.toInt() -> {
                    require(multiline && (codePoint == '\n'.code || codePoint == '\t'.code)) { error }
                }
                Character.FORMAT.toInt(), Character.SURROGATE.toInt(),
                Character.LINE_SEPARATOR.toInt(), Character.PARAGRAPH_SEPARATOR.toInt() -> throw IllegalArgumentException(error)
            }
            offset += Character.charCount(codePoint)
        }
        require(toByteArray(Charsets.UTF_8).size <= maxBytes) { error }
        return this
    }

    private fun sha256(bytes: ByteArray): String = buildString(64) {
        for (byte in MessageDigest.getInstance("SHA-256").digest(bytes)) {
            val value = byte.toInt() and 0xff
            append(HEX[value ushr 4]).append(HEX[value and 0xf])
        }
    }

    private object ExtensionPackageTransform {
        fun find(wireName: String?): AuthoredTextOperation? =
            AuthoredTextOperation.entries.find { it.wireName == wireName }
    }
}
