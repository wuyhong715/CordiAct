package com.cordiact.interaction

/** The memory segment is reserved: it maps to the memory subtree rather than a real directory. */
const val MEMORY_SEGMENT = "记忆"
const val MEMORY_SEGMENT_ASCII = "memory"

/**
 * A parsed cross-Workspace scope reference.
 *
 * The grant is expressed by the reference path itself:
 *
 *   `@team`            -> the whole Workspace
 *   `@team：记忆`       -> only that Workspace's memory
 *
 * Keeping the grant in the path rather than in a permission flag means the thing the user
 * tapped and the thing they authorized are the same object, so they cannot disagree.
 */
data class WorkspaceScopeReference(
    /** The Workspace label the user typed, e.g. `team`. Resolution to an id happens later. */
    val workspaceLabel: String,
    /** Workspace-relative subtree prefix; null means the entire Workspace. */
    val prefix: String?,
    /** True when the reference names the reserved memory segment. */
    val targetsMemory: Boolean,
) {
    init {
        require(workspaceLabel.isNotBlank()) { "Workspace label must not be blank" }
    }
}

sealed interface ScopeReferenceResult {
    data class Parsed(val reference: WorkspaceScopeReference) : ScopeReferenceResult
    data class Invalid(val reason: String) : ScopeReferenceResult
}

/**
 * Parses `workspace[:segment...]` references.
 *
 * Both the full-width `：` and the ASCII `:` are accepted, because the full-width form is what
 * a Chinese keyboard produces and forcing one form would be a papercut with no security value.
 * The two are normalized to the same shape before parsing.
 */
object WorkspaceScopeParser {

    fun parse(raw: String): ScopeReferenceResult {
        val text = raw.trim()
        if (text.isEmpty()) return ScopeReferenceResult.Invalid("Reference is empty")

        val normalized = text.replace('\uFF1A', ':')
        val segments = normalized.split(':')

        val label = segments.first().trim()
        if (label.isEmpty()) return ScopeReferenceResult.Invalid("Workspace label is missing")

        val rest = segments.drop(1).filter { it.isNotBlank() }
        if (rest.isEmpty()) {
            return ScopeReferenceResult.Parsed(WorkspaceScopeReference(label, prefix = null, targetsMemory = false))
        }

        // Only the memory segment is meaningful today. Anything else is rejected rather than
        // treated as a general path: a general path grant would be a much wider opening than
        // the one the product decided on.
        val head = rest.first().trim()
        if (!head.equals(MEMORY_SEGMENT, ignoreCase = true) && !head.equals(MEMORY_SEGMENT_ASCII, ignoreCase = true)) {
            return ScopeReferenceResult.Invalid(
                "Only the '$MEMORY_SEGMENT' segment may follow a Workspace name, found '$head'",
            )
        }
        if (rest.size > 1) {
            return ScopeReferenceResult.Invalid("Memory references cannot be narrowed further: ${rest.drop(1)}")
        }

        return ScopeReferenceResult.Parsed(
            WorkspaceScopeReference(label, prefix = "memory/", targetsMemory = true),
        )
    }
}

/**
 * Guard for reads performed under a granted prefix.
 *
 * Two rules that look similar but answer different questions:
 *
 * - [withinPrefix] decides whether a path is inside the granted subtree. Comparison is by path
 *   segment, not by string prefix, so `memory` does not authorize `memory-backup/x`.
 * - [isSafeRelativePath] rejects malformed or escaping paths before prefix logic even runs.
 *
 * Keeping them separate matters: a prefix check on an unnormalized path is exactly how
 * `memory/../../secrets` slips through.
 */
object ScopePathGuard {

    /**
     * True when [path] is inside [prefix]. A null prefix means the whole Workspace.
     *
     * The prefix must match on segment boundaries: `memory/` matches `memory/MEMORY.md` but
     * not `memory-backup/MEMORY.md`.
     */
    fun withinPrefix(path: String, prefix: String?): Boolean {
        if (prefix == null) return true
        val normalizedPrefix = prefix.trimEnd('/')
        if (normalizedPrefix.isEmpty()) return true
        return path == normalizedPrefix || path.startsWith("$normalizedPrefix/")
    }

    /** Rejects absolute paths, traversal, empty segments and dot-prefixed segments. */
    fun isSafeRelativePath(path: String): Boolean {
        if (path.isBlank()) return false
        if (path.startsWith("/")) return false
        val segments = path.split('/')
        if (segments.any { it.isEmpty() || it == "." || it == ".." }) return false
        // The Workspace root is a git repository, so a dot-prefixed segment could reach .git.
        if (segments.any { it.startsWith(".") }) return false
        return true
    }
}
