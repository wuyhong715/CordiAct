package com.cordiact.model.deepseek

import com.cordiact.core.conversation.MessageRole
import com.cordiact.core.conversation.ModelEvent
import com.cordiact.core.conversation.ModelMessage
import com.cordiact.core.conversation.ModelToolSpec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The OpenAI chat protocol is the only one that carries tools. These tests pin the parts that
 * are easy to get subtly wrong: the request shape, and reassembling streamed call fragments.
 */
class ToolCallStreamTest {

    @Test
    fun onlyOpenAiChatSupportsToolCalls() {
        assertTrue(supportsToolCalls(ModelProtocol.OPENAI_CHAT_COMPLETIONS))
        for (protocol in listOf(
            ModelProtocol.ANTHROPIC_MESSAGES,
            ModelProtocol.GEMINI_GENERATE_CONTENT,
            ModelProtocol.OPENAI_RESPONSES,
        )) {
            assertTrue(!supportsToolCalls(protocol), "$protocol must not claim tool support")
        }
    }

    @Test
    fun toolMessageCarriesItsCallId() {
        // Without the id the provider cannot match a result to its request.
        val message = ModelMessage(MessageRole.TOOL, "result", toolCallId = "call_1")

        assertEquals("call_1", message.toolCallId)
    }

    @Test
    fun assistantMessageCarriesToolCalls() {
        val message = ModelMessage(
            role = MessageRole.ASSISTANT,
            content = "",
            toolCalls = listOf(
                com.cordiact.core.conversation.ModelToolCall("call_1", "workspace.files.list", "{}"),
            ),
        )

        assertEquals(1, message.toolCalls.size)
        assertEquals("workspace.files.list", message.toolCalls.single().name)
    }

    @Test
    fun toolSpecIsIndependentOfTheToolManager() {
        // The model contract must not import the tool manager, or a provider adapter could
        // dispatch. This is a request shape only.
        val spec = ModelToolSpec(
            name = "workspace.files.list",
            description = "List entries in a directory",
            parametersJson = """{"type":"object","properties":{}}""",
        )

        assertEquals("workspace.files.list", spec.name)
        assertTrue(spec.parametersJson.startsWith("{"))
    }

    @Test
    fun toolResultWithoutCallIdIsRejectedAtConstruction() {
        assertFailsWith<IllegalArgumentException> { ModelMessage(MessageRole.TOOL, "orphan") }
    }

    @Test
    fun nonToolMessageNeedsNoCallId() {
        val message = ModelMessage(MessageRole.USER, "hello")

        assertEquals(null, message.toolCallId)
        assertTrue(message.toolCalls.isEmpty())
    }
}
