package com.cordiact.core.memory

/**
 * A repeated pattern detected across sessions.
 *
 * Procedural memory is the step between "this happened" and "here is a reusable procedure".
 * Codex only writes a skill when the same task family recurs, and records how often: a one-off
 * is a fact, not a procedure. [repeats] carries that count so a single occurrence cannot be
 * promoted by accident.
 */
data class DetectedPattern(
    /** Lowercase hyphenated slug; also the skill directory name. */
    val name: String,
    /** One line describing when the procedure applies. */
    val description: String,
    /** Sessions that showed this pattern. */
    val sessionIds: Set<String>,
    /** Durable know-how extracted from those sessions. */
    val steps: List<String>,
    /** Evidence, so a generated skill stays traceable. */
    val citations: List<MemoryCitation> = emptyList(),
) {
    init {
        require(name.isNotBlank()) { "Pattern name must not be blank" }
        require(description.isNotBlank()) { "Pattern description must not be blank" }
    }

    /** How many distinct sessions demonstrated this pattern. */
    val repeats: Int get() = sessionIds.size
}

/** Why a pattern was or was not promoted to a skill. */
sealed interface PromotionDecision {
    data class Promote(val skill: GeneratedSkill) : PromotionDecision

    data class Reject(val reason: Reason, val detail: String) : PromotionDecision {
        enum class Reason {
            /** Seen once. A single occurrence is a fact, not a procedure. */
            TOO_FEW_REPEATS,

            /** The name would collide with a user-authored skill. */
            CONFLICTS_WITH_USER_SKILL,

            /** Identical content already exists, so writing again would commit nothing useful. */
            UNCHANGED,

            /** The pattern has no durable steps, so there is nothing to reuse. */
            NO_STEPS,

            /** The name is not a valid slug. */
            INVALID_NAME,
        }
    }
}

/** A SKILL.md that procedural memory proposes to write. */
data class GeneratedSkill(
    val name: String,
    val description: String,
    val body: String,
    /** Workspace-relative path under the memory subtree. */
    val path: String,
    val repeats: Int,
    val citations: List<MemoryCitation>,
)

/**
 * Turns detected patterns into skills.
 *
 * Conservative in three ways, each because the failure would be silent:
 *
 * - A pattern seen once is not a procedure. Promoting it fills the index with one-off noise,
 *   and the index is what the model scans before deciding to open anything.
 * - A generated skill never overwrites a user-authored one. A memory pass that silently
 *   replaces the user's own file is worse than one that leaves it alone.
 * - Repeat counting is by distinct session, because a session that restates a task is still one
 *   occurrence of that task.
 */
class ProceduralMemoryPromoter(
    private val minRepeats: Int = 2,
) {
    init {
        require(minRepeats >= 1) { "minRepeats must be at least 1" }
    }

    /**
     * Decides what to write for [pattern].
     *
     * [userSkillNames] are the names of skills the user wrote in `skills/`. A collision is
     * rejected rather than resolved by renaming: a renamed procedure is a different procedure
     * to the model choosing between them.
     */
    fun decide(
        pattern: DetectedPattern,
        existingGenerated: String?,
        userSkillNames: Set<String>,
    ): PromotionDecision {
        if (pattern.repeats < minRepeats) {
            return PromotionDecision.Reject(
                PromotionDecision.Reject.Reason.TOO_FEW_REPEATS,
                "'${pattern.name}' was seen in ${pattern.repeats} session(s), needs $minRepeats",
            )
        }
        if (!NAME_PATTERN.matches(pattern.name)) {
            return PromotionDecision.Reject(
                PromotionDecision.Reject.Reason.INVALID_NAME,
                "'${pattern.name}' must be lowercase letters, digits and dashes",
            )
        }
        if (pattern.name in userSkillNames) {
            return PromotionDecision.Reject(
                PromotionDecision.Reject.Reason.CONFLICTS_WITH_USER_SKILL,
                "A user-authored skill named '${pattern.name}' already exists",
            )
        }
        if (pattern.steps.isEmpty()) {
            return PromotionDecision.Reject(
                PromotionDecision.Reject.Reason.NO_STEPS,
                "'${pattern.name}' has no durable steps to reuse",
            )
        }

        val skill = render(pattern)
        if (existingGenerated == document(skill)) {
            // Rewriting identical content produces a commit with no information.
            return PromotionDecision.Reject(
                PromotionDecision.Reject.Reason.UNCHANGED,
                "'${pattern.name}' is already up to date",
            )
        }
        return PromotionDecision.Promote(skill)
    }

    /** Renders the skill body. Deterministic, so an unchanged pattern yields no diff. */
    fun render(pattern: DetectedPattern): GeneratedSkill {
        val body = buildString {
            appendLine("# ${pattern.description}")
            appendLine()
            appendLine("Learned from ${pattern.repeats} sessions.")
            appendLine()
            for ((index, step) in pattern.steps.withIndex()) {
                appendLine("${index + 1}. $step")
            }
            if (pattern.citations.isNotEmpty()) {
                appendLine()
                append(renderCitations(pattern.citations))
            }
        }
        return GeneratedSkill(
            name = pattern.name,
            description = pattern.description,
            body = body,
            path = "$SKILLS_PREFIX${pattern.name}/SKILL.md",
            repeats = pattern.repeats,
            citations = pattern.citations,
        )
    }

    /**
     * The full `SKILL.md`, including the frontmatter the skill loader requires.
     *
     * Kept as the single place the header is produced, so this writer and the loader's parser
     * cannot disagree about the format.
     */
    fun document(skill: GeneratedSkill): String = buildString {
        appendLine("---")
        appendLine("name: ${skill.name}")
        appendLine("description: ${skill.description}")
        appendLine("---")
        appendLine()
        append(skill.body)
    }

    companion object {
        /**
         * Where generated skills live.
         *
         * Under the memory subtree rather than beside user skills: generated procedures are
         * memory artifacts, they belong in the git-committed region, and keeping them apart is
         * what makes a collision with a user skill detectable instead of silently overwriting.
         */
        const val SKILLS_PREFIX = "memory/skills/"

        private val NAME_PATTERN = Regex("^[a-z0-9][a-z0-9-]*$")
    }
}
