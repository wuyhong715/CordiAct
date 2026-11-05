package com.cordiact.subagent

import com.cordiact.core.conversation.ModelId
import com.cordiact.core.conversation.SessionManagerService
import com.cordiact.core.plugin.ManagedPlugin
import com.cordiact.core.plugin.PluginSpec
import com.cordiact.core.subagent.ChildResult
import com.cordiact.core.subagent.ChildSessionInfo
import com.cordiact.core.subagent.ChildSpec
import com.cordiact.core.subagent.ChildToolPlanner
import com.cordiact.core.subagent.ChildTurnRequest
import com.cordiact.core.subagent.ChildTurnRunner
import com.cordiact.core.subagent.SUBAGENT_LIST_TOOL_ID
import com.cordiact.core.subagent.SUBAGENT_TOOL_ID
import com.cordiact.core.subagent.SubagentLimits
import com.cordiact.core.subagent.SubagentRuntimeService
import com.cordiact.core.subagent.childContext
import com.cordiact.core.subagent.subagentPluginSpec
import com.cordiact.core.tool.ToolInvocationContext
import com.cordiact.core.toolloop.ToolLoopStop

/**
 * Runs delegated agents as ordinary Sessions.
 *
 * Every rule that decides whether a child may run lives here rather than in the tool adapter:
 * the depth ceiling, the per-Session child budget, the Workspace equality check and the grant
 * copy. The tool only translates arguments, so a bug in the adapter can produce a wrong task but
 * never an unauthorized one — the split [com.cordiact.core.tool.ToolManagerService] already uses.
 *
 * Synchronous by design. A CordiAct turn is a blocking call, so there is nowhere to deliver a
 * later completion notice; a child outliving its parent's turn would have nobody to report to.
 */
