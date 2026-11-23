package com.cordiact.app.experience.model

/**
 * Pure Kotlin block splitter for assistant Markdown.
 * Splits raw markdown into prose chunks, fenced code blocks, and GFM tables,
 * while repairing LLM outputs where a GFM table starts immediately after text without a blank line.
 */
sealed interface MarkdownBlock {
    data class Prose(val text: String) : MarkdownBlock
    data class Code(val language: String?, val code: String, val closed: Boolean) : MarkdownBlock
    data class Table(val headers: List<String>, val rows: List<List<String>>, val raw: String) : MarkdownBlock
}

private val TABLE_SEP_CELL = Regex("^:?-{3,}:?$")
private val FENCE_START = Regex("^\\s{0,3}(`{3,}|~{3,})(.*)$")

internal fun normalizeMarkdownForDisplay(raw: String): String {
    if (raw.isEmpty()) return raw
    val lines = raw.replace("\r\n", "\n").split('\n')
    val out = ArrayList<String>(lines.size + 4)
    var inFence = false
    var fenceChar = '`'
    var fenceLen = 3
    for (i in lines.indices) {
        val line = lines[i]
        val fence = FENCE_START.matchEntire(line)
        if (!inFence && fence != null) {
            inFence = true
            val marker = fence.groupValues[1]
            fenceChar = marker[0]
            fenceLen = marker.length
            out.add(line)
            continue
        }
        if (inFence) {
            val trimmed = line.trim()
            if (trimmed.length >= fenceLen && trimmed.all { it == fenceChar }) inFence = false
            out.add(line)
            continue
        }
        // Repair inline table starting on the same line after a heading/numbered label:
        // e.g. "1. 标题 | 列1 | 列2 |" followed by "|---|---|" or "||---|---|"
        val next = lines.getOrNull(i + 1)
        if (next != null && isTableSeparatorLine(next) && !looksLikePureTableRow(line)) {
            val pipeIdx = line.indexOf('|')
            if (pipeIdx > 0) {
                val prefix = line.substring(0, pipeIdx).trimEnd()
                val tableHead = line.substring(pipeIdx).trim()
                if (prefix.isNotBlank() && splitTableCells(tableHead).size >= 2) {
                    out.add(prefix)
                    out.add("")
                    out.add(tableHead)
                    continue
                }
            }
        }
        // Repair a GFM table immediately following non-blank text without an empty line.
        if (next != null && isTableSeparatorLine(next) && looksLikePureTableRow(line)) {
            val prev = out.lastOrNull()
            if (prev != null && prev.isNotBlank()) out.add("")
        }
        out.add(line)
    }
    return out.joinToString("\n")
}

internal fun splitMarkdownBlocks(raw: String): List<MarkdownBlock> {
    if (raw.isBlank()) return emptyList()
    val lines = normalizeMarkdownForDisplay(raw).split('\n')
    val blocks = mutableListOf<MarkdownBlock>()
    val prose = mutableListOf<String>()

    fun flushProse() {
        val text = prose.joinToString("\n").trim('\n')
        if (text.isNotBlank()) blocks += MarkdownBlock.Prose(text)
        prose.clear()
    }

    var i = 0
    while (i < lines.size) {
        val line = lines[i]
        // 1. Fenced code block
        val fence = FENCE_START.matchEntire(line)
        if (fence != null) {
            flushProse()
            val marker = fence.groupValues[1]
            val fenceChar = marker[0]
            val fenceLen = marker.length
            val lang = fence.groupValues[2].trim().split(Regex("\\s+")).firstOrNull()?.takeIf { it.isNotBlank() }
            val codeLines = mutableListOf<String>()
            var closed = false
            i++
            while (i < lines.size) {
                val trimmed = lines[i].trim()
                if (trimmed.length >= fenceLen && trimmed.all { it == fenceChar }) {
                    closed = true
                    i++
                    break
                }
                codeLines += lines[i]
                i++
            }
            blocks += MarkdownBlock.Code(lang, codeLines.joinToString("\n"), closed)
            continue
        }

        // 2. GFM table (header + separator + 0..N rows)
        val next = lines.getOrNull(i + 1)
        if (next != null && looksLikePureTableRow(line) && isTableSeparatorLine(next)) {
            val headers = splitTableCells(line)
            val sepCells = splitTableCells(next)
            if (headers.size >= 2 && sepCells.size >= 2) {
                flushProse()
                val rawLines = mutableListOf(line, next)
                val rows = mutableListOf<List<String>>()
                i += 2
                while (i < lines.size && looksLikePureTableRow(lines[i])) {
                    val rowLine = lines[i]
                    rawLines += rowLine
                    val cells = splitTableCells(rowLine)
                    val normalized = List(headers.size) { col -> cells.getOrElse(col) { "" } }
                    rows += normalized
                    i++
                }
                blocks += MarkdownBlock.Table(headers, rows, rawLines.joinToString("\n"))
                continue
            }
        }

        // 3. Collapsed single-line pipe table (model emitted "1. 标题 | col1 | col2 | |---|---| | a | b |")
        if (line.contains("|---|") || line.contains("| --- |")) {
            val inline = parseCollapsedPipeTable(line)
            if (inline != null) {
                val (prefix, table) = inline
                if (prefix.isNotBlank()) prose += prefix
                flushProse()
                blocks += table
                i++
                continue
            }
        }

        prose += line
        i++
    }
    flushProse()
    return blocks
}

