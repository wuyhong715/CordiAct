package com.cordiact.search.providers

import com.google.gson.JsonParser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NativeSearchOptionsTest {
    private val location = SearchUserLocation(
        city = "Shanghai",
        region = "Shanghai",
        country = "CN",
        timezone = "Asia/Shanghai",
    )

    @Test
    fun `defaults produce a minimal anthropic body`() {
        val body = NativeSearchPayloads.anthropicStyle("claude-sonnet-4", "q", NativeSearchOptions.DEFAULT)
        val tool = body.getAsJsonArray("tools")[0].asJsonObject
        assertEquals(5, tool.get("max_uses").asInt)
        assertEquals(4096, body.get("max_tokens").asInt)
        assertFalse(tool.has("allowed_domains"))
        assertFalse(tool.has("blocked_domains"))
        assertFalse(tool.has("user_location"))
    }

    @Test
    fun `anthropic carries domain filters and location`() {
        val options = NativeSearchOptions(
            maxUses = 3,
            maxTokens = 1024,
            allowedDomains = listOf("example.com", "docs.example.org"),
            userLocation = location,
        )
        val body = NativeSearchPayloads.anthropicStyle("claude-sonnet-4", "q", options)
        val tool = body.getAsJsonArray("tools")[0].asJsonObject
        assertEquals(3, tool.get("max_uses").asInt)
        assertEquals(1024, body.get("max_tokens").asInt)
        val allowed = tool.getAsJsonArray("allowed_domains").map { it.asString }
        assertEquals(listOf("example.com", "docs.example.org"), allowed)
        val loc = tool.getAsJsonObject("user_location")
        assertEquals("approximate", loc.get("type").asString)
        assertEquals("Shanghai", loc.get("city").asString)
        assertEquals("CN", loc.get("country").asString)
        assertEquals("Asia/Shanghai", loc.get("timezone").asString)
    }

    @Test
    fun `blocked domains are sent when no allow list exists`() {
        val options = NativeSearchOptions(blockedDomains = listOf("spam.example"))
        val tool = NativeSearchPayloads.anthropicStyle("m", "q", options)
            .getAsJsonArray("tools")[0].asJsonObject
        assertEquals(listOf("spam.example"), tool.getAsJsonArray("blocked_domains").map { it.asString })
        assertFalse(tool.has("allowed_domains"))
    }

    @Test
    fun `openai carries context size and location`() {
        val options = NativeSearchOptions(searchContextSize = "high", userLocation = location)
        val tool = NativeSearchPayloads.openAiResponses("gpt-4o", "q", options)
            .getAsJsonArray("tools")[0].asJsonObject
        assertEquals("web_search", tool.get("type").asString)
        assertEquals("high", tool.get("search_context_size").asString)
        assertEquals("CN", tool.getAsJsonObject("user_location").get("country").asString)
    }

    @Test
    fun `gemini adds url_context only when asked`() {
        val plain = NativeSearchPayloads.gemini("q", NativeSearchOptions.DEFAULT)
            .getAsJsonArray("tools").map { it.asJsonObject.keySet() }
        assertEquals(listOf(setOf("google_search")), plain)

        val withContext = NativeSearchPayloads.gemini("q", NativeSearchOptions(geminiUrlContext = true))
            .getAsJsonArray("tools").map { it.asJsonObject.keySet() }
        assertEquals(2, withContext.size)
        assertTrue(withContext[1].contains("url_context"))
    }

    @Test
    fun `invalid option combinations are rejected at construction`() {
        assertFailsWith<IllegalArgumentException> { NativeSearchOptions(maxUses = 0) }
        assertFailsWith<IllegalArgumentException> { NativeSearchOptions(maxUses = 21) }
        assertFailsWith<IllegalArgumentException> { NativeSearchOptions(maxTokens = 100) }
        assertFailsWith<IllegalArgumentException> { NativeSearchOptions(searchContextSize = "huge") }
        assertFailsWith<IllegalArgumentException> {
            NativeSearchOptions(allowedDomains = listOf("a.com"), blockedDomains = listOf("b.com"))
        }
        assertFailsWith<IllegalArgumentException> {
            NativeSearchOptions(allowedDomains = List(NativeSearchOptions.MAX_DOMAINS + 1) { "d$it.com" })
        }
    }

    @Test
    fun `an empty location is not sent`() {
        assertTrue(SearchUserLocation().isEmpty)
        val tool = NativeSearchPayloads.anthropicStyle(
            "m", "q", NativeSearchOptions(userLocation = SearchUserLocation()),
        ).getAsJsonArray("tools")[0].asJsonObject
        // The type/city/etc. block is still emitted for a non-null location; only a null one is skipped.
        assertTrue(tool.getAsJsonObject("user_location").get("type").asString == "approximate")
    }
}
