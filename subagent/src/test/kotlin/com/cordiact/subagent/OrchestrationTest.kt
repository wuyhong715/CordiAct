package com.cordiact.subagent

import com.cordiact.core.conversation.ConversationMessage
import com.cordiact.core.conversation.ConversationSession
import com.cordiact.core.conversation.ConversationSessionStore
import com.cordiact.core.conversation.MessageRole
import com.cordiact.core.conversation.ModelToolSpec
import com.cordiact.core.conversation.SessionKind
import com.cordiact.core.conversation.SessionManagerPlugin
import com.cordiact.core.subagent.ChildToolPlanner
import com.cordiact.core.subagent.ChildTurnOutcome
import com.cordiact.core.subagent.ChildTurnRequest
import com.cordiact.core.subagent.ChildTurnRunner
import com.cordiact.core.subagent.OrchestrationLimits
import com.cordiact.core.subagent.OrchestrationStop
import com.cordiact.core.tool.InMemoryToolDefinitionRegistry
import com.cordiact.core.tool.ToolArguments
import com.cordiact.core.tool.ToolInvocationContext
import com.cordiact.core.tool.ToolValue
import com.cordiact.core.toolloop.ToolLoopStop
import com.cordiact.core.workspace.Workspace
import com.cordiact.core.workspace.WorkspaceSessionResolver
import org.junit.jupiter.api.Test
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

private const val WS = "ws-1"

private class OrchestrationStore : ConversationSessionStore {
    private val sessions = Collections.synchronizedMap(linkedMapOf<String, ConversationSession>())
    private val counter = AtomicInteger()

    override fun create(
        id: String, workspaceId: String, title: String, nowMillis: Long,
        parentSessionId: String?, kind: SessionKind, agentLabel: String?, depth: Int,
    ): ConversationSession = ConversationSession(
        id, workspaceId, title, nowMillis, nowMillis, parentSessionId, kind, agentLabel, depth,
    ).also { synchronized(sessions) { sessions[id] = it } }

    override fun get(sessionId: String) = synchronized(sessions) { sessions[sessionId] }
    override fun list(workspaceId: String) = synchronized(sessions) {
        sessions.values.filter { it.workspaceId == workspaceId && it.kind == SessionKind.USER }
    }
    override fun listChildren(parentSessionId: String) = synchronized(sessions) {
        sessions.values.filter { it.parentSessionId == parentSessionId }
    }
    override fun append(sessionId: String, role: MessageRole, content: String, nowMillis: Long, metadataJson: String?) =
        ConversationMessage("m-${counter.incrementAndGet()}", sessionId, role, content, nowMillis, metadataJson)
    override fun messages(sessionId: String) = emptyList<ConversationMessage>()
}

private class OrchestrationHarness(
    val limits: OrchestrationLimits = OrchestrationLimits(),
) {
    val store = OrchestrationStore()
    val sessions = SessionManagerPlugin(
        store,
        WorkspaceSessionResolver { id -> if (id == WS) Workspace(id, id, 1L, 1L) else null },
    ).apply { onEnable() }

    /** Prompts the children were asked to run, in call order. */
    val prompts: MutableList<String> = Collections.synchronizedList(mutableListOf())
    val running = AtomicInteger()
    val peakConcurrency = AtomicInteger()

    /** Decides each child's outcome from its prompt. */
    var behaviour: (String) -> Pair<String, ToolLoopStop> = { prompt -> "answer to: $prompt" to ToolLoopStop.COMPLETED }
    var sleepMillis: Long = 0

    val runtime = SubagentRuntime(
        sessions = sessions,
        runner = ChildTurnRunner { request ->
            prompts += request.prompt
            val now = running.incrementAndGet()
            peakConcurrency.updateAndGet { max -> maxOf(max, now) }
            try {
                if (sleepMillis > 0) Thread.sleep(sleepMillis)
                val (text, stop) = behaviour(request.prompt)
                ChildTurnOutcome(text, stop, 7, 11)
            } finally {
                running.decrementAndGet()
            }
        },
        toolPlanner = ChildToolPlanner { _, _ -> emptyList() },
    ).apply { onEnable() }

    val plugin = OrchestrationPlugin(runtime, limits).apply { onEnable() }
    val registry = InMemoryToolDefinitionRegistry().also { registerOrchestrationTools(it, plugin) }
    val root = sessions.createSession(WS, "root")

    fun context() = ToolInvocationContext(
        workspaceId = WS,
        sessionId = root.id,
        modelProviderId = "deepseek",
        modelId = "deepseek-flash",
    )

    fun orchestrate(plan: String): Map<String, ToolValue> =
        registry.get(com.cordiact.core.subagent.ORCHESTRATION_TOOL_ID)!!
            .executor.invoke(
                context(),
                ToolArguments(mapOf("plan" to ToolValue.StringValue(plan))),
            )
            .values
}

