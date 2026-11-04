package com.cordiact.core.subagent

import com.cordiact.core.plugin.PluginSpec
import com.cordiact.core.plugin.PluginTarget

const val ORCHESTRATION_PLUGIN_ID = "core.subagent-orchestration"
const val ORCHESTRATION_VERSION = "0.1.0"
const val ORCHESTRATION_TOOL_ID = "core.orchestration.run"

/**
 * Capability an orchestrated run needs.
 *
 * Separate from delegation's capability because the two are different risks: delegation is one
 * child per call and a human reads the result, while an orchestration is up to sixty-four children
 * per call and nobody reads them one by one.
 */
const val ORCHESTRATION_CAPABILITY = "core.agents.orchestrate"

fun orchestrationPluginSpec() = PluginSpec(
    id = ORCHESTRATION_PLUGIN_ID,
    version = ORCHESTRATION_VERSION,
    target = PluginTarget.ANDROID_NATIVE,
    requestedCapabilities = setOf(ORCHESTRATION_CAPABILITY),
    // Built on the delegation runtime, not beside it: a plan's children go through the same
    // admission checks, so the depth ceiling and the grant copy apply unchanged.
    dependencies = setOf("core.subagent"),
)

/** How a stage turns its inputs into children. */
enum class StageMode {
    /** One child per item; `{{item}}` is replaced by that item. */
    MAP,

    /** One child over the joined output of the previous stage; `{{prev}}` is replaced. */
    REDUCE,
}

/** How a REDUCE stage's `{{prev}}` is rendered. */
enum class Combine {
    /** A JSON array of the previous stage's outputs, with `null` for failed items. */
    JSON,

    /** The non-null outputs joined by a blank line. */
    LINES,
}

/**
 * One declared stage.
 *
 * `items` is empty for a REDUCE stage and for a MAP stage that wants a single child; the two are
 * told apart by [mode], never by the list being empty, because "one item" and "reduce everything"
 * are different intentions that happen to produce the same child count.
 */
data class OrchestrationStage(
    /** Unique within the plan. Used in the child's Session title so a fan-out stays readable. */
    val id: String,
    /** Prompt template. `{{item}}` in a MAP stage, `{{prev}}` in a REDUCE stage. */
    val task: String,
    val items: List<String> = emptyList(),
    val mode: StageMode = StageMode.MAP,
    val combine: Combine = Combine.JSON,
) {
    /** How many children this stage starts. */
    val childCount: Int get() = when (mode) {
        StageMode.REDUCE -> 1
        // An empty MAP stage is a single child: mapping over nothing would start zero children,
        // which is never what a stage was written for.
        StageMode.MAP -> items.size.coerceAtLeast(1)
    }
}

data class OrchestrationPlan(
    /** Kebab-case name; prefixes every child Session title. */
    val name: String,
    val stages: List<OrchestrationStage>,
) {
    /** Total children this plan would start. Validated before anything runs. */
    val childCount: Int get() = stages.sumOf { it.childCount }
}

/** One stage's outcome: outputs are positional, so callers can align them with the items. */
data class StageOutcome(
    val stageId: String,
    /** Per-child text, `null` where that child failed. */
    val outputs: List<String?>,
    /** Per-child diagnostic, aligned with [outputs]; `null` where the child succeeded. */
    val errors: List<String?>,
    /**
     * Per-child Session ids, aligned with [outputs].
     *
     * Carried because a fan-out's value is partly that each child's full transcript stays
     * reachable: the outputs here are capped, the Sessions are not.
     */
    val childSessionIds: List<String> = emptyList(),
    val tokenInput: Long = 0L,
    val tokenOutput: Long = 0L,
) {
    val failedCount: Int get() = outputs.count { it == null }
}

/** Why an orchestration ended. */
enum class OrchestrationStop {
    /** Every child completed. */
    COMPLETED,

    /** At least one child failed; the run finished anyway, so the successful outputs are usable. */
    PARTIAL,

    /** Nothing ran: the plan was rejected, or no child could start. */
    FAILED,
}

data class OrchestrationResult(
    val planName: String,
    val stages: List<StageOutcome>,
    /** Children actually started, which is what the run costs. */
    val childrenStarted: Int,
    val inputTokens: Long? = null,
    val outputTokens: Long? = null,
    val stop: OrchestrationStop,
    /** Present when [stop] is FAILED. */
    val message: String? = null,
)

/**
 * Fixed bounds for one orchestration.
 *
 * All of them are host constants rather than plan arguments: a plan that could raise its own
 * ceiling would be the model deciding how much of the user's money to spend.
 */
data class OrchestrationLimits(
    val maxStages: Int = 8,
    /** Items in a single MAP stage. */
    val maxItemsPerStage: Int = 32,
    /** Children in one run — the runaway backstop, not a feature. */
    val maxTotalChildren: Int = 64,
    /** Children running at once. */
    val maxConcurrentChildren: Int = 4,
    /**
     * Whole-run wall clock; each child gets the run's remaining budget, never a fresh one.
     *
     * Capped by the tool manager, not by preference: a tool's host timeout must stay within
     * `1..300_000` ms, and [toolTimeoutMillis] adds room to report after the run ends. Four
     * minutes of orchestration plus one minute of slack is the largest that fits.
     */
    val wallClockMillis: Long = 240_000,
    /** Characters of previous-stage output substituted into `{{prev}}`. */
    val maxCombineChars: Int = 100_000,
    /** Characters of one child's text carried back in the tool result. */
    val maxOutputChars: Int = 4_000,
) {
    init {
        require(maxStages > 0) { "maxStages must be positive" }
        require(maxItemsPerStage > 0) { "maxItemsPerStage must be positive" }
        require(maxTotalChildren > 0) { "maxTotalChildren must be positive" }
        require(maxConcurrentChildren > 0) { "maxConcurrentChildren must be positive" }
        require(wallClockMillis > 0) { "wallClockMillis must be positive" }
        require(maxCombineChars > 0) { "maxCombineChars must be positive" }
        require(maxOutputChars > 0) { "maxOutputChars must be positive" }
        // Fail here rather than at registration: the registry rejects an out-of-range timeout,
        // and discovering that when the app boots is worse than discovering it in a unit test.
        require(toolTimeoutMillis <= MAX_TOOL_TIMEOUT_MS) {
            "wallClockMillis is too large: the tool timeout (wallClock + 60s) must stay within " +
                "1..$MAX_TOOL_TIMEOUT_MS ms"
        }
    }

    /** The tool's host timeout: the run's own budget plus room to finish reporting. */
    val toolTimeoutMillis: Long get() = wallClockMillis + 60_000

    private companion object {
        /** Mirrors `ToolManagerService`'s ceiling; core owns the constant, this module cannot see it. */
        const val MAX_TOOL_TIMEOUT_MS = 300_000L
    }
}
