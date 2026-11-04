package com.cordiact.subagent

import com.cordiact.core.plugin.ManagedPlugin
import com.cordiact.core.plugin.PluginSpec
import com.cordiact.core.subagent.ORCHESTRATION_PLUGIN_ID
import com.cordiact.core.subagent.OrchestrationLimits
import com.cordiact.core.subagent.OrchestrationPlan
import com.cordiact.core.subagent.OrchestrationResult
import com.cordiact.core.subagent.OrchestrationRejected
import com.cordiact.core.subagent.OrchestrationService
import com.cordiact.core.subagent.SubagentRuntimeService
import com.cordiact.core.subagent.orchestrationPluginSpec
import com.cordiact.core.tool.ToolInvocationContext

/**
 * The orchestration plugin: plan validation plus bounded fan-out, over the delegation runtime.
 *
 * A separate plugin rather than a mode of `core.subagent` because it is a different risk to turn
 * off: delegation is one child per call, an orchestration is up to sixty-four. The dependency
 * declaration makes the second unusable without the first, which is the only correct ordering.
 */
class OrchestrationPlugin(
    private val subagents: SubagentRuntimeService,
    private val limits: OrchestrationLimits = OrchestrationLimits(),
) : ManagedPlugin, OrchestrationService {

    override val spec: PluginSpec = orchestrationPluginSpec()

    private val runner = OrchestrationRunner(subagents, limits)

    @Volatile
    private var enabled = false

    override fun onEnable() {
        enabled = true
    }

    override fun onDisable() {
        enabled = false
    }

    override fun onUninstall() = onDisable()

    override fun limits(): OrchestrationLimits = limits

    override fun run(
        planJson: String,
        parent: ToolInvocationContext,
        deadlineAtMillis: Long,
    ): OrchestrationResult {
        check(enabled) { "Orchestration plugin '$ORCHESTRATION_PLUGIN_ID' is not enabled" }
        // Parsed here, not in the adapter, so every caller gets the same validation and the
        // "rejected before the first child" property holds for all of them.
        val plan: OrchestrationPlan = try {
            OrchestrationPlanParser.parse(planJson, limits)
        } catch (failure: OrchestrationPlanException) {
            throw OrchestrationRejected(failure.message ?: "The plan is not valid")
        }
        return runner.run(plan, parent, deadlineAtMillis)
    }
}