private fun value(v: ToolValue?): String = when (v) {
    is ToolValue.StringValue -> v.value
    is ToolValue.BooleanValue -> v.value.toString()
    is ToolValue.NumberValue -> v.value.toInt().toString()
    else -> v.toString()
}

class OrchestrationTest {

    @Test
    fun `a MAP stage starts one child per item in order`() {
        val h = OrchestrationHarness()
        h.behaviour = { prompt -> "done $prompt" to ToolLoopStop.COMPLETED }

        val result = h.orchestrate(
            """{"name":"pricing","stages":[{"id":"scan","task":"price of {{item}}","items":["A","B","C"]}]}""",
        )

        assertEquals("true", value(result["ok"]))
        assertEquals("COMPLETED", value(result["stop"]))
        assertEquals("3", value(result["childrenStarted"]))
        assertEquals(listOf("price of A", "price of B", "price of C"), h.prompts)
    }

    @Test
    fun `outputs are positional even when children finish out of order`() {
        val h = OrchestrationHarness(limits = OrchestrationLimits(maxConcurrentChildren = 4))
        // The first item is the slowest, so completion order is the reverse of item order.
        h.behaviour = { prompt ->
            when {
                prompt.contains("slow") -> { Thread.sleep(120); "result slow" to ToolLoopStop.COMPLETED }
                prompt.contains("fast") -> "result fast" to ToolLoopStop.COMPLETED
                else -> "result other" to ToolLoopStop.COMPLETED
            }
        }

        val result = h.orchestrate(
            """{"name":"p","stages":[{"id":"s","task":"do {{item}}","items":["slow","fast","other"]}]}""",
        )

        val stage = (result["stages"] as ToolValue.ArrayValue).values.single() as ToolValue.ObjectValue
        val outputs = (stage.values["results"] as ToolValue.ArrayValue).values
            .map { ((it as ToolValue.ObjectValue).values["output"] as ToolValue.StringValue).value }
        assertEquals(listOf("result slow", "result fast", "result other"), outputs)
    }

    @Test
    fun `children run in parallel up to the concurrency limit`() {
        val h = OrchestrationHarness(limits = OrchestrationLimits(maxConcurrentChildren = 3))
        h.sleepMillis = 60

        h.orchestrate(
            """{"name":"p","stages":[{"id":"s","task":"do {{item}}","items":["a","b","c","d","e","f"]}]}""",
        )

        assertTrue(h.peakConcurrency.get() > 1, "expected parallel children, peak was ${h.peakConcurrency.get()}")
        assertTrue(h.peakConcurrency.get() <= 3, "concurrency limit exceeded: ${h.peakConcurrency.get()}")
    }

    @Test
    fun `a failing item becomes null and the others survive`() {
        val h = OrchestrationHarness()
        h.behaviour = { prompt ->
            if (prompt.contains("bad")) "partial output" to ToolLoopStop.FAILED
            else "ok" to ToolLoopStop.COMPLETED
        }

        val result = h.orchestrate(
            """{"name":"p","stages":[{"id":"s","task":"do {{item}}","items":["good","bad"]}]}""",
        )

        assertEquals("PARTIAL", value(result["stop"]))
        assertEquals("true", value(result["ok"]))
        val stage = (result["stages"] as ToolValue.ArrayValue).values.single() as ToolValue.ObjectValue
        assertEquals("1", value(stage.values["failed"]))
        val results = (stage.values["results"] as ToolValue.ArrayValue).values
        assertEquals("ok", value((results[0] as ToolValue.ObjectValue).values["output"]))
        assertEquals(ToolValue.NullValue, (results[1] as ToolValue.ObjectValue).values["output"])
        assertTrue(value((results[1] as ToolValue.ObjectValue).values["error"]).contains("FAILED"))
    }

    @Test
    fun `a REDUCE stage receives the previous stage as a JSON array`() {
        val h = OrchestrationHarness()
        h.behaviour = { prompt -> "out($prompt)" to ToolLoopStop.COMPLETED }

        h.orchestrate(
            """{"name":"p","stages":[
              {"id":"scan","task":"do {{item}}","items":["a","b"]},
              {"id":"sum","mode":"REDUCE","task":"combine: {{prev}}"}
            ]}""",
        )

        val reducePrompt = h.prompts.last()
        assertTrue(reducePrompt.startsWith("combine: "), "got: $reducePrompt")
        assertTrue(reducePrompt.contains("out(do a)"), "expected item a's answer in: $reducePrompt")
        assertTrue(reducePrompt.contains("out(do b)"), "expected item b's answer in: $reducePrompt")
        assertTrue(reducePrompt.contains("results"), "expected the wrapper in: $reducePrompt")
    }

