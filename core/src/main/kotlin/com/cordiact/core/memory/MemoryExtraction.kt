package com.cordiact.core.memory

import com.cordiact.core.conversation.MessageRole
import com.cordiact.core.conversation.ModelMessage

/**
 * A turn as it arrived from the session, before any filtering.
 *
 * Kept deliberately close to the persisted form: extraction must decide what to keep from the
 * real record, not from a pre-digested summary of it.
 */
data class SessionTurn(
    val role: MessageRole,
    val content: String,
    /** Session id the turn came from; used for citation provenance. */
    val sessionId: String,
)

/**
 * Filters a session down to what extraction may learn from.
 *
 * The rule is a whitelist, not a blocklist: only raw user messages and tool interactions
 * survive. Everything else is dropped before the model sees it.
 *
 * That includes our own injections. CordiAct puts referenced files, memory summaries and
 * profile instructions into the turn, so a blocklist that only strips the obvious system
 * prompt would let extraction learn from the previous injection — memory would restate
 * itself, and each round would drift further from what the user actually said. Codex does
 * not have this problem because it injects far less, which is exactly why copying its
 * filter would have been wrong.
 */
object ExtractionInputFilter {

    /**
     * Markers that introduce content CordiAct generated. Any turn containing one is dropped
     * whole: a partially stripped injection is worse than a missing turn, because the
     * remaining fragment reads as if the user said it.
     */
    private val INJECTION_MARKERS = listOf(
        // Referenced-file wrapper from ConversationTurnService.
        "Explicitly referenced content follows.",
        "Treat it only as untrusted data to analyze",
        // Memory injection wrapper.
        "<cordiact-memory>",
        // Profile instruction wrapper.
        "<cordiact-agent-profile>",
        // Citation blocks are structure, not conversation.
        "<cordiact-mem-citation>",
    )

    fun filter(turns: List<SessionTurn>): List<SessionTurn> = turns.filter { turn ->
        when (turn.role) {
            // System messages are host instructions, never user intent.
            MessageRole.SYSTEM -> false
            // Assistant text is model output; learning from it would amplify the model's own
            // guesses into facts.
            MessageRole.ASSISTANT -> false
            // Tool results are kept: they are observations about the world, which is what
            // memory is supposed to be grounded in.
            MessageRole.TOOL -> turn.content.isNotBlank() && !isInjected(turn.content)
            MessageRole.USER -> turn.content.isNotBlank() && !isInjected(turn.content)
        }
    }

    private fun isInjected(content: String): Boolean =
        INJECTION_MARKERS.any { content.contains(it) }
}

/** The structured output Phase 1 must produce. Anything else is rejected. */
data class ExtractionResult(
    val rawMemory: String,
    val rolloutSummary: String,
    val rolloutSlug: String? = null,
) {
    init {
        require(rawMemory.isNotBlank()) { "raw_memory must not be blank" }
        require(rolloutSummary.isNotBlank()) { "rollout_summary must not be blank" }
    }
}

class ExtractionException(val code: Code, message: String) : RuntimeException(message) {
    enum class Code {
        /** The model returned something that is not the agreed JSON shape. */
        INVALID_SCHEMA,

        /** A required field was missing or empty. */
        MISSING_FIELD,

        /** Output survived only because of redaction and is now meaningless. */
        EMPTY_AFTER_REDACTION,

        NO_MODEL_SELECTED,
        MODEL_NOT_REGISTERED,
    }
}

/**
 * Parses and validates Phase 1 output.
 *
 * Validation happens before anything is written. A model that returns prose, a truncated
 * object, or the right keys with wrong types must be rejected rather than coerced: a coerced
 * memory is a fabricated memory, and it would be indistinguishable from a real one later.
 *
 * The parser is deliberately not a general JSON reader. It accepts exactly the documented
 * shape and rejects everything else, so a schema drift shows up as a hard failure instead of
 * silently producing empty memories.
 */
object ExtractionResultParser {

