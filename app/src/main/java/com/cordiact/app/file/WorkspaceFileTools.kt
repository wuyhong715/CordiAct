package com.cordiact.app.file

import com.cordiact.core.file.WorkspaceFileService
import com.cordiact.core.file.WorkspaceFileException
import com.cordiact.core.tool.GrantedScopeAccess
import com.cordiact.core.tool.InMemoryToolDefinitionRegistry
import com.cordiact.core.tool.ToolArguments
import com.cordiact.core.tool.ToolDefinition
import com.cordiact.core.tool.ToolDescriptor
import com.cordiact.core.tool.ToolInputSchema
import com.cordiact.core.tool.ToolInvocationContext
import com.cordiact.core.tool.ToolManagerException
import com.cordiact.core.tool.ToolParameter
import com.cordiact.core.tool.ToolResult
import com.cordiact.core.tool.ToolValue
import com.cordiact.core.tool.ToolValueType

private const val FILE_MANAGER_PLUGIN_ID = "core.file-manager"
private const val MAX_TEXT_BYTES = 1024 * 1024

fun registerWorkspaceFileTools(registry: InMemoryToolDefinitionRegistry, files: WorkspaceFileService) {
    registry.register(
        ToolDefinition(
            descriptor = ToolDescriptor(
                id = "workspace.files.list",
                name = "List workspace files",
                description = "List entries in a directory inside the current workspace.",
                inputSchema = ToolInputSchema(
                    listOf(
                        ToolParameter("directory", ToolValueType.STRING),
                        ToolParameter("cursor", ToolValueType.STRING),
                        // Only useful when the turn referenced another Workspace; omitted means
                        // the session's own Workspace.
                        ToolParameter("workspace", ToolValueType.STRING),
                    ),
                    allowAdditional = false,
                ),
                ownerPluginId = FILE_MANAGER_PLUGIN_ID,
            ),
            executor = { context, arguments ->
                val directory = arguments.string("directory", required = false).orEmpty()
                val cursor = arguments.string("cursor", required = false)
                val target = arguments.string("workspace", required = false) ?: context.workspaceId
                requireReadable(context, target, directory)
                val page = files.list(target, directory, cursor = cursor, limit = 100)
                toolResult(
                    "entries" to ToolValue.ArrayValue(page.entries.map { entry ->
                        ToolValue.ObjectValue(
                            mapOf(
                                "name" to ToolValue.StringValue(entry.name),
                                "directory" to ToolValue.BooleanValue(entry.directory),
                                "sizeBytes" to ToolValue.NumberValue(entry.sizeBytes.toDouble()),
                            ),
                        )
                    }),
                    "nextCursor" to (page.nextCursor?.let(ToolValue::StringValue) ?: ToolValue.NullValue),
                )
            },
        ),
    )

    registry.register(
        ToolDefinition(
            descriptor = ToolDescriptor(
                id = "workspace.files.read_text",
                name = "Read workspace text file",
                description = "Read a bounded UTF-8 text range from a regular file in the current workspace.",
                inputSchema = ToolInputSchema(
                    listOf(
                        ToolParameter("path", ToolValueType.STRING, required = true),
                        ToolParameter("offset", ToolValueType.NUMBER),
                        ToolParameter("maxBytes", ToolValueType.NUMBER),
                        ToolParameter("workspace", ToolValueType.STRING),
                    ),
                ),
                ownerPluginId = FILE_MANAGER_PLUGIN_ID,
            ),
            executor = { context, arguments ->
                val path = arguments.string("path", required = true)!!
                val offset = arguments.long("offset", default = 0L)
                val maxBytes = arguments.int("maxBytes", default = MAX_TEXT_BYTES)
                if (offset < 0 || maxBytes !in 1..MAX_TEXT_BYTES) throw invalidArgument()
                val target = arguments.string("workspace", required = false) ?: context.workspaceId
                requireReadable(context, target, path)
                val content = files.read(target, path, offset, maxBytes)
                val text = try {
                    Charsets.UTF_8.newDecoder()
                        .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                        .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
                        .decode(java.nio.ByteBuffer.wrap(content.bytes))
                        .toString()
                } catch (_: java.nio.charset.CharacterCodingException) {
                    throw WorkspaceFileException(WorkspaceFileException.Code.INVALID_TEXT_ENCODING, "File is not valid UTF-8 text")
                }
                toolResult(
                    "text" to ToolValue.StringValue(text),
                    "revision" to ToolValue.StringValue(content.revision),
                    "bytesRead" to ToolValue.NumberValue(content.bytes.size.toDouble()),
                )
            },
        ),
    )

    registry.register(
        ToolDefinition(
            descriptor = ToolDescriptor(
                id = "workspace.files.write_text",
                name = "Write workspace text file",
                description = "Create or replace a UTF-8 text file in the current workspace; replacement requires a matching revision.",
                inputSchema = ToolInputSchema(
                    listOf(
                        ToolParameter("path", ToolValueType.STRING, required = true),
                        ToolParameter("text", ToolValueType.STRING, required = true),
                        ToolParameter("expectedRevision", ToolValueType.STRING),
                    ),
                ),
                ownerPluginId = FILE_MANAGER_PLUGIN_ID,
            ),
            executor = { context, arguments ->
                val path = arguments.string("path", required = true)!!
                val text = arguments.string("text", required = true)!!
                val expectedRevision = arguments.string("expectedRevision", required = false)
                // Writing is never cross-Workspace. A read grant must not silently become a
                // write grant: `@` authorizes inspection, and letting it mutate another
                // Workspace would be a permission the user never granted.
                val mutation = files.write(context.workspaceId, path, text.toByteArray(Charsets.UTF_8), expectedRevision)
                toolResult("revision" to (mutation.revision?.let(ToolValue::StringValue) ?: ToolValue.NullValue))
            },
        ),
    )
}