private fun looksLikePureTableRow(line: String): Boolean {
    val t = line.trim()
    if (t.isEmpty() || !t.contains('|')) return false
    if (t.startsWith(">") || t.startsWith("#")) return false
    return splitTableCells(t).size >= 2
}

private fun isTableSeparatorLine(line: String): Boolean {
    val t = line.trim()
    if (!t.contains('-') || !t.contains('|')) return false
    val cells = splitTableCells(t)
    return cells.size >= 2 && cells.all { TABLE_SEP_CELL.matches(it.trim()) }
}

internal fun splitTableCells(line: String): List<String> {
    var s = line.trim()
    if (s.startsWith("|")) s = s.substring(1)
    if (s.endsWith("|") && !s.endsWith("\\|")) s = s.dropLast(1)
    val cells = mutableListOf<String>()
    val cur = StringBuilder()
    var i = 0
    while (i < s.length) {
        val c = s[i]
        if (c == '\\' && i + 1 < s.length && s[i + 1] == '|') {
            cur.append('|')
            i += 2
            continue
        }
        if (c == '|') {
            cells += cur.toString().trim()
            cur.setLength(0)
        } else {
            cur.append(c)
        }
        i++
    }
    cells += cur.toString().trim()
    return cells
}

/** Handles rare collapsed tables written on one line separated by "||" (as observed in real device screenshots). */
private fun parseCollapsedPipeTable(line: String): Pair<String, MarkdownBlock.Table>? {
    val parts = line.split(Regex("\\|\\s*\\|"))
    if (parts.size < 2) return null
    val sepIdx = parts.indexOfFirst { isTableSeparatorLine("|$it|") }
    if (sepIdx <= 0) return null
    val headPart = parts[sepIdx - 1]
    val firstPipe = headPart.indexOf('|')
    val prefix = if (firstPipe > 0) headPart.substring(0, firstPipe).trim() else ""
    val headRow = if (firstPipe >= 0) headPart.substring(firstPipe) else headPart
    val headers = splitTableCells(headRow).filter { it.isNotBlank() }
    if (headers.size < 2) return null
    val rows = parts.drop(sepIdx + 1)
        .map { splitTableCells(it) }
        .filter { cells -> cells.any { it.isNotBlank() } }
        .map { cells -> List(headers.size) { col -> cells.getOrElse(col) { "" } } }
    if (rows.isEmpty()) return null
    val raw = buildString {
        append("| ").append(headers.joinToString(" | ")).append(" |\n")
        append("| ").append(headers.joinToString(" | ") { "---" }).append(" |")
        rows.forEach { r -> append("\n| ").append(r.joinToString(" | ")).append(" |") }
    }
    return prefix to MarkdownBlock.Table(headers, rows, raw)
}
