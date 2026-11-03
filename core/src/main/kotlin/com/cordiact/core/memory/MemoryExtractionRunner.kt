package com.cordiact.core.memory

import com.cordiact.core.conversation.MessageRole
import com.cordiact.core.conversation.ModelCall
import com.cordiact.core.conversation.ModelEvent
import com.cordiact.core.conversation.ModelGenerationRequest
import com.cordiact.core.conversation.ModelId
import com.cordiact.core.conversation.ModelMessage
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Where the extractor's model selection comes from. No default: extraction is opt-in per user choice. */
fun interface ExtractionModelSelection {
    /** Returns the chosen model, or null when the user has not picked one. */
    fun selectedModel(workspaceId: String): ModelId?
}

/** Turns the filtered turns into the prompt the extractor model receives. */
object ExtractionPrompt {

    /**
     * Instructions for the extractor.
     *
     * Phrased as a contract with an exact output shape rather than a description of a task,
     * because the parser rejects anything that is not the documented JSON object. A model that
     * is asked politely for "a summary" produces prose; a model given a schema produces JSON.
     */
    val SYSTEM = """
        You extract durable memory from a conversation tail.

        Rules:
        - Record only what the user stated or what tool output demonstrated.
        - Do not record your own inferences, plans or opinions.
        - Prefer durable facts, decisions and preferences over transient details.
        - Never include credentials, tokens or keys.

        Reply with a single JSON object and nothing else:
        {"raw_memory":"<detailed notes>","rollout_summary":"<one line routing summary>","rollout_slug":"<optional stable id>"}
    """.trimIndent()

    fun render(turns: List<SessionTurn>): String = buildString {
        appendLine("Conversation tail follows.")
        appendLine()
        for (turn in turns) {
            val label = when (turn.role) {
                MessageRole.USER -> "user"
                MessageRole.TOOL -> "tool"
                MessageRole.ASSISTANT -> "assistant"
                MessageRole.SYSTEM -> "system"
            }
            append("[").append(label).appendLine("]")
            appendLine(turn.content)
            appendLine()
        }
    }
}

/** What one extraction attempt produced. */
sealed interface ExtractionOutcome {
    data class Extracted(val result: ExtractionResult) : ExtractionOutcome

    /** Nothing worth learning from; not a failure. */
    data object NoContent : ExtractionOutcome

    data class Deferred(val reason: String) : ExtractionOutcome

    data class Failed(val reason: String, val retryable: Boolean) : ExtractionOutcome
}

/**
 * Runs Phase 1 for one session: read, filter, call the model, validate, redact.
 *
 * Every step that can reject does so before anything is written, and the model call is the
 * only expensive part, so the filter runs first. Nothing here writes to disk: the result is
 * handed to the consolidation side, which is what makes Phase 1 restartable.
 */
class MemoryExtractionRunner(
    private val loadMessages: (sessionId: String) -> List<SessionTurn>,
    private val generate: (ModelGenerationRequest, (ModelEvent) -> Unit) -> ModelCall,
    private val selection: ExtractionModelSelection,
    private val policy: MemorySchedulingPolicy = MemorySchedulingPolicy(),
    private val timeoutMillis: Long = DEFAULT_TIMEOUT_MILLIS,
    private val maxTurns: Int = DEFAULT_MAX_TURNS,
) {

    fun extract(
        workspaceId: String,
        sessionId: String,
        conditions: DeviceConditions,
    ): ExtractionOutcome {
        val model = selection.selectedModel(workspaceId)
            ?: return ExtractionOutcome.Deferred("No extraction model selected")

        // Conditions are checked before the model call, which is the only costly step.
        val decision = policy.evaluate(conditions, modelSelected = true, needsNetwork = true)
        if (decision is SchedulingDecision.Defer) {
            return ExtractionOutcome.Deferred(decision.detail)
        }

        val turns = try {
            loadMessages(sessionId)
        } catch (e: Exception) {
            return ExtractionOutcome.Failed("Could not read the session: ${e.message}", retryable = true)
        }

        // Filtering before the call is both cheaper and safer: injected blocks never reach the
        // model, so it cannot learn from content CordiAct itself produced.
        val filtered = ExtractionInputFilter.filter(turns).takeLast(maxTurns)
        if (filtered.isEmpty()) {
            return ExtractionOutcome.NoContent
        }

        val reply = StringBuilder()
        val failure = AtomicReference<ModelEvent.Failed?>(null)
        val done = CountDownLatch(1)
        val call = try {
            generate(
                ModelGenerationRequest(
                    sessionId = sessionId,
                    model = model,
                    messages = buildMessages(filtered),
                ),
            ) { event ->
                when (event) {
                    is ModelEvent.Delta -> reply.append(event.text)
                    // Extraction is a plain completion: a model that asks for tools here has
                    // misunderstood the prompt, so the request is ignored and the round ends
                    // on Completed as usual.
                    is ModelEvent.ToolCallsRequested -> Unit
                    is ModelEvent.Completed -> done.countDown()
                    is ModelEvent.Failed -> {
                        failure.set(event)
                        done.countDown()
                    }
                }
            }
        } catch (e: Exception) {
            return ExtractionOutcome.Failed("Model call could not start: ${e.message}", retryable = true)
        }

        try {
            if (!done.await(timeoutMillis, TimeUnit.MILLISECONDS)) {
                call.cancel()
                return ExtractionOutcome.Failed("Extraction timed out after $timeoutMillis ms", retryable = true)
            }
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            call.cancel()
            return ExtractionOutcome.Failed("Extraction was interrupted", retryable = true)
        }

        failure.get()?.let { failed ->
            return ExtractionOutcome.Failed(
                "Model reported ${failed.code}: ${failed.message}",
                retryable = failed.retryable,
            )
        }

        val parsed = try {
            ExtractionResultParser.parse(reply.toString())
        } catch (e: ExtractionException) {
            // A schema violation is not retryable: the same prompt tends to produce the same
            // malformed answer, and retrying only burns battery to fail identically.
            return ExtractionOutcome.Failed("Model output rejected: ${e.message}", retryable = false)
        }

        return try {
            ExtractionOutcome.Extracted(ExtractionSanitizer.sanitize(parsed))
        } catch (e: ExtractionException) {
            ExtractionOutcome.Failed("Model output was unusable after redaction: ${e.message}", retryable = false)
        }
    }

    private fun buildMessages(turns: List<SessionTurn>) = listOf(
        ModelMessage(MessageRole.SYSTEM, ExtractionPrompt.SYSTEM),
        ModelMessage(MessageRole.USER, ExtractionPrompt.render(turns)),
    )

    companion object {
        const val DEFAULT_TIMEOUT_MILLIS = 90_000L

        /**
         * Only the tail is extracted. Older turns are already covered by earlier rounds, and
         * sending the whole session would grow cost without adding information.
         */
        const val DEFAULT_MAX_TURNS = 40
    }
}
