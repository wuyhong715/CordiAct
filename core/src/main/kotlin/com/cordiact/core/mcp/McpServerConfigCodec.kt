package com.cordiact.core.mcp

/**
 * Serializes [McpServerConfig] to and from the text stored in `PluginSpec.config`.
 *
 * `core` carries no JSON library on purpose — every module that needed one got a different
 * one — so this is a small, strict, hand-written codec for exactly one shape. It accepts a
 * fixed set of keys, rejects everything else, and never touches credentials.
 *
 * The format is JSON so a user can read and edit `PluginSpec.config` by hand; the parser is
 * deliberately narrow (no escapes beyond `\"`, `\\`, `\n`, `\t`) because only this codec ever
 * writes the text.
 */
object McpServerConfigCodec {

    fun encode(config: McpServerConfig): String = buildString {
        append('{')
        appendField("serverName", config.serverName)
        append(',')
        appendField("url", config.url)
        append(',')
        appendHeaders(config.headers)
        append(',')
        append("\"toolCallTimeoutMs\":").append(config.toolCallTimeoutMs)
        append(',')
        append("\"connectTimeoutMs\":").append(config.connectTimeoutMs)
        append(',')
        append("\"reconnect\":{")
        append("\"enabled\":").append(config.reconnect.enabled)
        append(",\"initialDelayMs\":").append(config.reconnect.initialDelayMs)
        append(",\"maxDelayMs\":").append(config.reconnect.maxDelayMs)
        append(",\"maxAttempts\":").append(config.reconnect.maxAttempts)
        append("}}")
    }

    /**
     * Parse stored config text.
     *
     * @throws McpConfigException when the text is not a well-formed config object; the
     *   message names the offending key so the settings screen can point at a field.
     */
    fun decode(text: String): McpServerConfig {
        val reader = JsonReader(text)
        val obj = reader.readObject()
        reader.requireEnd()
        val serverName = obj.string("serverName") ?: throw McpConfigException("config has no serverName")
        val url = obj.string("url") ?: throw McpConfigException("config has no url")
        val headers = obj["headers"].asStringMap("headers")
        val toolCallTimeoutMs = obj.long("toolCallTimeoutMs") ?: McpServerConfig.DEFAULT_TOOL_CALL_TIMEOUT_MS
        val connectTimeoutMs = obj.long("connectTimeoutMs") ?: McpServerConfig.DEFAULT_CONNECT_TIMEOUT_MS
        val reconnectObj = obj["reconnect"] as? Map<*, *>
        val reconnect = if (reconnectObj == null) {
            McpReconnectPolicy()
        } else {
            val defaults = McpReconnectPolicy()
            McpReconnectPolicy(
                enabled = (reconnectObj["enabled"] as? Boolean) ?: defaults.enabled,
                initialDelayMs = (reconnectObj["initialDelayMs"] as? Long) ?: defaults.initialDelayMs,
                maxDelayMs = (reconnectObj["maxDelayMs"] as? Long) ?: defaults.maxDelayMs,
                maxAttempts = ((reconnectObj["maxAttempts"] as? Long) ?: defaults.maxAttempts.toLong()).toInt(),
            )
        }
        return McpServerConfig.validated(
            McpServerConfig(
                serverName = serverName,
                url = url,
                headers = headers,
                toolCallTimeoutMs = toolCallTimeoutMs,
                connectTimeoutMs = connectTimeoutMs,
                reconnect = reconnect,
            ),
        )
    }

    private fun StringBuilder.appendField(name: String, value: String) {
        append('"').append(name).append("\":").appendQuoted(value)
    }

    private fun StringBuilder.appendHeaders(headers: Map<String, String>) {
        append("\"headers\":{")
        headers.entries.forEachIndexed { index, (key, value) ->
            if (index > 0) append(',')
            appendQuoted(key)
            append(':')
            appendQuoted(value)
        }
        append('}')
    }

