package com.cordiact.integration.interaction

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class CrossWorkspaceScopeTest {

    private val wholeWorkspace = CrossWorkspaceGrant("ws-other", prefix = null)
    private val memoryOnly = CrossWorkspaceGrant("ws-other", prefix = "memory/")

    private fun denied(reason: CrossWorkspaceAccess.Decision): String {
        check(reason is CrossWorkspaceAccess.Decision.Denied) { "expected denial, got $reason" }
        return reason.reason
    }

    @Test
    fun memoryGrantAllowsMemoryPaths() {
        assertTrue(
            CrossWorkspaceAccess.check(memoryOnly, "memory/MEMORY.md") is CrossWorkspaceAccess.Decision.Allowed,
        )
        assertTrue(
            CrossWorkspaceAccess.check(memoryOnly, "memory/rollout_summaries/s1.md") is CrossWorkspaceAccess.Decision.Allowed,
        )
    }

    @Test
    fun memoryGrantDeniesAnythingOutsideMemory() {
        // The whole point of `@workspace：记忆` is that it is narrower than `@workspace`.
        for (path in listOf("notes.txt", "docs/design.md", "memory-backup/MEMORY.md")) {
            val reason = denied(CrossWorkspaceAccess.check(memoryOnly, path))
            assertTrue("outside the granted subtree" in reason, "got: $reason")
        }
    }

    @Test
    fun wholeWorkspaceGrantAllowsAnySafePath() {
        assertTrue(
            CrossWorkspaceAccess.check(wholeWorkspace, "notes.txt") is CrossWorkspaceAccess.Decision.Allowed,
        )
        assertTrue(
            CrossWorkspaceAccess.check(wholeWorkspace, "memory/MEMORY.md") is CrossWorkspaceAccess.Decision.Allowed,
        )
    }

    @Test
    fun wholeWorkspaceGrantStillRejectsUnsafePaths() {
        // A wide grant widens the subtree, not the safety rules.
        for (path in listOf("../escape.md", "/etc/passwd", ".git/config", "a//b.md", "a/./b.md")) {
            denied(CrossWorkspaceAccess.check(wholeWorkspace, path))
        }
    }

    @Test
    fun traversalIsRejectedEvenWhenThePrefixMatches() {
        // The reason safety runs before containment: this string does start with "memory/",
        // so a naive containment check alone would allow it.
        val path = "memory/../../secrets.txt"

        assertTrue(path.startsWith("memory/"), "precondition: the string prefix matches")
        val reason = denied(CrossWorkspaceAccess.check(memoryOnly, path))
        assertTrue("not a safe" in reason, "safety must be what rejects it, got: $reason")
    }

    @Test
    fun segmentBoundaryIsRespected() {
        // "memory" must not authorize "memory-backup": a raw string prefix check would.
        denied(CrossWorkspaceAccess.check(memoryOnly, "memory-backup/secrets.md"))
    }

    @Test
    fun dotPrefixedSegmentsAreDeniedUnderAnyGrant() {
        // The Workspace root is a git repository; .git holds objects for deleted content.
        for (grant in listOf(wholeWorkspace, memoryOnly)) {
            for (path in listOf(".git/config", "memory/.hidden", ".env")) {
                denied(CrossWorkspaceAccess.check(grant, path))
            }
        }
    }

    @Test
    fun checkAllStopsAtTheFirstDenial() {
        val allowed = listOf("memory/MEMORY.md", "memory/rollout_summaries/s1.md")
        assertTrue(CrossWorkspaceAccess.checkAll(memoryOnly, allowed) is CrossWorkspaceAccess.Decision.Allowed)

        val mixed = listOf("memory/MEMORY.md", "notes.txt")
        val reason = denied(CrossWorkspaceAccess.checkAll(memoryOnly, mixed))
        assertTrue("outside the granted subtree" in reason)
    }

    @Test
    fun emptyPathListIsAllowed() {
        assertEquals(
            CrossWorkspaceAccess.Decision.Allowed,
            CrossWorkspaceAccess.checkAll(memoryOnly, emptyList()),
        )
    }

    @Test
    fun grantRejectsAbsolutePrefix() {
        assertFailsWith<IllegalArgumentException> { CrossWorkspaceGrant("ws", "/memory") }
    }

    @Test
    fun grantRejectsBlankWorkspace() {
        assertFailsWith<IllegalArgumentException> { CrossWorkspaceGrant(" ", prefix = null) }
    }

    @Test
    fun parserAndAccessAgreeOnMemoryScope() {
        // The parser produces the prefix and the access check consumes it; if these two ever
        // disagree, `@workspace：记忆` would grant something other than memory.
        val parsed = WorkspaceScopeParser.parse("team\uFF1A\u8BB0\u5FC6")
        check(parsed is ScopeReferenceResult.Parsed)
        val grant = CrossWorkspaceGrant("ws-other", parsed.reference.prefix)

        assertTrue(CrossWorkspaceAccess.check(grant, "memory/MEMORY.md") is CrossWorkspaceAccess.Decision.Allowed)
        assertTrue(CrossWorkspaceAccess.check(grant, "notes.txt") is CrossWorkspaceAccess.Decision.Denied)
    }

    @Test
    fun parserAndAccessAgreeOnWholeWorkspace() {
        val parsed = WorkspaceScopeParser.parse("team")
        check(parsed is ScopeReferenceResult.Parsed)
        val grant = CrossWorkspaceGrant("ws-other", parsed.reference.prefix)

        assertTrue(CrossWorkspaceAccess.check(grant, "notes.txt") is CrossWorkspaceAccess.Decision.Allowed)
    }
}