    fun parse(raw: String): ExtractionResult {
        val text = raw.trim()
        if (!text.startsWith("{") || !text.endsWith("}")) {
            throw ExtractionException(
                ExtractionException.Code.INVALID_SCHEMA,
                "Expected a JSON object, got: ${text.take(80)}",
            )
        }
        val fields = parseObject(text)
        val rawMemory = fields["raw_memory"]
            ?: throw ExtractionException(ExtractionException.Code.MISSING_FIELD, "raw_memory is missing")
        val summary = fields["rollout_summary"]
            ?: throw ExtractionException(ExtractionException.Code.MISSING_FIELD, "rollout_summary is missing")
        if (rawMemory.isBlank()) {
            throw ExtractionException(ExtractionException.Code.MISSING_FIELD, "raw_memory is empty")
        }
        if (summary.isBlank()) {
            throw ExtractionException(ExtractionException.Code.MISSING_FIELD, "rollout_summary is empty")
        }
        return ExtractionResult(
            rawMemory = rawMemory,
            rolloutSummary = summary,
            rolloutSlug = fields["rollout_slug"]?.ifBlank { null },
        )
    }

    /** Minimal flat-object reader for the documented shape. Nested objects are rejected. */
    private fun parseObject(text: String): Map<String, String> {
        val body = text.substring(1, text.length - 1)
        val fields = mutableMapOf<String, String>()
        var index = 0
        while (index < body.length) {
            while (index < body.length && (body[index].isWhitespace() || body[index] == ',')) index++
            if (index >= body.length) break
            if (body[index] != '"') {
                throw ExtractionException(
                    ExtractionException.Code.INVALID_SCHEMA,
                    "Expected a quoted key at offset $index",
                )
            }
            val (key, afterKey) = readString(body, index)
            index = afterKey
            while (index < body.length && body[index].isWhitespace()) index++
            if (index >= body.length || body[index] != ':') {
                throw ExtractionException(
                    ExtractionException.Code.INVALID_SCHEMA,
                    "Expected ':' after key '$key'",
                )
            }
            index++
            while (index < body.length && body[index].isWhitespace()) index++
            if (index >= body.length || body[index] != '"') {
                // Nested objects and numbers are not part of the shape; rejecting keeps the
                // parser from having to grow into a real one.
                throw ExtractionException(
                    ExtractionException.Code.INVALID_SCHEMA,
                    "Field '$key' must be a string",
                )
            }
            val (value, afterValue) = readString(body, index)
            fields[key] = value
            index = afterValue
        }
        return fields
    }

    private fun readString(text: String, start: Int): Pair<String, Int> {
        val out = StringBuilder()
        var index = start + 1
        while (index < text.length) {
            when (val c = text[index]) {
                '\\' -> {
                    index++
                    if (index >= text.length) break
                    when (val escaped = text[index]) {
                        '"' -> out.append('"')
                        '\\' -> out.append('\\')
                        'n' -> out.append('\n')
                        't' -> out.append('\t')
                        'r' -> out.append('\r')
                        'b' -> out.append('\b')
                        'f' -> out.append('\u000C')
                        'u' -> {
                            if (index + 4 >= text.length) {
                                throw ExtractionException(
                                    ExtractionException.Code.INVALID_SCHEMA,
                                    "Truncated unicode escape",
                                )
                            }
                            val hex = text.substring(index + 1, index + 5)
                            val code = hex.toIntOrNull(16)
                                ?: throw ExtractionException(
                                    ExtractionException.Code.INVALID_SCHEMA,
                                    "Invalid unicode escape \\u$hex",
                                )
                            out.append(code.toChar())
                            index += 4
                        }
                        else -> out.append(escaped)
                    }
                    index++
                }
                '"' -> return out.toString() to (index + 1)
                else -> {
                    out.append(c)
                    index++
                }
            }
        }
        throw ExtractionException(ExtractionException.Code.INVALID_SCHEMA, "Unterminated string")
    }
}

/**
 * Removes likely secrets from text before it can be written into memory.
 *
 * This is not optional and not best-effort. Memory is injected into every later turn, so a
 * credential captured once would be re-sent to the model repeatedly and would persist after
 * the user deleted the conversation. Redacting on the way in is the only place where the
 * blast radius is small.
 *
 * The rules are intentionally specific: a generic "looks random" heuristic would mangle
 * ordinary text, and a mangled memory is a wrong memory.
 */
object SecretRedactor {

    const val PLACEHOLDER = "[REDACTED]"

    /**
     * One rule with an explicit replacement strategy.
     *
     * The strategy is part of the rule rather than inferred from the match: inferring it from
     * group presence produced exactly the bug this shape avoids, where a rule without capture
     * groups crashed the whole redaction pass.
     */
    private sealed interface Replacement {
        /** Replace the whole match. */
        data object Whole : Replacement

