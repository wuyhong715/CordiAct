package com.cordiact.subagent

import com.cordiact.core.subagent.ORCHESTRATION_CAPABILITY
import com.cordiact.core.subagent.ORCHESTRATION_PLUGIN_ID
import com.cordiact.core.subagent.ORCHESTRATION_TOOL_ID
import com.cordiact.core.subagent.OrchestrationLimits
import com.cordiact.core.subagent.OrchestrationRejected
import com.cordiact.core.subagent.OrchestrationResult
import com.cordiact.core.subagent.OrchestrationService
import com.cordiact.core.subagent.OrchestrationStop
import com.cordiact.core.subagent.StageOutcome
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

fun registerOrchestrationTools(registry: ToolDefinitionRegistry, service: OrchestrationService) {
    val limits = service.limits()
    registry.register(ToolDefinition(orchestrateDescriptor(limits), ToolExecutor { context, arguments ->
        executeOrchestrate(service, context, arguments, limits)
    }))
}

private fun orchestrateDescriptor(limits: OrchestrationLimits) = ToolDescriptor(
    id = ORCHESTRATION_TOOL_ID,
    name = "orchestrate",
    description = """
        Fan one task out across many subagents and return every result, using a multi-stage plan.

        Use it when the work splits into independent pieces you want handled together — audit
        every file in a directory, research several competitors, check ten claims — instead of
        delegating them one at a time. Unlike delegate_task, this starts children in parallel.

        The plan is JSON:
        {"name":"kebab-case-name","stages":[
          {"id":"scan","task":"Investigate {{item}} pricing.","items":["A","B","C"]},
          {"id":"sum","mode":"REDUCE","task":"Compare these results: {{prev}}"}
        ]}

        Stage rules:
        - MAP (default): one subagent per item, {{item}} replaced by that item.
          Omit `items` to run a single child.
        - REDUCE: one subagent over the previous stage's outputs, {{prev}} replaced.
          Its `combine` is JSON (default, an array with nulls for failures) or LINES.
        - Stages run in order; a stage starts only after the previous one finishes.

        Failures are per item: a child that fails yields null and a diagnostic, and the run
        continues. Check `errors` before trusting a total.

        Each child has its own Session — readable later, and not part of this conversation. It
        never sees this conversation, so every task must be self-contained.

        Limits: ${limits.maxStages} stages, ${limits.maxItemsPerStage} items per stage,
        ${limits.maxTotalChildren} children per run, ${limits.maxConcurrentChildren} at once.
    """.trimIndent(),
    inputSchema = ToolInputSchema(
        parameters = listOf(
            ToolParameter(
                name = "plan",
                type = ToolValueType.STRING,
                required = true,
                description = "The plan as a JSON string. See the tool description for the shape.",
            ),
        ),
    ),
    ownerPluginId = ORCHESTRATION_PLUGIN_ID,
    defaultEnabled = true,
    requiredCapabilities = setOf(ORCHESTRATION_CAPABILITY),
    // Always declared: the tool manager runs an untimed tool on the caller's thread, and this
    // tool blocks for the whole run.
    timeoutMs = limits.toolTimeoutMillis,
    concurrencySafe = true,
)

private fun executeOrchestrate(
    service: OrchestrationService,
    context: ToolInvocationContext,
    arguments: ToolArguments,
    limits: OrchestrationLimits,
): ToolResult {
    val raw = (arguments.values["plan"] as? ToolValue.StringValue)?.value?.trim().orEmpty()
    if (raw.isEmpty()) return error("invalid_arguments", "plan must not be empty")

    val sessionId = context.sessionId
        ?: return error("no_session", "orchestrate requires a calling Session")

    // Parsed and validated before anything starts: a malformed plan must cost zero children.
    val result = try {
        service.run(
            planJson = raw,
            parent = context,
            deadlineAtMillis = System.currentTimeMillis() + limits.wallClockMillis,
        )
    } catch (rejected: OrchestrationRejected) {
        return error("invalid_plan", rejected.message ?: "The plan is not valid")
    }

    return ToolResult(values = render(result))
}

/** Renders a run for the model, bounded per child so a wide fan-out cannot flood the turn. */
private fun render(result: OrchestrationResult): Map<String, ToolValue> {
    val values = linkedMapOf<String, ToolValue>(
        "ok" to ToolValue.BooleanValue(result.stop != OrchestrationStop.FAILED),
        "plan" to ToolValue.StringValue(result.planName),
        "stop" to ToolValue.StringValue(result.stop.name),
        "childrenStarted" to ToolValue.NumberValue(result.childrenStarted.toDouble()),
        "stages" to ToolValue.ArrayValue(result.stages.map(::renderStage)),
    )
    result.inputTokens?.let { values["inputTokens"] = ToolValue.NumberValue(it.toDouble()) }
    result.outputTokens?.let { values["outputTokens"] = ToolValue.NumberValue(it.toDouble()) }
    result.message?.let { values["message"] = ToolValue.StringValue(it) }
    return values
}

private fun renderStage(stage: StageOutcome): ToolValue = ToolValue.ObjectValue(
    linkedMapOf(
        "id" to ToolValue.StringValue(stage.stageId),
        "failed" to ToolValue.NumberValue(stage.failedCount.toDouble()),
        "results" to ToolValue.ArrayValue(
            stage.outputs.mapIndexed { index, output ->
                ToolValue.ObjectValue(
                    linkedMapOf<String, ToolValue>(
                        // Position, so a caller can align a result with the item it came from.
                        "index" to ToolValue.NumberValue(index.toDouble()),
                        "output" to (output?.let { ToolValue.StringValue(it) } ?: ToolValue.NullValue),
                        "error" to (
                            stage.errors.getOrNull(index)?.let { ToolValue.StringValue(it) }
                                ?: ToolValue.NullValue
                            ),
                        "sessionId" to (
                            stage.childSessionIds.getOrNull(index)?.takeIf { it.isNotBlank() }
                                ?.let { ToolValue.StringValue(it) } ?: ToolValue.NullValue
                            ),
                    ),
                )
            },
        ),
    ),
)

private fun error(code: String, message: String) = ToolResult(
    values = linkedMapOf(
        "ok" to ToolValue.BooleanValue(false),
        "error" to ToolValue.StringValue(code),
        "message" to ToolValue.StringValue(message),
    ),
)
