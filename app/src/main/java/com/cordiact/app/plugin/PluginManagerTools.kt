package com.cordiact.app.plugin

import com.cordiact.core.mcp.MCP_PLUGIN_ID
import com.cordiact.core.mcp.McpConfigException
import com.cordiact.core.mcp.McpServerConfig
import com.cordiact.core.mcp.mcpPluginSpec
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
                description = "Auth header value. Stored by the app credential store, never in the plugin config.",
                maxLength = 4_000,
            ),
        ),
    ),
    ownerPluginId = PLUGIN_OWNER_ID,
    defaultEnabled = false,
    timeoutMs = TIMEOUT_MS,
)

/**
 * Register the tool.
 *
 * @param registerCredential persists an auth header for one server; the tool never writes a
 *   credential into the plugin config, which `plugin_manager list` and logs would leak.
 */
fun registerPluginManagerTools(
    registry: ToolDefinitionRegistry,
    service: PluginManagerService,
    registerCredential: (serverName: String, headerName: String, headerValue: String) -> Unit = { _, _, _ -> },
) {
    registry.register(
        ToolDefinition(
            descriptor = pluginManagerToolDescriptor(),
            executor = ToolExecutor { context, arguments ->
                execute(service, registerCredential, context, arguments)
            },
        ),
    )
}

private fun execute(
    service: PluginManagerService,
    registerCredential: (String, String, String) -> Unit,
    context: ToolInvocationContext,
    arguments: ToolArguments,
): ToolResult {
    val action = arguments.text("action") ?: return failure("action is required")
    return try {
        when (action) {
            "list" -> ToolResult(
                mapOf("plugins" to ToolValue.StringValue(PluginManagerPresenter.presentInventory(service.inventory()))),
            )
            "enable", "disable" -> {
                val pluginId = arguments.text("plugin_id") ?: return failure("plugin_id is required for $action")
                ok(service.setEnabled(pluginId, enabled = action == "enable"))
            }
            "add_mcp" -> addMcp(service, registerCredential, arguments)
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
        ToolResult(mapOf("error" to ToolValue.StringValue(failure.message ?: "plugin management failed")))
    }
}

private fun addMcp(
    service: PluginManagerService,
    registerCredential: (String, String, String) -> Unit,
    arguments: ToolArguments,
): ToolResult {
    val serverName = arguments.text("server_name") ?: return failure("server_name is required for add_mcp")
    val url = arguments.text("url") ?: return failure("url is required for add_mcp")
    val config = try {
        McpServerConfig.validated(McpServerConfig(serverName = serverName, url = url))
    } catch (failure: McpConfigException) {
        return failure(failure.message ?: "invalid MCP configuration")
    }
    val headerName = arguments.text("header_name")
    val headerValue = arguments.text("header_value")
    if ((headerName == null) != (headerValue == null)) {
        return failure("header_name and header_value must be given together")
    }
    val result = service.install(mcpPluginSpec(config))
    if (headerName != null && headerValue != null && result.application == PluginChangeResult.Application.APPLIED) {
        registerCredential(serverName, headerName, headerValue)
    }
    return ok(result)
}

private fun ok(result: PluginChangeResult): ToolResult =
    ToolResult(mapOf("result" to ToolValue.StringValue(PluginManagerPresenter.present(result))))

private fun failure(message: String): ToolResult =
    ToolResult(mapOf("error" to ToolValue.StringValue(message)))

private fun ToolArguments.text(name: String): String? =
    (values[name] as? ToolValue.StringValue)?.value?.takeIf { it.isNotBlank() }

/** True for the plugin ids this app can construct directly. */
internal fun isMcpPluginId(pluginId: String): Boolean = pluginId.startsWith("$MCP_PLUGIN_ID.")
