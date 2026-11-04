package com.cordiact.subagent

import com.cordiact.core.subagent.ChildResult
import com.cordiact.core.subagent.ChildSpec
import com.cordiact.core.subagent.Combine
import com.cordiact.core.subagent.OrchestrationLimits
import com.cordiact.core.subagent.OrchestrationPlan
import com.cordiact.core.subagent.OrchestrationResult
import com.cordiact.core.subagent.OrchestrationStage
import com.cordiact.core.subagent.OrchestrationStop
import com.cordiact.core.subagent.StageMode
import com.cordiact.core.subagent.StageOutcome
import com.cordiact.core.conversation.SessionKind
import com.cordiact.core.subagent.SubagentRuntimeService

import com.cordiact.core.tool.ToolInvocationContext
import com.cordiact.core.toolloop.ToolLoopStop
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import java.util.concurrent.Callable
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit

/**
 * Executes a plan stage by stage.
 *
 * Two properties are worth stating because everything else follows from them:
 *
 * 1. **Stages are barriers.** Every child of a stage settles before the next stage starts. A
 *    `REDUCE` therefore always sees a complete previous stage, and a partial fan-out cannot leak
 *    into a summarisation that then reports a confident total.
 * 2. **A failed child becomes a `null` output, not a failed run.** One flaky item must not throw
 *    away twenty good ones; the failure is reported positionally so the caller can see which.
 *    A *stage-level* fault (no items, a plan that could not be built) is different and aborts.
 *
 * Concurrency is a fixed pool of [OrchestrationLimits.maxConcurrentChildren]; each task blocks in
 * [SubagentRuntimeService.runChild], which is the shape the delegation runtime already has.
 */
