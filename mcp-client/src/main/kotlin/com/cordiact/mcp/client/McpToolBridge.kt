package com.cordiact.mcp.client

import com.cordiact.core.mcp.McpConnection
import com.cordiact.core.mcp.McpProtocolException
import com.cordiact.core.mcp.McpServerConfig
import com.cordiact.core.mcp.McpToolDescriptor
import com.cordiact.core.mcp.mcpToolId
import com.cordiact.core.tool.GlobalToolPublication
import com.cordiact.core.tool.ToolArguments
import com.cordiact.core.tool.ToolDefinition
import com.cordiact.core.tool.ToolDefinitionRegistry
import com.cordiact.core.tool.ToolDescriptor
import com.cordiact.core.tool.ToolExecutor
import com.cordiact.core.tool.ToolInputSchema
import com.cordiact.core.tool.ToolResult
import com.cordiact.core.tool.ToolValue

/**
 * Publishes one MCP server's tools into the ordinary tool registry.
 *
 * Each generation captures its own transport. A sync prepares the next set before retiring
 * the old one, and opens dispatch only after installing every definition and visibility gate.
 * Retained callbacks never follow a newer transport, even if the tool IDs or connection match.
 * The owner still closes transports; withdrawing a generation cannot undo dispatched work.
 */
class McpToolBridge(
    private val serverName: String,
    private val registry: ToolDefinitionRegistry,
    private val ownerPluginId: String,
    private val toolTimeoutMs: Long,
    private val approval: ((String, McpToolDescriptor) -> Boolean)? = null,
) {
    private class Generation(val connection: McpConnection, val tools: Map<String, String>)

    @Volatile private var active: Generation? = null
    // Retained on cleanup failure; the handle cannot withdraw a replacement by ID.
    private var publication: AutoCloseable? = null

    /** Active tool IDs mapped to their raw MCP names; pending cleanup is not publication. */
    fun publishedTools(): Map<String, String> = active?.tools?.toMap().orEmpty()

    /**
     * Replace the previous generation with whatever [live] reports.
     *
     * @throws McpProtocolException when the tool list cannot be read.
     * If publication is refused, no new tools are installed. A failed withdrawal keeps
     * its exact handle for a later disposal retry and blocks replacement.
     */
    @Synchronized
    fun sync(live: McpConnection): Map<String, String> = sync(live, live.listTools(toolTimeoutMs))

    @Synchronized
    fun sync(live: McpConnection, descriptors: List<McpToolDescriptor>): Map<String, String> {
        val byId = indexDescriptors(descriptors)
        val next = Generation(live, byId.mapValues { (_, raw) -> raw.name })
        val definitions = byId.map { (id, raw) ->
            GlobalToolPublication(buildDefinition(id, raw, next)) { ctx ->
                active === next && approval?.invoke(ctx.workspaceId, raw) != false && active === next
            }
        }

        // A listing or indexing failure leaves the currently working generation in place.
        dispose()
        publication = registry.publishGlobalBatch(definitions)
        active = next
        return next.tools.toMap()
    }

    /** Close admission before withdrawing registrations, even if a withdrawal then fails. */
    @Synchronized
    fun dispose() {
        active = null
        publication?.close()
        publication = null
    }

    private fun indexDescriptors(descriptors: List<McpToolDescriptor>): Map<String, McpToolDescriptor> {
        val byId = linkedMapOf<String, McpToolDescriptor>()
        for (tool in descriptors) {
            val id = mcpToolId(serverName, tool.name)
            if (byId.put(id, tool) != null) {
                throw McpProtocolException("server '$serverName' exposes two tools that map to '$id'")
            }
        }
        return byId
    }

    private fun buildDefinition(id: String, raw: McpToolDescriptor, owner: Generation) = ToolDefinition(
        descriptor = ToolDescriptor(
            id = id,
            name = raw.name,
            description = raw.description.ifBlank { "MCP tool '${raw.name}' on server '$serverName'" },
            inputSchema = ToolInputSchema(),
            ownerPluginId = ownerPluginId,
            defaultEnabled = true,
            timeoutMs = toolTimeoutMs,
            concurrencySafe = true,
            rawInputSchemaJson = raw.inputSchemaJson,
        ),
        executor = ToolExecutor { ctx, arguments ->
            when {
                active !== owner -> retiredResult()
                approval?.invoke(ctx.workspaceId, raw) == false -> errorResult("MCP tool authorization is no longer valid")
                else -> invoke(owner, raw.name, arguments)
            }
        },
    )

    private fun invoke(owner: Generation, rawName: String, arguments: ToolArguments): ToolResult {
        val json = argumentsToJson(arguments)
        // Approval or argument serialization can overlap retirement. Do not switch transports.
        if (active !== owner) return retiredResult()
        return try {
            val result = owner.connection.callTool(rawName, json, toolTimeoutMs)
            if (active !== owner) return retiredResult()
            if (result.isError) {
                errorResult(result.text.ifBlank { "MCP tool '$rawName' reported a failure" })
            } else {
                ToolResult(values = mapOf(
                    "result" to ToolValue.StringValue(result.text),
                    "untrusted" to ToolValue.BooleanValue(true),
                ))
            }
        } catch (failure: McpProtocolException) {
            if (active !== owner) retiredResult() else errorResult(failure.message ?: "MCP call to '$rawName' failed")
        }
    }

    private fun retiredResult() = errorResult("MCP tool registration is no longer active")

    private fun errorResult(message: String): ToolResult =
        ToolResult(values = mapOf(
            "ok" to ToolValue.BooleanValue(false),
            "error" to ToolValue.StringValue(message),
            "untrusted" to ToolValue.BooleanValue(true),
        ))

    private fun argumentsToJson(arguments: ToolArguments): String = buildString {
        append('{')
        arguments.values.entries.forEachIndexed { index, (key, value) ->
            if (index > 0) append(',')
            append(JsonWrite.string(key)).append(':').append(valueToJson(value))
        }
        append('}')
    }

    private fun valueToJson(value: ToolValue): String = when (value) {
        is ToolValue.StringValue -> JsonWrite.string(value.value)
        is ToolValue.NumberValue -> JsonWrite.number(value.value.toLong()).let {
            if (value.value == value.value.toLong().toDouble()) it else value.value.toString()
        }
        is ToolValue.BooleanValue -> JsonWrite.bool(value.value)
        is ToolValue.ArrayValue -> value.values.joinToString(",", "[", "]") { valueToJson(it) }
        is ToolValue.ObjectValue -> value.values.entries.joinToString(",", "{", "}") { (key, child) ->
            JsonWrite.string(key) + ":" + valueToJson(child)
        }
        ToolValue.NullValue -> "null"
    }
}

/** Where a tool call error should point when the server itself is the problem. */
internal fun McpServerConfig.unreachableHint(): String = "Check that $url is reachable and that this device can reach it."
