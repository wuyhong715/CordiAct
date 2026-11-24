package com.cordiact.core.plugin

import com.google.gson.Strictness
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
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
}

/** One host-executed tool. [operation] is null for a fixed-text tool. */
data class ExtensionPackageTool(
    val name: String,
    val description: String,
    val text: String,
    val operation: AuthoredTextOperation? = null,
) {
    init { require(operation == null || text.isEmpty()) }
}

/** One host-executed slash command. It never calls a model or runs package code. */
data class ExtensionPackageCommand(
    val name: String,
    val aliases: Set<String>,
    val description: String,
    val text: String,
)

/** Optional client contributions described by schema version 2; no package code is run. */
data class ExtensionPackageClient(
    val settingsPage: ExtensionPackageSettingsPage? = null,
    val sessionActions: List<ExtensionPackageSessionAction> = emptyList(),
)

data class ExtensionPackageSettingsPage(
    val title: String,
    val description: String,
    val rows: List<ExtensionPackageSettingsRow> = emptyList(),
)

data class ExtensionPackageSettingsRow(val label: String, val value: String)

/** [command] is the primary name of a command from this package, without a leading slash. */
data class ExtensionPackageSessionAction(val id: String, val label: String, val command: String)

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
)


/** The one stable spec projection of a package manifest; hosts and the runtime must agree. */
fun extensionPackageSpec(manifest: ExtensionPackageManifest): PluginSpec = PluginSpec(
    id = "pkg.${manifest.id}",
    version = manifest.version,
    target = PluginTarget.ANDROID_NATIVE,
    requestedCapabilities = manifest.capabilities.mapTo(linkedSetOf()) { it.wireName },
    config = manifest.sha256,
)

/**
 * Strict, bounded reader for declarative extension packages. It compiles declarations only; it
 * never evaluates package code, reads workspace files, or performs network access.
 */
object ExtensionPackageSource {
    const val MAX_SOURCE_BYTES = 32 * 1024
    const val MAX_TOOLS = 4
    const val MAX_COMMANDS = 4
    private const val MAX_SETTINGS_ROWS = 4
    private const val MAX_SESSION_ACTIONS = 2
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
                else -> throw IllegalArgumentException("Unknown package field")
            }
        }
        reader.endObject()
        require(reader.peek() == JsonToken.END_DOCUMENT) { "Trailing package source content" }
        val compiledSchema = requireNotNull(schemaVersion) { "Missing package schemaVersion" }
        if (compiledSchema == 1) {
            require(client == null) { "Unknown package field" }
        } else {
            require(client != null) { "Schema version 2 requires a client declaration" }
        }
        require(fields.size == if (compiledSchema == 1) 9 else 10) { "Missing package field" }
        val compiledTools = Collections.unmodifiableList(requireNotNull(tools))
        val compiledCommands = Collections.unmodifiableList(requireNotNull(commands))
        val required = requireNotNull(capabilities)
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
        )
    }

    private fun readSchemaVersion(reader: JsonReader): Int {
        require(reader.peek() == JsonToken.NUMBER) { "Unsupported package schemaVersion" }
        return when (reader.nextString()) {
            "1" -> 1
            "2" -> 2
            else -> throw IllegalArgumentException("Unsupported package schema version")
        }
    }

    private fun readClient(reader: JsonReader): ExtensionPackageClient {
        var settingsPage: ExtensionPackageSettingsPage? = null
        var sessionActions: List<ExtensionPackageSessionAction> = emptyList()
        reader.readClientObject("client") { field ->
            when (field) {
                "settingsPage" -> settingsPage = readSettingsPage(reader)
                "sessionActions" -> sessionActions = readSessionActions(reader)
                else -> throw IllegalArgumentException("Unknown package client field")
            }
        }
        require(settingsPage != null || sessionActions.isNotEmpty()) {
            "Package client must declare settingsPage or sessionActions"
        }
        return ExtensionPackageClient(settingsPage, sessionActions)
    }

    private fun readSettingsPage(reader: JsonReader): ExtensionPackageSettingsPage {
        var title: String? = null
        var description: String? = null
        var rows: List<ExtensionPackageSettingsRow> = emptyList()
        reader.readClientObject("settings page") { field ->
            when (field) {
                "title" -> title = reader.readClientText(MAX_TITLE_BYTES, "settings page title")
                "description" -> description = reader.readClientText(MAX_DESCRIPTION_BYTES, "settings page description")
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
                else -> throw IllegalArgumentException("Unknown package settings page field")
            }
        }
        return ExtensionPackageSettingsPage(
            title = requireNotNull(title) { "Missing package settings page title" },
            description = requireNotNull(description) { "Missing package settings page description" },
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
                else -> throw IllegalArgumentException("Unknown package tool field")
            }
        }
        reader.endObject()
        return when (requireNotNull(kind) { "Missing package tool kind" }) {
            "static_text" -> {
                require(operation == null) { "static_text does not accept operation" }
                ExtensionPackageTool(
                    name = requireNotNull(name) { "Missing package tool field" },
                    description = requireNotNull(description) { "Missing package tool field" },
                    text = requireNotNull(text) { "static_text requires text" },
                )
            }
            "text_transform" -> {
                require(text == null) { "text_transform does not accept source text" }
                val action = ExtensionPackageTransform.find(operation)
                    ?: throw IllegalArgumentException("Unknown text transform operation")
                ExtensionPackageTool(
                    name = requireNotNull(name) { "Missing package tool field" },
                    description = requireNotNull(description) { "Missing package tool field" },
                    text = "",
                    operation = action,
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