    @Test
    fun `a REDUCE in LINES mode joins without a wrapper`() {
        val h = OrchestrationHarness()
        h.behaviour = { prompt -> "out($prompt)" to ToolLoopStop.COMPLETED }

        h.orchestrate(
            """{"name":"p","stages":[
              {"id":"scan","task":"do {{item}}","items":["a"]},
              {"id":"sum","mode":"REDUCE","combine":"LINES","task":"combine: {{prev}}"}
            ]}""",
        )

        val reducePrompt = h.prompts.last()
        assertTrue(reducePrompt.startsWith("combine: out(do a)"), "got: $reducePrompt")
    }

    @Test
    fun `stages are barriers - stage two starts only after stage one settles`() {
        val h = OrchestrationHarness(limits = OrchestrationLimits(maxConcurrentChildren = 4))
        h.sleepMillis = 20

        h.orchestrate(
            """{"name":"p","stages":[
              {"id":"one","task":"first {{item}}","items":["a","b","c"]},
              {"id":"two","task":"second {{item}}","items":["x"]}
            ]}""",
        )

        assertEquals(4, h.prompts.size)
        assertTrue(h.prompts.take(3).all { it.startsWith("first") }, "got: ${h.prompts}")
        assertTrue(h.prompts.last().startsWith("second"), "got: ${h.prompts}")
    }

    @Test
    fun `each child is a Session that belongs to the calling conversation`() {
        val h = OrchestrationHarness()

        h.orchestrate("""{"name":"p","stages":[{"id":"s","task":"do {{item}}","items":["a","b"]}]}""")

        val children = h.sessions.listChildSessions(h.root.id)
        assertEquals(2, children.size)
        assertTrue(children.all { it.kind == SessionKind.ORCHESTRATION })
        assertEquals(listOf("p/s/1", "p/s/2"), children.map { it.title })
        assertEquals(setOf(1), children.map { it.depth }.toSet())
    }

    @Test
    fun `child session ids are reported so a capped output stays reachable`() {
        val h = OrchestrationHarness()
        val result = h.orchestrate("""{"name":"p","stages":[{"id":"s","task":"do {{item}}","items":["a"]}]}""")
        val stage = (result["stages"] as ToolValue.ArrayValue).values.single() as ToolValue.ObjectValue
        val entry = (stage.values["results"] as ToolValue.ArrayValue).values.single() as ToolValue.ObjectValue
        val sessionId = value(entry.values["sessionId"])
        assertTrue(sessionId.isNotBlank())
        assertNotNull(h.sessions.getSession(sessionId))
    }

    @Test
    fun `token usage is summed across children`() {
        val h = OrchestrationHarness()
        val result = h.orchestrate("""{"name":"p","stages":[{"id":"s","task":"do {{item}}","items":["a","b"]}]}""")
        assertEquals("14", value(result["inputTokens"]))
        assertEquals("22", value(result["outputTokens"]))
    }

    // --- validation: every rejection must cost zero children ---

    @Test
    fun `an over-capacity plan starts nothing`() {
        val h = OrchestrationHarness(limits = OrchestrationLimits(maxTotalChildren = 2))
        val result = h.orchestrate(
            """{"name":"p","stages":[{"id":"s","task":"do {{item}}","items":["a","b","c"]}]}""",
        )
        assertEquals("false", value(result["ok"]))
        assertEquals("invalid_plan", value(result["error"]))
        assertTrue(value(result["message"]).contains("limit is 2"), "got: ${value(result["message"])}")
        assertTrue(h.prompts.isEmpty(), "no child may start on a rejected plan")
    }

    @Test
    fun `too many items in a stage is rejected before any child`() {
        val h = OrchestrationHarness(limits = OrchestrationLimits(maxItemsPerStage = 1))
        val result = h.orchestrate(
            """{"name":"p","stages":[{"id":"s","task":"do {{item}}","items":["a","b"]}]}""",
        )
        assertEquals("invalid_plan", value(result["error"]))
        assertTrue(h.prompts.isEmpty())
    }

    @Test
    fun `a REDUCE first stage is rejected`() {
        val h = OrchestrationHarness()
        val result = h.orchestrate("""{"name":"p","stages":[{"id":"s","mode":"REDUCE","task":"x {{prev}}"}]}""")
        assertEquals("invalid_plan", value(result["error"]))
        assertTrue(value(result["message"]).contains("first stage"), "got: ${value(result["message"])}")
        assertTrue(h.prompts.isEmpty())
    }