    private fun StringBuilder.appendQuoted(value: String) {
        append('"')
        for (char in value) {
            when (char) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (char < ' ') throw McpConfigException("control character in config value") else append(char)
            }
        }
        append('"')
    }

    private fun Any?.asStringMap(key: String): Map<String, String> {
        if (this == null) return emptyMap()
        @Suppress("UNCHECKED_CAST")
        val map = this as? Map<*, *> ?: throw McpConfigException("$key must be an object")
        return map.entries.associate { (k, v) ->
            val name = k as? String ?: throw McpConfigException("$key has a non-string key")
            val text = v as? String ?: throw McpConfigException("$key.$name must be a string")
            name to text
        }
    }

    private fun Map<String, Any?>.string(key: String): String? {
        val value = this[key] ?: return null
        return value as? String ?: throw McpConfigException("$key must be a string")
    }

    private fun Map<String, Any?>.long(key: String): Long? {
        val value = this[key] ?: return null
        return when (value) {
            is Long -> value
            is Int -> value.toLong()
            else -> throw McpConfigException("$key must be a number")
        }
    }

    /**
     * A minimal recursive-descent reader for JSON objects, arrays, strings, numbers,
     * booleans and null.
     *
     * Written out because pulling a JSON library into `core` for one config shape would make
     * the module graph depend on whichever library happened to be picked first.
     */
    private class JsonReader(private val text: String) {
        private var index = 0

        fun requireEnd() {
            skipWhitespace()
            if (index != text.length) throw McpConfigException("trailing content at offset $index")
        }

        fun readObject(): Map<String, Any?> {
            skipWhitespace()
            expect('{')
            val result = linkedMapOf<String, Any?>()
            skipWhitespace()
            if (peek() == '}') { index++; return result }
            while (true) {
                skipWhitespace()
                val key = readString()
                skipWhitespace()
                expect(':')
                skipWhitespace()
                result[key] = readValue()
                skipWhitespace()
                when (peek()) {
                    ',' -> index++
                    '}' -> { index++; return result }
                    else -> throw McpConfigException("expected , or } at offset $index")
                }
            }
        }

        private fun readValue(): Any? {
            skipWhitespace()
            return when (val char = peek()) {
                '{' -> readObject()
                '[' -> readArray()
                '"' -> readString()
                't' -> readLiteral("true", true)
                'f' -> readLiteral("false", false)
                'n' -> readLiteral("null", null)
                else -> if (char == '-' || char in '0'..'9') readNumber() else {
                    throw McpConfigException("unexpected character '$char' at offset $index")
                }
            }
        }

        private fun readArray(): List<Any?> {
            expect('[')
            val values = mutableListOf<Any?>()
            skipWhitespace()
            if (peek() == ']') { index++; return values }
            while (true) {
                values += readValue()
                skipWhitespace()
                when (peek()) {
                    ',' -> index++
                    ']' -> { index++; return values }
                    else -> throw McpConfigException("expected , or ] at offset $index")
                }
            }
        }

        private fun readString(): String {
            expect('"')
            val builder = StringBuilder()
            while (true) {
                if (index >= text.length) throw McpConfigException("unterminated string")
                when (val char = text[index++]) {
                    '"' -> return builder.toString()
                    '\\' -> {
                        if (index >= text.length) throw McpConfigException("unterminated escape")
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
                                if (index + 4 > text.length) throw McpConfigException("truncated \\u escape")
                                val code = text.substring(index, index + 4).toIntOrNull(16)
                                    ?: throw McpConfigException("invalid \\u escape")
                                builder.append(code.toChar())
                                index += 4
                            }
                            else -> throw McpConfigException("invalid escape '\\$escaped'")
                        }
                    }
                    else -> builder.append(char)
                }
            }
        }

        private fun readNumber(): Any {
            val start = index
            if (peek() == '-') index++
            while (index < text.length && text[index] in '0'..'9') index++
            val isInteger = index >= text.length || (text[index] != '.' && text[index] != 'e' && text[index] != 'E')
            if (!isInteger) {
                if (text[index] == '.') {
                    index++
                    while (index < text.length && text[index] in '0'..'9') index++
                }
                if (index < text.length && (text[index] == 'e' || text[index] == 'E')) {
                    index++
                    if (index < text.length && (text[index] == '+' || text[index] == '-')) index++
                    while (index < text.length && text[index] in '0'..'9') index++
                }
            }
            val raw = text.substring(start, index)
            return if (isInteger) {
                raw.toLongOrNull() ?: throw McpConfigException("number out of range: $raw")
            } else {
                raw.toDoubleOrNull() ?: throw McpConfigException("invalid number: $raw")
            }
        }

        private fun readLiteral(literal: String, value: Any?): Any? {
            if (!text.startsWith(literal, index)) throw McpConfigException("invalid literal at offset $index")
            index += literal.length
            return value
        }

        private fun expect(char: Char) {
            if (peek() != char) throw McpConfigException("expected '$char' at offset $index")
            index++
        }

        private fun peek(): Char =
            if (index < text.length) text[index] else throw McpConfigException("unexpected end of config")

        private fun skipWhitespace() {
            while (index < text.length && text[index].isWhitespace()) index++
        }
    }
}
