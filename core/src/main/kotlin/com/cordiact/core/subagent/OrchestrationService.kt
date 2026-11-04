package com.cordiact.core.subagent

import com.cordiact.core.tool.ToolInvocationContext

/**
 * Runs plans.
 *
 * An interface rather than a concrete call so the tool adapter can be verified against a fake
 * plan runner: the interesting tool behaviour is plan rejection and result rendering, not
 * scheduling, and a test that has to schedule real children to check a rejection message is a
 * test that will be slow and flaky.
 */
interface OrchestrationService {
    fun limits(): OrchestrationLimits

    /** Parses, validates and runs [planJson]. Throws [OrchestrationRejected] when the plan is bad. */
    fun run(planJson: String, parent: ToolInvocationContext, deadlineAtMillis: Long): OrchestrationResult
}

/**
 * A plan that was refused.
 *
 * Distinct from a failed run: nothing started, so the caller can report a correction rather than
 * a partial result. Always raised before the first child.
 */
class OrchestrationRejected(message: String) : IllegalArgumentException(message)
