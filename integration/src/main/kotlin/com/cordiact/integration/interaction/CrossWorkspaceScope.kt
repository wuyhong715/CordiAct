package com.cordiact.integration.interaction

/**
 * Which Workspace a reference points at, and how much of it is granted.
 *
 * Distinct from [MentionDescriptor] on purpose. A descriptor answers "what did the user point
 * at"; this answers "what may be read as a result". Keeping them apart means the visibility
 * side can list a scope without granting anything, and the authorization side can grant a
 * scope without deciding what is inside it.
 */
data class CrossWorkspaceGrant(
    /** Workspace the grant applies to. Never the current one; intra-Workspace needs no grant. */
    val workspaceId: String,
    /**
     * Workspace-relative subtree, or null for the whole Workspace.
     *
     * Only `memory/` is producible today. The type allows null because `@workspace` is a real
     * form, but the parser never widens a memory reference into it.
     */
    val prefix: String?,
) {
    init {
        require(workspaceId.isNotBlank()) { "Grant workspace must not be blank" }
        require(prefix == null || !prefix.startsWith("/")) { "Grant prefix must be relative: $prefix" }
    }
}

/**
 * Decides whether a path may be read under a grant.
 *
 * This is the only place that answers that question. The directory decides what is visible and
 * the authorization decides whether the reference is legitimate; neither re-implements path
 * containment, because three copies of a prefix check is three chances to get it wrong.
 */
object CrossWorkspaceAccess {

    sealed interface Decision {
        data object Allowed : Decision
        data class Denied(val reason: String) : Decision
    }

    /**
     * Checks [path] against [grant].
     *
     * Order matters: the path safety check runs before containment, because a traversal path
     * that is merely stripped or prefix-matched would pass containment while reaching outside
     * the subtree.
     */
    fun check(grant: CrossWorkspaceGrant, path: String): Decision {
        if (!ScopePathGuard.isSafeRelativePath(path)) {
            return Decision.Denied("Path is not a safe Workspace-relative path: $path")
        }
        if (!ScopePathGuard.withinPrefix(path, grant.prefix)) {
            return Decision.Denied("Path is outside the granted subtree: $path")
        }
        return Decision.Allowed
    }

    /** Checks every path in [paths]; the first denial wins. */
    fun checkAll(grant: CrossWorkspaceGrant, paths: List<String>): Decision {
        for (path in paths) {
            when (val decision = check(grant, path)) {
                is Decision.Allowed -> Unit
                is Decision.Denied -> return decision
            }
        }
        return Decision.Allowed
    }
}
