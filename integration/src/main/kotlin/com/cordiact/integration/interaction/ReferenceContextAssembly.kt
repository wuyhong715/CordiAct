package com.cordiact.integration.interaction

/**
 * How much referenced content one turn may carry.
 *
 * A whole-Workspace grant is bounded only by these numbers. Without them, `@somewhere` reads as
 * "send me everything", and the turn fails at the model's context limit — after paying for the
 * reads. The budget is enforced here rather than at the model boundary because at the model
 * boundary the bytes have already been read and concatenated.
 */
data class ReferenceBudget(
    val maxFiles: Int = 16,
    val maxTotalChars: Int = 120_000,
    val maxCharsPerFile: Int = 64_000,
) {
    init {
        require(maxFiles > 0) { "maxFiles must be positive" }
        require(maxTotalChars > 0) { "maxTotalChars must be positive" }
        require(maxCharsPerFile > 0) { "maxCharsPerFile must be positive" }
        require(maxCharsPerFile <= maxTotalChars) { "a single file must fit inside the total budget" }
    }
}

/** One resolved reference, ready to be wrapped for the prompt. */
data class ResolvedReference(
    val id: String,
    val type: MentionType,
    /** Workspace the content came from; differs from the session's for cross-Workspace reads. */
    val workspaceId: String,
    val path: String,
    val text: String,
)

/**
 * Why a reference was not included.
 *
 * Reported rather than silently dropped: a user who referenced five files and got three needs
 * to know which two were skipped and why, otherwise the model's answer looks inexplicably
 * uninformed.
 */
sealed interface ReferenceSkip {
    data class OverFileBudget(val id: String) : ReferenceSkip
    data class OverCharBudget(val id: String, val chars: Int) : ReferenceSkip
    data class Truncated(val id: String, val originalChars: Int, val keptChars: Int) : ReferenceSkip
    data class Unreadable(val id: String) : ReferenceSkip
    data class Denied(val id: String, val reason: String) : ReferenceSkip
}

data class ReferenceAssembly(
    val references: List<ResolvedReference>,
    val skipped: List<ReferenceSkip>,
) {
    val isEmpty: Boolean get() = references.isEmpty()

    /**
     * Renders the wrapped block, or null when there is nothing to send.
     *
     * The wrapper and its "data, not instructions" sentence live here rather than at the call
     * site so every referenced block, file or memory, is labelled the same way. A memory
     * without that label would be read as an instruction, and a wrong memory would then steer
     * behaviour for as long as it exists.
     */
    fun render(): String? {
        if (references.isEmpty()) return null
        return references.joinToString("\n\n") { reference ->
            val tag = when (reference.type) {
                MentionType.MEMORY -> "referenced-memory"
                else -> "referenced-file"
            }
            "<$tag id=\"${reference.id}\" workspace=\"${reference.workspaceId}\" path=\"${reference.path}\">\n" +
                "${reference.text}\n</$tag>"
        }
    }
}

/**
 * Resolves mentions into bounded, labelled context blocks.
 *
 * Ordering is by mention position and then by id, so the same references always produce the
 * same prompt. A prompt that reorders itself between turns confuses both the model and anyone
 * comparing two transcripts.
 *
 * Truncation is preferred over exclusion for an oversized file: dropping a file entirely makes
 * the model answer as if it were never referenced, while a truncated file at least signals that
 * more exists. The skip list records the truncation either way.
 */
class ReferenceContextAssembler(
    private val budget: ReferenceBudget = ReferenceBudget(),
) {

    fun assemble(
        mentions: List<MentionReference>,
        context: InteractionContext,
        read: (MentionDescriptor) -> String?,
    ): ReferenceAssembly {
        // Flatten preserving mention order, then dedupe by id: the same file mentioned twice is
        // still one file in the prompt, but which mention resolved it stays stable.
        val ordered = mentions
            .flatMap { it.entities }
            .filter { it.type == MentionType.FILE || it.type == MentionType.MEMORY }
            .distinctBy { it.id }
            .sortedWith(compareBy({ it.workspaceId }, { it.relativePath ?: "" }, { it.id }))

        val resolved = mutableListOf<ResolvedReference>()
        val skipped = mutableListOf<ReferenceSkip>()
        var usedChars = 0

        for (descriptor in ordered) {
            if (resolved.size >= budget.maxFiles) {
                skipped += ReferenceSkip.OverFileBudget(descriptor.id)
                continue
            }

            val text = try {
                read(descriptor)
            } catch (e: Exception) {
                null
            }
            if (text == null) {
                // Covers both "gone" and "not permitted": from the caller's side they are the
                // same outcome, and distinguishing them would leak whether a path exists.
                skipped += ReferenceSkip.Unreadable(descriptor.id)
                continue
            }

            val capped = text.take(budget.maxCharsPerFile)
            if (capped.length < text.length) {
                skipped += ReferenceSkip.Truncated(descriptor.id, text.length, capped.length)
            }

            if (usedChars + capped.length > budget.maxTotalChars) {
                skipped += ReferenceSkip.OverCharBudget(descriptor.id, capped.length)
                continue
            }

            usedChars += capped.length
            resolved += ResolvedReference(
                id = descriptor.id,
                type = descriptor.type,
                workspaceId = descriptor.workspaceId,
                path = descriptor.relativePath ?: "",
                text = capped,
            )
        }

        return ReferenceAssembly(references = resolved, skipped = skipped)
    }

    /**
     * Assembles with the Workspace boundary enforced per descriptor.
     *
     * Used by the host so that a descriptor which somehow reached this path without passing
     * authorization cannot be read: the grant is re-checked against the descriptor's own
     * prefix rather than trusted from whatever produced it.
     */
    fun assembleAuthorized(
        mentions: List<MentionReference>,
        context: InteractionContext,
        read: (MentionDescriptor) -> String?,
    ): ReferenceAssembly = assemble(mentions, context) { descriptor ->
        val path = descriptor.relativePath
        if (path == null) {
            null
        } else if (descriptor.workspaceId != context.workspaceId) {
            val grant = CrossWorkspaceGrant(descriptor.workspaceId, descriptor.authorizedPrefix)
            when (val decision = CrossWorkspaceAccess.check(grant, path)) {
                is CrossWorkspaceAccess.Decision.Allowed -> read(descriptor)
                is CrossWorkspaceAccess.Decision.Denied -> null
            }
        } else {
            read(descriptor)
        }
    }
}
