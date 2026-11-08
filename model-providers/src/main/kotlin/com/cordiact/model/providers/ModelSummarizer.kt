package com.cordiact.model.providers

import com.cordiact.core.conversation.ConversationMessage
import com.cordiact.core.conversation.MessageRole
import com.cordiact.core.conversation.ModelAdapterService
import com.cordiact.core.conversation.ModelEvent
import com.cordiact.core.conversation.ModelGenerationRequest
import com.cordiact.core.conversation.ModelId
import com.cordiact.core.conversation.ModelMessage
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Runs one blocking summarisation request against a model the user chose.
 *
 * Blocking on purpose: compaction happens inside a turn, and the caller needs the text or a
 * failure, not a stream. A separate [ModelAdapterService] call keeps this out of the turn's own
 * request, so a summary never contaminates the transcript it is summarising.
 *
 * Every failure mode returns null rather than throwing. The compactor treats null as "no model
 * summary" and falls back to its deterministic levels — a broken summariser must degrade the
 * quality of a compaction, never fail the turn that needed it.
 */
class ModelSummarizer(
    private val models: ModelAdapterService,
    private val timeoutMillis: Long = 20_000L,
    /** Per-message cap so one huge tool log cannot make the summary request itself enormous. */
    private val maxCharsPerMessage: Int = 2_000,
    /** Total transcript cap for the same reason. */
    private val maxTotalChars: Int = 48_000,
) {

    fun summarize(prompt: String, body: List<ConversationMessage>, model: ModelId, sessionId: String): String? {
        if (body.isEmpty()) return null
        val transcript = renderTranscript(body) ?: return null

        val messages = listOf(
            ModelMessage(MessageRole.SYSTEM, prompt),
            ModelMessage(MessageRole.USER, transcript),
        )

        val reply = StringBuilder()
        val settled = CountDownLatch(1)
        var failed = false

        val call = try {
            models.generate(
                ModelGenerationRequest(
                    sessionId = sessionId,
                    model = model,
                    messages = messages,
                    // No tools: a summary is text, and offering tools invites a tool call instead.
                    tools = emptyList(),
                ),
            ) { event ->
                when (event) {
                    is ModelEvent.Delta -> synchronized(reply) { reply.append(event.text) }
                    is ModelEvent.Failed -> {
                        failed = true
                        settled.countDown()
                    }
                    is ModelEvent.Completed -> settled.countDown()
                    is ModelEvent.ToolCallsRequested -> Unit
                    else -> Unit
                }
            }
        } catch (_: Exception) {
            return null
        }

        val finished = settled.await(timeoutMillis, TimeUnit.MILLISECONDS)
        if (!finished || failed) {
            // A summariser that cannot finish must not hold the turn open.
            runCatching { call.cancel() }
            return null
        }

        return synchronized(reply) { reply.toString() }.takeIf { it.isNotBlank() }
    }

    private fun renderTranscript(body: List<ConversationMessage>): String? {
        val builder = StringBuilder()
        for (message in body) {
            val label = when (message.role) {
                MessageRole.USER -> "USER"
                MessageRole.ASSISTANT -> "ASSISTANT"
                MessageRole.SYSTEM -> "SYSTEM"
                MessageRole.TOOL -> "TOOL"
            }
            val content = if (message.content.length > maxCharsPerMessage) {
                message.content.take(maxCharsPerMessage) + " …[truncated]"
            } else {
                message.content
            }
            val line = "[$label] $content\n"
            if (builder.length + line.length > maxTotalChars) {
                builder.append("…[earlier messages omitted]\n")
                break
            }
            builder.append(line)
        }
        return builder.toString().takeIf { it.isNotBlank() }
    }
}
