package com.cordiact.core.toolloop

import com.cordiact.core.conversation.ModelEvent
import com.cordiact.core.conversation.ModelMessage
import com.cordiact.core.conversation.ModelToolCall
import com.cordiact.core.plugin.ManagedPlugin
import com.cordiact.core.plugin.PluginSpec
import com.cordiact.core.plugin.PluginTarget
import com.cordiact.core.tool.ToolInvocationContext

const val TOOL_LOOP_PLUGIN_ID = "core.tool-loop"
const val TOOL_LOOP_VERSION = "0.1.0"

fun toolLoopPluginSpec() = PluginSpec(
    id = TOOL_LOOP_PLUGIN_ID,
    version = TOOL_LOOP_VERSION,
    target = PluginTarget.ANDROID_NATIVE,
    // Dispatch goes through the tool manager, which already owns enablement, ownership and
    // capability checks. The loop must not re-implement any of those.
    dependencies = setOf("core.workspace-manager", "core.tool-manager"),
)

/**
 * Bounds one turn's tool usage.
 *
 * Every field exists because a model can otherwise loop indefinitely: it asks for a tool, gets
 * a result, and asks again. Without a cap the only stopping condition would be the user
 * cancelling, which means a runaway turn costs real money before anyone notices.
 */
data class ToolLoopLimits(
    /** Maximum model round trips. One round trip may request several tools. */
    val maxRounds: Int = 8,
    /** Maximum tool executions across the whole turn. */
    val maxToolCalls: Int = 24,
    /** Characters of a single tool result handed back to the model. */
    val maxResultChars: Int = 32_000,
    /**
     * How many of the most recent tool results stay verbatim.
     *
     * Older results are folded to a one-line stub: a turn with two dozen calls otherwise carries
     * every result in full until it ends, and most of that text stopped mattering immediately.
     * Zero disables folding.
     */
    val foldToolResultsAfter: Int = 6,
) {
    init {
        require(maxRounds > 0) { "maxRounds must be positive" }
        require(maxToolCalls > 0) { "maxToolCalls must be positive" }
        require(maxResultChars > 0) { "maxResultChars must be positive" }
        require(foldToolResultsAfter >= 0) { "foldToolResultsAfter must not be negative" }
    }
}

/** Why the loop stopped. Recorded so a truncated turn is explainable rather than mysterious. */
enum class ToolLoopStop {
    /** The model produced a final answer. */
    COMPLETED,

    /** The round budget ran out; the model was not asked again. */
    ROUND_LIMIT,

    /** The tool-call budget ran out. */
    CALL_LIMIT,

    /** The user cancelled. */
    CANCELLED,

    /** The model or the transport failed. */
    FAILED,
}

/**
 * Applies one turn's tool requests and produces the messages to send back.
 *
 * This is the seam the loop drives: it takes what the model asked for, executes it through the
 * manager, and returns role-correct messages. Keeping dispatch behind an interface lets the
 * loop be verified without a tool manager, and keeps the manager unaware of the loop.
 */
fun interface ToolDispatcher {
    /** Executes one call and returns the tool result message for [call]. Never throws. */
    fun dispatch(context: ToolInvocationContext, call: ModelToolCall): ModelMessage
}

/**
 * Result of a single round: either more tools are needed or the answer arrived.
 *
 * [text] accumulates deltas so the caller still sees streaming output while a tool round is in
 * progress; a turn that ends in tool use must not have shown the user nothing.
 */
data class ToolLoopRound(
    val text: String,
    val calls: List<ModelToolCall>,
    val events: List<ModelEvent>,
)

sealed interface ToolLoopOutcome {
    /** Final answer reached after [rounds] round trips. */
    data class Completed(val text: String, val rounds: Int, val evidence: List<ToolEvidence> = emptyList()) : ToolLoopOutcome

    /** Stopped without a final answer. [reason] says why, [lastText] is whatever was produced. */
    data class Stopped(
        val reason: ToolLoopStop,
        val rounds: Int,
        val lastText: String,
        val evidence: List<ToolEvidence> = emptyList(),
    ) : ToolLoopOutcome
}

/**
 * Host-only evidence a tool produced during the turn.
 *
 * It travels beside the model-facing messages rather than inside them, so persisting it can
 * never change what a provider is asked to serialise.
 */
data class ToolEvidence(
    val toolId: String,
    val metadataJson: String,
)

interface ToolLoopService {
    /**
     * Runs the loop.
     *
     * [send] performs one model round trip with the accumulated messages and returns what it
     * produced. The loop owns the message list from then on, because appending assistant tool
     * requests and their results in the right shape is the part that must be identical for
     * every provider.
     */
    fun run(
        context: ToolInvocationContext,
        initialMessages: List<ModelMessage>,
        dispatcher: ToolDispatcher,
        send: (List<ModelMessage>) -> ToolLoopRound,
    ): ToolLoopOutcome
}

/**
 * Runs model/tool rounds until the model answers or a budget is exhausted.
 *
 * The loop is deliberately provider-agnostic and does not touch storage: it appends messages to
 * the list it is given and hands that list to [ToolLoopService.run]'s callback. Persisting the
 * transcript remains the session layer's job, so a failed round cannot leave a half-written
 * conversation behind.
 */
