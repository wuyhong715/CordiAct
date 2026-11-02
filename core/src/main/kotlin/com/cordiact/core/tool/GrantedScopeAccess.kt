package com.cordiact.core.tool

/**
 * Decides whether a tool call may touch a given Workspace path.
 *
 * This is the enforcement point for `@` grants. A grant is an authorization, not an
 * attachment: nothing is read when the reference is made, and the model decides what to fetch.
 * That only works if the grant is checked at the moment of each read, which is here.
 *
 * Deliberately separate from the interaction layer's path guard: that one decides what a
 * reference *means*, this one decides what a tool call may *do*. Keeping them apart means a
 * tool cannot be talked into a wider read by a descriptor that skipped authorization.
 */
object GrantedScopeAccess {

    sealed interface Decision {
        data object Allowed : Decision
        data class Denied(val reason: String) : Decision
    }

    /**
     * Checks [path] against what [context] grants for [targetWorkspaceId].
     *
     * The order is: reachability, path safety, then containment. Reachability first because a
     * Workspace the turn never referenced should produce "not authorized" rather than a
     * detailed path complaint that confirms the file exists.
     */
    fun check(
        context: ToolInvocationContext,
        targetWorkspaceId: String,
        path: String,
    ): Decision {
        val scope = context.scopeFor(targetWorkspaceId)
        if (targetWorkspaceId != context.workspaceId && scope == null) {
            return Decision.Denied("Workspace is not referenced by this turn")
        }
        // An empty path is the Workspace root, which listing needs. It is not a malformed
        // path: treating it as one would make a Workspace impossible to enumerate.
        if (path.isEmpty()) {
            return if (scope == null || scope.prefix == null) {
                Decision.Allowed
            } else {
                Decision.Denied("The Workspace root is outside the granted subtree")
            }
        }
        if (!isSafeRelativePath(path)) {
            return Decision.Denied("Path is not a safe Workspace-relative path: $path")
        }
        if (scope != null && !withinPrefix(path, scope.prefix)) {
            return Decision.Denied("Path is outside the granted subtree: $path")
        }
        return Decision.Allowed
    }

    /**
     * Rejects absolute paths, traversal, empty segments and dot-prefixed segments.
     *
     * Dot segments are banned outright: the Workspace root is a git repository, so `.git` would
     * otherwise be reachable through a tool call and would expose objects for deleted content.
     */
    fun isSafeRelativePath(path: String): Boolean {
        if (path.isBlank()) return false
        if (path.startsWith("/")) return false
        val segments = path.split('/')
        if (segments.any { it.isEmpty() || it == "." || it == ".." }) return false
        if (segments.any { it.startsWith(".") }) return false
        return true
    }

    /**
     * True when [path] is inside [prefix]; a null prefix means the whole Workspace.
     *
     * Matched on segment boundaries so `memory` never authorizes `memory-backup`.
     */
    fun withinPrefix(path: String, prefix: String?): Boolean {
        if (prefix == null) return true
        val normalized = prefix.trimEnd('/')
        if (normalized.isEmpty()) return true
        return path == normalized || path.startsWith("$normalized/")
    }
}
