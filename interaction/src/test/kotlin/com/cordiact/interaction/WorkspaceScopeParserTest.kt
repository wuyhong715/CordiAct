package com.cordiact.interaction

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WorkspaceScopeParserTest {

    private fun parsed(raw: String): WorkspaceScopeReference {
        val result = WorkspaceScopeParser.parse(raw)
        check(result is ScopeReferenceResult.Parsed) { "expected a parsed reference for '$raw', got $result" }
        return result.reference
    }

    private fun invalid(raw: String): String {
        val result = WorkspaceScopeParser.parse(raw)
        check(result is ScopeReferenceResult.Invalid) { "expected rejection for '$raw', got $result" }
        return result.reason
    }

    @Test
    fun bareWorkspaceGrantsTheWholeWorkspace() {
        val reference = parsed("team")

        assertEquals("team", reference.workspaceLabel)
        assertEquals(null, reference.prefix)
        assertFalse(reference.targetsMemory)
    }

    @Test
    fun memorySegmentNarrowsToMemory() {
        // Full-width colon, as a Chinese keyboard produces.
        val fullWidth = parsed("team\uFF1A\u8BB0\u5FC6")
        assertEquals("team", fullWidth.workspaceLabel)
        assertEquals("memory/", fullWidth.prefix)
        assertTrue(fullWidth.targetsMemory)

        // ASCII colon must behave identically rather than being a separate code path.
        val ascii = parsed("team:\u8BB0\u5FC6")
        assertEquals(fullWidth, ascii)
    }

    @Test
    fun asciiMemoryAliasIsAccepted() {
        val reference = parsed("team:memory")

        assertEquals("memory/", reference.prefix)
        assertTrue(reference.targetsMemory)
    }

    @Test
    fun whitespaceIsTolerated() {
        val reference = parsed("  team :  记忆  ")

        assertEquals("team", reference.workspaceLabel)
        assertEquals("memory/", reference.prefix)
    }

    @Test
    fun emptyReferenceIsRejected() {
        invalid("")
        invalid("   ")
    }

    @Test
    fun missingWorkspaceLabelIsRejected() {
        invalid(":记忆")
        invalid("  :memory")
    }

    @Test
    fun unknownSegmentIsRejected() {
        // A general path grant would be a wider opening than the product decided on.
        val reason = invalid("team:secrets")
        assertTrue("Only the" in reason, "reason should explain the restriction, got: $reason")
    }

    @Test
    fun furtherNarrowingIsRejected() {
        invalid("team:记忆:extra")
    }

    @Test
    fun multipleEmptySegmentsAreIgnored() {
        // `team:` has a trailing separator and no segment; treat it as the whole Workspace.
        assertEquals(null, parsed("team:").prefix)
    }

    // ---- ScopePathGuard ----

    @Test
    fun nullPrefixAuthorizesEverything() {
        assertTrue(ScopePathGuard.withinPrefix("notes.txt", null))
        assertTrue(ScopePathGuard.withinPrefix("memory/MEMORY.md", null))
    }

    @Test
    fun memoryPrefixAuthorizesMemoryOnly() {
        assertTrue(ScopePathGuard.withinPrefix("memory/MEMORY.md", "memory/"))
        assertTrue(ScopePathGuard.withinPrefix("memory/rollout_summaries/s1.md", "memory/"))
        assertTrue(ScopePathGuard.withinPrefix("memory", "memory/"))
        assertFalse(ScopePathGuard.withinPrefix("notes.txt", "memory/"))
        assertFalse(ScopePathGuard.withinPrefix("memoryx/MEMORY.md", "memory/"))
    }

    @Test
    fun prefixMatchesOnSegmentBoundariesNotStringPrefix() {
        // The classic prefix bug: "memory" must not authorize "memory-backup".
        assertFalse(ScopePathGuard.withinPrefix("memory-backup/MEMORY.md", "memory/"))
        assertTrue(ScopePathGuard.withinPrefix("memory/a.md", "memory/"))
    }

    @Test
    fun safePathsAreAccepted() {
        assertTrue(ScopePathGuard.isSafeRelativePath("memory/MEMORY.md"))
        assertTrue(ScopePathGuard.isSafeRelativePath("notes.v2.txt"))
        assertTrue(ScopePathGuard.isSafeRelativePath("a/b/c.md"))
    }

    @Test
    fun traversalAndAbsolutePathsAreRejected() {
        for (path in listOf(
            "memory/../../etc/passwd",
            "../outside.md",
            "/etc/passwd",
            "memory//double.md",
            "memory/./here.md",
        )) {
            assertFalse(ScopePathGuard.isSafeRelativePath(path), "expected rejection for $path")
        }
    }

    @Test
    fun dotPrefixedSegmentsAreRejected() {
        // The Workspace root is a git repository, so .git must not be addressable.
        for (path in listOf(".git/config", "memory/.hidden", ".env")) {
            assertFalse(ScopePathGuard.isSafeRelativePath(path), "expected rejection for $path")
        }
    }

    @Test
    fun blankPathIsRejected() {
        assertFalse(ScopePathGuard.isSafeRelativePath(""))
        assertFalse(ScopePathGuard.isSafeRelativePath("   "))
    }

    @Test
    fun guardAndPrefixComposeSafely() {
        // The reason these are two functions: a traversal path must fail the safety check even
        // though a naive prefix test would consider its text to start with "memory/".
        val path = "memory/../../secrets.txt"
        assertFalse(ScopePathGuard.isSafeRelativePath(path))
        // And it would have passed a string-prefix check, which is exactly the bug.
        assertTrue(path.startsWith("memory/"))
    }
}