class OrchestrationRunner(
    private val runtime: SubagentRuntimeService,
    private val limits: OrchestrationLimits = OrchestrationLimits(),
) {

    /**
     * Runs [plan] to completion.
     *
     * [deadlineAtMillis] is the absolute end of the whole run; every child gets what is left of it
     * rather than a fresh budget, so a long stage cannot extend the run.
     */
    fun run(
        plan: OrchestrationPlan,
        parent: ToolInvocationContext,
        deadlineAtMillis: Long,
    ): OrchestrationResult {
        val parentSessionId = requireNotNull(parent.sessionId) {
            "Orchestration requires a calling Session"
        }

        if (!runtime.canDelegate(parent)) {
            return failed(plan, "Delegation is not available for this Session (depth limit reached)")
        }

        var started = 0
        var inputTokens: Long? = null
        var outputTokens: Long? = null
        val outcomes = mutableListOf<StageOutcome>()
        var previousOutputs: List<String?> = emptyList()

        for (stage in plan.stages) {
            val remaining = deadlineAtMillis - System.currentTimeMillis()
            if (remaining <= 0) {
                return failed(
                    plan,
                    "Wall-clock budget exhausted before stage '${stage.id}'",
                    stepsSoFar = outcomes,
                    started = started,
                )
            }

            val stageOutcome = try {
                runStage(stage, plan, parent, parentSessionId, previousOutputs, remaining)
            } catch (failure: Exception) {
                return failed(
                    plan,
                    "Stage '${stage.id}' could not run: ${failure.message ?: failure::class.simpleName}",
                    stepsSoFar = outcomes,
                    started = started,
                )
            }

            outcomes += stageOutcome
            started += stage.childCount
            inputTokens = inputTokens.plus(stageOutcome.tokenInput)
            outputTokens = outputTokens.plus(stageOutcome.tokenOutput)
            previousOutputs = stageOutcome.outputs
        }

        val failedChildren = outcomes.sumOf { it.failedCount }
        return OrchestrationResult(
            planName = plan.name,
            stages = outcomes,
            childrenStarted = started,
            inputTokens = inputTokens,
            outputTokens = outputTokens,
            stop = if (failedChildren == 0) OrchestrationStop.COMPLETED else OrchestrationStop.PARTIAL,
        )
    }

    /** One stage's children, run on a bounded pool, returned in item order. */
    private fun runStage(
        stage: OrchestrationStage,
        plan: OrchestrationPlan,
        parent: ToolInvocationContext,
        parentSessionId: String,
        previousOutputs: List<String?>,
        remainingMillis: Long,
    ): StageOutcome {
        val prompts: List<String> = when (stage.mode) {
            StageMode.MAP -> stage.items.ifEmpty { listOf("") }.map { item ->
                stage.task.replace(OrchestrationPlanParser.ITEM_PLACEHOLDER, item)
            }

            StageMode.REDUCE -> listOf(
                stage.task.replace(
                    OrchestrationPlanParser.PREV_PLACEHOLDER,
                    combinePrevious(previousOutputs, stage.combine),
                ),
            )
        }

        val deadline = System.currentTimeMillis() + remainingMillis
        val pool = newPool(minOf(limits.maxConcurrentChildren, prompts.size))
        try {
            val tasks: List<Future<ChildResult>> = prompts.mapIndexed { index, prompt ->
                pool.submit(
                    Callable {
                        runtime.runChild(
                            spec = ChildSpec(
                                workspaceId = parent.workspaceId,
                                parentSessionId = parentSessionId,
                                label = childLabel(plan, stage, index, prompts.size),
                                prompt = prompt,
                                model = null,
                                kind = SessionKind.ORCHESTRATION,
                            ),
                            parent = parent,
                            deadlineAtMillis = deadline,
                        )
                    },
                )
            }
            // Reading the futures in submit order keeps outputs aligned with items, whatever
            // order they actually finished in.
            val results = tasks.map { future ->
                try {
                    future.get(remainingMillis.coerceAtLeast(1), TimeUnit.MILLISECONDS)
                } catch (failure: Exception) {
                    // A task that never produced a ChildResult is a child failure like any other;
                    // it must occupy its slot in the output list, not shift the others.
                    null
                }
            }

            // A child that failed is `null` even when it produced partial text: downstream a
            // reducer would otherwise fold a half-finished answer into a confident total, and
            // the only trace of it would be an `error` string nobody is obliged to read.
            val outputs = results.map { result ->
                if (result != null && result.stop == ToolLoopStop.COMPLETED) {
                    result.text.takeIf { it.isNotBlank() }
                } else {
                    null
                }
            }
            val errors = results.map { result ->
                when {
                    result == null -> "the delegated child did not report a result"
                    result.stop == ToolLoopStop.COMPLETED -> null
                    else -> "child stopped: ${result.stop}" +
                        (result.text.takeIf { it.isNotBlank() }?.let { " — partial text: ${it.take(200)}" } ?: "")
                }
            }
            return StageOutcome(
                stageId = stage.id,
                outputs = outputs,
                errors = errors,
                tokenInput = results.filterNotNull().sumOf { it.inputTokens ?: 0L },
                tokenOutput = results.filterNotNull().sumOf { it.outputTokens ?: 0L },
                childSessionIds = results.map { it?.sessionId.orEmpty() },
            )
        } finally {
            pool.shutdownNow()
        }
    }

    /**
     * Renders a previous stage for `{{prev}}`.
     *
     * `JSON` keeps the per-item structure — a reducer can then say "item 3 failed" rather than
     * paging through prose. Failed items are `null`, which is how the caller can tell a missing
     * answer from an empty one.
     */
    private fun combinePrevious(outputs: List<String?>, combine: Combine): String {
        val rendered = when (combine) {
            Combine.JSON -> {
                val array = JsonArray()
                outputs.forEach { output ->
                    if (output == null) array.add(com.google.gson.JsonNull.INSTANCE) else array.add(output)
                }
                JsonObject().apply { add("results", array) }.toString()
            }

            Combine.LINES -> outputs.filterNotNull().joinToString("\n\n")
        }
        if (rendered.length <= limits.maxCombineChars) return rendered
        return rendered.take(limits.maxCombineChars) +
            "\n\n[truncated: the previous stage produced ${rendered.length} characters]"
    }

    /** `<plan>/<stage>/<n>`: a fan-out of forty children stays readable in a Session list. */
    private fun childLabel(
        plan: OrchestrationPlan,
        stage: OrchestrationStage,
        index: Int,
        count: Int,
    ): String {
        val suffix = if (count > 1) "/${index + 1}" else ""
        return "${plan.name}/${stage.id}$suffix".take(120)
    }

    private fun newPool(threads: Int): ExecutorService =
        Executors.newFixedThreadPool(threads.coerceAtLeast(1)) { runnable ->
            Thread(runnable, "cordiact-orchestration").apply { isDaemon = true }
        }

    private fun failed(
        plan: OrchestrationPlan,
        message: String,
        stepsSoFar: List<StageOutcome> = emptyList(),
        started: Int = 0,
    ) = OrchestrationResult(
        planName = if (plan.name.isBlank()) "unnamed" else plan.name,
        stages = stepsSoFar,
        childrenStarted = started,
        stop = OrchestrationStop.FAILED,
        message = message,
    )

    private fun Long?.plus(other: Long): Long = (this ?: 0L) + other
}
