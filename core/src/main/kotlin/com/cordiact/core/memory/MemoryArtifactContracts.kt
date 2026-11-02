package com.cordiact.core.memory

import com.cordiact.core.plugin.ManagedPlugin
import com.cordiact.core.plugin.PluginSpec
import com.cordiact.core.plugin.PluginTarget

const val AGENT_MEMORY_PLUGIN_ID = "core.agent-memory"
const val AGENT_MEMORY_VERSION = "0.1.0"

fun agentMemoryPluginSpec() = PluginSpec(
    id = AGENT_MEMORY_PLUGIN_ID,
    version = AGENT_MEMORY_VERSION,
    target = PluginTarget.ANDROID_NATIVE,
    dependencies = setOf("core.workspace-manager"),
)

/**
 * The three on-disk artifacts, in the order the model encounters them.
 *
 * Progressive disclosure: the summary is small and always injected, the manual is the routing
 * layer that gets grepped, and the per-session evidence is only opened when the manual points
 * at it. There is no index beyond this: retrieval is grep plus file access.
 */
object MemoryPaths {
    /** Directory holding every memory artifact. The only subtree git commits. */
    const val ROOT = "memory"
    const val ROOT_PREFIX = "memory/"

    /** Always injected; it is the index that tells the model what is worth searching for. */
    const val SUMMARY = "memory/memory_summary.md"

    /** Grep target and routing layer; every entry here should point at evidence. */
    const val MANUAL = "memory/MEMORY.md"

    /** Per-session evidence, named by session id. */
    const val EVIDENCE_PREFIX = "memory/rollout_summaries/"

    fun evidencePath(sessionId: String): String = "$EVIDENCE_PREFIX$sessionId.md"

    /** True when [path] belongs to the memory subtree. Used by @ scope and git path checks. */
    fun contains(path: String): Boolean =
        path == ROOT || path.startsWith(ROOT_PREFIX)

    /** The first line of the summary. A file without it is not a summary we wrote. */
    const val SUMMARY_MARKER = "v1"
}

/** Budget for one artifact. Sizes are characters, not tokens: tokenizers differ per provider. */
data class MemoryBudget(
    val maxSummaryChars: Int = 20_000,
    val maxManualChars: Int = 200_000,
    val maxEvidenceChars: Int = 64_000,
) {
    init {
        require(maxSummaryChars > 0 && maxManualChars > 0 && maxEvidenceChars > 0) {
            "Memory budgets must be positive"
        }
        require(maxSummaryChars <= maxManualChars) {
            "The summary is an index and must not exceed the manual it indexes"
        }
    }
}

/** A parsed citation: which artifact, which line, and which conversation produced it. */
data class MemoryCitation(
    val file: String,
    val line: Int,
    val session: String,
    /** Set once the artifact is committed; makes a memory traceable to an immutable revision. */
    val commit: String? = null,
) {
    init {
        require(file.isNotBlank()) { "Citation file must not be blank" }
        require(line >= 1) { "Citation line starts at 1" }
        require(session.isNotBlank()) { "Citation session must not be blank" }
    }
}

/** Why an artifact was rejected. Every case rejects the artifact rather than repairing it. */
class MemoryArtifactException(val code: Code, message: String) : RuntimeException(message) {
    enum class Code {
        /** The summary does not start with the expected marker. */
        MISSING_MARKER,

        /** An artifact is larger than its budget. */
        OVER_BUDGET,

        /** A citation could not be parsed. */
        MALFORMED_CITATION,

        /** A citation points at evidence that does not exist. */
        DANGLING_CITATION,

        /** The artifact lives outside the memory subtree. */
        OUTSIDE_MEMORY,

        ARTIFACT_UNREADABLE,
        STORAGE_FAILURE,
    }
}

/** Result of validating a set of artifacts before they are committed. */
data class MemoryArtifactValidation(
    val citations: List<MemoryCitation>,
    /** Evidence files referenced by citations but absent on disk. */
    val danglingCitations: List<MemoryCitation>,
) {
    val hasDanglingCitations: Boolean get() = danglingCitations.isNotEmpty()
}

/**
 * Reads and writes memory artifacts.
 *
 * The files are the authority for memory *content*; coordination state (leases, watermarks,
 * usage counters) lives elsewhere. This port does not know about sessions or models, so it can
 * be verified without either.
 */
interface MemoryArtifactStore {
    /** Returns the text of [path], or null when the file does not exist. */
    fun read(workspaceId: String, path: String, maxChars: Int): String?

    /** Writes atomically; a partial artifact must never be observable. */
    fun write(workspaceId: String, path: String, content: String)

    fun delete(workspaceId: String, path: String)

    fun exists(workspaceId: String, path: String): Boolean

    /** Evidence files present for this Workspace, as Workspace-relative paths. */
    fun evidenceFiles(workspaceId: String): List<String>
}

/**
 * Validates artifacts before they are allowed to take effect.
 *
 * This is the substitute for codex's git-diff check. Three questions, all answerable from the
 * artifacts alone: does the summary carry its marker, are the files inside budget, and does
 * every citation resolve to evidence that exists? A failure rejects the whole consolidation
 * rather than shipping a half-consistent memory.
 */
class MemoryArtifactValidator(private val budget: MemoryBudget = MemoryBudget()) {

