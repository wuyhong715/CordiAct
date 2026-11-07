package com.cordiact.model.providers

import com.cordiact.core.conversation.ConversationMessage
import com.cordiact.core.conversation.MessageRole
import com.cordiact.core.conversation.ModelAdapterService
import com.cordiact.core.conversation.ModelCall
import com.cordiact.core.conversation.ModelDescriptor
import com.cordiact.core.conversation.ModelEvent
import com.cordiact.core.conversation.ModelGenerationRequest
import com.cordiact.core.conversation.ModelId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ModelSummarizerTest {

    private val model = ModelId("p", "m")

    private fun body(count: Int = 3, chars: Int = 50) = (0 until count).map { index ->
        ConversationMessage(
            id = "m$index",
            sessionId = "s1",
            role = if (index % 2 == 0) MessageRole.USER else MessageRole.ASSISTANT,
            content = "message $index " + "x".repeat(chars),
            createdAtMillis = index.toLong(),
        )
    }

    private class ScriptedAdapter(
        private val emit: (ModelGenerationRequest, (ModelEvent) -> Unit) -> Unit,
    ) : ModelAdapterService {
        var lastRequest: ModelGenerationRequest? = null
        override fun models() = listOf(ModelDescriptor(model0, "M"))
        override fun generate(request: ModelGenerationRequest, onEvent: (ModelEvent) -> Unit): ModelCall {
            lastRequest = request
            emit(request, onEvent)
            return ModelCall {}
        }
        companion object {
            val model0 = ModelId("p", "m")
        }
    }

    @Test
    fun `a completed response becomes the summary text`() {
        val adapter = ScriptedAdapter { _, onEvent ->
            onEvent(ModelEvent.Delta("## 1. 任务与目标\nrefactor"))
            onEvent(ModelEvent.Completed(inputTokens = 10L, outputTokens = 5L))
        }

        val text = ModelSummarizer(adapter).summarize("PROMPT", body(), model, "s1")

        assertEquals("## 1. 任务与目标\nrefactor", text)
    }

    @Test
    fun `the request carries no tools and a rendered transcript`() {
        val adapter = ScriptedAdapter { _, onEvent -> onEvent(ModelEvent.Completed()) }

        ModelSummarizer(adapter).summarize("PROMPT", body(), model, "s1")

        val request = adapter.lastRequest!!
        assertTrue(request.tools.isEmpty(), "a summary must not invite a tool call")
        assertEquals("PROMPT", request.messages.first().content)
        val transcript = request.messages[1].content
        assertTrue(transcript.contains("[USER]"))
        assertTrue(transcript.contains("[ASSISTANT]"))
    }

    @Test
    fun `a failure yields null so the compactor can fall back`() {
        val adapter = ScriptedAdapter { _, onEvent ->
            onEvent(ModelEvent.Failed("400", "bad request"))
        }

        assertNull(ModelSummarizer(adapter).summarize("PROMPT", body(), model, "s1"))
    }

    @Test
    fun `a summariser that never finishes is abandoned rather than holding the turn`() {
        val adapter = ScriptedAdapter { _, _ ->
            // Emits nothing terminal: the model call simply hangs.
        }

        val started = System.currentTimeMillis()
        val text = ModelSummarizer(adapter, timeoutMillis = 150).summarize("PROMPT", body(), model, "s1")
        val elapsed = System.currentTimeMillis() - started

        assertNull(text)
        assertTrue(elapsed < 2_000, "must give up near the timeout, not block the turn")
    }

    @Test
    fun `an empty body is not summarised`() {
        val adapter = ScriptedAdapter { _, onEvent -> onEvent(ModelEvent.Completed()) }
        assertNull(ModelSummarizer(adapter).summarize("PROMPT", emptyList(), model, "s1"))
    }

    @Test
    fun `one huge message cannot make the summary request enormous`() {
        val adapter = ScriptedAdapter { _, onEvent -> onEvent(ModelEvent.Completed()) }
        val huge = listOf(
            ConversationMessage("m0", "s1", MessageRole.ASSISTANT, "y".repeat(100_000), 0L),
        )

        ModelSummarizer(adapter, maxCharsPerMessage = 100, maxTotalChars = 10_000)
            .summarize("PROMPT", huge, model, "s1")

        val transcript = adapter.lastRequest!!.messages[1].content
        assertTrue(transcript.length < 500, "per-message cap must apply")
        assertTrue(transcript.contains("truncated"))
    }
}
