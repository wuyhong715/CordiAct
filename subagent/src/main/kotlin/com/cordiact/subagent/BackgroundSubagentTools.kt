package com.cordiact.subagent

import com.cordiact.core.subagent.BACKGROUND_CAPABILITY
import com.cordiact.core.subagent.BACKGROUND_PLUGIN_ID
import com.cordiact.core.subagent.BackgroundLimits
import com.cordiact.core.subagent.BackgroundSubagentService
import com.cordiact.core.subagent.CLOSE_CHILD_TOOL_ID
import com.cordiact.core.subagent.ChildRun
import com.cordiact.core.subagent.ChildRunState
import com.cordiact.core.subagent.ChildSpec
import com.cordiact.core.subagent.SEND_CHILD_TOOL_ID
import com.cordiact.core.subagent.SPAWN_CHILD_TOOL_ID
import com.cordiact.core.subagent.WAIT_CHILDREN_TOOL_ID
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

/**
 * The asynchronous tool surface.
 *
 * Four tools rather than one, because each answers a different question the model has to be able
 * to ask separately: start something, wait for it, look at what exists, stop paying for it. A
 * single "delegate" with a wait flag cannot express "start three, then collect", which is the
 * entire point of background delegation.
 */
fun registerBackgroundSubagentTools(
    registry: ToolDefinitionRegistry,
    service: BackgroundSubagentService,
    limits: BackgroundLimits = BackgroundLimits(),
) {
    registry.register(ToolDefinition(spawnDescriptor(limits), ToolExecutor { context, arguments ->
        executeSpawn(service, context, arguments)
    }))
    registry.register(ToolDefinition(waitDescriptor(limits), ToolExecutor { context, arguments ->
        executeWait(service, context, arguments, limits)
    }))
    registry.register(ToolDefinition(closeDescriptor(), ToolExecutor { context, arguments ->
        executeClose(service, context, arguments)
    }))
    registry.register(ToolDefinition(sendDescriptor(), ToolExecutor { context, arguments ->
        executeSend(service, context, arguments)
    }))
}

private val COMMON_DESCRIPTION = """
    These tools start delegated subagents that keep running while you do other work. Each child is
    an ordinary Session with its own transcript; it does NOT see this conversation, so every task
    must be self-contained.

    When a child finishes you are told at the start of your NEXT turn — you are not interrupted
    mid-answer. If you need its result now, call wait_children.
""".trimIndent()

private fun spawnDescriptor(limits: BackgroundLimits) = ToolDescriptor(
    id = SPAWN_CHILD_TOOL_ID,
    name = "spawn_child",
    description = """
        Start a subagent and return immediately with its Session id.

        $COMMON_DESCRIPTION

        Use this when several independent tasks should run together, or when a task will take a
        while and you would rather keep working. At most ${limits.maxRunningChildren} run at once;
        a spawn past that limit fails rather than queueing.

        A finished child still holds its place until close_child. Do not spawn and forget: either
        wait_children for what you need, or close what you are done with.
    """.trimIndent(),
    inputSchema = ToolInputSchema(
        parameters = listOf(
            ToolParameter(
                name = "label",
                type = ToolValueType.STRING,
                required = true,
                description = "3-5 word display name, used to identify the child later.",
                maxLength = 120,
            ),
            ToolParameter(
                name = "prompt",
                type = ToolValueType.STRING,
                required = true,
                description = "The complete, self-contained task. The subagent shares none of this " +
                    "conversation's context.",
            ),
        ),
    ),
    ownerPluginId = BACKGROUND_PLUGIN_ID,
    defaultEnabled = true,
    requiredCapabilities = setOf(BACKGROUND_CAPABILITY),
    // Fast by construction: admission is synchronous and the turn runs elsewhere.
    timeoutMs = 30_000,
    concurrencySafe = true,
)