    fun validate(
        summary: String,
        manual: String,
        evidencePaths: Set<String>,
    ): MemoryArtifactValidation {
        requireArtifact(MemoryPaths.SUMMARY, summary, budget.maxSummaryChars)
        requireArtifact(MemoryPaths.MANUAL, manual, budget.maxManualChars)

        val firstLine = summary.lineSequence().firstOrNull()?.trim().orEmpty()
        if (firstLine != MemoryPaths.SUMMARY_MARKER) {
            throw MemoryArtifactException(
                MemoryArtifactException.Code.MISSING_MARKER,
                "Summary must start with '${MemoryPaths.SUMMARY_MARKER}', found '${firstLine.take(40)}'",
            )
        }

        val citations = parseCitations(manual)
        val dangling = citations.filter { it.file !in evidencePaths && it.file != MemoryPaths.MANUAL }
        return MemoryArtifactValidation(citations = citations, danglingCitations = dangling)
    }

    /** Extracts every citation block from [text]. Malformed input is rejected, not skipped. */
    fun parseCitations(text: String): List<MemoryCitation> {
        val out = mutableListOf<MemoryCitation>()
        var index = 0
        while (true) {
            val start = text.indexOf(CITATION_OPEN, index)
            if (start < 0) break
            val end = text.indexOf(CITATION_CLOSE, start)
            if (end < 0) {
                throw MemoryArtifactException(
                    MemoryArtifactException.Code.MALFORMED_CITATION,
                    "Unterminated citation block at offset $start",
                )
            }
            val block = text.substring(start + CITATION_OPEN.length, end)
            out += parseBlock(block)
            index = end + CITATION_CLOSE.length
        }
        return out
    }

    private fun parseBlock(block: String): List<MemoryCitation> {
        val entries = mutableListOf<MemoryCitation>()
        var index = 0
        while (true) {
            val start = block.indexOf("<entry", index)
            if (start < 0) break
            val end = block.indexOf("/>", start)
            if (end < 0) {
                throw MemoryArtifactException(
                    MemoryArtifactException.Code.MALFORMED_CITATION,
                    "Unterminated citation entry",
                )
            }
            val tag = block.substring(start, end)
            entries += parseEntry(tag)
            index = end + 2
        }
        if (entries.isEmpty()) {
            throw MemoryArtifactException(
                MemoryArtifactException.Code.MALFORMED_CITATION,
                "Citation block contains no entries",
            )
        }
        return entries
    }

    private fun parseEntry(tag: String): MemoryCitation {
        val file = attribute(tag, "file")
        val line = attribute(tag, "line")?.toIntOrNull()
        val session = attribute(tag, "session")
        if (file == null || line == null || session == null) {
            throw MemoryArtifactException(
                MemoryArtifactException.Code.MALFORMED_CITATION,
                "Citation entry must carry file, line and session: $tag",
            )
        }
        if (!MemoryPaths.contains(file)) {
            throw MemoryArtifactException(
                MemoryArtifactException.Code.OUTSIDE_MEMORY,
                "Citation must point inside ${MemoryPaths.ROOT_PREFIX}: $file",
            )
        }
        return MemoryCitation(file = file, line = line, session = session, commit = attribute(tag, "commit"))
    }

    private fun attribute(tag: String, name: String): String? {
        val prefix = "$name=\""
        val start = tag.indexOf(prefix)
        if (start < 0) return null
        val valueStart = start + prefix.length
        val end = tag.indexOf('"', valueStart)
        if (end < 0) return null
        return tag.substring(valueStart, end).ifBlank { null }
    }

    private fun requireArtifact(path: String, content: String, maxChars: Int) {
        if (content.length > maxChars) {
            throw MemoryArtifactException(
                MemoryArtifactException.Code.OVER_BUDGET,
                "$path is ${content.length} chars, over the $maxChars budget",
            )
        }
    }

    private companion object {
        const val CITATION_OPEN = "<cordiact-mem-citation>"
        const val CITATION_CLOSE = "</cordiact-mem-citation>"
    }
}

/** Renders a citation block. Kept beside the parser so the two cannot drift apart. */
fun renderCitations(citations: List<MemoryCitation>): String {
    if (citations.isEmpty()) return ""
    return buildString {
        appendLine("<cordiact-mem-citation>")
        for (citation in citations) {
            append("  <entry file=\"").append(citation.file)
                .append("\" line=\"").append(citation.line)
                .append("\" session=\"").append(citation.session).append('"')
            if (citation.commit != null) append(" commit=\"").append(citation.commit).append('"')
            appendLine(" />")
        }
        appendLine("</cordiact-mem-citation>")
    }
}

/** Builds the always-injected summary from the manual's entries. */
fun renderSummary(entries: List<SummaryEntry>): String = buildString {
    appendLine(MemoryPaths.SUMMARY_MARKER)
    appendLine()
    appendLine("# Memory index")
    appendLine()
    if (entries.isEmpty()) {
        appendLine("No memory recorded yet.")
    } else {
        for (entry in entries) {
            append("- ").append(entry.kind).append(": ").append(entry.title)
                .append(" (").append(entry.evidencePath).append(':').append(entry.line).append(')')
            appendLine()
        }
    }
}

/** One line of the resident index. Points at evidence; never carries the evidence itself. */
data class SummaryEntry(
    val kind: String,
    val title: String,
    val evidencePath: String,
    val line: Int,
)

open class MemoryArtifactPlugin(
    private val store: MemoryArtifactStore,
    private val budget: MemoryBudget = MemoryBudget(),
) : ManagedPlugin {
    override val spec: PluginSpec = agentMemoryPluginSpec()
    @Volatile private var enabled = false

    override fun onEnable() {
        enabled = true
    }

    override fun onDisable() {
        enabled = false
    }

    protected fun requireEnabled() {
        check(enabled) { "Agent memory plugin is not enabled" }
    }

    protected val validator = MemoryArtifactValidator(budget)
    protected val artifactStore: MemoryArtifactStore get() = store
    protected val artifactBudget: MemoryBudget get() = budget
}
