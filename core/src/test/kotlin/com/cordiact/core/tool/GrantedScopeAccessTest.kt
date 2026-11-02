package com.cordiact.core.tool

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val CURRENT = "ws-current"
private const val OTHER = "ws-other"

class GrantedScopeAccessTest {

    private fun context(vararg scopes: GrantedScope) = ToolInvocationContext(
        workspaceId = CURRENT,
        grantedScopes = scopes.toList(),
    )

    private fun denied(decision: GrantedScopeAccess.Decision): String {
        check(decision is GrantedScopeAccess.Decision.Denied) { "expected denial, got $decision" }
        return decision.reason
    }

    @Test
    fun ownWorkspaceIsAlwaysReadable() {
        val plain = context()
        assertEquals(
            GrantedScopeAccess.Decision.Allowed,
            GrantedScopeAccess.check(plain, CURRENT, "notes.txt"),
        )
    }

    @Test
    fun unreferencedWorkspaceIsNotReadable() {
        // This is the core of the authorization model: without a reference, nothing outside the
        // session's own Workspace is reachable at all.
        val reason = denied(GrantedScopeAccess.check(context(), OTHER, "notes.txt"))
        assertTrue("not referenced" in reason, "got: $reason")
    }

    @Test
    fun memoryGrantAllowsOnlyMemory() {
        val ctx = context(GrantedScope(OTHER, "memory/"))

        assertEquals(
            GrantedScopeAccess.Decision.Allowed,
            GrantedScopeAccess.check(ctx, OTHER, "memory/MEMORY.md"),
        )
        assertTrue(
            GrantedScopeAccess.check(ctx, OTHER, "notes.txt") is GrantedScopeAccess.Decision.Denied,
        )
    }

    @Test
    fun wholeWorkspaceGrantAllowsAnySafePath() {
        val ctx = context(GrantedScope(OTHER, prefix = null))

        assertEquals(
            GrantedScopeAccess.Decision.Allowed,
            GrantedScopeAccess.check(ctx, OTHER, "docs/design.md"),
        )
    }

    @Test
    fun wholeWorkspaceGrantStillRejectsUnsafePaths() {
        // A wide grant widens the subtree, not the safety rules.
        val ctx = context(GrantedScope(OTHER, prefix = null))

        for (path in listOf("../escape.md", "/etc/passwd", ".git/config", "a//b.md")) {
            denied(GrantedScopeAccess.check(ctx, OTHER, path))
        }
    }

    @Test
    fun traversalIsRejectedEvenWhenThePrefixMatches() {
        val ctx = context(GrantedScope(OTHER, "memory/"))
        val path = "memory/../../secrets.txt"

        assertTrue(path.startsWith("memory/"), "precondition: the string prefix matches")
        val reason = denied(GrantedScopeAccess.check(ctx, OTHER, path))
        assertTrue("not a safe" in reason, "safety must be what rejects it, got: $reason")
    }

    @Test
    fun segmentBoundaryIsRespected() {
        val ctx = context(GrantedScope(OTHER, "memory/"))

        // A raw string-prefix test would let this through.
        denied(GrantedScopeAccess.check(ctx, OTHER, "memory-backup/secrets.md"))
    }

    @Test
    fun dotSegmentsAreRejectedEvenForTheOwnWorkspace() {
        // The git directory lives in the own Workspace, so the own-Workspace path is the one
        // that most needs this rule.
        val plain = context()

        for (path in listOf(".git/config", "memory/.hidden", ".env")) {
            denied(GrantedScopeAccess.check(plain, CURRENT, path))
        }
    }

    @Test
    fun reachabilityIsCheckedBeforePathValidity() {
        // An unreferenced Workspace should not produce a path-shaped complaint, which would
        // confirm that a file exists there.
        val reason = denied(GrantedScopeAccess.check(context(), OTHER, "../whatever"))
        assertTrue("not referenced" in reason, "got: $reason")
    }

    @Test
    fun scopeForFindsTheGrant() {
        val ctx = context(GrantedScope("ws-a", "memory/"), GrantedScope("ws-b"))

        assertEquals("memory/", ctx.scopeFor("ws-a")?.prefix)
        assertNull(ctx.scopeFor("ws-b")?.prefix)
        assertNull(ctx.scopeFor("ws-c"))
    }

    @Test
    fun canReadReflectsGrants() {
        val ctx = context(GrantedScope(OTHER))

        assertTrue(ctx.canRead(CURRENT))
        assertTrue(ctx.canRead(OTHER))
        assertFalse(ctx.canRead("ws-third"))
    }

    @Test
    fun anEmptyTurnCanOnlyReadItsOwnWorkspace() {
        val ctx = ToolInvocationContext(workspaceId = CURRENT)

        assertTrue(ctx.grantedScopes.isEmpty())
        assertTrue(ctx.canRead(CURRENT))
        assertFalse(ctx.canRead(OTHER))
    }

    @Test
    fun grantRejectsAbsolutePrefix() {
        assertFailsWith<IllegalArgumentException> { GrantedScope(OTHER, "/memory") }
    }

    @Test
    fun grantRejectsBlankWorkspace() {
        assertFailsWith<IllegalArgumentException> { GrantedScope(" ") }
    }

    @Test
    fun emptyPathIsTheWorkspaceRootAndIsListableForTheOwnWorkspace() {
        // Listing a Workspace passes "" as the directory. Rejecting it as malformed would make
        // the own Workspace impossible to enumerate at all.
        assertEquals(
            GrantedScopeAccess.Decision.Allowed,
            GrantedScopeAccess.check(context(), CURRENT, ""),
        )
    }

    @Test
    fun emptyPathIsAllowedForAWholeWorkspaceGrant() {
        val ctx = context(GrantedScope(OTHER, prefix = null))
        assertEquals(
            GrantedScopeAccess.Decision.Allowed,
            GrantedScopeAccess.check(ctx, OTHER, ""),
        )
    }

    @Test
    fun emptyPathIsDeniedForAMemoryOnlyGrant() {
        // The root of another Workspace is not inside memory/, so it must not be listable.
        val ctx = context(GrantedScope(OTHER, "memory/"))
        denied(GrantedScopeAccess.check(ctx, OTHER, ""))
    }
}
