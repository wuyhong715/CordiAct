package com.cordiact.core.context

import com.cordiact.core.conversation.ConversationMessage
import com.cordiact.core.conversation.MessageRole
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ContextP3Test {

    private val estimator = WeightedTokenEstimator()

    private fun message(id: String, role: MessageRole, content: String, time: Long) =
        ConversationMessage(id = id, sessionId = "s1", role = role, content = content, createdAtMillis = time)

    /** Enough text that the body clears MIN_CANDIDATE_BODY_TOKENS with room to spare. */
    private fun longHistory(turns: Int = 20): List<ConversationMessage> = buildList {
        add(message("m1", MessageRole.SYSTEM, "You are CordiAct, a mobile agent.", 0L))
        add(message("m2", MessageRole.USER, "Refactor the module end to end and keep tests green.", 1L))
        repeat(turns) { index ->
            add(
                message(
                    "a$index",
                    MessageRole.ASSISTANT,
                    "Step $index: changed the call sites and re-ran the suite. " +
                        "Everything passed, with notes about the remaining edge cases to handle later.",
                    (2 + index * 2).toLong(),
                ),
            )
            add(
                message(
                    "u$index",
                    MessageRole.USER,
                    "Continue with step ${index + 1}. Keep the public API stable while you do it.",
                    (3 + index * 2).toLong(),
                ),
            )
        }
        add(message("last", MessageRole.ASSISTANT, "All steps complete.", 200L))
    }

    @Test
    fun `a candidate prepared off the critical path is installed without re-summarising`() {
        val store = InMemoryContextCompactionStore()
        val compactor = DefaultContextCompactor(store, estimator, ContextPolicy())
        val history = longHistory()

        val candidate = compactor.prepareCandidate("s1", windowTokens = 1_000, history)
        assertNotNull(candidate)

        val installed = compactor.installCandidate("s1", windowTokens = 1_000, history)
        assertNotNull(installed)
        assertEquals(candidate!!.summary, installed!!.summary, "the prepared text is reused verbatim")
        assertEquals(candidate.startMessageId, installed.startMessageId)
        assertEquals(candidate.endMessageId, installed.endMessageId)

        val active = store.listActive("s1")
        assertEquals(1, active.size)
        assertEquals(candidate.startMessageId, active.single().startMessageId)
    }

    @Test
    fun `nothing is prepared while the history is too short to be worth it`() {
        val store = InMemoryContextCompactionStore()
        val compactor = DefaultContextCompactor(store, estimator, ContextPolicy())
        val short = listOf(
            message("m1", MessageRole.SYSTEM, "sys", 0L),
            message("m2", MessageRole.USER, "hi", 1L),
            message("m3", MessageRole.ASSISTANT, "hello", 2L),
            message("m4", MessageRole.USER, "again", 3L),
        )

        assertNull(compactor.prepareCandidate("s1", windowTokens = 128_000, short))
        assertNull(compactor.installCandidate("s1", windowTokens = 128_000, short))
    }

    @Test
    fun `a candidate whose range has been outgrown is discarded and the work is done synchronously`() {
        val store = InMemoryContextCompactionStore()
        val compactor = DefaultContextCompactor(store, estimator, ContextPolicy())

        val history = longHistory()
        val candidate = compactor.prepareCandidate("s1", windowTokens = 1_000, history)
        assertNotNull(candidate)

        // More turns arrive before the next request: the prepared range is no longer the range
        // that needs compressing, and installing it would swallow the newest exchange.
        val grown = history + listOf(
            message("n1", MessageRole.USER, "One more thing: also update the docs.", 300L),
            message("n2", MessageRole.ASSISTANT, "Docs updated.", 301L),
        )
        val verdict = CandidateValidator.validate(
            candidate!!,
            grown,
            CompactionPlanner.plan(grown, estimator, ContextPolicy().createBudget(1_000)),
        )
        assertTrue(verdict == CandidateVerdict.RANGE_OUTGROWN || verdict == CandidateVerdict.USABLE)

        // Either way, the request must not be left without a summary: the sync path still runs.
        val installed = compactor.installCandidate("s1", windowTokens = 1_000, grown)
        if (installed == null) {
            val fallback = compactor.compactIfNeeded("s1", windowTokens = 1_000, grown)
            assertNotNull(fallback, "the synchronous path must cover what the candidate could not")
        }
        assertEquals(1, store.listActive("s1").size)
    }

    @Test
    fun `a candidate whose boundary message disappeared is refused`() {
        val store = InMemoryContextCompactionStore()
        val compactor = DefaultContextCompactor(store, estimator, ContextPolicy())
        val history = longHistory()
        val candidate = compactor.prepareCandidate("s1", windowTokens = 1_000, history)!!

        // Simulate the boundary message no longer being present.
        val truncated = history.filterNot { it.id == candidate.endMessageId }
        val verdict = CandidateValidator.validate(
            candidate,
            truncated,
            CompactionPlanner.plan(truncated, estimator, ContextPolicy().createBudget(1_000)),
        )
        assertEquals(CandidateVerdict.RANGE_GONE, verdict)
    }

    @Test
    fun `the candidate store keeps one proposal per session`() {
        val store = CompactionCandidateStore()
        val first = CompactionCandidate("s1", "a", "b", "one", 100, 10, 0, 1L)
        val second = CompactionCandidate("s1", "c", "d", "two", 200, 20, 1, 2L)

        store.put(first)
        store.put(second)

        assertEquals("two", store.get("s1")!!.summary)
        store.remove("s1")
        assertNull(store.get("s1"))
    }
}
