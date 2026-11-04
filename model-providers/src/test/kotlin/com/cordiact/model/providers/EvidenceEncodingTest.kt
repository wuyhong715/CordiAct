package com.cordiact.model.providers

import com.cordiact.core.toolloop.ToolEvidence
import com.google.gson.JsonParser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EvidenceEncodingTest {
    @Test
    fun `empty evidence encodes to null`() {
        assertNull(encodeEvidence(emptyList()))
    }

    @Test
    fun `evidence is wrapped with the tool id`() {
        val encoded = encodeEvidence(listOf(ToolEvidence("search.web", """{"sources":[{"url":"https://a.example"}]}""")))
        val array = JsonParser.parseString(encoded).asJsonArray
        assertEquals(1, array.size())
        val entry = array[0].asJsonObject
        assertEquals("search.web", entry.get("tool").asString)
        assertEquals("https://a.example", entry.getAsJsonObject("data").getAsJsonArray("sources")[0]
            .asJsonObject.get("url").asString)
    }

    @Test
    fun `malformed evidence is skipped rather than failing the turn`() {
        val encoded = encodeEvidence(
            listOf(
                ToolEvidence("broken", "not json"),
                ToolEvidence("search.web", """{"ok":true}"""),
            ),
        )
        val array = JsonParser.parseString(encoded).asJsonArray
        assertEquals(1, array.size())
        assertEquals("search.web", array[0].asJsonObject.get("tool").asString)
    }

    @Test
    fun `evidence is bounded by total size`() {
        val big = """{"blob":"""" + "x".repeat(MAX_EVIDENCE_CHARS) + "\"}"
        val encoded = encodeEvidence(
            listOf(
                ToolEvidence("a", big),
                ToolEvidence("b", """{"ok":true}"""),
            ),
        )
        assertNull(encoded, "the first entry already exceeds the cap, so nothing is kept")
        assertTrue(MAX_EVIDENCE_CHARS == 32_000)
    }
}
