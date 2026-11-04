package com.cordiact.mcp.client

import com.cordiact.core.mcp.McpProtocolException

/**
 * Just enough JSON writing for the MCP messages this client sends.
 *
 * The request side is fixed and small, so building it with a StringBuilder avoids adding a
 * JSON dependency to a module that only needs to emit three shapes. Parsing lives in
 * [JsonValue] because responses are arbitrary.
 */
internal object JsonWrite {
    fun string(value: String): String = buildString {
        append('"')
        for (char in value) {
            when (char) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (char < ' ') append("\\u%04x".format(char.code)) else append(char)
            }
        }
        append('"')
    }

    fun number(value: Long): String = value.toString()
    fun bool(value: Boolean): String = value.toString()
}

/** The JSON value model used for MCP responses. */
internal sealed interface JsonValue {
    data class Obj(val fields: Map<String, JsonValue>) : JsonValue
    data class Arr(val items: List<JsonValue>) : JsonValue
    data class Str(val value: String) : JsonValue
    data class Num(val value: Double) : JsonValue
    data class Bool(val value: Boolean) : JsonValue
    data object Null : JsonValue

    fun asObject(): Obj = this as? Obj
        ?: throw McpProtocolException("expected a JSON object, got ${describe()}")

    fun asString(): String = (this as? Str)?.value
        ?: throw McpProtocolException("expected a JSON string, got ${describe()}")

    fun asBoolean(what: String): Boolean = (this as? Bool)?.value
        ?: throw McpProtocolException("$what must be a boolean, got ${describe()}")

    fun asArray(): List<JsonValue> = (this as? Arr)?.items
        ?: throw McpProtocolException("expected a JSON array, got ${describe()}")

    fun field(name: String): JsonValue? = (this as? Obj)?.fields?.get(name)

    fun describe(): String = when (this) {
        is Obj -> "object"
        is Arr -> "array"
        is Str -> "string"
        is Num -> "number"
        is Bool -> "boolean"
        Null -> "null"
    }

    /** Re-serialize, used to hand a schema or an argument object back as text. */
    fun encode(): String = when (this) {
        is Obj -> fields.entries.joinToString(",", "{", "}") { (key, value) -> JsonWrite.string(key) + ":" + value.encode() }
        is Arr -> items.joinToString(",", "[", "]") { it.encode() }
        is Str -> JsonWrite.string(value)
        is Num -> if (value == value.toLong().toDouble()) value.toLong().toString() else value.toString()
        is Bool -> value.toString()
        Null -> "null"
    }
}

/**
 * A strict JSON parser for one response body.
 *
 * Rejects trailing content and control characters in strings, which keeps a misbehaving
 * server from smuggling extra frames through one response.
 */
internal class JsonParser(private val text: String) {
    private var index = 0

    fun parseDocument(): JsonValue {
        val value = parseValue()
        skipWhitespace()
        if (index != text.length) throw McpProtocolException("trailing content at offset $index")
        return value
    }

    private fun parseValue(): JsonValue {
        skipWhitespace()
        return when (val char = peek()) {
            '{' -> parseObject()
            '[' -> parseArray()
            '"' -> JsonValue.Str(parseString())
            't' -> parseLiteral("true", JsonValue.Bool(true))
            'f' -> parseLiteral("false", JsonValue.Bool(false))
            'n' -> parseLiteral("null", JsonValue.Null)
            else -> if (char == '-' || char in '0'..'9') parseNumber() else {
                throw McpProtocolException("unexpected character '$char' at offset $index")
            }
        }
    }

    private fun parseObject(): JsonValue.Obj {
        expect('{')
        val fields = linkedMapOf<String, JsonValue>()
        skipWhitespace()
        if (peek() == '}') { index++; return JsonValue.Obj(fields) }
        while (true) {
            skipWhitespace()
            val key = parseString()
            skipWhitespace()
            expect(':')
            fields[key] = parseValue()
            skipWhitespace()
            when (peek()) {
                ',' -> index++
                '}' -> { index++; return JsonValue.Obj(fields) }
                else -> throw McpProtocolException("expected , or } at offset $index")
            }
        }
    }

    private fun parseArray(): JsonValue.Arr {
        expect('[')
        val items = mutableListOf<JsonValue>()
        skipWhitespace()
        if (peek() == ']') { index++; return JsonValue.Arr(items) }
        while (true) {
            items += parseValue()
            skipWhitespace()
            when (peek()) {
                ',' -> index++
                ']' -> { index++; return JsonValue.Arr(items) }
                else -> throw McpProtocolException("expected , or ] at offset $index")
            }
        }
    }

    private fun parseString(): String {
        expect('"')
        val builder = StringBuilder()
        while (true) {
            if (index >= text.length) throw McpProtocolException("unterminated string")
            when (val char = text[index++]) {
                '"' -> return builder.toString()
                '\\' -> {
                    if (index >= text.length) throw McpProtocolException("unterminated escape")
                    when (val escaped = text[index++]) {
                        '"' -> builder.append('"')
                        '\\' -> builder.append('\\')
                        '/' -> builder.append('/')
                        'n' -> builder.append('\n')
                        'r' -> builder.append('\r')
                        't' -> builder.append('\t')
                        'b' -> builder.append('\b')
                        'f' -> builder.append('\u000C')
                        'u' -> {
                            if (index + 4 > text.length) throw McpProtocolException("truncated \\u escape")
                            val code = text.substring(index, index + 4).toIntOrNull(16)
                                ?: throw McpProtocolException("invalid \\u escape")
                            builder.append(code.toChar())
                            index += 4
                        }
                        else -> throw McpProtocolException("invalid escape '\\$escaped'")
                    }
                }
                else -> builder.append(char)
            }
        }
    }

    private fun parseNumber(): JsonValue.Num {
        val start = index
        if (peek() == '-') index++
        skipDigits()
        if (index < text.length && text[index] == '.') {
            index++
            skipDigits()
        }
        if (index < text.length && (text[index] == 'e' || text[index] == 'E')) {
            index++
            if (index < text.length && (text[index] == '+' || text[index] == '-')) index++
            skipDigits()
        }
        val raw = text.substring(start, index)
        val value = raw.toDoubleOrNull() ?: throw McpProtocolException("invalid number: $raw")
        return JsonValue.Num(value)
    }

    private fun skipDigits() {
        while (index < text.length && text[index] in '0'..'9') index++
    }

    private fun parseLiteral(literal: String, value: JsonValue): JsonValue {
        if (!text.startsWith(literal, index)) throw McpProtocolException("invalid literal at offset $index")
        index += literal.length
        return value
    }

    private fun expect(char: Char) {
        if (peek() != char) throw McpProtocolException("expected '$char' at offset $index")
        index++
    }

    private fun peek(): Char =
        if (index < text.length) text[index] else throw McpProtocolException("unexpected end of JSON")

    private fun skipWhitespace() {
        while (index < text.length && text[index].isWhitespace()) index++
    }
}
