package com.cordiact.core.memory

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

private const val DAY = 24L * 60 * 60 * 1000
private const val NOW = 1_800_000_000_000L

class MemoryCandidateRankerTest {

    private fun candidate(
        session: String,
        usage: Int = 0,
        lastUsed: Long? = null,
        generated: Long = NOW,
    ) = MemoryCandidate(
        sessionId = session,
        title = session,
        body = "body",
        usageCount = usage,
        lastUsedAtMillis = lastUsed,
        generatedAtMillis = generated,
    )

    @Test
    fun ordersByUsageBeforeRecency() {
        // A frequently used memory outranks a newer one that was never read.
        val ranked = MemoryCandidateRanker.rank(
            listOf(
                candidate("new", usage = 0, generated = NOW),
                candidate("used", usage = 20, lastUsed = NOW - DAY),
            ),
            ConsolidationPolicy(),
            NOW,
        )

        assertEquals(listOf("used", "new"), ranked.map { it.sessionId })
    }

    @Test
    fun breaksUsageTiesByRecency() {
        val ranked = MemoryCandidateRanker.rank(
            listOf(
                candidate("older", usage = 3, lastUsed = NOW - 10 * DAY),
                candidate("newer", usage = 3, lastUsed = NOW - DAY),
            ),
            ConsolidationPolicy(),
            NOW,
        )

        assertEquals(listOf("newer", "older"), ranked.map { it.sessionId })
    }

    @Test
    fun neverUsedFallsBackToGenerationTime() {
        // Without the fallback every new memory would be evicted immediately for having no
        // usage timestamp at all.
        val ranked = MemoryCandidateRanker.rank(
            listOf(candidate("fresh", usage = 0, generated = NOW - DAY)),
            ConsolidationPolicy(maxUnusedDays = 30),
            NOW,
        )

        assertEquals(1, ranked.size)
    }

    @Test
    fun evictsCandidatesPastTheUnusedWindow() {
        val ranked = MemoryCandidateRanker.rank(
            listOf(
                candidate("stale", usage = 0, lastUsed = NOW - 40 * DAY),
                candidate("live", usage = 0, lastUsed = NOW - DAY),
            ),
            ConsolidationPolicy(maxUnusedDays = 30),
            NOW,
        )

        assertEquals(listOf("live"), ranked.map { it.sessionId })
    }

    @Test
    fun capsTheNumberOfCandidates() {
        val many = (1..10).map { candidate("s$it") }

        val ranked = MemoryCandidateRanker.rank(many, ConsolidationPolicy(maxCandidates = 3), NOW)

        assertEquals(3, ranked.size)
    }

    @Test
    fun rankingIsDeterministic() {
        val input = listOf(
            candidate("b", usage = 1, lastUsed = NOW),
            candidate("a", usage = 1, lastUsed = NOW),
        )

        val first = MemoryCandidateRanker.rank(input, ConsolidationPolicy(), NOW).map { it.sessionId }
        val second = MemoryCandidateRanker.rank(input.reversed(), ConsolidationPolicy(), NOW).map { it.sessionId }

        // Same order regardless of input order, so a no-op round produces no commit.
        assertEquals(first, second)
    }

    @Test
    fun policyRejectsSummaryLargerThanManual() {
        assertFailsWith<IllegalArgumentException> {
            ConsolidationPolicy(maxSummaryChars = 1000, maxManualChars = 100)
        }
    }
}

class ConsolidationDiffTest {

    @Test
    fun rejectsSessionsInTwoSets() {
        assertFailsWith<IllegalArgumentException> {
            ConsolidationDiff(added = setOf("a"), retained = setOf("a"))
        }
        assertFailsWith<IllegalArgumentException> {
            ConsolidationDiff(added = setOf("a"), removed = setOf("a"))
        }
        assertFailsWith<IllegalArgumentException> {
            ConsolidationDiff(retained = setOf("a"), removed = setOf("a"))
        }
    }

    @Test
    fun survivingIsAddedPlusRetained() {
        val diff = ConsolidationDiff(added = setOf("new"), retained = setOf("old"), removed = setOf("gone"))

        assertEquals(setOf("new", "old"), diff.surviving)
    }
}

class MemoryConsolidatorTest {

    private fun candidate(session: String, title: String = session) = MemoryCandidate(
        sessionId = session,
        title = title,
        body = "body of $session",
        generatedAtMillis = NOW,
    )

    @Test
    fun rendersSummaryManualAndEvidence() {
        val artifacts = MemoryConsolidator.render(
            listOf(candidate("s1")),
            ConsolidationDiff(added = setOf("s1")),
            ConsolidationPolicy(),
        )

        assertTrue(artifacts.summary.startsWith(MemoryPaths.SUMMARY_MARKER))
        assertTrue("s1" in artifacts.manual)
        assertTrue(MemoryPaths.evidencePath("s1") in artifacts.evidence)
    }

    @Test
    fun droppedSessionsProduceNoEvidence() {
        // Only surviving sessions may appear; anything else would resurrect a removed memory.
        val artifacts = MemoryConsolidator.render(
            listOf(candidate("kept"), candidate("dropped")),
            ConsolidationDiff(retained = setOf("kept"), removed = setOf("dropped")),
            ConsolidationPolicy(),
        )

        assertTrue(MemoryPaths.evidencePath("kept") in artifacts.evidence)
        assertTrue(MemoryPaths.evidencePath("dropped") !in artifacts.evidence)
        assertTrue("dropped" !in artifacts.manual)
        assertEquals(setOf("dropped"), artifacts.evictedSessions)
    }

    @Test
    fun renderingIsByteIdenticalForIdenticalInput() {
        val input = listOf(candidate("a"), candidate("b"))
        val diff = ConsolidationDiff(added = setOf("a", "b"))

        val first = MemoryConsolidator.render(input, diff, ConsolidationPolicy())
        val second = MemoryConsolidator.render(input, diff, ConsolidationPolicy())

        assertEquals(first.summary, second.summary)
        assertEquals(first.manual, second.manual)
        assertEquals(first.evidence, second.evidence)
    }

    @Test
    fun rejectsManualOverBudget() {
        val big = MemoryCandidate("s1", "t", "x".repeat(500), generatedAtMillis = NOW)

        val error = assertFailsWith<MemoryArtifactException> {
            MemoryConsolidator.render(
                listOf(big),
                ConsolidationDiff(added = setOf("s1")),
                ConsolidationPolicy(maxManualChars = 200, maxSummaryChars = 100),
            )
        }
        assertEquals(MemoryArtifactException.Code.OVER_BUDGET, error.code)
    }
}