class ToolLoopPlugin(
    private val limits: ToolLoopLimits = ToolLoopLimits(),
    private val gate: com.cordiact.core.context.ToolOutputGate? = null,
    /**
     * Reports from children and background jobs, delivered at round boundaries.
     *
     * Null in tests and in compositions without background work. The loop is the only place a
     * turn can be extended mid-flight: the model request itself is never interrupted.
     */
    private val inbox: com.cordiact.core.inbox.AgentInbox? = null,
    private val maxReportsPerRound: Int = 8,
    /**
     * Called once reports have actually been handed to a model request.
     *
     * The durable notice queue must be told, or the same report would be delivered again at the
     * head of the next turn. Deliberately not called when the request never left.
     */
    private val onReportsDelivered: ((List<com.cordiact.core.inbox.InboxItem>) -> Unit)? = null,
) : ManagedPlugin, ToolLoopService {

    override val spec: PluginSpec = toolLoopPluginSpec()

    @Volatile
    private var enabled = false

    override fun onEnable() {
        enabled = true
    }

    override fun onDisable() {
        enabled = false
    }

    override fun run(
        context: ToolInvocationContext,
        initialMessages: List<ModelMessage>,
        dispatcher: ToolDispatcher,
        send: (List<ModelMessage>) -> ToolLoopRound,
    ): ToolLoopOutcome {
        check(enabled) { "Tool loop plugin is not enabled" }

        val messages = initialMessages.toMutableList()
        var rounds = 0
        var calls = 0
        var lastText = ""
        val evidence = mutableListOf<ToolEvidence>()

        while (rounds < limits.maxRounds) {
            rounds++

            // Deliver queued reports before the request, so the model sees them in this round
            // rather than only at the head of the next turn. Consumed by drain; put back if the
            // request never leaves, or a child's outcome would be silently lost.
            val delivered = context.sessionId
                ?.let { inbox?.drain(it, maxReportsPerRound) }
                .orEmpty()
            if (delivered.isNotEmpty()) {
                com.cordiact.core.inbox.ChildReportRenderer.render(delivered)?.let {
                    messages += ModelMessage(role = com.cordiact.core.conversation.MessageRole.SYSTEM, content = it)
                }
            }

            // Fold results the model has already moved past, so a long turn does not pay for
            // text it stopped reading several calls ago.
            if (limits.foldToolResultsAfter > 0) {
                val folded = com.cordiact.core.context.ToolResultFolder.fold(messages, limits.foldToolResultsAfter)
                if (folded !== messages) {
                    messages.clear()
                    messages.addAll(folded)
                }
            }

            val round = try {
                send(messages)
            } catch (e: Exception) {
                // A transport or provider failure ends the turn; retrying inside the loop would
                // duplicate whatever the model already streamed.
                if (delivered.isNotEmpty()) inbox?.requeue(delivered)
                return ToolLoopOutcome.Stopped(ToolLoopStop.FAILED, rounds, lastText, evidence.toList())
            }

            // The request left, so the reports are genuinely delivered. Only now may the durable
            // queue drop them; acknowledging earlier would lose a report the model never saw.
            if (delivered.isNotEmpty()) {
                runCatching { onReportsDelivered?.invoke(delivered) }
            }
            lastText = round.text.ifBlank { lastText }

            if (round.calls.isEmpty()) {
                return ToolLoopOutcome.Completed(lastText, rounds, evidence.toList())
            }

            // Record what the model asked for before the results, or the next request has tool
            // messages with no request to answer.
            messages += ModelMessage(
                role = com.cordiact.core.conversation.MessageRole.ASSISTANT,
                content = round.text,
                toolCalls = round.calls,
            )

            for (call in round.calls) {
                if (calls >= limits.maxToolCalls) {
                    messages += ModelMessage(
                        role = com.cordiact.core.conversation.MessageRole.TOOL,
                        content = "Tool call limit reached; no further tools will run this turn.",
                        toolCallId = call.id,
                        toolName = call.name,
                    )
                    continue
                }
                calls++
                context.onEvent?.invoke(
                    com.cordiact.core.conversation.ModelEvent.ToolCallStarted(
                        callId = call.id,
                        toolName = call.name,
                        argumentsJson = call.argumentsJson,
                    )
                )
                val startMs = System.currentTimeMillis()
                val result = dispatcher.dispatch(context, call)
                val duration = System.currentTimeMillis() - startMs
                val isSuccess = !result.content.startsWith("Error:") && !result.content.startsWith("{\"error\"")
                context.onEvent?.invoke(
                    com.cordiact.core.conversation.ModelEvent.ToolCallCompleted(
                        callId = call.id,
                        toolName = call.name,
                        success = isSuccess,
                        durationMillis = duration,
                        summary = result.content.take(120),
                    )
                )
                result.metadataJson?.let { evidence += ToolEvidence(call.name, it) }
                val targetSessionId = context.sessionId ?: "ephemeral"
                val gatedResult = gate?.gate(targetSessionId, result, limits.maxResultChars)
                    ?: if (result.content.length > limits.maxResultChars) {
                        result.copy(
                            content = result.content.substring(0, limits.maxResultChars) +
                                "\n...[truncated ${result.content.length - limits.maxResultChars} chars]"
                        )
                    } else result
                messages += gatedResult
            }

            if (calls >= limits.maxToolCalls) {
                return ToolLoopOutcome.Stopped(ToolLoopStop.CALL_LIMIT, rounds, lastText, evidence.toList())
            }
        }

        return ToolLoopOutcome.Stopped(ToolLoopStop.ROUND_LIMIT, rounds, lastText, evidence.toList())
    }
}