    @Test
    fun `a MAP stage with items but no placeholder is rejected`() {
        val h = OrchestrationHarness()
        val result = h.orchestrate(
            """{"name":"p","stages":[{"id":"s","task":"do the thing","items":["a","b"]}]}""",
        )
        assertEquals("invalid_plan", value(result["error"]))
        assertTrue(value(result["message"]).contains("{{item}}"), "got: ${value(result["message"])}")
        assertTrue(h.prompts.isEmpty())
    }

    @Test
    fun `duplicate stage ids are rejected`() {
        val h = OrchestrationHarness()
        val result = h.orchestrate(
            """{"name":"p","stages":[
              {"id":"s","task":"a {{item}}","items":["x"]},
              {"id":"s","task":"b {{item}}","items":["y"]}
            ]}""",
        )
        assertEquals("invalid_plan", value(result["error"]))
        assertTrue(value(result["message"]).contains("duplicate"), "got: ${value(result["message"])}")
        assertTrue(h.prompts.isEmpty())
    }

    @Test
    fun `malformed JSON is rejected as data, not an exception`() {
        val h = OrchestrationHarness()
        val result = h.orchestrate("{not json")
        assertEquals("invalid_plan", value(result["error"]))
        assertTrue(h.prompts.isEmpty())
    }

    @Test
    fun `an unknown stage mode is rejected rather than defaulting`() {
        val h = OrchestrationHarness()
        val result = h.orchestrate("""{"name":"p","stages":[{"id":"s","task":"x","mode":"REDUCE_ALL"}]}""")
        assertEquals("invalid_plan", value(result["error"]))
        assertTrue(h.prompts.isEmpty())
    }

    @Test
    fun `an empty plan is rejected`() {
        val h = OrchestrationHarness()
        assertEquals("invalid_arguments", value(h.orchestrate("")["error"]))
        assertEquals("invalid_plan", value(h.orchestrate("""{"name":"p","stages":[]}""")["error"]))
        assertEquals("invalid_plan", value(h.orchestrate("""{"stages":[{"id":"s","task":"x"}]}""")["error"]))
    }

    @Test
    fun `orchestration without a calling Session is refused`() {
        val h = OrchestrationHarness()
        val result = h.registry.get(com.cordiact.core.subagent.ORCHESTRATION_TOOL_ID)!!
            .executor.invoke(
                ToolInvocationContext(workspaceId = WS, sessionId = null),
                ToolArguments(mapOf("plan" to ToolValue.StringValue("""{"name":"p","stages":[]}"""))),
            )
        assertEquals("no_session", (result.values["error"] as ToolValue.StringValue).value)
    }

    @Test
    fun `a single-child MAP stage needs no items`() {
        val h = OrchestrationHarness()
        val result = h.orchestrate("""{"name":"p","stages":[{"id":"s","task":"do the whole thing"}]}""")
        assertEquals("1", value(result["childrenStarted"]))
        assertEquals(listOf("do the whole thing"), h.prompts)
    }

    @Test
    fun `an orchestration wider than the delegation cap is still admitted`() {
        // The two limits must not disagree: a plan that passes validation and then hits the
        // per-Session child cap would abort after paying for the children that already ran.
        val h = OrchestrationHarness(
            limits = OrchestrationLimits(maxItemsPerStage = 40, maxTotalChildren = 40),
        )
        val plan = (1..40).joinToString(",") { "\"i$it\"" }
        val result = h.orchestrate("""{"name":"p","stages":[{"id":"s","task":"do {{item}}","items":[$plan]}]}""")
        assertEquals("40", value(result["childrenStarted"]))
        assertEquals("COMPLETED", value(result["stop"]))
    }

    @Test
    fun `a plan larger than the total-child cap is rejected before anything runs`() {
        val h = OrchestrationHarness(limits = OrchestrationLimits(maxTotalChildren = 3, maxItemsPerStage = 32))
        val result = h.orchestrate(
            """{"name":"p","stages":[{"id":"s","task":"do {{item}}","items":["a","b","c","d"]}]}""",
        )
        assertEquals("invalid_plan", value(result["error"]))
        assertTrue(h.prompts.isEmpty())
    }

    @Test
    fun `the orchestration tool declares a host timeout within the tool manager limit`() {
        val h = OrchestrationHarness()
        val descriptor = h.registry.get(com.cordiact.core.subagent.ORCHESTRATION_TOOL_ID)!!.descriptor
        val timeout = assertNotNull(descriptor.timeoutMs)
        assertTrue(timeout in 1..300_000, "timeout $timeout is outside the tool manager's range")
        assertEquals(
            setOf(com.cordiact.core.subagent.ORCHESTRATION_CAPABILITY),
            descriptor.requiredCapabilities,
        )
    }
}
