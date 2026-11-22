package com.cordiact.interaction

data class InputCompletionTarget(val kind: Char, val start: Int, val endExclusive: Int, val query: String)
data class InputCompletionCandidate(val label: String, val insertText: String, val detail: String = "")

/** Lexical shielding shared by the editor and the submission parser. Offsets are UTF-16. */
object InputSyntax {
    fun validFillKey(key: String): Boolean = key.length in 1..64 && key.all { it.isLetterOrDigit() || it == '_' || it == '-' }
    fun isActive(text: String, index: Int): Boolean {
        if (index !in text.indices) return false
        var i = 0
        var inlineTicks = 0
        var fenceChar: Char? = null
        var fenceLength = 0
        while (i <= index) {
            if (text[i] == '\\' && fenceChar == null && inlineTicks == 0) {
                if (i == index || i + 1 == index) return false
                i += 2; continue
            }
            if (text[i] == '`' || text[i] == '~') {
                val marker = text[i]
                var end = i + 1
                while (end < text.length && text[end] == marker) end++
                val count = end - i
                val lineStart = text.lastIndexOf('\n', i - 1) + 1
                val linePrefix = text.substring(lineStart, i)
                val atLineStart = linePrefix.length <= 3 && linePrefix.all { it == ' ' }
                if (count >= 3 && atLineStart && inlineTicks == 0) {
                    if (fenceChar == null) { fenceChar = marker; fenceLength = count }
                    else if (fenceChar == marker && count >= fenceLength) fenceChar = null
                    if (index < end) return false
                } else if (marker == '`' && fenceChar == null) {
                    if (inlineTicks == 0) inlineTicks = count else if (inlineTicks == count) inlineTicks = 0
                    if (index < end) return false
                }
                i = end; continue
            }
            if (i == index) {
                if (fenceChar != null || inlineTicks != 0) return false
                var start = i
                while (start > 0 && !text[start - 1].isWhitespace()) start--
                val word = text.substring(start, i + 1)
                return !word.contains("://") && !word.startsWith("www.")
            }
            i++
        }
        return false
    }
    fun completionTarget(text: String, cursor: Int): InputCompletionTarget? {
        if (cursor !in 0..text.length) return null
        var start = cursor - 1
        while (start >= 0 && !text[start].isWhitespace()) {
            val kind = text[start]
            if (kind in "/@#" && isActive(text, start)) {
                if (kind == '/' && start != 0) return null
                if (start > 0 && (text[start - 1] in "_/@#" || text[start - 1].isLetterOrDigit() && text[start - 1].code < 128)) return null
                val explicit = start + 1 < text.length && text[start + 1] == '{'
                val queryStart = start + if (explicit) 2 else 1
                if (cursor < queryStart) return null
                val query = text.substring(queryStart, cursor)
                if (query.any { !(it.isLetterOrDigit() || it in "_-.:：") }) return null
                var end = cursor
                while (end < text.length && (text[end].isLetterOrDigit() || text[end] in "_-.:：")) end++
                if (explicit && end < text.length && text[end] == '}') end++
                return InputCompletionTarget(kind, start, end, query)
            }
            if (text[start] in "},;，。；") return null
            start--
        }
        return null
    }
}

fun completionCandidates(target: InputCompletionTarget, commands: InteractionCommandRegistry,
    directory: MentionDirectory, fills: FillValueStore, context: InteractionContext): List<InputCompletionCandidate> = when(target.kind) {
    '/' -> commands.candidates(context).flatMap { command -> (listOf(command.name) + command.aliases).map { name ->
        InputCompletionCandidate("/$name", "/$name ", if (command.allowsEmptyBody) "本地命令" else "需要输入内容")
    } }
    '#' -> fills.keys().sorted().map { key -> InputCompletionCandidate("#$key", "#{${key}}", fills.get(key).orEmpty().take(100)) }
    '@' -> run {
        val roots = directory.children(context, null)
        (roots + roots.filter { it.type == MentionType.WORKSPACE && it.id == context.workspaceId }
            .flatMap { directory.children(context, it.id) }).distinctBy { it.id }
    }.map { item ->
        InputCompletionCandidate(item.label, "@{${item.id}} ", item.relativePath ?: item.type.name)
    }
    else -> emptyList()
}.filter { it.label.contains(target.query, ignoreCase = true) ||
    (target.kind == '@' && it.insertText.contains(target.query, ignoreCase = true)) }
