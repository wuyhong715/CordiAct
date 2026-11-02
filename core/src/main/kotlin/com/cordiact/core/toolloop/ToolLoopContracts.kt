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
) {
    init {
        require(maxRounds > 0) { "maxRounds must be positive" }
        require(maxToolCalls > 0) { "maxToolCalls must be positive" }
        require(maxResultChars > 0) { "maxResultChars must be positive" }
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
    data class Completed(val text: String, val rounds: Int) : ToolLoopOutcome

    /** Stopped without a final answer. [reason] says why, [lastText] is whatever was produced. */
    data class Stopped(
        val reason: ToolLoopStop,
        val rounds: Int,
        val lastText: String,
    ) : ToolLoopOutcome
}

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

        while (rounds < limits.maxRounds) {
            rounds++
            val round = try {
                send(messages)
            } catch (e: Exception) {
                // A transport or provider failure ends the turn; retrying inside the loop would
                // duplicate whatever the model already streamed.
                return ToolLoopOutcome.Stopped(ToolLoopStop.FAILED, rounds, lastText)
            }
            lastText = round.text.ifBlank { lastText }

            if (round.calls.isEmpty()) {
                return ToolLoopOutcome.Completed(lastText, rounds)
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
                    )
                    continue
                }
                calls++
                messages += dispatcher.dispatch(context, call)
            }

            if (calls >= limits.maxToolCalls) {
                return ToolLoopOutcome.Stopped(ToolLoopStop.CALL_LIMIT, rounds, lastText)
            }
        }

        return ToolLoopOutcome.Stopped(ToolLoopStop.ROUND_LIMIT, rounds, lastText)
    }
}
