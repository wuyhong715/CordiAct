package com.cordiact.core.plugin

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull

class AuthoredPluginSourceTest {
    @Test
    fun compilesOrderedTextToolsAndHashesTheOriginalUtf8Bytes() {
        val raw = """{"schemaVersion":1,"id":"hello","title":"问候","tools":[{"name":"greet","description":"提供提示","text":"你好😀\n\t世界"}]}"""
        val compiled = AuthoredPluginSource.compile("hello", raw.encodeToByteArray())
        assertEquals(AuthoredPluginDefinition(
            "hello", "问候", listOf(AuthoredToolDefinition("greet", "提供提示", "你好😀\n\t世界")),
            "7f1a627b2bb47386fceb2fb9631403cf1267a0ce7f19aee5c96516f318c6e027",
        ), compiled)
        val padded = AuthoredPluginSource.compile("hello", " $raw\n".encodeToByteArray())
        assertEquals(compiled.tools, padded.tools)
        assertNotEquals(compiled.sha256, padded.sha256)
        val escaped = compile(source(tools = tool(text = "\\uD83D\\uDE00")))
        assertEquals("😀", escaped.tools.single().text)
        assertEquals(listOf("aa", "bb", "cc", "dd"), compile(source(tools =
            listOf("aa", "bb", "cc", "dd").joinToString(",") { tool(name = it) },
        )).tools.map { it.name })
    }

    @Test
    fun compilesV2MixedToolsWithTheVersionAfterTheToolsAndHashesExactBytes() {
        val tools = listOf(
            """{"text":"Example","kind":"static_text","description":"Description","name":"greet"}""",
            transform(name = "cut", operation = "trim"),
            transform(name = "up", operation = "uppercase"),
            transform(name = "down", operation = "lowercase"),
        )
        val raw = """{"tools":[${tools.joinToString(",")}],"title":"Title","id":"hello","schemaVersion":2}"""
        val compiled = compile(raw)
        assertEquals(2, compiled.schemaVersion)
        assertEquals(listOf("greet", "cut", "up", "down"), compiled.tools.map { it.name })
        assertEquals(AuthoredToolDefinition("greet", "Description", "Example"), compiled.tools.first())
        assertEquals(listOf(AuthoredTextOperation.TRIM, AuthoredTextOperation.UPPERCASE, AuthoredTextOperation.LOWERCASE),
            compiled.tools.drop(1).map { requireNotNull(it.operation) })
        val reformatted = compile(source(version = "2", tools = tools.joinToString(",")))
        assertEquals(compiled.tools, reformatted.tools)
        assertNotEquals(compiled.sha256, reformatted.sha256)
    }

    @Test
    fun rejectsUnknownOrMismatchedV2FieldsAndKeepsV1Closed() {
        val static = staticV2()
        val transform = transform()
        for (invalid in listOf(
            tool(),
            static.replace(",\"text\":\"Example\"", ""),
            static.dropLast(1) + ",\"operation\":\"trim\"}",
            transform.replace(",\"operation\":\"trim\"", ""),
            transform.dropLast(1) + ",\"text\":\"Example\"}",
            transform.dropLast(1) + ",\"text\":null}",
            transform.dropLast(1) + ",\"network\":true}",
            transform.replace("text_transform", "script"),
            transform.replace("\"text_transform\"", "null"),
            transform.replace("\"trim\"", "\"TRIM\""),
            transform.replace("\"trim\"", "\"replace\""),
            transform.replace("\"trim\"", "{}"),
            transform.replace("\"trim\"", "null"),
            transform.replace("\"trim\"", "\"trim \""),
            transform.replace(",\"name\":\"greet\"", ""),
            transform.replace(",\"description\":\"Description\"", ""),
            transform.dropLast(1) + ",\"\\u006bind\":\"text_transform\"}",
            transform.dropLast(1) + ",\"operation\":\"uppercase\"}",
            "$static,$transform",
        )) rejected(source(version = "2", tools = invalid))
        rejected(source(version = "2", tools = transform).replace("\"schemaVersion\":2", "\"schemaVersion\":2,\"schemaVersion\":1"))
        rejected(source(version = "2", tools = transform).dropLast(1) + ",\"capabilities\":[]}")
        rejected(source(tools = static))
        rejected(source(tools = transform))
        rejected(source(tools = tool().dropLast(1) + ",\"operation\":\"trim\"}"))
    }

    @Test
    fun restrictsIdsToCanonicalWorkspaceSourcePaths() {
        assertEquals("plugins/a1.json", AuthoredPluginSource.path("a1"))
        assertEquals("plugins/a-2.json", AuthoredPluginSource.path("a-2"))
        val longest = "a".repeat(24)
        assertEquals("plugins/$longest.json", AuthoredPluginSource.path(longest))
        for (id in listOf("", "a", "Hello", "1letter", "../escape", "a/b", "a\\b", "a.b", "a_b", "a\n", "a".repeat(25))) {
            assertFailsWith<IllegalArgumentException> { AuthoredPluginSource.path(id) }
            assertFailsWith<IllegalArgumentException> { AuthoredPluginSource.compile(id, source().encodeToByteArray()) }
        }
        rejected(source(id = "other"))
        rejected(source(tools = tool(name = "Mismatched")))
    }