private fun waitDescriptor(limits: BackgroundLimits) = ToolDescriptor(
    id = WAIT_CHILDREN_TOOL_ID,
    name = "wait_children",
    description = """
        Wait until any of the given children settles, then report every child's state.

        $COMMON_DESCRIPTION

        Omit `child_ids` to wait on whichever children are still running. The timeout is bounded
        to ${limits.minWaitMillis}..${limits.maxWaitMillis} ms (default ${limits.defaultWaitMillis});
        prefer a long wait over calling this repeatedly — each call costs a model round trip.
    """.trimIndent(),
    inputSchema = ToolInputSchema(
        parameters = listOf(
            ToolParameter(
                name = "child_ids",
                type = ToolValueType.ARRAY,
                description = "Session ids to wait on. Omit to wait on all running children.",
                itemType = ToolValueType.STRING,
                maxItems = 64,
            ),
            ToolParameter(
                name = "timeout_ms",
                type = ToolValueType.INTEGER,
                description = "How long to wait. Defaults to ${limits.defaultWaitMillis}.",
                minimum = 1.0,
                maximum = limits.maxWaitMillis.toDouble(),
            ),
        ),
    ),
    ownerPluginId = BACKGROUND_PLUGIN_ID,
    defaultEnabled = true,
    requiredCapabilities = setOf(BACKGROUND_CAPABILITY),
    // The host timeout must exceed the longest wait the schema allows, or the tool is killed
    // while it is still legitimately waiting.
    timeoutMs = limits.maxWaitMillis + 30_000,
    concurrencySafe = true,
)

private fun closeDescriptor() = ToolDescriptor(
    id = CLOSE_CHILD_TOOL_ID,
    name = "close_child",
    description = """
        Release a delegated subagent and refuse further messages to it.

        A finished child keeps holding one of the concurrency slots until you close it, so close
        the ones you no longer need. Closing a child that is still running aborts it — its output
        is discarded, so only do that when the result no longer matters.
    """.trimIndent(),
    inputSchema = ToolInputSchema(
        parameters = listOf(
            ToolParameter(
                name = "child_id",
                type = ToolValueType.STRING,
                required = true,
                description = "Session id returned by spawn_child.",
            ),
        ),
    ),
    ownerPluginId = BACKGROUND_PLUGIN_ID,
    defaultEnabled = true,
    requiredCapabilities = setOf(BACKGROUND_CAPABILITY),
    timeoutMs = 15_000,
    concurrencySafe = true,
)

private fun sendDescriptor() = ToolDescriptor(
    id = SEND_CHILD_TOOL_ID,
    name = "send_child_message",
    description = """
        Give an idle delegated subagent another task, keeping its earlier transcript.

        Use this instead of spawning a new child when the follow-up builds on what that child
        already did — it still remembers its own earlier turns (though never this conversation).
        The child must have finished: a running child cannot be steered from here.
    """.trimIndent(),
    inputSchema = ToolInputSchema(
        parameters = listOf(
            ToolParameter(
                name = "child_id",
                type = ToolValueType.STRING,
                required = true,
                description = "Session id returned by spawn_child.",
            ),
            ToolParameter(
                name = "message",
                type = ToolValueType.STRING,
                required = true,
                description = "The follow-up task. The child sees its own earlier work, not this conversation.",
            ),
        ),
    ),
    ownerPluginId = BACKGROUND_PLUGIN_ID,
    defaultEnabled = true,
    requiredCapabilities = setOf(BACKGROUND_CAPABILITY),
    timeoutMs = 30_000,
    concurrencySafe = true,
)

private fun executeSpawn(
    service: BackgroundSubagentService,
    context: ToolInvocationContext,
    arguments: ToolArguments,
): ToolResult {
    val label = (arguments.values["label"] as? ToolValue.StringValue)?.value?.trim().orEmpty()
    val prompt = (arguments.values["prompt"] as? ToolValue.StringValue)?.value?.trim().orEmpty()
    if (label.isEmpty()) return error("invalid_arguments", "label must not be empty")
    if (prompt.isEmpty()) return error("invalid_arguments", "prompt must not be empty")
    val sessionId = context.sessionId
        ?: return error("no_session", "spawn_child requires a calling Session")

    val run = service.spawn(
        ChildSpec(
            workspaceId = context.workspaceId,
            parentSessionId = sessionId,
            label = label,
            prompt = prompt,
        ),
        context,
    )
    if (run.state == ChildRunState.FAILED && run.sessionId.isBlank()) {
        return error("spawn_refused", run.diagnostic ?: "The delegated task was refused")
    }
    return ToolResult(values = renderRun(run))
}

