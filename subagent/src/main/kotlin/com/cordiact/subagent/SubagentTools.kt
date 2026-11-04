package com.cordiact.subagent

import com.cordiact.core.subagent.SUBAGENT_DELEGATE_CAPABILITY
import com.cordiact.core.subagent.SUBAGENT_LIST_TOOL_ID
import com.cordiact.core.subagent.SUBAGENT_PLUGIN_ID
import com.cordiact.core.subagent.SUBAGENT_TOOL_ID
import com.cordiact.core.subagent.ChildSpec
import com.cordiact.core.subagent.SubagentRuntimeService
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
import com.cordiact.core.toolloop.ToolLoopStop

/**
 * Host timeout for one delegation call.
 *
 * Mandatory, not a preference: [com.cordiact.core.tool.ToolManagerService] only leaves the
 * caller's thread when a tool declares a timeout, and the caller is the turn's own thread. An
 * undeclared timeout would freeze the UI for the whole child turn.
 */
private const val DELEGATE_TIMEOUT_MS = 300_000L

fun registerSubagentTools(registry: ToolDefinitionRegistry, runtime: SubagentRuntimeService) {
    registry.register(ToolDefinition(delegateDescriptor(), ToolExecutor { context, arguments ->
        executeDelegate(runtime, context, arguments)
    }))
    registry.register(ToolDefinition(listChildrenDescriptor(), ToolExecutor { context, _ ->
        executeListChildren(runtime, context)
    }))
}

private fun delegateDescriptor() = ToolDescriptor(
    id = SUBAGENT_TOOL_ID,
    name = "delegate_task",
    description = """
        Hand one self-contained task to a subagent that works in its own context, and return its
        final answer.

        Use it for work that is self-contained and would otherwise take over this conversation:
        a scoped investigation, one file's implementation, a focused analysis.

        The subagent does NOT see this conversation and does NOT return its intermediate steps —
        only its final text and its Session id. Put everything it needs into the prompt: the
        goal, the facts you already established, the constraints, and the shape of the answer
        you expect.

        It is synchronous: you get no control back until it finishes. Do not plan "start three,
        then collect them one by one" — you cannot run two delegations at once.

        Not suitable for: follow-ups that need this conversation's context (it cannot see it);
        tasks that must interleave with your own next step.
    """.trimIndent(),
    inputSchema = ToolInputSchema(
        parameters = listOf(
            ToolParameter(
                name = "label",
                type = ToolValueType.STRING,
                required = true,
                description = "3-5 word display name for the delegated task, used in the Session list.",
                maxLength = 120,
            ),
            ToolParameter(
                name = "prompt",
                type = ToolValueType.STRING,
                required = true,
                description = "The complete, self-contained task. The subagent shares none of this " +
                    "conversation's context, so include the goal, the known facts, the constraints " +
                    "and the expected output shape.",
            ),
        ),
    ),
    ownerPluginId = SUBAGENT_PLUGIN_ID,
    defaultEnabled = true,
    requiredCapabilities = setOf(SUBAGENT_DELEGATE_CAPABILITY),
    timeoutMs = DELEGATE_TIMEOUT_MS,
    // Children are separate Sessions with separate transcripts; nothing is shared but the
    // read-only Workspace, whose tools are themselves safe to call concurrently.
    concurrencySafe = true,
)

private fun listChildrenDescriptor() = ToolDescriptor(
    id = SUBAGENT_LIST_TOOL_ID,
    name = "list_children",
    description = """
        List the subagents you delegated in this Session, oldest first, with their Session ids.
        Use it to refer back to earlier delegated work — a Session id is how its full transcript
        stays reachable.
    """.trimIndent(),
    inputSchema = ToolInputSchema(),
    ownerPluginId = SUBAGENT_PLUGIN_ID,
    defaultEnabled = true,
    requiredCapabilities = setOf(SUBAGENT_DELEGATE_CAPABILITY),
    timeoutMs = 10_000L,
    concurrencySafe = true,
)

private fun executeDelegate(
    runtime: SubagentRuntimeService,
    context: ToolInvocationContext,
    arguments: ToolArguments,
): ToolResult {
    val label = (arguments.values["label"] as? ToolValue.StringValue)?.value?.trim().orEmpty()
    val prompt = (arguments.values["prompt"] as? ToolValue.StringValue)?.value?.trim().orEmpty()
    if (label.isEmpty()) return error("invalid_arguments", "label must not be empty")
    if (prompt.isEmpty()) return error("invalid_arguments", "prompt must not be empty")

    val sessionId = context.sessionId
        ?: return error("no_session", "delegate_task requires a calling Session")

    // The deadline is computed once, here, from the host timeout. A nested delegation receives
    // its parent's remaining budget rather than a fresh one, so recursion cannot extend it.
    val deadline = System.currentTimeMillis() + DELEGATE_TIMEOUT_MS

    val result = runtime.runChild(
        spec = ChildSpec(
            workspaceId = context.workspaceId,
            parentSessionId = sessionId,
            label = label,
            prompt = prompt,
        ),
        parent = context,
        deadlineAtMillis = deadline,
    )

    return ToolResult(
        values = linkedMapOf(
            "ok" to ToolValue.BooleanValue(result.stop == ToolLoopStop.COMPLETED),
            "childSessionId" to ToolValue.StringValue(result.sessionId),
            "label" to ToolValue.StringValue(result.label),
            "stop" to ToolValue.StringValue(result.stop.name),
            "text" to ToolValue.StringValue(result.text),
            "truncated" to ToolValue.BooleanValue(result.truncated),
            "note" to ToolValue.StringValue(
                if (result.stop == ToolLoopStop.COMPLETED) {
                    "The subagent's full transcript is in Session ${result.sessionId}."
                } else {
                    "The subagent did not finish (${result.stop}). Any text above is partial."
                },
            ),
        ),
    )
}

private fun executeListChildren(
    runtime: SubagentRuntimeService,
    context: ToolInvocationContext,
): ToolResult {
    val sessionId = context.sessionId
        ?: return error("no_session", "list_children requires a calling Session")
    val children = runtime.listChildren(sessionId)
    return ToolResult(
        values = linkedMapOf(
            "children" to ToolValue.ArrayValue(
                children.map { child ->
                    ToolValue.ObjectValue(
                        linkedMapOf(
                            "sessionId" to ToolValue.StringValue(child.sessionId),
                            "label" to (child.label?.let { ToolValue.StringValue(it) } ?: ToolValue.NullValue),
                            "createdAtMillis" to ToolValue.NumberValue(child.createdAtMillis.toDouble()),
                        ),
                    )
                },
            ),
        ),
    )
}

private fun error(code: String, message: String) = ToolResult(
    values = linkedMapOf(
        "ok" to ToolValue.BooleanValue(false),
        "error" to ToolValue.StringValue(code),
        "message" to ToolValue.StringValue(message),
    ),
)
