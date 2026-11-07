package com.cordiact.app.termux

import com.cordiact.core.automation.AutomationCapabilities
import com.cordiact.core.automation.AutomationPlugins
import com.cordiact.core.automation.ShellCommandPolicy
import com.cordiact.core.execution.TermuxCommandParams
import com.cordiact.core.execution.TermuxSessionAction
import com.cordiact.core.execution.TermuxSessionParams
import com.cordiact.core.tool.ToolArguments
import com.cordiact.core.tool.ToolDefinition
import com.cordiact.core.tool.ToolDefinitionRegistry
import com.cordiact.core.tool.ToolDescriptor
import com.cordiact.core.tool.ToolInputSchema
import com.cordiact.core.tool.ToolParameter
import com.cordiact.core.tool.ToolResult
import com.cordiact.core.tool.ToolValue
import com.cordiact.core.tool.ToolValueType

private const val TIMEOUT_STATUS = 5_000L
private const val TIMEOUT_DEFAULT_EXEC = 60_000L
private const val TIMEOUT_SESSION_OP = 120_000L

fun termuxTools(plugin: TermuxPlugin): List<ToolDefinition> = listOf(
    statusTool(plugin),
    runCommandTool(plugin),
    sessionTool(plugin),
)

fun registerTermuxTools(registry: ToolDefinitionRegistry, plugin: TermuxPlugin) {
    termuxTools(plugin).forEach(registry::register)
}

private fun statusTool(plugin: TermuxPlugin) = ToolDefinition(
    descriptor = ToolDescriptor(
        id = "termux_status",
        name = "termux_status",
        description = "Check Termux installation, RUN_COMMAND permission, and bridge readiness on this device.",
        inputSchema = ToolInputSchema(),
        ownerPluginId = AutomationPlugins.TERMUX,
        defaultEnabled = false,
        requiredCapabilities = emptySet(),
        timeoutMs = TIMEOUT_STATUS,
        concurrencySafe = true,
    ),
    executor = { _, _ ->
        val status = plugin.bridge.inspectStatus()
        ToolResult(
            values = linkedMapOf(
                "ok" to ToolValue.BooleanValue(true),
                "installed" to ToolValue.BooleanValue(status.isInstalled),
                "has_permission" to ToolValue.BooleanValue(status.hasPermission),
                "allows_external_apps" to ToolValue.BooleanValue(status.allowsExternalApps),
                "is_available" to ToolValue.BooleanValue(status.isAvailable),
                "message" to ToolValue.StringValue(status.message),
            ),
        )
    },
)

private fun runCommandTool(plugin: TermuxPlugin) = ToolDefinition(
    descriptor = ToolDescriptor(
        id = "termux_run_command",
        name = "termux_run_command",
        description = "Run a shell command or script inside Termux using the official RUN_COMMAND service.",
        inputSchema = ToolInputSchema(
            parameters = listOf(
                param("command", ToolValueType.STRING, "Shell command or script to execute.", required = true, maxLength = 8_192),
                param("work_dir", ToolValueType.STRING, "Working directory inside Termux (default: /data/data/com.termux/files/home)."),
                param("background", ToolValueType.BOOLEAN, "Run command silently in background without popping up the Termux window (default: true)."),
                param("timeout_ms", ToolValueType.INTEGER, "Timeout in milliseconds, max 300000 (default: 30000).", minimum = 1_000.0, maximum = 300_000.0),
            ),
        ),
        ownerPluginId = AutomationPlugins.TERMUX,
        defaultEnabled = false,
        requiredCapabilities = setOf(AutomationCapabilities.TERMUX_EXEC),
        timeoutMs = TIMEOUT_DEFAULT_EXEC,
        concurrencySafe = false,
    ),
    executor = { _, args ->
        val command = args.string("command") ?: return@ToolDefinition error("missing_arg", "command is required")
        val workDir = args.string("work_dir")
        val background = args.boolean("background") ?: true
        val timeoutMs = (args.int("timeout_ms")?.toLong() ?: 30_000L).coerceIn(1000L, 300_000L)

        val params = TermuxCommandParams(
            command = command,
            workDir = workDir,
            background = background,
            timeoutMs = timeoutMs,
        )

        val result = plugin.bridge.executeCommand(params)
        ToolResult(
            values = linkedMapOf(
                "ok" to ToolValue.BooleanValue(result.isSuccess),
                "exit_code" to ToolValue.NumberValue(result.exitCode.toDouble()),
                "stdout" to ToolValue.StringValue(result.stdout),
                "stderr" to ToolValue.StringValue(result.stderr),
                "timed_out" to ToolValue.BooleanValue(result.timedOut),
                "truncated" to ToolValue.BooleanValue(result.truncated),
                "safety_class" to ToolValue.StringValue(ShellCommandPolicy.classify(command).name),
            ),
        )
    },
)

