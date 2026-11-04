package com.cordiact.core.subagent

import com.cordiact.core.conversation.ModelId
import com.cordiact.core.conversation.ModelToolSpec
import com.cordiact.core.conversation.SessionKind
import com.cordiact.core.conversation.SessionManagerService
import com.cordiact.core.plugin.ManagedPlugin
import com.cordiact.core.plugin.PluginSpec
import com.cordiact.core.plugin.PluginTarget
import com.cordiact.core.tool.ToolInvocationContext
import com.cordiact.core.toolloop.ToolLoopStop

const val SUBAGENT_PLUGIN_ID = "core.subagent"
const val SUBAGENT_VERSION = "0.1.0"

/**
 * Capability a Session must already hold to delegate.
 *
 * It could not be an "extra" permission: the child inherits the parent's tools, so a Session that
 * could not run a tool itself must not be able to obtain it by delegating.
 */
const val SUBAGENT_DELEGATE_CAPABILITY = "core.agents.delegate"

const val SUBAGENT_TOOL_ID = "core.subagent.delegate"
const val SUBAGENT_LIST_TOOL_ID = "core.subagent.list-children"

fun subagentPluginSpec() = PluginSpec(
    id = SUBAGENT_PLUGIN_ID,
    version = SUBAGENT_VERSION,
    target = PluginTarget.ANDROID_NATIVE,
    requestedCapabilities = setOf(SUBAGENT_DELEGATE_CAPABILITY),
    dependencies = setOf("core.session-manager", "core.tool-manager"),
)

/** One delegation request. Everything here is data the runtime validates, never a capability. */
data class ChildSpec(
    /** Always the parent's Workspace: v1 has no cross-Workspace delegation. */
    val workspaceId: String,
    val parentSessionId: String,
    /** 3-5 word display label, persisted as the child Session's title. */
    val label: String,
    /** The complete, self-contained task. The child does not see the parent's conversation. */
    val prompt: String,
    /** The model route to use. Null inherits the parent turn's route. */
    val model: ModelId? = null,
    /** Why this Session exists; orchestrated children are distinguishable from one-off delegations. */
    val kind: SessionKind = SessionKind.SUBAGENT,
)

/** What a delegated run produced. A failure is a [stop] value, never an exception. */
data class ChildResult(
    val sessionId: String,
    val label: String,
    /** The child's final text. May be partial when [stop] is not COMPLETED. */
    val text: String,
    val stop: ToolLoopStop,
    val inputTokens: Long? = null,
    val outputTokens: Long? = null,
    /** True when [text] was cut to the result-character budget; the full trace is in the child. */
    val truncated: Boolean = false,
)

/** One direct child, as the parent's own tool sees it. */
data class ChildSessionInfo(
    val sessionId: String,
    val label: String?,
    val createdAtMillis: Long,
    val depth: Int,
)

/**
 * One child turn, handed to the host.
 *
 * The runner is a seam rather than a call into the model module so that the delegation rules
 * (depth, budgets, authorization, session creation) can be verified in a JVM test with a fake
 * runner — the same reason [com.cordiact.core.toolloop.ToolDispatcher] exists.
 */
data class ChildTurnRequest(
    val sessionId: String,
    val model: ModelId,
    val prompt: String,
    val tools: List<ModelToolSpec>,
    val context: ToolInvocationContext,
    val systemContext: String?,
    /** Absolute wall-clock bound inherited from the delegating turn, not a fresh budget. */
    val deadlineAtMillis: Long,
)

/** One child turn's outcome, reported as data rather than through an event stream. */
data class ChildTurnOutcome(
    val text: String,
    val stop: ToolLoopStop,
    val inputTokens: Long? = null,
    val outputTokens: Long? = null,
)

fun interface ChildTurnRunner {
    /** Runs one complete turn in [ChildTurnRequest.sessionId]. Never throws. */
    fun run(request: ChildTurnRequest): ChildTurnOutcome
}

/**
 * Assembles the tool list a child may call.
 *
 * Implemented by the host, which owns the enablement and capability checks; the runtime only
 * removes the delegation tools themselves when the child is at the depth limit.
 */
fun interface ChildToolPlanner {
    fun specs(workspaceId: String, model: ModelId): List<ModelToolSpec>
}

/**
 * Turn context for a child Session, derived from the parent's.
 *
 * Kept as a function so the derivation is one testable place: the child gets the parent's
 * grants verbatim (a subset could silently break a delegated task; a superset would be an
 * escalation) and its own prompt as `requestText`, never the user's words.
 */
fun childContext(
    parent: ToolInvocationContext,
    childSessionId: String,
    prompt: String,
): ToolInvocationContext = parent.copy(
    sessionId = childSessionId,
    requestText = prompt,
    grantedScopes = parent.grantedScopes,
)

interface SubagentRuntimeService {
    /** Delegation depth ceiling; a child at this depth has no delegation tools at all. */
    val maxDepth: Int

    /** Depth of [sessionId]; 0 for a root conversation, -1 when the Session is unknown. */
    fun depthOf(sessionId: String): Int

    /** Whether the Session behind [context] may still delegate. */
    fun canDelegate(context: ToolInvocationContext): Boolean

    /**
     * Runs one child to completion and returns what happened.
     *
     * Blocks: a synchronous parent turn has nowhere to put a later notification. [deadlineAtMillis]
     * is an absolute wall-clock bound, so a nested run cannot reset the budget it inherited.
     */
    fun runChild(spec: ChildSpec, parent: ToolInvocationContext, deadlineAtMillis: Long): ChildResult

    fun listChildren(parentSessionId: String): List<ChildSessionInfo>
}

/** Fixed, non-configurable bounds for in-process delegation. */
data class SubagentLimits(
    /** Root comments may delegate; the depth limit is a separate check on the child's depth. */
    val maxDepth: Int = 1,
    /**
     * Children a single parent Session may create, over its whole life.
     *
     * Must stay above [OrchestrationLimits.maxTotalChildren]: an orchestration fans out under one
     * parent Session, so a lower cap here would abort a plan that passed validation — after
     * paying for the children that already ran.
     */
    val maxChildrenPerSession: Int = 128,
    /** Child result budget flowing back into the parent's tool result. */
    val maxResultChars: Int = 32_000,
    /** A child turn may not outlive this, counted from the delegation call. */
    val childBudgetMillis: Long = 240_000,
) {
    init {
        require(maxDepth >= 0) { "maxDepth must not be negative" }
        require(maxChildrenPerSession > 0) { "maxChildrenPerSession must be positive" }
        require(maxResultChars > 0) { "maxResultChars must be positive" }
        require(childBudgetMillis > 0) { "childBudgetMillis must be positive" }
    }
}
