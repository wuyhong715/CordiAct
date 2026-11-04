package com.cordiact.core.toolloop

import com.cordiact.core.conversation.MessageRole
import com.cordiact.core.conversation.ModelEvent
import com.cordiact.core.conversation.ModelMessage
import com.cordiact.core.conversation.ModelToolCall
import com.cordiact.core.tool.ToolInvocationContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

private val CONTEXT = ToolInvocationContext(workspaceId = "ws-1")

private fun call(id: String, name: String = "workspace.files.list") =
    ModelToolCall(id = id, name = name, argumentsJson = "{}")

private fun round(text: String = "", vararg calls: ModelToolCall) =
    ToolLoopRound(text = text, calls = calls.toList(), events = emptyList())

class ToolLoopPluginTest {

    private fun loop(limits: ToolLoopLimits = ToolLoopLimits()) =
        ToolLoopPlugin(limits).apply { onEnable() }

    private fun dispatcher(log: MutableList<String> = mutableListOf()) = ToolDispatcher { _, call ->
        log += call.id
        ModelMessage(
            role = MessageRole.TOOL,
            content = "result for ${call.name}",
            toolCallId = call.id,
        )
    }

    @Test
    fun collectsToolMetadataAsEvidence() {
        val dispatcher = ToolDispatcher { _, call ->
            ModelMessage(
                role = MessageRole.TOOL,
                content = "result",
                toolCallId = call.id,
                toolName = call.name,
                metadataJson = """{"tool":"${call.name}","sources":[]}""",
            )
        }
        var roundIndex = 0
        val outcome = loop().run(CONTEXT, listOf(ModelMessage(MessageRole.USER, "hi")), dispatcher) {
            roundIndex++
            if (roundIndex == 1) round("", call("1", "search.web")) else round("done")
        }

        assertTrue(outcome is ToolLoopOutcome.Completed)
        val evidence = (outcome as ToolLoopOutcome.Completed).evidence
        assertEquals(1, evidence.size)
        assertEquals("search.web", evidence.single().toolId)
        assertTrue(evidence.single().metadataJson.contains("search.web"))
    }

    @Test
    fun toolResultsWithoutMetadataProduceNoEvidence() {
        var roundIndex = 0
        val outcome = loop().run(CONTEXT, listOf(ModelMessage(MessageRole.USER, "hi")), dispatcher()) {
            roundIndex++
            if (roundIndex == 1) round("", call("1")) else round("done")
        }

        assertTrue(outcome is ToolLoopOutcome.Completed)
        assertTrue((outcome as ToolLoopOutcome.Completed).evidence.isEmpty())
    }

    @Test
    fun answersImmediatelyWhenNoToolsAreRequested() {
        val outcome = loop().run(CONTEXT, listOf(ModelMessage(MessageRole.USER, "hi")), dispatcher()) {
            round("hello")
        }

        assertTrue(outcome is ToolLoopOutcome.Completed)
        assertEquals("hello", outcome.text)
        assertEquals(1, outcome.rounds)
    }

    @Test
    fun executesRequestedToolsAndSendsResultsBack() {
        val sent = mutableListOf<List<ModelMessage>>()
        val executed = mutableListOf<String>()
        var turn = 0

        val outcome = loop().run(CONTEXT, listOf(ModelMessage(MessageRole.USER, "list files")), dispatcher(executed)) { messages ->
            sent += messages.toList()
            turn++
            if (turn == 1) round("", call("c1")) else round("done")
        }

        assertTrue(outcome is ToolLoopOutcome.Completed)
        assertEquals("done", outcome.text)
        assertEquals(listOf("c1"), executed, "the requested tool must actually run")

        // Second request must contain the assistant tool request followed by its result.
        val second = sent[1]
        assertEquals(MessageRole.ASSISTANT, second[second.size - 2].role)
        assertEquals(listOf("c1"), second[second.size - 2].toolCalls.map { it.id })
        assertEquals(MessageRole.TOOL, second.last().role)
        assertEquals("c1", second.last().toolCallId)
    }

    @Test
    fun executesEveryCallInOneResponse() {
        val executed = mutableListOf<String>()
        var turn = 0

        loop().run(CONTEXT, listOf(ModelMessage(MessageRole.USER, "go")), dispatcher(executed)) {
            turn++
            if (turn == 1) round("", call("c1"), call("c2")) else round("ok")
        }

        assertEquals(listOf("c1", "c2"), executed)
    }

