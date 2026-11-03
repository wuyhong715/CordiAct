package com.cordiact.interaction

private data class ParsedHeader(
    val command: CommandDescriptor?,
    val bodyStart: Int,
    val error: InteractionDiagnostic? = null,
    val token: InteractionToken? = null,
)

/** Pure parser used by both editor diagnostics and final send-time validation. */
class InteractionParser(
    private val commands: InteractionCommandRegistry,
    private val directory: MentionDirectory,
    private val fills: FillValueStore,
) {
    fun parse(text: String, context: InteractionContext): PreparedInteraction {
        val tokens = mutableListOf<InteractionToken>()
        val diagnostics = mutableListOf<InteractionDiagnostic>()
        val header = parseHeader(text)
        header.token?.let(tokens::add)
        header.error?.let(diagnostics::add)
        if (text.isBlank()) {
            diagnostics += InteractionDiagnostic("message_empty", "Message must not be empty", 0, text.length)
        }

        val bodyStart = header.bodyStart.coerceIn(0, text.length)
        val references = parseMentions(text, bodyStart, context, tokens, diagnostics)
        val bindings = resolvePronouns(text, references, tokens, diagnostics)
        val submitted = expandFills(text, bodyStart, tokens, diagnostics)
        val command = header.command

        if (command?.requiresMention == true && references.isEmpty()) {
            diagnostics += InteractionDiagnostic(
                code = "mention_required",
                message = "This command requires at least one explicit @ recipient",
                start = bodyStart,
                endExclusive = text.length,
            )
        }
        if (command != null && references.isNotEmpty() && command.allowedMentionTypes.isNotEmpty()) {
            references.forEach { reference ->
                reference.entities.filter { it.type !in command.allowedMentionTypes }.forEach { entity ->
                    diagnostics += InteractionDiagnostic(
                        code = "mention_type_not_allowed",
                        message = "Command '${command.name}' does not accept ${entity.type.name.lowercase()} references",
                        start = reference.start,
                        endExclusive = reference.endExclusive,
                    )
                }
            }
        }

        val envelope = if (diagnostics.isEmpty()) {
            InteractionEnvelope(
                context = context,
                originalText = text,
                submittedText = submitted,
                commandInput = if (command == null) submitted else submitted.substring(bodyStart.coerceAtMost(submitted.length)),
                command = command,
                mentions = references,
                pronounBindings = bindings,
                requiredCapabilities = command?.requiredCapabilities.orEmpty() + references.flatMap { ref ->
                    ref.entities.flatMap(MentionDescriptor::requiredCapabilities)
                },
            )
        } else null
        return PreparedInteraction(envelope, tokens.sortedBy { it.start }, diagnostics.sortedBy { it.start })
    }

    private fun parseHeader(text: String): ParsedHeader {
        if (!text.startsWith('/')) return ParsedHeader(command = null, bodyStart = 0)
        if (text.length == 1) return invalidHeader("command_missing", "Choose a registered command", 0, 1)

        val names = commands.candidates().flatMap { descriptor ->
            (descriptor.aliases + descriptor.name).map { it to descriptor }
        }.distinctBy { it.first }.sortedByDescending { it.first.length }
        val match = names.firstOrNull { (name, _) -> text.startsWith("/$name") }
            ?: return invalidHeader("unknown_command", "Unknown or disabled behavior command", 0, tokenEnd(text, 0))
        val (name, command) = match
        val index = 1 + name.length
        if (index == text.length) {
            return invalidHeader("command_separator_missing", "Separate the command from its body with ':' or spaces", 0, index)
        }
        if (text[index] != '：' && text[index] != ':' && !text[index].isWhitespace()) {
            return invalidHeader("command_separator_missing", "Use a complete registered command name followed by ':' or spaces", 0, index + 1)
        }

        var bodyStart = index
        if (text[bodyStart] == '：' || text[bodyStart] == ':') bodyStart++
        else while (bodyStart < text.length && text[bodyStart].isWhitespace()) bodyStart++

        val token = InteractionToken(InteractionTokenKind.COMMAND, 0, bodyStart, TokenStatus.VALID, command.id)
        if (bodyStart == text.length && !command.allowsEmptyBody) {
            return ParsedHeader(
                command = command,
                bodyStart = bodyStart,
                error = InteractionDiagnostic("command_body_empty", "Command body must not be empty", 0, bodyStart),
                token = token.copy(status = TokenStatus.INVALID),
            )
        }
        return ParsedHeader(command, bodyStart, token = token)
    }

    private fun invalidHeader(code: String, message: String, start: Int, end: Int) = ParsedHeader(
        command = null,
        bodyStart = end,
        error = InteractionDiagnostic(code, message, start, end),
        token = InteractionToken(InteractionTokenKind.COMMAND, start, end, TokenStatus.INVALID),
    )

    private fun parseMentions(
        text: String,
        bodyStart: Int,
        context: InteractionContext,
        tokens: MutableList<InteractionToken>,
        diagnostics: MutableList<InteractionDiagnostic>,
    ): List<MentionReference> {
        val result = mutableListOf<MentionReference>()
        var index = bodyStart
        while (index < text.length) {
            if (text[index] == '：' && index + 1 == text.length) {
                val start = (index - 1).coerceAtLeast(bodyStart)
                val end = text.length
                tokens += InteractionToken(InteractionTokenKind.MENTION, start, end, TokenStatus.INCOMPLETE)
                diagnostics += InteractionDiagnostic(
                    "mention_child_incomplete",
                    "Choose a second-level recipient or remove the hierarchy separator",
                    start,
                    end,
                )
                break
            }
            if (text[index] != '@' || isEscaped(text, index)) {
                index++
                continue
            }
            val parsed = parseOneMention(text, index, context)
            if (parsed == null) {
                val end = tokenEnd(text, index)
                val status = if (index + 1 == text.length) TokenStatus.INCOMPLETE else TokenStatus.INVALID
                tokens += InteractionToken(InteractionTokenKind.MENTION, index, end, status)
                diagnostics += InteractionDiagnostic(
                    if (status == TokenStatus.INCOMPLETE) "mention_incomplete" else "mention_unresolved",
                    if (status == TokenStatus.INCOMPLETE) "Choose a recipient before sending" else "Unknown or ambiguous recipient",
                    index,
                    end,
                )
                index = end.coerceAtLeast(index + 1)
            } else {
                result += parsed
                tokens += InteractionToken(
                    InteractionTokenKind.MENTION,
                    parsed.start,
                    parsed.endExclusive,
                    TokenStatus.VALID,
                    parsed.entities.joinToString("/") { it.id },
                )
                index = parsed.endExclusive
            }
        }
        return result
    }

    private fun parseOneMention(text: String, start: Int, context: InteractionContext): MentionReference? {
        if (text.startsWith("@{", start)) {
            val firstEnd = text.indexOf('}', start + 2)
            if (firstEnd < 0 || firstEnd == start + 2) return null
            val first = directory.find(context, text.substring(start + 2, firstEnd)) ?: return null
            var end = firstEnd + 1
            val entities = mutableListOf(first)
            if (text.startsWith("：@{", end)) {
                val secondEnd = text.indexOf('}', end + 3)
                if (secondEnd < 0 || secondEnd == end + 3) return null
                val second = directory.find(context, text.substring(end + 3, secondEnd)) ?: return null
                if (second.parentId != first.id) return null
                entities += second
                end = secondEnd + 1
            }
            return MentionReference(entities, start, end)
        }

        val roots = directory.children(context).filter { descriptor ->
            descriptor.label.isNotEmpty() && text.startsWith(descriptor.label, start + 1)
        }
        if (roots.isEmpty()) return null
        val matches = mutableListOf<MentionReference>()
        roots.forEach { root ->
            val rootEnd = start + 1 + root.label.length
            matches += MentionReference(listOf(root), start, rootEnd)
            if (text.startsWith("：", rootEnd)) {
                val childStart = rootEnd + 1
                directory.children(context, root.id)
                    .filter { it.parentId == root.id && it.label.isNotEmpty() && text.startsWith(it.label, childStart) }
                    .forEach { child ->
                        val end = childStart + child.label.length
                        matches += MentionReference(listOf(root, child), start, end)
                    }
            }
        }
        val longest = matches.maxOfOrNull { it.endExclusive } ?: return null
        val best = matches.filter { it.endExclusive == longest }
            .distinctBy { it.entities.joinToString("/") { entity -> entity.id } }
        return best.singleOrNull()
    }

    private fun resolvePronouns(
        text: String,
        mentions: List<MentionReference>,
        tokens: MutableList<InteractionToken>,
        diagnostics: MutableList<InteractionDiagnostic>,
    ): Map<String, String> {
        if (mentions.isEmpty()) return emptyMap()
        val bindings = linkedMapOf<String, String>()
        val occurrences = listOf("前者", "后者", "该工作区", "该文件", "它")
            .flatMap { pronoun -> Regex(Regex.escape(pronoun)).findAll(text).map { pronoun to it } }
            .filter { (_, match) -> mentions.any { it.start < match.range.first } }
            .sortedBy { it.second.range.first }

        occurrences.forEach { (pronoun, match) ->
            val prior = mentions.filter { it.start < match.range.first }
            val target = when (pronoun) {
                "前者" -> prior.getOrNull(prior.lastIndex - 1)
                "后者" -> prior.lastOrNull()
                "该工作区" -> prior.lastOrNull { it.leaf.type == MentionType.WORKSPACE }
                "该文件" -> prior.lastOrNull { it.leaf.type == MentionType.FILE || it.leaf.type == MentionType.FOLDER }
                else -> prior.lastOrNull()
            }
            val compatible = when (pronoun) {
                "该工作区" -> target?.leaf?.type == MentionType.WORKSPACE
                "该文件" -> target?.leaf?.type in setOf(MentionType.FILE, MentionType.FOLDER)
                else -> target != null
            }
            val start = match.range.first
            val end = match.range.last + 1
            if (!compatible || target == null) {
                tokens += InteractionToken(InteractionTokenKind.PRONOUN, start, end, TokenStatus.INVALID, pronoun)
                diagnostics += InteractionDiagnostic(
                    "pronoun_unresolved",
                    "Pronoun '$pronoun' has no compatible earlier reference",
                    start,
                    end,
                )
            } else {
                bindings["$pronoun@$start"] = target.leaf.id
                tokens += InteractionToken(InteractionTokenKind.PRONOUN, start, end, TokenStatus.VALID, target.leaf.id)
            }
        }
        return bindings
    }

    private fun expandFills(
        text: String,
        bodyStart: Int,
        tokens: MutableList<InteractionToken>,
        diagnostics: MutableList<InteractionDiagnostic>,
    ): String {
        val output = StringBuilder(text.length)
        var cursor = 0
        val pattern = Regex("(?<![A-Za-z0-9_])#([A-Za-z0-9_-]+)(?![A-Za-z0-9_-])")
        pattern.findAll(text).forEach { match ->
            val key = match.groupValues[1]
            if (match.range.first < bodyStart) return@forEach
            val start = match.range.first
            val end = match.range.last + 1
            val value = fills.get(key)
            if (value == null) {
                tokens += InteractionToken(InteractionTokenKind.FILL, start, end, TokenStatus.INVALID, key)
                diagnostics += InteractionDiagnostic("fill_undefined", "No local value is defined for #$key", start, end)
            } else {
                tokens += InteractionToken(InteractionTokenKind.FILL, start, end, TokenStatus.VALID, key)
                output.append(text, cursor, start).append(value)
                cursor = end
            }
        }
        output.append(text, cursor, text.length)
        return output.toString()
    }

    private fun tokenEnd(text: String, start: Int): Int {
        var end = start + 1
        while (end < text.length && !text[end].isWhitespace() && text[end] !in "，。；,.;!?！？") end++
        return end
    }

    private fun isEscaped(text: String, index: Int): Boolean {
        var slashes = 0
        var cursor = index - 1
        while (cursor >= 0 && text[cursor] == '\\') {
            slashes++
            cursor--
        }
        return slashes % 2 == 1
    }
}
