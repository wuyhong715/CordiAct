package com.cordiact.app.plugin

import com.cordiact.core.plugin.PluginChangeResult
import com.cordiact.core.plugin.PluginManagementException
import com.cordiact.core.plugin.PluginManagerPresenter
import com.cordiact.core.plugin.PluginManagerService
import com.cordiact.core.tool.ToolArguments
import com.cordiact.core.tool.ToolDefinition
import com.cordiact.core.tool.ToolDefinitionRegistry
import com.cordiact.core.tool.ToolDescriptor
import com.cordiact.core.tool.ToolExecutor
import com.cordiact.core.tool.ToolInputSchema
import com.cordiact.core.mcp.MCP_PLUGIN_ID
import com.cordiact.core.tool.ToolInvocationContext
import com.cordiact.core.tool.ToolParameter
import com.cordiact.core.tool.ToolResult
import com.cordiact.core.tool.ToolValue
import com.cordiact.core.tool.ToolValueType

const val PLUGIN_MANAGER_TOOL_ID = "plugin_manager"
const val PLUGIN_OWNER_ID = "core.plugin-manager"

private const val TIMEOUT_MS = 30_000L

/**
 * The one tool a model uses to inspect and change installed plugins.
 *
 * One tool with an action enum rather than five tools: the actions share state, and a model that
 * sees `plugin_list` / `plugin_add` / `plugin_remove` separately tends to call them without first
 * discovering what exists. Actions that change something return the `nextStep` sentence from
 * [PluginManagerPresenter], which is what keeps "saved" from being reported as "working".
 */
fun pluginManagerToolDescriptor(): ToolDescriptor = ToolDescriptor(
    id = PLUGIN_MANAGER_TOOL_ID,
    name = "plugin_manager",
    description = """
        List, add, enable, disable or remove plugins for this app.
        Use action=list first to see exact plugin ids and states.
        MCP servers are added with action=add_mcp and removed with action=remove.
        A result whose application is not "applied" carries nextStep: follow it and tell the
        user what it says rather than reporting success.
    """.trimIndent(),
    inputSchema = ToolInputSchema(
        parameters = listOf(
            ToolParameter(
                name = "action",
                type = ToolValueType.STRING,
                required = true,
                description = "Operation to perform.",
                enumValues = listOf("list", "enable", "disable", "add_mcp", "remove"),
            ),
            ToolParameter(
                name = "plugin_id",
                type = ToolValueType.STRING,
                required = false,
                description = "Target plugin id, for enable, disable and remove. Get it from list.",
                maxLength = 128,
            ),
            ToolParameter(
                name = "server_name",
                type = ToolValueType.STRING,
                required = false,
                description = "Short namespace for an MCP server, letters/digits/_/- only, max 32 chars.",
                maxLength = 32,
            ),
            ToolParameter(
                name = "url",
                type = ToolValueType.STRING,
                required = false,
                description = "MCP Streamable HTTP endpoint. Must be https; http only for loopback.",
                maxLength = 2_000,
            ),
            ToolParameter(
                name = "header_name",
                type = ToolValueType.STRING,
                required = false,
                description = "Auth header name, when the server needs one (for example Authorization).",
                maxLength = 128,
            ),
            ToolParameter(
                name = "header_value",
                type = ToolValueType.STRING,
                required = false,
                description = "Not accepted for declarations; never send secrets in tool arguments.",
                maxLength = 4_000,
            ),
        ),
    ),
    ownerPluginId = PLUGIN_OWNER_ID,
    defaultEnabled = false,
    timeoutMs = TIMEOUT_MS,
)

/** Agent-side MCP requests produce offline workspace declarations, never installs. */
fun registerPluginManagerTools(
    registry: ToolDefinitionRegistry,
    service: PluginManagerService,
    declareMcp: ((ToolInvocationContext, String, String) -> Unit)? = null,
) {
    registry.register(ToolDefinition(pluginManagerToolDescriptor(), ToolExecutor { context, arguments ->
        execute(service, declareMcp, context, arguments)
    }))
}

private fun execute(
    service: PluginManagerService,
    declareMcp: ((ToolInvocationContext, String, String) -> Unit)?,
    context: ToolInvocationContext,
    arguments: ToolArguments,
): ToolResult {
    val action = arguments.text("action") ?: return failure("action is required")
    return try {
        when (action) {
            "list" -> ToolResult(
                mapOf("plugins" to ToolValue.StringValue(PluginManagerPresenter.presentInventory(service.inventory(context.workspaceId)))),
            )
            "enable", "disable" -> {
                val pluginId = arguments.text("plugin_id") ?: return failure("plugin_id is required for $action")
                ok(service.setEnabled(pluginId, enabled = action == "enable"))
            }
            "add_mcp" -> {
                val name = arguments.text("server_name") ?: return failure("server_name is required")
                val url = arguments.text("url") ?: return failure("url is required")
                if (arguments.text("header_name") != null || arguments.text("header_value") != null) {
                    return failure("Do not pass credentials in tool calls; the user adds them in workspace settings")
                }
                val declare = declareMcp ?: return failure("Workspace declaration storage unavailable")
                declare(context, name, url)
                ToolResult(mapOf("status" to ToolValue.StringValue("Offline declaration saved. No connection or tool grants. Review in Settings → Plugins and Connections → MCP declarations.")))
            }
            "remove" -> {
                val pluginId = arguments.text("plugin_id") ?: return failure("plugin_id is required for remove")
                ok(service.remove(pluginId))
            }
            else -> failure("action must be one of list, enable, disable, add_mcp, remove")
        }
    } catch (failure: PluginManagementException) {
        // A structured refusal is information the model can act on, not a turn-ending error.
        ToolResult(mapOf("error" to ToolValue.StringValue("${failure.code.name.lowercase()}: ${failure.message}")))
    } catch (failure: Exception) {
        ToolResult(mapOf("error" to ToolValue.StringValue("plugin management failed; inspect settings")))
    }
}

private fun ok(result: PluginChangeResult): ToolResult =
    ToolResult(mapOf("result" to ToolValue.StringValue(PluginManagerPresenter.present(result))))

private fun failure(message: String): ToolResult =
    ToolResult(mapOf("error" to ToolValue.StringValue(message)))

private fun ToolArguments.text(name: String): String? =
    (values[name] as? ToolValue.StringValue)?.value?.takeIf { it.isNotBlank() }

/** True for the plugin ids this app can construct directly. */
internal fun isMcpPluginId(pluginId: String): Boolean = pluginId.startsWith("$MCP_PLUGIN_ID.")
