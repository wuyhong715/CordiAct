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

/** Compiled data only. A definition contains no executable code or capability declarations. */
data class AuthoredPluginDefinition(
    val id: String,
    val title: String,
    val tools: List<AuthoredToolDefinition>,
    val sha256: String,
    val schemaVersion: Int = 1,
)

/** [operation] is null for static tools; transforms carry no source [text]. */
data class AuthoredToolDefinition(
    val name: String,
    val description: String,
    val text: String,
    val operation: AuthoredTextOperation? = null,
) {
    init { require(operation == null || text.isEmpty()) }
}

enum class AuthoredTextOperation(val wireName: String) {
    TRIM("trim"), UPPERCASE("uppercase"), LOWERCASE("lowercase"),
}

/** Reads the bounded, strict v1 and v2 source formats for Agent-authored text tools. */
object AuthoredPluginSource {
    const val MAX_SOURCE_BYTES = 16 * 1024
    private const val MAX_TITLE_BYTES = 120
    private const val MAX_DESCRIPTION_BYTES = 480
    internal const val MAX_STATIC_TEXT_BYTES = 4 * 1024
    private const val MAX_TOOLS = 4
    private const val HEX = "0123456789abcdef"
    private val slug = Regex("[a-z][a-z0-9-]{1,23}")

    /** Returns the only workspace-relative source path for this plugin ID. */
    fun path(pluginId: String): String {
        validateSlug(pluginId, "Invalid plugin ID")
        return "plugins/$pluginId.json"
    }