private fun executeWait(
    service: BackgroundSubagentService,
    context: ToolInvocationContext,
    arguments: ToolArguments,
    limits: BackgroundLimits,
): ToolResult {
    val ids = (arguments.values["child_ids"] as? ToolValue.ArrayValue)
        ?.values
        ?.mapNotNull { (it as? ToolValue.StringValue)?.value }
        ?.filter { it.isNotBlank() }
        .orEmpty()
    val timeout = (arguments.values["timeout_ms"] as? ToolValue.NumberValue)?.value
        ?.toLong()
        ?: limits.defaultWaitMillis

    val runs = service.waitFor(context, ids, timeout)
    return ToolResult(
        values = linkedMapOf(
            "children" to ToolValue.ArrayValue(runs.map(::renderRunObject)),
            "running" to ToolValue.NumberValue(runs.count { it.state == ChildRunState.RUNNING }.toDouble()),
        ),
    )
}

private fun executeClose(
    service: BackgroundSubagentService,
    context: ToolInvocationContext,
    arguments: ToolArguments,
): ToolResult {
    val childId = (arguments.values["child_id"] as? ToolValue.StringValue)?.value?.trim().orEmpty()
    if (childId.isEmpty()) return error("invalid_arguments", "child_id must not be empty")
    return try {
        ToolResult(values = renderRun(service.close(context, childId)))
    } catch (failure: IllegalArgumentException) {
        error("unknown_child", failure.message ?: "Unknown child")
    }
}

private fun executeSend(
    service: BackgroundSubagentService,
    context: ToolInvocationContext,
    arguments: ToolArguments,
): ToolResult {
    val childId = (arguments.values["child_id"] as? ToolValue.StringValue)?.value?.trim().orEmpty()
    val message = (arguments.values["message"] as? ToolValue.StringValue)?.value?.trim().orEmpty()
    if (childId.isEmpty()) return error("invalid_arguments", "child_id must not be empty")
    if (message.isEmpty()) return error("invalid_arguments", "message must not be empty")
    return try {
        ToolResult(values = renderRun(service.send(context, childId, message)))
    } catch (failure: IllegalStateException) {
        error("child_not_idle", failure.message ?: "The child cannot accept a message")
    } catch (failure: IllegalArgumentException) {
        error("unknown_child", failure.message ?: "Unknown child")
    }
}

private fun renderRun(run: ChildRun): Map<String, ToolValue> = linkedMapOf(
    "ok" to ToolValue.BooleanValue(run.state != ChildRunState.FAILED || run.sessionId.isNotBlank()),
    "childId" to ToolValue.StringValue(run.sessionId),
    "label" to ToolValue.StringValue(run.label),
    "state" to ToolValue.StringValue(run.state.name),
    "text" to (run.result?.text?.let { ToolValue.StringValue(it) } ?: ToolValue.NullValue),
    "diagnostic" to (run.diagnostic?.let { ToolValue.StringValue(it) } ?: ToolValue.NullValue),
)

private fun renderRunObject(run: ChildRun): ToolValue = ToolValue.ObjectValue(
    linkedMapOf(
        "childId" to ToolValue.StringValue(run.sessionId),
        "label" to ToolValue.StringValue(run.label),
        "state" to ToolValue.StringValue(run.state.name),
        "text" to (run.result?.text?.let { ToolValue.StringValue(it) } ?: ToolValue.NullValue),
        "diagnostic" to (run.diagnostic?.let { ToolValue.StringValue(it) } ?: ToolValue.NullValue),
    ),
)

private fun error(code: String, message: String) = ToolResult(
    values = linkedMapOf(
        "ok" to ToolValue.BooleanValue(false),
        "error" to ToolValue.StringValue(code),
        "message" to ToolValue.StringValue(message),
    ),
)
