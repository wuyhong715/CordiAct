package com.cordiact.core.memory

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class MemoryArtifactValidatorTest {

    private val validator = MemoryArtifactValidator()

    @Test
    fun acceptsWellFormedArtifacts() {
        val summary = renderSummary(listOf(SummaryEntry("fact", "User prefers Kotlin", "memory/rollout_summaries/s1.md", 1)))
        val manual = """
            # Memory

            - User prefers Kotlin
            ${renderCitations(listOf(MemoryCitation("memory/rollout_summaries/s1.md", 1, "s1")))}
        """.trimIndent()

        val result = validator.validate(summary, manual, setOf("memory/rollout_summaries/s1.md"))

        assertEquals(1, result.citations.size)
        assertTrue(!result.hasDanglingCitations)
    }

    @Test
    fun rejectsSummaryWithoutMarker() {
        val error = assertFailsWith<MemoryArtifactException> {
            validator.validate("# Memory index\n", "", emptySet())
        }
        assertEquals(MemoryArtifactException.Code.MISSING_MARKER, error.code)
    }

    @Test
    fun rejectsSummaryThatIsOverBudget() {
        val tight = MemoryArtifactValidator(MemoryBudget(maxSummaryChars = 20, maxManualChars = 100))
        val summary = MemoryPaths.SUMMARY_MARKER + "\n" + "x".repeat(50)

        val error = assertFailsWith<MemoryArtifactException> { tight.validate(summary, "", emptySet()) }
        assertEquals(MemoryArtifactException.Code.OVER_BUDGET, error.code)
    }

    @Test
    fun rejectsManualThatIsOverBudget() {
        val tight = MemoryArtifactValidator(MemoryBudget(maxSummaryChars = 20, maxManualChars = 30))
        val error = assertFailsWith<MemoryArtifactException> {
            tight.validate(MemoryPaths.SUMMARY_MARKER, "y".repeat(100), emptySet())
        }
        assertEquals(MemoryArtifactException.Code.OVER_BUDGET, error.code)
    }

    @Test
    fun budgetRejectsSummaryLargerThanManual() {
        // The summary is an index; an index bigger than what it indexes is a design error.
        assertFailsWith<IllegalArgumentException> {
            MemoryBudget(maxSummaryChars = 1000, maxManualChars = 100)
        }
    }

    @Test
    fun parsesMultipleCitationBlocks() {
        val text = buildString {
            appendLine(renderCitations(listOf(MemoryCitation("memory/rollout_summaries/a.md", 3, "a"))))
            appendLine("some prose")
            appendLine(renderCitations(listOf(MemoryCitation("memory/rollout_summaries/b.md", 7, "b", commit = "abc123"))))
        }

        val citations = validator.parseCitations(text)

        assertEquals(2, citations.size)
        assertEquals("memory/rollout_summaries/a.md", citations[0].file)
        assertEquals(3, citations[0].line)
        assertEquals("abc123", citations[1].commit)
    }

    @Test
    fun parsesMultipleEntriesInOneBlock() {
        val block = buildString {
            appendLine("<cordiact-mem-citation>")
            appendLine("  <entry file=\"memory/rollout_summaries/a.md\" line=\"1\" session=\"a\" />")
            appendLine("  <entry file=\"memory/rollout_summaries/b.md\" line=\"2\" session=\"b\" />")
            appendLine("</cordiact-mem-citation>")
        }

        assertEquals(2, validator.parseCitations(block).size)
    }

    @Test
    fun rejectsUnterminatedBlock() {
        val error = assertFailsWith<MemoryArtifactException> {
            validator.parseCitations("<cordiact-mem-citation>\n  <entry file=\"memory/a.md\" line=\"1\" session=\"s\" />")
        }
        assertEquals(MemoryArtifactException.Code.MALFORMED_CITATION, error.code)
    }

    @Test
    fun rejectsEntryMissingRequiredAttribute() {
        for (tag in listOf(
            "<entry line=\"1\" session=\"s\" />",
            "<entry file=\"memory/a.md\" session=\"s\" />",
            "<entry file=\"memory/a.md\" line=\"1\" />",
            "<entry file=\"memory/a.md\" line=\"not-a-number\" session=\"s\" />",
        )) {
            val error = assertFailsWith<MemoryArtifactException>("expected rejection for $tag") {
                validator.parseCitations("<cordiact-mem-citation>\n  $tag\n</cordiact-mem-citation>")
            }
            assertEquals(MemoryArtifactException.Code.MALFORMED_CITATION, error.code)
        }
    }

    @Test
    fun rejectsCitationOutsideMemory() {
        // A citation must not be usable to point the reading path at an arbitrary file.
        val error = assertFailsWith<MemoryArtifactException> {
            validator.parseCitations(
                "<cordiact-mem-citation>\n  <entry file=\"secrets.env\" line=\"1\" session=\"s\" />\n</cordiact-mem-citation>",
            )
        }
        assertEquals(MemoryArtifactException.Code.OUTSIDE_MEMORY, error.code)
    }

    @Test
    fun reportsDanglingCitationsWithoutThrowing() {
        val manual = renderCitations(listOf(MemoryCitation("memory/rollout_summaries/gone.md", 1, "gone")))

        val result = validator.validate(MemoryPaths.SUMMARY_MARKER, manual, emptySet())

        // Reported, not thrown: the caller decides whether a dangling citation invalidates the
        // whole consolidation, and callers need the list to explain the failure.
        assertTrue(result.hasDanglingCitations)
        assertEquals("memory/rollout_summaries/gone.md", result.danglingCitations.single().file)
    }

    @Test
    fun summaryRendersMarkerAndEntries() {
        val summary = renderSummary(listOf(SummaryEntry("decision", "Use SQLite for state", "memory/rollout_summaries/s1.md", 4)))

        assertTrue(summary.lineSequence().first() == MemoryPaths.SUMMARY_MARKER)
        assertTrue("Use SQLite for state" in summary)
        assertTrue("memory/rollout_summaries/s1.md:4" in summary)
    }

    @Test
    fun emptySummaryIsValidAndSaysSo() {
        val summary = renderSummary(emptyList())

        assertTrue("No memory recorded yet." in summary)
        validator.validate(summary, "", emptySet())
    }

    @Test
    fun renderAndParseRoundTrip() {
        val citations = listOf(
            MemoryCitation("memory/rollout_summaries/a.md", 12, "a", commit = "deadbeef"),
            MemoryCitation("memory/MEMORY.md", 1, "b"),
        )

        val parsed = validator.parseCitations(renderCitations(citations))

        assertEquals(citations, parsed)
    }

    @Test
    fun memoryPathsRecogniseTheSubtree() {
        assertTrue(MemoryPaths.contains("memory"))
        assertTrue(MemoryPaths.contains("memory/MEMORY.md"))
        assertTrue(MemoryPaths.contains("memory/rollout_summaries/x.md"))
        // Prefix confusion must not let a sibling directory pass as memory.
        assertTrue(!MemoryPaths.contains("memory-backup/x.md"))
        assertTrue(!MemoryPaths.contains("notes.txt"))
    }
}