    /** Compiles one input snapshot and hashes those exact bytes for review and approval. */
    fun compile(pluginId: String, bytes: ByteArray): AuthoredPluginDefinition {
        validateSlug(pluginId, "Invalid plugin ID")
        require(bytes.size in 1..MAX_SOURCE_BYTES) { "Invalid plugin source size" }
        val sourceBytes = bytes.copyOf()
        val source = try {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(sourceBytes)).toString()
        } catch (_: CharacterCodingException) {
            throw IllegalArgumentException("Plugin source must be valid UTF-8")
        }
        // JsonReader ignores an initial BOM even in strict mode. Keep source unambiguous.
        require(!source.startsWith('\uFEFF')) { "Plugin source must not start with a BOM" }
        return try {
            JsonReader(StringReader(source)).use { reader ->
                reader.strictness = Strictness.STRICT
                readPlugin(reader, pluginId, sourceBytes)
            }
        } catch (_: IOException) {
            // Gson errors can include text from the input or a user-supplied key in their paths.
            throw IllegalArgumentException("Invalid plugin JSON")
        } catch (_: IllegalStateException) {
            throw IllegalArgumentException("Invalid plugin JSON")
        } catch (_: NumberFormatException) {
            throw IllegalArgumentException("Invalid plugin JSON")
        }
    }

    private fun readPlugin(reader: JsonReader, pluginId: String, bytes: ByteArray): AuthoredPluginDefinition {
        require(reader.peek() == JsonToken.BEGIN_OBJECT) { "Plugin source must be a JSON object" }
        reader.beginObject()
        val fields = mutableSetOf<String>()
        var schemaVersion: Int? = null
        var title: String? = null
        var tools: List<ToolFields>? = null
        while (reader.hasNext()) {
            val field = reader.nextName()
            require(fields.add(field)) { "Duplicate plugin field" }
            when (field) {
                "schemaVersion" -> schemaVersion = readVersion(reader)
                "id" -> require(reader.readString() == pluginId) { "Plugin ID does not match its source path" }
                "title" -> title = reader.readString().validatedText(MAX_TITLE_BYTES, multiline = false, error = "Invalid plugin title")
                "tools" -> tools = readTools(reader)
                else -> throw IllegalArgumentException("Unknown plugin field")
            }
        }
        reader.endObject()
        require(reader.peek() == JsonToken.END_DOCUMENT) { "Trailing plugin source content" }
        require(fields.size == 4) { "Missing plugin field" }
        val version = requireNotNull(schemaVersion)
        // JSON object order is immaterial: the version can follow the tools array.
        val compiledTools = Collections.unmodifiableList(requireNotNull(tools).map { it.compile(version) })
        val digest = buildString(64) {
            for (byte in MessageDigest.getInstance("SHA-256").digest(bytes)) {
                val n = byte.toInt() and 0xff
                append(HEX[n ushr 4]).append(HEX[n and 0xf])
            }
        }
        return AuthoredPluginDefinition(pluginId, requireNotNull(title), compiledTools, digest, version)
    }

    private fun readVersion(reader: JsonReader): Int {
        require(reader.peek() == JsonToken.NUMBER) { "Unsupported plugin schema version" }
        return when (reader.nextString()) {
            "1" -> 1
            "2" -> 2
            else -> throw IllegalArgumentException("Unsupported plugin schema version")
        }
    }

    private fun readTools(reader: JsonReader): List<ToolFields> {
        require(reader.peek() == JsonToken.BEGIN_ARRAY) { "Plugin tools must be a JSON array" }
        reader.beginArray()
        val tools = mutableListOf<ToolFields>()
        val names = mutableSetOf<String>()
        while (reader.hasNext()) {
            require(tools.size < MAX_TOOLS) { "Too many plugin tools" }
            val tool = readTool(reader)
            require(names.add(tool.name)) { "Duplicate plugin tool name" }
            tools += tool
        }
        reader.endArray()
        require(tools.isNotEmpty()) { "Plugin must define at least one tool" }
        return tools
    }

    private fun readTool(reader: JsonReader): ToolFields {
        require(reader.peek() == JsonToken.BEGIN_OBJECT) { "Plugin tool must be a JSON object" }
        reader.beginObject()
        val fields = mutableSetOf<String>()
        var name: String? = null
        var description: String? = null
        var text: String? = null
        var kind: String? = null
        var operation: String? = null
        while (reader.hasNext()) {
            val field = reader.nextName()
            require(fields.add(field)) { "Duplicate tool field" }
            when (field) {
                "name" -> name = reader.readString().also { validateSlug(it, "Invalid tool name") }
                "description" -> description = reader.readString()
                    .validatedText(MAX_DESCRIPTION_BYTES, multiline = false, error = "Invalid tool description")
                "text" -> text = reader.readString()
                    .validatedText(MAX_STATIC_TEXT_BYTES, multiline = true, error = "Invalid tool text")
                "kind" -> kind = reader.readString()
                "operation" -> operation = reader.readString()
                else -> throw IllegalArgumentException("Unknown tool field")
            }
        }
        reader.endObject()
        return ToolFields(
            requireNotNull(name) { "Missing tool field" }, requireNotNull(description) { "Missing tool field" },
            text, kind, operation,
        )
    }

    private data class ToolFields(
        val name: String,
        val description: String,
        val text: String?,
        val kind: String?,
        val operation: String?,
    ) {
        fun compile(version: Int): AuthoredToolDefinition {
            if (version == 1) {
                require(kind == null && operation == null) { "Unknown tool field" }
                return AuthoredToolDefinition(name, description, requireNotNull(text) { "Missing tool field" })
            }
            return when (kind) {
                "static_text" -> {
                    require(operation == null) { "static_text does not accept operation" }
                    AuthoredToolDefinition(name, description, requireNotNull(text) { "static_text requires text" })
                }
                "text_transform" -> {
                    require(text == null) { "text_transform does not accept source text" }
                    requireNotNull(operation) { "text_transform requires operation" }
                    val transform = AuthoredTextOperation.entries.find { it.wireName == operation }
                        ?: throw IllegalArgumentException("Unknown text transform operation")
                    AuthoredToolDefinition(name, description, text = "", operation = transform)
                }
                null -> throw IllegalArgumentException("Missing tool kind")
                else -> throw IllegalArgumentException("Unknown tool kind")
            }
        }
    }

    private fun JsonReader.readString(): String {
        require(peek() == JsonToken.STRING) { "Plugin field must be a JSON string" }
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
}