    @Test
    fun rejectsDuplicateUnknownAndMissingFieldsAtBothLevels() {
        val valid = source()
        rejected(valid.replace("\"schemaVersion\":1", "\"schemaVersion\":1,\"schemaVersion\":1"))
        rejected(valid.replace("\"id\":\"hello\"", "\"id\":\"hello\",\"\\u0069d\":\"hello\""))
        rejected(valid.replace("\"text\":\"Example\"", "\"text\":\"Example\",\"text\":\"Example\""))
        rejected(valid.replace("\"title\":\"Title\",", ""))
        rejected(valid.replace(",\"description\":\"Description\"", ""))
        rejected(valid.dropLast(1) + ",\"capabilities\":[]}")
        rejected(source(tools = tool().dropLast(1) + ",\"script\":\"payload\"}"))
        rejected(source(tools = ""))
        rejected(source(tools = listOf(tool(), tool()).joinToString(",")))
        rejected(source(tools = (1..5).joinToString(",") { tool(name = "t$it") }))
    }

    @Test
    fun strictlyRejectsOtherJsonDialectsTrailingContentAndWrongTypes() {
        val valid = source()
        for (version in listOf("3", "0", "\"1\"", "1.0", "1e0", "01", "+1", "null", "true", "[]")) {
            rejected(source(version = version))
        }
        for (version in listOf("3", "\"2\"", "2.0", "2e0", "02", "+2", "null", "true", "[]")) {
            rejected(source(version = version, tools = transform()))
        }
        for (invalid in listOf(
            "[]", "null", "true", "\uFEFF$valid", "$valid {}", "$valid true", "$valid // comment", "/* comment */ $valid",
            valid.replace('"', '\''), valid.replace("\"title\"", "title"), valid.dropLast(1) + ",}",
            valid.replace("\"Title\"", "123"), valid.replace("\"hello\"", "true"),
            valid.replace("\"Example\"", "null"), valid.replace("\"Description\"", "{}"),
            valid.replace("\"greet\"", "1"), valid.replace("[${tool()}]", "{}"), source(tools = "null"),
            source(tools = tool(text = "raw\nline")), source(tools = tool(text = "\\x41")),
        )) rejected(invalid)
    }

    @Test
    fun rejectsMalformedUtf8SurrogatesAndInvisibleControlCharacters() {
        val prefix = "{\"schemaVersion\":1,\"id\":\"hello\",\"title\":\"".encodeToByteArray()
        val suffix = "\",\"tools\":[${tool()}]}".encodeToByteArray()
        for (malformed in listOf(
            byteArrayOf(0xc3.toByte(), 0x28),
            byteArrayOf(0xc0.toByte(), 0xaf.toByte()),
            byteArrayOf(0xed.toByte(), 0xa0.toByte(), 0x80.toByte()),
            byteArrayOf(0xff.toByte()),
        )) assertFailsWith<IllegalArgumentException> { AuthoredPluginSource.compile("hello", prefix + malformed + suffix) }
        for (escaped in listOf("\\uD800", "\\uDC00", "\\uDC00\\uD800", "\\u202E", "\\u2066", "\\u200B", "\\uFEFF", "\\u0000", "\\u007F", "\\u0085", "\\u2028", "\\u2029", "\\r")) {
            rejected(source(title = "a$escaped"))
            rejected(source(tools = tool(text = "a$escaped")))
        }
        rejected(source(title = "a\\nsecond"))
        rejected(source(tools = tool(description = "a\\tsecond")))
        rejected(source(tools = tool(text = "\\n\\t")))
        assertEquals("a\n\tb", compile(source(tools = tool(text = "a\\n\\tb"))).tools.single().text)
    }

    @Test
    fun enforcesIndependentUtf8BudgetsAndTheTotalSourceLimit() {
        assertEquals(16 * 1024, AuthoredPluginSource.MAX_SOURCE_BYTES)
        assertEquals("界".repeat(40), compile(source(title = "界".repeat(40))).title)
        rejected(source(title = "界".repeat(41)))
        assertEquals("界".repeat(160), compile(source(tools = tool(description = "界".repeat(160)))).tools.single().description)
        rejected(source(tools = tool(description = "界".repeat(161))))
        val body = "界".repeat(1365) + "x"
        assertEquals(body, compile(source(tools = tool(text = body))).tools.single().text)
        rejected(source(tools = tool(text = "${body}x")))
        val source = source()
        val exact = source + " ".repeat(AuthoredPluginSource.MAX_SOURCE_BYTES - source.encodeToByteArray().size)
        assertEquals("hello", compile(exact).id)
        rejected("$exact ")
        assertFailsWith<IllegalArgumentException> { AuthoredPluginSource.compile("hello", byteArrayOf()) }
    }

    @Test
    fun reportsErrorsWithoutReturningUserContentOrGsonErrorCause() {
        val marker = "DO_NOT_REVEAL_SOURCE"
        for (invalid in listOf(
            source(title = "$marker\\u202E"), source().dropLast(1) + ",\"$marker\": \"data\"}",
            source(tools = tool(text = "$marker\\uZZZZ")), source().replace("\"Title\"", "\"$marker"),
        )) {
            val error = assertFailsWith<IllegalArgumentException> { compile(invalid) }
            assertFalse(error.message.orEmpty().contains(marker))
            assertNull(error.cause)
        }
    }

    private fun compile(text: String) = AuthoredPluginSource.compile("hello", text.encodeToByteArray())
    private fun rejected(text: String) { assertFailsWith<IllegalArgumentException> { compile(text) } }

    private fun source(id: String = "hello", title: String = "Title", tools: String = tool(), version: String = "1") =
        """{"schemaVersion":$version,"id":"$id","title":"$title","tools":[$tools]}"""

    private fun tool(name: String = "greet", description: String = "Description", text: String = "Example") =
        """{"name":"$name","description":"$description","text":"$text"}"""

    private fun staticV2() = tool().dropLast(1) + ",\"kind\":\"static_text\"}"

    private fun transform(name: String = "greet", operation: String = "trim") =
        """{"kind":"text_transform","name":"$name","description":"Description","operation":"$operation"}"""
}