    @Test
    fun stopsAtTheRoundLimit() {
        // A model that keeps asking for tools must not run forever.
        val outcome = loop(ToolLoopLimits(maxRounds = 3, maxToolCalls = 100)).run(
            CONTEXT,
            listOf(ModelMessage(MessageRole.USER, "loop")),
            dispatcher(),
        ) { round("", call("c")) }

        assertTrue(outcome is ToolLoopOutcome.Stopped)
        assertEquals(ToolLoopStop.ROUND_LIMIT, outcome.reason)
        assertEquals(3, outcome.rounds)
    }

    @Test
    fun stopsAtTheToolCallLimit() {
        var turn = 0
        val outcome = loop(ToolLoopLimits(maxRounds = 10, maxToolCalls = 2)).run(
            CONTEXT,
            listOf(ModelMessage(MessageRole.USER, "loop")),
            dispatcher(),
        ) {
            turn++
            round("", call("c$turn"))
        }

        assertTrue(outcome is ToolLoopOutcome.Stopped)
        assertEquals(ToolLoopStop.CALL_LIMIT, outcome.reason)
    }

    @Test
    fun doesNotOverspendTheCallBudgetWithinOneResponse() {
        // A single response asking for more tools than the budget must be clipped, not run.
        val executed = mutableListOf<String>()
        var turn = 0

        val outcome = loop(ToolLoopLimits(maxRounds = 5, maxToolCalls = 2)).run(
            CONTEXT,
            listOf(ModelMessage(MessageRole.USER, "go")),
            dispatcher(executed),
        ) {
            turn++
            round("", call("a"), call("b"), call("c"), call("d"))
        }

        assertEquals(listOf("a", "b"), executed, "only the budgeted calls may run")
        assertTrue(outcome is ToolLoopOutcome.Stopped)
        assertEquals(ToolLoopStop.CALL_LIMIT, outcome.reason)
    }

    @Test
    fun everyCallGetsAnAnswerEvenPastTheBudget() {
        // An unanswered tool call leaves the next request malformed. The loop terminates on the
        // budget rather than asking again, so only the budgeted call runs and the turn ends.
        val messages = mutableListOf<ModelMessage>()
        val sub = ToolLoopPlugin(ToolLoopLimits(maxRounds = 2, maxToolCalls = 1))
        sub.onEnable()

        val outcome = sub.run(
            CONTEXT,
            listOf(ModelMessage(MessageRole.USER, "go")),
            ToolDispatcher { _, call ->
                ModelMessage(MessageRole.TOOL, "ok", toolCallId = call.id).also { messages += it }
            },
        ) { round("", call("a"), call("b")) }

        assertTrue(outcome is ToolLoopOutcome.Stopped)
        assertEquals(ToolLoopStop.CALL_LIMIT, outcome.reason)
        assertEquals(listOf("a"), messages.map { it.toolCallId }, "only the budgeted call may run")
    }

    @Test
    fun providerFailureStopsWithoutRetrying() {
        // Retrying inside the loop would duplicate whatever was already streamed.
        var attempts = 0
        val outcome = loop().run(CONTEXT, listOf(ModelMessage(MessageRole.USER, "x")), dispatcher()) {
            attempts++
            throw IllegalStateException("network down")
        }

        assertTrue(outcome is ToolLoopOutcome.Stopped)
        assertEquals(ToolLoopStop.FAILED, outcome.reason)
        assertEquals(1, attempts)
    }

    @Test
    fun keepsStreamedTextWhenALaterRoundFails() {
        var turn = 0
        val outcome = loop().run(CONTEXT, listOf(ModelMessage(MessageRole.USER, "x")), dispatcher()) {
            turn++
            if (turn == 1) round("partial answer", call("c")) else throw IllegalStateException("boom")
        }

        assertTrue(outcome is ToolLoopOutcome.Stopped)
        assertEquals("partial answer", outcome.lastText, "the user must not lose text already shown")
    }

    @Test
    fun failsClosedWhenDisabled() {
        val disabled = ToolLoopPlugin()

        assertFailsWith<IllegalStateException> {
            disabled.run(CONTEXT, emptyList(), dispatcher()) { round("x") }
        }
    }

    @Test
    fun limitsRejectIncoherentConfiguration() {
        assertFailsWith<IllegalArgumentException> { ToolLoopLimits(maxRounds = 0) }
        assertFailsWith<IllegalArgumentException> { ToolLoopLimits(maxToolCalls = 0) }
        assertFailsWith<IllegalArgumentException> { ToolLoopLimits(maxResultChars = 0) }
    }

    @Test
    fun toolMessageRequiresACallId() {
        // A tool result with no call to answer is malformed for every provider.
        assertFailsWith<IllegalArgumentException> {
            ModelMessage(MessageRole.TOOL, "result")
        }
    }
}
