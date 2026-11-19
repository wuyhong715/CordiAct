package com.cordiact.core.context

import com.cordiact.core.tool.InMemoryToolDefinitionRegistry
import com.cordiact.core.tool.ToolArguments
import com.cordiact.core.tool.ToolInvocationContext
import com.cordiact.core.tool.ToolValue
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class ContextToolsTest {

    @Test
    fun `test tool_output_read execution and pagination`(@TempDir tempDir: File) {
        val registry = InMemoryToolDefinitionRegistry()
        val store = StandardSpillStore(tempDir)
        registerContextTools(registry, store)

        val toolDef = registry.get(TOOL_OUTPUT_READ_ID)
        assertNotNull(toolDef)

        val content = "LINE1: " + "a".repeat(100) + "\nLINE2: " + "b".repeat(100)
        val uri = store.saveSpill("session_1", "bash", content)

        val context = ToolInvocationContext(workspaceId = "ws1", sessionId = "session_1")

        // Read first 50 chars
        val args1 = ToolArguments(
            mapOf(
                "file" to ToolValue.StringValue(uri),
                "offset" to ToolValue.NumberValue(0.0),
                "limit_chars" to ToolValue.NumberValue(50.0),
            )
        )
        val res1 = toolDef!!.executor.invoke(context, args1)
        assertEquals(ToolValue.BooleanValue(true), res1.values["ok"])
        val text1 = (res1.values["content"] as ToolValue.StringValue).value
        assertTrue(text1.startsWith("LINE1:"))
        assertTrue(text1.contains("has_more: true"))
        assertTrue(text1.contains("next_offset: 50"))

        // Read remaining
        val args2 = ToolArguments(
            mapOf(
                "file" to ToolValue.StringValue(uri),
                "offset" to ToolValue.NumberValue(50.0),
                "limit_chars" to ToolValue.NumberValue(200.0),
            )
        )
        val res2 = toolDef.executor.invoke(context, args2)
        assertEquals(ToolValue.BooleanValue(true), res2.values["ok"])
        val text2 = (res2.values["content"] as ToolValue.StringValue).value
        assertTrue(text2.contains("LINE2:"))
        assertTrue(text2.contains("EOF reached"))
        assertEquals(ToolValue.BooleanValue(true), res1.values["untrusted"])

        val otherSession = toolDef.executor.invoke(
            ToolInvocationContext(workspaceId = "ws1", sessionId = "session_2"), args1,
        )
        assertEquals(ToolValue.BooleanValue(false), otherSession.values["ok"])
        assertTrue((otherSession.values["error"] as ToolValue.StringValue).value.contains("different session"))

        // UTF-8 code points and supplementary characters paginate without splitting.
        val unicodeStore = StandardSpillStore(tempDir)
        val unicode = "甲乙😀丙丁"
        val unicodeUri = unicodeStore.saveSpill("session_unicode", "utf8", unicode)
        val firstPage = requireNotNull(unicodeStore.readSpill(unicodeUri, 0, 3))
        assertEquals("甲乙😀", firstPage.content)
        assertEquals(3, firstPage.charsRead)
        assertEquals("丙丁", requireNotNull(unicodeStore.readSpill(unicodeUri, 3, 3)).content)

        // Missing argument
        val errRes = toolDef.executor.invoke(context, ToolArguments())
        assertEquals(ToolValue.BooleanValue(false), errRes.values["ok"])
        val errText = (errRes.values["error"] as ToolValue.StringValue).value
        assertTrue(errText.contains("Missing required parameter"))

        // Invalid file path
        val notFoundRes = toolDef.executor.invoke(
            context,
            ToolArguments(mapOf("file" to ToolValue.StringValue("spill://session_1/nonexistent.txt")))
        )
        assertEquals(ToolValue.BooleanValue(false), notFoundRes.values["ok"])
        val notFoundText = (notFoundRes.values["error"] as ToolValue.StringValue).value
        assertTrue(notFoundText.contains("File not found"))
    }
}