/**
 * Enforces the turn's read grant for [targetWorkspaceId].
 *
 * Called on every list and read, not once per turn: the grant is an authorization that travels
 * with the tool call, and checking it here is what makes `@` a range rather than a preload.
 */
private fun requireReadable(
    context: ToolInvocationContext,
    targetWorkspaceId: String,
    path: String,
) {
    when (val decision = GrantedScopeAccess.check(context, targetWorkspaceId, path)) {
        is GrantedScopeAccess.Decision.Allowed -> Unit
        is GrantedScopeAccess.Decision.Denied -> throw ToolManagerException(
            ToolManagerException.Code.CAPABILITY_DENIED,
            decision.reason,
        )
    }
}

private fun ToolArguments.string(name: String, required: Boolean): String? = when (val value = values[name]) {
    null, ToolValue.NullValue -> if (required) throw invalidArgument() else null
    is ToolValue.StringValue -> value.value
    else -> throw invalidArgument()
}

private fun ToolArguments.long(name: String, default: Long): Long = when (val value = values[name]) {
    null -> default
    is ToolValue.NumberValue -> value.value.takeIf { it.isFinite() && it >= 0.0 && it <= Long.MAX_VALUE.toDouble() }
        ?.toLong() ?: throw invalidArgument()
    else -> throw invalidArgument()
}

private fun ToolArguments.int(name: String, default: Int): Int = when (val value = values[name]) {
    null -> default
    is ToolValue.NumberValue -> value.value.takeIf {
        it.isFinite() && it % 1.0 == 0.0 && it >= Int.MIN_VALUE.toDouble() && it <= Int.MAX_VALUE.toDouble()
    }?.toInt() ?: throw invalidArgument()
    else -> throw invalidArgument()
}

private fun invalidArgument() = ToolManagerException(ToolManagerException.Code.INVALID_ARGUMENT, "Tool argument has the wrong type")

private fun toolResult(vararg entries: Pair<String, ToolValue>) = ToolResult(entries.toMap())
