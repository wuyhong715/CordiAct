package com.cordiact.core.context

import com.cordiact.core.tool.InMemoryToolDefinitionRegistry
import com.cordiact.core.tool.ToolArguments
import com.cordiact.core.tool.ToolDefinition
import com.cordiact.core.tool.ToolDescriptor
import com.cordiact.core.tool.ToolExecutor
import com.cordiact.core.tool.ToolInputSchema
import com.cordiact.core.tool.ToolParameter
import com.cordiact.core.tool.ToolResult
import com.cordiact.core.tool.ToolValue
import com.cordiact.core.tool.ToolValueType

const val CONTEXT_PLUGIN_ID = "core.context"
const val TOOL_OUTPUT_READ_ID = "tool_output_read"

fun toolOutputReadDescriptor(): ToolDescriptor = ToolDescriptor(
    id = TOOL_OUTPUT_READ_ID,
    name = "tool_output_read",
    description = """
        Read the full text of a tool result that was truncated to fit the context budget.
        Call this with the spill URI mentioned in the truncation notice (e.g. "spill://<sessionId>/<file>.txt").
        Supports pagination via offset and limit_chars.
    """.trimIndent(),
    inputSchema = ToolInputSchema(
        parameters = listOf(
            ToolParameter(
                name = "file",
                type = ToolValueType.STRING,
                required = true,
                description = "Spill URI from the truncation notice (e.g. spill://sessionId/filename.txt).",
            ),
            ToolParameter(
                name = "offset",
                type = ToolValueType.INTEGER,
                required = false,
                description = "Character offset to start reading from. Defaults to 0.",
            ),
            ToolParameter(
                name = "limit_chars",
                type = ToolValueType.INTEGER,
                required = false,
                description = "Maximum characters to return in this page (500..20000). Defaults to 8000.",
            ),
        ),
    ),
    ownerPluginId = CONTEXT_PLUGIN_ID,
    defaultEnabled = true,
    timeoutMs = 10_000L,
    concurrencySafe = true,
)

fun registerContextTools(registry: InMemoryToolDefinitionRegistry, spillStore: SpillStore) {
    registry.register(
        ToolDefinition(
            toolOutputReadDescriptor(),
            ToolExecutor { _, arguments ->
                val file = (arguments.values["file"] as? ToolValue.StringValue)?.value
                    ?: return@ToolExecutor ToolResult(
                        values = mapOf(
                            "ok" to ToolValue.BooleanValue(false),
                            "error" to ToolValue.StringValue("Missing required parameter 'file'"),
                        ),
                    )

                val offset = (arguments.values["offset"] as? ToolValue.NumberValue)?.value?.toLong() ?: 0L
                val limit = (arguments.values["limit_chars"] as? ToolValue.NumberValue)?.value?.toInt() ?: 8_000

                val readResult = spillStore.readSpill(file, offset, limit)
                    ?: return@ToolExecutor ToolResult(
                        values = mapOf(
                            "ok" to ToolValue.BooleanValue(false),
                            "error" to ToolValue.StringValue("File not found, invalid scheme, or path traversal detected: $file"),
                        ),
                    )

                val summaryText = if (readResult.hasMore) {
                    val nextOffset = readResult.offset + readResult.bytesRead
                    "${readResult.content}\n\n[has_more: true, next_offset: $nextOffset, total_chars: ${readResult.totalChars}]"
                } else {
                    "${readResult.content}\n\n[EOF reached, total_chars: ${readResult.totalChars}]"
                }

                ToolResult(
                    values = mapOf(
                        "ok" to ToolValue.BooleanValue(true),
                        "content" to ToolValue.StringValue(summaryText),
                        "offset" to ToolValue.NumberValue(readResult.offset.toDouble()),
                        "bytes_read" to ToolValue.NumberValue(readResult.bytesRead.toDouble()),
                        "total_chars" to ToolValue.NumberValue(readResult.totalChars.toDouble()),
                        "has_more" to ToolValue.BooleanValue(readResult.hasMore),
                    ),
                    metadata = mapOf(
                        "offset" to ToolValue.NumberValue(readResult.offset.toDouble()),
                        "bytes_read" to ToolValue.NumberValue(readResult.bytesRead.toDouble()),
                        "total_chars" to ToolValue.NumberValue(readResult.totalChars.toDouble()),
                        "has_more" to ToolValue.BooleanValue(readResult.hasMore),
                    ),
                )
            },
        ),
    )
}
