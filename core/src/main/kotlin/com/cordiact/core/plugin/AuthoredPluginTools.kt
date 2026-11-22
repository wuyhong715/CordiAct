package com.cordiact.core.plugin

import com.cordiact.core.file.WorkspaceFileException
import com.cordiact.core.tool.ToolDefinition
import com.cordiact.core.tool.ToolDefinitionRegistry
import com.cordiact.core.tool.ToolDescriptor
import com.cordiact.core.tool.ToolInputSchema
import com.cordiact.core.tool.ToolParameter
import com.cordiact.core.tool.ToolResult
import com.cordiact.core.tool.ToolValue
import com.cordiact.core.tool.ToolValueType

/** Agent authoring surface. Source creation uses the existing workspace.files.write_text tool. */
fun registerAuthoredPluginTool(registry: ToolDefinitionRegistry, runtime: AuthoredPluginRuntime) {
    registry.register(ToolDefinition(ToolDescriptor(
        id = AUTHORED_PLUGIN_TOOL_ID,
        name = "Author a session plugin",
        description = """
            Write plugins/<plugin_id>.json with workspace.files.write_text. Use schemaVersion:2, id, title, and
            1–4 tools, each with name, description and kind. For kind "static_text", supply only the extra field
            "text" (fixed output). For kind "text_transform", supply only "operation": "trim", "uppercase", or
            "lowercase" (Locale.ROOT for case conversion). Transform calls take one required string "text";
            input and output are each limited to $AUTHORED_TRANSFORM_MAX_BYTES UTF-8 bytes, with no truncation.
            The tools treat text as literal untrusted data; they cannot run code, read local files, or use the network.
            Legacy schemaVersion:1 still accepts name, description and fixed text without kind. Use lowercase slugs
            of 2–24 characters for id and name. Do not put secrets in source or approval previews.
            Inspect validates the draft and returns its SHA-256. Activate and deactivate require that SHA-256 and
            human approval. New tools appear on the next foreground turn in this session; list shows only this
            session's live plugins. Source edits never hot-reload an active plugin. Restart ends activation, not the draft.
        """.trimIndent(),
        inputSchema = ToolInputSchema(listOf(
            ToolParameter("action", ToolValueType.STRING, required = true,
                enumValues = listOf("inspect", "activate", "deactivate", "list")),
            ToolParameter("plugin_id", ToolValueType.STRING, maxLength = 24),
            ToolParameter("sha256", ToolValueType.STRING, maxLength = 64),
        )),
        ownerPluginId = AUTHORED_PLUGIN_HOST_ID,
        defaultEnabled = true,
        concurrencySafe = true,
    )) { context, arguments ->
        fun required(key: String): String = (arguments.values[key] as? ToolValue.StringValue)?.value
            ?: throw IllegalArgumentException("Missing field: $key")
        try {
            when (required("action")) {
                "list" -> ToolResult(mapOf("active" to ToolValue.ArrayValue(runtime.list(context).map(::receiptValue))))
                "inspect" -> {
                    val candidate = runtime.inspect(context, required("plugin_id"))
                    val receipt = AuthoredPluginReceipt(candidate.id, candidate.sha256,
                        candidate.tools.map { AuthoredPluginNames.tool(candidate.id, it.name) })
                    ToolResult(mapOf("draft" to receiptValue(receipt), "state" to ToolValue.StringValue("draft_only")))
                }
                "activate" -> result("active_next_turn", runtime.activate(context, required("plugin_id"), required("sha256")))
                "deactivate" -> result("inactive", runtime.deactivate(context, required("plugin_id"), required("sha256")))
                else -> errorResult("INVALID_ACTION", "Unknown plugin authoring action")
            }
        } catch (failure: AuthoredPluginException) {
            errorResult(failure.code.name, failure.message ?: "Plugin change refused")
        } catch (failure: WorkspaceFileException) {
            errorResult(failure.code.name, "Could not read this plugin source in the current workspace")
        } catch (failure: IllegalArgumentException) {
            errorResult("INVALID_SOURCE_OR_ARGUMENTS", failure.message ?: "Invalid plugin source or arguments")
        }
    })
}

private fun result(state: String, receipt: AuthoredPluginReceipt) = ToolResult(mapOf(
    "state" to ToolValue.StringValue(state), "plugin" to receiptValue(receipt),
))

private fun receiptValue(receipt: AuthoredPluginReceipt) = ToolValue.ObjectValue(mapOf(
    "id" to ToolValue.StringValue(receipt.id),
    "sha256" to ToolValue.StringValue(receipt.sha256),
    "tool_ids" to ToolValue.ArrayValue(receipt.toolIds.map(ToolValue::StringValue)),
))

private fun errorResult(code: String, message: String) = ToolResult(mapOf(
    "error" to ToolValue.ObjectValue(mapOf("code" to ToolValue.StringValue(code), "message" to ToolValue.StringValue(message))),
))