class SubagentRuntime(
    private val sessions: SessionManagerService,
    private val runner: ChildTurnRunner,
    private val toolPlanner: ChildToolPlanner,
    private val systemContextFor: (workspaceId: String) -> String? = { null },
    private val limits: SubagentLimits = SubagentLimits(),
) : ManagedPlugin, SubagentRuntimeService {

    override val spec: PluginSpec = subagentPluginSpec()

    @Volatile
    private var enabled = false

    override fun onEnable() {
        enabled = true
    }

    override fun onDisable() {
        enabled = false
    }

    override fun onUninstall() = onDisable()

    override val maxDepth: Int get() = limits.maxDepth

    override fun depthOf(sessionId: String): Int =
        sessions.getSession(sessionId)?.depth ?: -1

    override fun canDelegate(context: ToolInvocationContext): Boolean {
        val parentId = context.sessionId ?: return false
        val depth = depthOf(parentId)
        return depth >= 0 && depth < limits.maxDepth
    }

    override fun listChildren(parentSessionId: String): List<ChildSessionInfo> =
        sessions.listChildSessions(parentSessionId).map { child ->
            ChildSessionInfo(
                sessionId = child.id,
                label = child.agentLabel ?: child.title.takeIf { it.isNotBlank() },
                createdAtMillis = child.createdAtMillis,
                depth = child.depth,
            )
        }

    override fun runChild(
        spec: ChildSpec,
        parent: ToolInvocationContext,
        deadlineAtMillis: Long,
    ): ChildResult {
        check(enabled) { "Subagent runtime is not enabled" }
        return when (val admission = admit(spec, parent)) {
            is Admission.Refused -> failed(spec, admission.reason)
            is Admission.Admitted -> execute(admission.child, deadlineAtMillis)
        }
    }

    /**
     * Everything that decides whether a child may run, and the child Session itself.
     *
     * Split from [execute] so a background run can be admitted synchronously — the parent needs
     * the child's id before it answers — while the turn itself runs elsewhere. Both paths go
     * through this one function, so depth, Workspace and grant rules cannot diverge between them.
     */
    @Synchronized
    fun admit(spec: ChildSpec, parent: ToolInvocationContext): Admission {
        val parentSessionId = parent.sessionId
            ?: return Admission.Refused("Delegation requires a calling Session")
        val parentSession = sessions.getSession(parentSessionId)
            ?: return Admission.Refused("The calling Session no longer exists")

        // A child may only run where its parent could. The Workspace check is not an
        // optimization: cross-Workspace delegation would need the `@` authorization semantics
        // copied into a second place, and 0003 fixed that authorization to the referencing turn.
        if (spec.workspaceId != parentSession.workspaceId) {
            return Admission.Refused("A delegated agent cannot run in a different Workspace")
        }

        val childDepth = parentSession.depth + 1
        if (childDepth > limits.maxDepth) {
            return Admission.Refused("Delegation depth limit reached (max ${limits.maxDepth})")
        }

        val existing = sessions.listChildSessions(parentSessionId).size
        if (existing >= limits.maxChildrenPerSession) {
            return Admission.Refused(
                "This Session already delegated $existing tasks (max ${limits.maxChildrenPerSession})",
            )
        }

        val model = spec.model ?: parent.modelRoute()
            ?: return Admission.Refused("No model route for the delegated agent")

        val child = try {
            sessions.createChildSession(
                workspaceId = spec.workspaceId,
                parentSessionId = parentSessionId,
                label = spec.label,
                kind = spec.kind,
                depth = childDepth,
            )
        } catch (failure: Exception) {
            return Admission.Refused(failure.message ?: "Could not create the delegated Session")
        }

        // The depth gate has two halves on purpose: hiding the delegation tools keeps a child
        // from planning work it cannot finish; the check in canDelegate is what actually enforces
        // the ceiling if that list is ever wrong.
        val tools = toolPlanner.specs(spec.workspaceId, model)
            .filter { childDepth < limits.maxDepth || it.name !in DELEGATION_TOOL_NAMES }

        return Admission.Admitted(
            AdmittedChild(
                sessionId = child.id,
            label = spec.label,
            request = ChildTurnRequest(
                sessionId = child.id,
                model = model,
                prompt = spec.prompt,
                tools = tools,
                context = childContext(parent, child.id, spec.prompt),
                systemContext = systemContextFor(spec.workspaceId),
                deadlineAtMillis = 0L,
            ),
            ),
        )
    }

    /** Runs an admitted child. Callable from any thread; the runner owns its own synchronisation. */
    fun execute(admitted: AdmittedChild, deadlineAtMillis: Long): ChildResult {        val outcome = try {
            runner.run(admitted.request.copy(deadlineAtMillis = deadlineAtMillis))
        } catch (failure: Exception) {
            // The child Session stays: it holds whatever the child wrote before failing, and the
            // user can open it. Only the result is reported as a failure.
            return ChildResult(
                sessionId = admitted.sessionId,
                label = admitted.label,
                text = failure.message ?: "The delegated turn failed",
                stop = ToolLoopStop.FAILED,
            )
        }

        val (text, truncated) = truncate(outcome.text, limits.maxResultChars)
        return ChildResult(
            sessionId = admitted.sessionId,
            label = admitted.label,
            text = text,
            stop = outcome.stop,
            inputTokens = outcome.inputTokens,
            outputTokens = outcome.outputTokens,
            truncated = truncated,
        )
    }

    /**
     * Prepares another turn in an **existing** child Session.
     *
     * Used by `send_child_message`: the child keeps its transcript, so the follow-up has the
     * earlier work in view. No admission check is repeated because the Session already passed
     * one — but the depth ceiling still applies, since the child's own depth has not changed.
     */
    fun admitExisting(
        childSessionId: String,
        prompt: String,
        parent: ToolInvocationContext,
    ): Admission {
        val parentSessionId = parent.sessionId
            ?: return Admission.Refused("Delegation requires a calling Session")
        val child = sessions.getSession(childSessionId)
            ?: return Admission.Refused("Child Session '$childSessionId' no longer exists")
        // The id is not a capability: a Session from another conversation must not be drivable
        // from this one.
        if (child.parentSessionId != parentSessionId) {
            return Admission.Refused("Child '$childSessionId' belongs to another conversation")
        }
        if (child.depth > limits.maxDepth) {
            return Admission.Refused("Delegation depth limit reached (max ${limits.maxDepth})")
        }
        val model = parent.modelRoute()
            ?: return Admission.Refused("No model route for the delegated agent")

        val tools = toolPlanner.specs(child.workspaceId, model)
            .filter { child.depth < limits.maxDepth || it.name !in DELEGATION_TOOL_NAMES }

        return Admission.Admitted(
            AdmittedChild(
                sessionId = child.id,
                label = child.agentLabel ?: child.title,
                request = ChildTurnRequest(
                    sessionId = child.id,
                    model = model,
                    prompt = prompt,
                    tools = tools,
                    context = childContext(parent, child.id, prompt),
                    systemContext = systemContextFor(child.workspaceId),
                    deadlineAtMillis = 0L,
                ),
            ),
        )
    }

    /** One admitted child: everything needed to run the turn, and nothing that can widen it. */
    data class AdmittedChild(
        val sessionId: String,
        val label: String,
        val request: ChildTurnRequest,
    )

    /** The outcome of [admit]. A refusal carries its reason instead of a flag to read back. */
    sealed interface Admission {
        data class Admitted(val child: AdmittedChild) : Admission
        data class Refused(val reason: String) : Admission
    }

    /** The parent turn's route, carried in its tool context. */
    private fun ToolInvocationContext.modelRoute(): ModelId? {
        val provider = modelProviderId ?: return null
        val model = modelId ?: return null
        return ModelId(provider, model)
    }

    private fun truncate(text: String, maxChars: Int): Pair<String, Boolean> =
        if (text.length <= maxChars) text to false else text.take(maxChars) to true

    private fun failed(spec: ChildSpec, reason: String) = ChildResult(
        sessionId = "",
        label = spec.label,
        text = reason,
        stop = ToolLoopStop.FAILED,
    )

    private companion object {
        /** Tools that delegate. Removed from a child's list once it is at the depth ceiling. */
        val DELEGATION_TOOL_NAMES = setOf(SUBAGENT_TOOL_ID, SUBAGENT_LIST_TOOL_ID)
    }
}