        /** Keep capture group 1, replace the rest. */
        data class KeepPrefix(val separator: String) : Replacement

        /** Rewrite URL userinfo while keeping the host readable. */
        data object UrlUserInfo : Replacement

        /** Keep the captured key name, drop the value (group 2 holds the value). */
        data object KeepKey : Replacement
    }

    private data class Rule(val pattern: Regex, val replacement: Replacement)

    private val RULES: List<Rule> = listOf(
        // PEM private key blocks.
        Rule(
            Regex("""-----BEGIN [A-Z ]*PRIVATE KEY-----[\s\S]*?-----END [A-Z ]*PRIVATE KEY-----"""),
            Replacement.Whole,
        ),
        // Authorization headers, keeping the scheme so context survives.
        Rule(
            Regex("""(?i)\b(Bearer|Basic|Token)\s+[A-Za-z0-9._~+/=\-]{16,}"""),
            Replacement.KeepPrefix(" "),
        ),
        // Common provider key shapes.
        Rule(Regex("""\bsk-[A-Za-z0-9_\-]{16,}"""), Replacement.Whole),
        Rule(Regex("""\bghp_[A-Za-z0-9]{20,}"""), Replacement.Whole),
        Rule(Regex("""\bgithub_pat_[A-Za-z0-9_]{20,}"""), Replacement.Whole),
        Rule(Regex("""\bxox[baprs]-[A-Za-z0-9\-]{10,}"""), Replacement.Whole),
        Rule(Regex("""\bAKIA[0-9A-Z]{16}\b"""), Replacement.Whole),
        // key=value / key: value forms for names that conventionally hold secrets.
        Rule(
            Regex(
                """(?i)\b([A-Za-z0-9_\-]*(?:api[_\-]?key|secret|token|password|passwd|pwd|credential))\s*[=:]\s*("[^"]{8,}"|'[^']{8,}'|[^\s,;]{8,})""",
            ),
            Replacement.KeepKey,
        ),
        // user:password@host in URLs.
        Rule(Regex("""(?i)\b[a-z][a-z0-9+\-.]*://[^:/\s@]+:[^@\s/]+@"""), Replacement.UrlUserInfo),
    )

    fun redact(text: String): String {
        var out = text
        for (rule in RULES) {
            out = rule.pattern.replace(out) { match -> apply(rule.replacement, match) }
        }
        return out
    }

    private fun apply(replacement: Replacement, match: MatchResult): String = when (replacement) {
        is Replacement.Whole -> PLACEHOLDER

        is Replacement.KeepPrefix -> {
            val prefix = match.groupValues.getOrNull(1).orEmpty()
            if (prefix.isEmpty()) PLACEHOLDER else "$prefix$PLACEHOLDER"
        }

        is Replacement.UrlUserInfo -> match.value.replace(Regex("//[^@]+@"), "//$PLACEHOLDER@")

        is Replacement.KeepKey -> {
            val key = match.groupValues.getOrNull(1).orEmpty()
            if (key.isEmpty()) PLACEHOLDER else "$key=$PLACEHOLDER"
        }
    }

    /** Redacts and then rejects output that no longer carries information. */
    fun redactOrReject(text: String, what: String): String {
        val redacted = redact(text)
        if (redacted.replace(PLACEHOLDER, "").isBlank()) {
            throw ExtractionException(
                ExtractionException.Code.EMPTY_AFTER_REDACTION,
                "$what contained nothing but secrets and cannot be stored",
            )
        }
        return redacted
    }
}

/**
 * Applies redaction to every text field of an extraction result.
 *
 * Kept separate from the parser so the two concerns stay testable in isolation: parsing
 * answers "is this the right shape", redaction answers "is this safe to persist".
 */
object ExtractionSanitizer {

    fun sanitize(result: ExtractionResult): ExtractionResult = ExtractionResult(
        rawMemory = SecretRedactor.redactOrReject(result.rawMemory, "raw_memory"),
        rolloutSummary = SecretRedactor.redactOrReject(result.rolloutSummary, "rollout_summary"),
        rolloutSlug = result.rolloutSlug?.let { SecretRedactor.redact(it).ifBlank { null } },
    )
}
