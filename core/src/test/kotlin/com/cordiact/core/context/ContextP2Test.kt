package com.cordiact.core.context

import com.cordiact.core.conversation.ConversationMessage
import com.cordiact.core.conversation.MessageRole
import com.cordiact.core.conversation.ModelMessage
import com.cordiact.core.conversation.ModelToolCall
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ContextP2Test {

    private val estimator = WeightedTokenEstimator()

    private fun toolResult(id: String, name: String, content: String) =
        ModelMessage(MessageRole.TOOL, content, toolCallId = id, toolName = name)

    private fun assistantCall(id: String) =
        ModelMessage(MessageRole.ASSISTANT, "", toolCalls = listOf(ModelToolCall(id, "read_file", "{}")))

    // --- G2: folding -------------------------------------------------------------------------

    @Test
    fun `folding keeps the recent results verbatim and stubs the older ones`() {
        val big = "x".repeat(1_000)
        val messages = listOf(
            ModelMessage(MessageRole.USER, "go"),
            assistantCall("c1"), toolResult("c1", "read_file", big),
            assistantCall("c2"), toolResult("c2", "read_file", big),
            assistantCall("c3"), toolResult("c3", "read_file", big),
            assistantCall("c4"), toolResult("c4", "read_file", big),
        )

        val folded = ToolResultFolder.fold(messages, keepRecentToolResults = 2)

        val results = folded.filter { it.role == MessageRole.TOOL }
        assertEquals(4, results.size)
        // The last two are untouched.
        assertEquals(big, results[2].content)
        assertEquals(big, results[3].content)
        // The first two are stubs that still name the tool.
        assertTrue(results[0].content.contains("read_file"))
        assertTrue(results[0].content.contains("1000 chars folded"))
        assertTrue(results[0].content.length < 300)
    }

    @Test
    fun `folding preserves tool pairing so the provider still accepts the transcript`() {
        val messages = listOf(
            assistantCall("c1"), toolResult("c1", "read_file", "y".repeat(500)),
            assistantCall("c2"), toolResult("c2", "read_file", "z".repeat(500)),
        )

        val folded = ToolResultFolder.fold(messages, keepRecentToolResults = 1)

        // Every TOOL message still answers a call; dropping the id would break the protocol.
        assertEquals(listOf("c1", "c2"), folded.filter { it.role == MessageRole.TOOL }.map { it.toolCallId })
        assertEquals(listOf("read_file", "read_file"), folded.filter { it.role == MessageRole.TOOL }.map { it.toolName })
    }

    @Test
    fun `folding points at the spill file when the result was spilled`() {
        val spilled = "head\n...[truncated 9000 chars; full output saved at spill://s1/run-abc.txt, " +
            "read in pages with tool_output_read]...\ntail"
        val messages = listOf(
            assistantCall("c1"), toolResult("c1", "shell", spilled),
            assistantCall("c2"), toolResult("c2", "shell", "small"),
        )

        val folded = ToolResultFolder.fold(messages, keepRecentToolResults = 1)
        val first = folded.first { it.role == MessageRole.TOOL }
        assertTrue(first.content.contains("spill://s1/run-abc.txt"))
        assertTrue(first.content.contains("tool_output_read"))
    }

    @Test
    fun `folding is a no-op when there is nothing old enough`() {
        val messages = listOf(
            assistantCall("c1"), toolResult("c1", "shell", "a".repeat(500)),
        )
        assertEquals(messages, ToolResultFolder.fold(messages, keepRecentToolResults = 3))
    }

    // --- G4: classification ------------------------------------------------------------------

    @Test
    fun `overflow is recognised across providers`() {
        val samples = listOf(
            Triple("context_length_exceeded", "This model's maximum context length is 128000 tokens.", "openai"),
            Triple("400", "prompt is too long: 210000 tokens > 200000 maximum", "anthropic"),
            Triple("INVALID_ARGUMENT", "The input token count (1100000) exceeds the maximum number of tokens", "google"),
            Triple("invalid_request_error", "This model's maximum context length is 65536 tokens", "deepseek"),
        )

        for ((code, message, provider) in samples) {
            val failure = OverflowClassifier.classify(code, message, provider)
            assertTrue(failure is ModelFailure.ContextOverflow, "$provider sample should be overflow: $message")
        }
    }

    @Test
    fun `an ordinary bad request is not mistaken for overflow`() {
        val samples = listOf(
            Triple("400", "Invalid value for 'temperature': must be <= 2", "openai"),
            Triple("invalid_request_error", "tools[0].function.parameters is not valid JSON", "anthropic"),
            Triple("400", "Unrecognized request argument supplied: foo", "openai"),
        )

        for ((code, message, provider) in samples) {
            val failure = OverflowClassifier.classify(code, message, provider)
            assertTrue(failure !is ModelFailure.ContextOverflow, "must not compact for: $message")
        }
    }

    @Test
    fun `rate limiting is its own failure, not overflow`() {
        val failure = OverflowClassifier.classify("429", "Rate limit reached for requests", "openai")
        assertTrue(failure is ModelFailure.RateLimited)
    }

    // --- compaction through the compactor ----------------------------------------------------

    private fun message(id: String, role: MessageRole, content: String, time: Long) =
        ConversationMessage(id = id, sessionId = "s1", role = role, content = content, createdAtMillis = time)

    private fun longHistory(): List<ConversationMessage> = buildList {
        add(message("m1", MessageRole.SYSTEM, "You are CordiAct.", 0L))
        add(message("m2", MessageRole.USER, "Build the feature end to end.", 1L))
        repeat(8) { index ->
            add(
                message(
                    "a$index",
                    MessageRole.ASSISTANT,
                    "Step $index done with a fairly long explanation of what changed.",
                    (2 + index * 2).toLong(),
                ),
            )
            add(
                message(
                    "u$index",
                    MessageRole.USER,
                    "Continue with step ${index + 1}, keep going.",
                    (3 + index * 2).toLong(),
                ),
            )
        }
        add(message("last", MessageRole.ASSISTANT, "All steps complete.", 100L))
    }

    @Test
    fun `compaction is skipped while the prompt is well under budget`() {
        val store = InMemoryContextCompactionStore()
        val compactor = DefaultContextCompactor(store, estimator, ContextPolicy())

        val result = compactor.compactIfNeeded("s1", windowTokens = 128_000, rawMessages = longHistory())

        assertNull(result)
        assertTrue(store.listActive("s1").isEmpty())
    }

    @Test
    fun `compaction records one active summary and supersedes the previous one`() {
        val store = InMemoryContextCompactionStore()
        val compactor = DefaultContextCompactor(store, estimator, ContextPolicy())

        val first = compactor.compactIfNeeded("s1", windowTokens = 200, rawMessages = longHistory(), force = true)
        assertNotNull(first)
        assertEquals(1, store.listActive("s1").size)

        val second = compactor.compactIfNeeded("s1", windowTokens = 200, rawMessages = longHistory(), force = true)
        assertNotNull(second)

        val active = store.listActive("s1")
        assertEquals(1, active.size, "exactly one summary may be active")
        assertEquals(second!!.recordId, active.single().id)
        assertTrue(store.getCompaction(first!!.recordId)!!.supersededBy == second.recordId)
    }

    @Test
    fun `emergency compaction fires even when the proactive threshold would not`() {
        val store = InMemoryContextCompactionStore()
        val compactor = DefaultContextCompactor(store, estimator, ContextPolicy())
        val history = longHistory()

        // A window large enough that the proactive path declines to act...
        assertNull(compactor.compactIfNeeded("s1", windowTokens = 128_000, rawMessages = history))
        assertTrue(store.listActive("s1").isEmpty())

        // ...but an overflow is not a prediction: it already happened, so it must compact anyway.
        val emergency = compactor.emergencyCompact("s1", windowTokens = 128_000, rawMessages = history)
        assertNotNull(emergency)
        assertEquals(1, store.listActive("s1").size)
        assertTrue(emergency!!.tokensAfter < emergency.tokensBefore)
    }
}
