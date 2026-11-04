package com.cordiact.mcp.client

import com.cordiact.core.mcp.McpConnection
import com.cordiact.core.mcp.McpProtocolException
import com.cordiact.core.mcp.McpServerConfig
import com.cordiact.core.mcp.McpToolDescriptor
import com.cordiact.core.mcp.mcpToolId
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
 * The swap is the interesting part. A model must never see half a server: either every tool
 * of the current generation is callable, or none is. So a sync fetches and builds the whole
 * next generation first, and only then retires the previous one. If registration fails midway
 * — which can only mean something foreign squats on this server's id namespace — the partial
 * generation is unregistered again and the sync reports failure.
 *
 * Not thread-safe by itself; [McpPlugin] calls it under its own lock.
 */
class McpToolBridge(
    private val serverName: String,
    private val registry: ToolDefinitionRegistry,
    private val ownerPluginId: String,
    private val toolTimeoutMs: Long,
) {
    private var generation: Map<String, String> = emptyMap()

    /**
     * The connection tools dispatch through.
     *
     * Set by [sync] rather than a separate `bind` call: a bridge whose registered tools point
     * at a different connection than the one it just listed would dispatch into a dead server,
     * and there is no ordering the caller could get right by accident.
     */
    private var connection: McpConnection? = null

    /** Tool ids currently registered by this bridge, keyed to the raw MCP name. */
    fun publishedTools(): Map<String, String> = generation

    /**
     * Replace the previous generation with whatever [live] reports.
     *
     * @throws McpProtocolException when the tool list cannot be read.
     * @throws IllegalStateException when registration is refused; the registry is left with
     *   zero tools from this server in that case.
     */
    fun sync(live: McpConnection): Map<String, String> {
        val descriptors = live.listTools(toolTimeoutMs)
        val next = buildGeneration(descriptors)
        val previous = generation

        // Retire the old generation only after the new one is fully built, so a listing failure
        // above leaves the currently working tools in place.
        previous.keys.forEach(registry::unregister)
        generation = emptyMap()
        connection = null

        val registered = linkedMapOf<String, String>()
        try {
            next.forEach { (toolId, definition) ->
                registry.register(definition)
                registered[toolId] = definition.descriptor.name
            }
        } catch (failure: Exception) {
            registered.keys.forEach(registry::unregister)
            throw failure
        }
        generation = registered
        connection = live
        return registered
    }

    /** Withdraw every tool this bridge published. Safe to call when nothing is registered. */
    fun dispose() {
        generation.keys.forEach(registry::unregister)
        generation = emptyMap()
        connection = null
    }

    private fun buildGeneration(descriptors: List<McpToolDescriptor>): Map<String, ToolDefinition> {
        val byId = linkedMapOf<String, ToolDefinition>()
        descriptors.forEach { tool ->
            val id = mcpToolId(serverName, tool.name)
            if (byId.containsKey(id)) {
                // Two raw names normalizing to one id would make the second silently shadow the
                // first; refuse the whole generation rather than publish an arbitrary winner.
                throw McpProtocolException("server '$serverName' exposes two tools that map to '$id'")
            }
            val raw = tool
            byId[id] = ToolDefinition(
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
                executor = ToolExecutor { _, arguments -> invoke(raw.name, arguments) },
            )
        }
        return byId
    }

    private fun invoke(rawName: String, arguments: ToolArguments): ToolResult {
        val live = connection ?: return errorResult("MCP server '$serverName' is not connected")
        val json = argumentsToJson(arguments)
        return try {
            val result = live.callTool(rawName, json, toolTimeoutMs)
            if (result.isError) {
                errorResult(result.text.ifBlank { "MCP tool '$rawName' reported a failure" })
            } else {
                ToolResult(values = mapOf("result" to ToolValue.StringValue(result.text)))
            }
        } catch (failure: McpProtocolException) {
            errorResult(failure.message ?: "MCP call to '$rawName' failed")
        }
    }

    private fun errorResult(message: String): ToolResult =
        ToolResult(values = mapOf("error" to ToolValue.StringValue(message)))

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