private fun sessionTool(plugin: TermuxPlugin) = ToolDefinition(
    descriptor = ToolDescriptor(
        id = "termux_session",
        name = "termux_session",
        description = "Manage persistent interactive tmux sessions in Termux (start, send keys, read pane, poll pattern, kill, list).",
        inputSchema = ToolInputSchema(
            parameters = listOf(
                param("session_name", ToolValueType.STRING, "Unique identifier for the tmux session.", required = true, maxLength = 64),
                param("action", ToolValueType.STRING, "Action to perform: start, send, read, kill, list.", required = true),
                param("input", ToolValueType.STRING, "Input text/command to send (for action='send')."),
                param("wait_for_pattern", ToolValueType.STRING, "Optional regex to poll output until matched (for action='send' or 'read')."),
                param("settle_ms", ToolValueType.INTEGER, "Duration in ms screen output must stay unchanged before returning when no pattern given (default: 500).", minimum = 200.0, maximum = 5000.0),
                param("timeout_ms", ToolValueType.INTEGER, "Timeout in milliseconds (default: 10000).", minimum = 1_000.0, maximum = 120_000.0),
                param("max_lines", ToolValueType.INTEGER, "Max lines to capture from history (default: 100).", minimum = 10.0, maximum = 500.0),
            ),
        ),
        ownerPluginId = AutomationPlugins.TERMUX,
        defaultEnabled = false,
        requiredCapabilities = setOf(AutomationCapabilities.TERMUX_SESSION),
        timeoutMs = TIMEOUT_SESSION_OP,
        concurrencySafe = false,
    ),
    executor = { _, args ->
        val sessionName = args.string("session_name").orEmpty()
        val actionStr = args.string("action")?.lowercase().orEmpty()
        val input = args.string("input")
        val waitForPattern = args.string("wait_for_pattern")
        val settleMs = (args.int("settle_ms")?.toLong() ?: 500L).coerceIn(200L, 5000L)
        val timeoutMs = (args.int("timeout_ms")?.toLong() ?: 10_000L).coerceIn(1000L, 120_000L)
        val maxLines = args.int("max_lines") ?: 100

        val action = when (actionStr) {
            "start" -> TermuxSessionAction.START
            "send" -> TermuxSessionAction.SEND
            "read" -> TermuxSessionAction.READ
            "kill" -> TermuxSessionAction.KILL
            "list" -> TermuxSessionAction.LIST
            else -> return@ToolDefinition error("invalid_action", "action must be one of: start, send, read, kill, list")
        }

        if (action != TermuxSessionAction.LIST && sessionName.isBlank()) {
            return@ToolDefinition error("missing_arg", "session_name is required for action '$actionStr'")
        }

        val params = TermuxSessionParams(
            sessionName = sessionName,
            action = action,
            input = input,
            waitForPattern = waitForPattern,
            settleMs = settleMs,
            timeoutMs = timeoutMs,
            maxLines = maxLines,
        )

        val result = plugin.sessionManager.executeSessionAction(params)
        ToolResult(
            values = linkedMapOf(
                "ok" to ToolValue.BooleanValue(result.error == null),
                "session_name" to ToolValue.StringValue(result.sessionName),
                "active" to ToolValue.BooleanValue(result.active),
                "screen_output" to ToolValue.StringValue(result.screenOutput),
                "matched" to ToolValue.BooleanValue(result.matched),
                "reason" to ToolValue.StringValue(result.reason),
                "error" to (result.error?.let { ToolValue.StringValue(it) } ?: ToolValue.NullValue),
            ),
        )
    },
)

private fun param(
    name: String,
    type: ToolValueType,
    description: String,
    required: Boolean = false,
    minimum: Double? = null,
    maximum: Double? = null,
    maxLength: Int? = null,
) = ToolParameter(
    name = name,
    type = type,
    required = required,
    description = description,
    minimum = minimum,
    maximum = maximum,
    maxLength = maxLength,
)

private fun error(code: String, message: String) = ToolResult(
    values = linkedMapOf(
        "ok" to ToolValue.BooleanValue(false),
        "error" to ToolValue.ObjectValue(
            linkedMapOf(
                "code" to ToolValue.StringValue(code),
                "message" to ToolValue.StringValue(message),
            ),
        ),
    ),
)

private fun ToolArguments.string(name: String): String? =
    (values[name] as? ToolValue.StringValue)?.value?.takeIf { it.isNotBlank() }

private fun ToolArguments.int(name: String): Int? = when (val value = values[name]) {
    is ToolValue.NumberValue -> value.value.toInt()
    is ToolValue.StringValue -> value.value.toIntOrNull()
    else -> null
}

private fun ToolArguments.boolean(name: String): Boolean? = when (val value = values[name]) {
    is ToolValue.BooleanValue -> value.value
    is ToolValue.StringValue -> value.value.toBooleanStrictOrNull()
    else -> null
}
