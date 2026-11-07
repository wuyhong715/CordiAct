package com.cordiact.core.context

import com.cordiact.core.conversation.MessageRole
import com.cordiact.core.conversation.ModelDescriptor
import com.cordiact.core.conversation.ModelId
import com.cordiact.core.conversation.ModelMessage
import com.cordiact.core.conversation.ModelToolCall
import com.cordiact.core.tool.ToolInvocationContext
import com.cordiact.core.toolloop.ToolDispatcher
import com.cordiact.core.toolloop.ToolLoopLimits
import com.cordiact.core.toolloop.ToolLoopPlugin
import com.cordiact.core.toolloop.ToolLoopRound
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class ContextP0Test {

    @Test
    fun `test token estimator weights and monotonicity`() {
        val estimator = WeightedTokenEstimator()

        assertEquals(0, estimator.estimate(""))
        val ascii = "abcdefghij" // 10 chars * 0.25 = 2.5 -> ceil = 3
        assertEquals(3, estimator.estimate(ascii))

        val cjk = "上下文控制插件" // 7 chars * 1.0 = 7
        assertEquals(7, estimator.estimate(cjk))

        val mixed = "Hello 世界" // 5*0.25 (1.25) + 0.10 + 2*1.0 (2) = 3.35 -> 4
        assertEquals(4, estimator.estimate(mixed))

        // Monotonicity check
        var prev = 0
        val sb = StringBuilder()
        for (i in 1..20) {
            sb.append("测试代码 ABC ")
            val current = estimator.estimate(sb.toString())
            assertTrue(current >= prev, "Estimate must be non-decreasing: $current >= $prev")
            prev = current
        }
    }

    @Test
    fun `test token estimator message estimation and calibration`() {
        val estimator = WeightedTokenEstimator()
        val messages = listOf(
            ModelMessage(MessageRole.USER, "请帮我分析日志"),
            ModelMessage(
                MessageRole.ASSISTANT,
                "好的",
                toolCalls = listOf(ModelToolCall("t1", "read_file", "{\"path\":\"a.txt\"}")),
            ),
        )

        val estimate = estimator.estimateMessages(messages, toolsJson = "{\"type\":\"object\"}")
        assertTrue(estimate > 20)

        val model = "deepseek-chat"
        val initialFactor = estimator.currentFactor(model)
        assertEquals(1.05, initialFactor, 0.001)

        // Calibrate with higher actual usage
        estimator.calibrate(actualInputTokens = 150, estimatedInputTokens = 100, modelId = model)
        val newFactor = estimator.currentFactor(model)
        // 0.8 * 1.05 + 0.2 * 1.5 = 0.84 + 0.3 = 1.14
        assertEquals(1.14, newFactor, 0.001)
    }

    @Test
    fun `test default context window resolution`() {
        val dsDesc = ModelDescriptor(ModelId("deepseek", "deepseek-chat"), "DeepSeek Chat")
        assertEquals(128_000, DefaultContextWindows.resolve(dsDesc))

        val claudeDesc = ModelDescriptor(ModelId("anthropic", "claude-3-5-sonnet-20241022"), "Claude 3.5")
        assertEquals(200_000, DefaultContextWindows.resolve(claudeDesc))

        val geminiDesc = ModelDescriptor(ModelId("google", "gemini-1.5-pro"), "Gemini 1.5 Pro")
        assertEquals(1_048_576, DefaultContextWindows.resolve(geminiDesc))

        val unknownDesc = ModelDescriptor(ModelId("custom", "my-local-model"), "Custom")
        assertEquals(32_768, DefaultContextWindows.resolve(unknownDesc))

        // Explicit descriptor override
        val explicitDesc = unknownDesc.copy(contextWindowTokens = 65_536)
        assertEquals(65_536, DefaultContextWindows.resolve(explicitDesc))

        // Policy override takes highest precedence
        assertEquals(16_384, DefaultContextWindows.resolve(explicitDesc, policyOverride = 16_384))
    }

    @Test
    fun `test spill store save, read, pagination and security`(@TempDir tempDir: File) {
        val store = StandardSpillStore(tempDir)
        val content = "HEAD_CONTENT\n" + "x".repeat(10_000) + "\nTAIL_CONTENT"

        val uri = store.saveSpill("session_123", "bash", content)
        assertTrue(uri.startsWith("spill://session_123/"))

        // Read page 1 (first 100 chars)
        val page1 = store.readSpill(uri, offset = 0, limitChars = 100)
        assertNotNull(page1)
        assertTrue(page1!!.content.startsWith("HEAD_CONTENT"))
        assertEquals(0L, page1.offset)
        assertEquals(100, page1.bytesRead)
        assertTrue(page1.hasMore)

        // Read tail page
        val tailOffset = content.length - 50L
        val pageTail = store.readSpill(uri, offset = tailOffset, limitChars = 100)
        assertNotNull(pageTail)
        assertTrue(pageTail!!.content.endsWith("TAIL_CONTENT"))
        assertFalse(pageTail.hasMore)

        // Security check: traversal attempts must return null
        assertNull(store.readSpill("spill://session_123/../../etc/passwd"))
        assertNull(store.readSpill("invalid-scheme://file"))

        // Cleanup
        store.cleanupSession("session_123")
        assertNull(store.readSpill(uri))
    }

    @Test
    fun `test default tool output gate with spill integration`(@TempDir tempDir: File) {
        val store = StandardSpillStore(tempDir)
        val gate = DefaultToolOutputGate(spillStore = store, headRatio = 0.5)

        val smallMessage = ModelMessage(MessageRole.TOOL, "small output", toolCallId = "call_1", toolName = "test")
        val smallGated = gate.gate("session_1", smallMessage, limitChars = 100)
        assertEquals("small output", smallGated.content)

        val largeContent = "START_ERROR_HEADER\n" + ".".repeat(5000) + "\nCRITICAL_EXIT_ERROR: NullPointerException"
        val largeMessage = ModelMessage(MessageRole.TOOL, largeContent, toolCallId = "call_2", toolName = "shell")
        val largeGated = gate.gate("session_1", largeMessage, limitChars = 100)

        assertTrue(largeGated.content.startsWith("START_ERROR_HEADER"))
        assertTrue(largeGated.content.endsWith("CRITICAL_EXIT_ERROR: NullPointerException"))
        assertTrue(largeGated.content.contains("full output saved at spill://session_1/"))
        assertTrue(largeGated.content.contains("tool_output_read"))
    }

    @Test
    fun `test tool loop plugin consumes maxResultChars and applies gate`(@TempDir tempDir: File) {
        val store = StandardSpillStore(tempDir)
        val gate = DefaultToolOutputGate(spillStore = store, headRatio = 0.5)
        val limits = ToolLoopLimits(maxRounds = 4, maxToolCalls = 5, maxResultChars = 50)
        val plugin = ToolLoopPlugin(limits = limits, gate = gate)
        plugin.onEnable()

        val capturedMessages = mutableListOf<ModelMessage>()
        val dummyContext = ToolInvocationContext(
            workspaceId = "ws1",
            sessionId = "session_test",
        )

        var roundCount = 0
        val outcome = plugin.run(
            context = dummyContext,
            initialMessages = listOf(ModelMessage(MessageRole.USER, "run command")),
            dispatcher = ToolDispatcher { _, call ->
                ModelMessage(
                    role = MessageRole.TOOL,
                    content = "LOG_START_" + "z".repeat(1000) + "_LOG_END_ERR",
                    toolCallId = call.id,
                    toolName = call.name,
                )
            },
            send = { msgs ->
                capturedMessages.clear()
                capturedMessages.addAll(msgs)
                roundCount++
                if (roundCount == 1) {
                    ToolLoopRound(
                        text = "Executing tool...",
                        calls = listOf(ModelToolCall("c1", "shell", "{}")),
                        events = emptyList(),
                    )
                } else {
                    ToolLoopRound(text = "Finished", calls = emptyList(), events = emptyList())
                }
            },
        )

        assertTrue(outcome is com.cordiact.core.toolloop.ToolLoopOutcome.Completed)
        val toolMessage = capturedMessages.firstOrNull { it.role == MessageRole.TOOL }
        assertNotNull(toolMessage)
        // Verify head and tail are preserved and size was bounded
        assertTrue(toolMessage!!.content.startsWith("LOG_START_"))
        assertTrue(toolMessage.content.endsWith("_LOG_END_ERR"))
        assertTrue(toolMessage.content.contains("spill://session_test/"))
    }
}
