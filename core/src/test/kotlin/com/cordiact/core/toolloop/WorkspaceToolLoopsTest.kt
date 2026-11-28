package com.cordiact.core.toolloop

import com.cordiact.core.conversation.MessageRole
import com.cordiact.core.conversation.ModelMessage
import com.cordiact.core.conversation.ModelToolCall
import com.cordiact.core.plugin.ContextPlugin
import com.cordiact.core.plugin.PluginContext
import com.cordiact.core.plugin.PluginInstanceId
import com.cordiact.core.plugin.PluginKernel
import com.cordiact.core.tool.ToolInvocationContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

class WorkspaceToolLoopsTest {
    @Test
    fun workspaceProviderCanReplaceTheDefaultWithoutChangingDispatchScope() {
        val fixture = Fixture()
        var dispatched: ToolInvocationContext? = null
        val selected = ToolInvocationContext("target", sessionId = "current")
        var savedSend: ((List<ModelMessage>) -> ToolLoopRound)? = null
        fixture.registerWorkspace(loop { context, dispatch, send ->
            savedSend = send
            dispatch.dispatch(context.copy(workspaceId = "other", sessionId = "foreign"), ModelToolCall("call", "read", "{}"))
            ToolLoopOutcome.Completed("override", 1)
        })
        val dispatcher = ToolDispatcher { context, call ->
            dispatched = context
            ModelMessage(MessageRole.TOOL, "ok", toolCallId = call.id)
        }
        fun run(context: ToolInvocationContext) = fixture.loops.run(context, emptyList(), dispatcher) {
            ToolLoopRound("default", emptyList(), emptyList())
        }
        assertEquals("override", assertIs<ToolLoopOutcome.Completed>(run(selected)).text)
        assertEquals(selected, dispatched)
        assertFailsWith<IllegalStateException> { requireNotNull(savedSend)(emptyList()) }
        assertEquals("default", assertIs<ToolLoopOutcome.Completed>(run(selected.copy(workspaceId = "other"))).text)
        fixture.kernel.disable(fixture.workspaceId)
        assertEquals("default", assertIs<ToolLoopOutcome.Completed>(run(selected)).text)
    }

    @Test
    fun withdrawalStopsAnExistingTurnBeforeItCanDispatchOrUseTheReplacement() {
        val fixture = Fixture()
        val selected = ToolInvocationContext("target")
        val delegate = ToolLoopPlugin().apply { onEnable() }
        fixture.registerWorkspace(delegate)
        var requests = 0
        val result = fixture.loops.run(selected, emptyList(), ToolDispatcher { _, _ -> error("Retired turn dispatched a tool") }) {
            requests++
            fixture.kernel.restart(fixture.workspaceId)
            ToolLoopRound("discard", listOf(ModelToolCall("call", "read", "{}")), emptyList())
        }
        val stopped = assertIs<ToolLoopOutcome.Stopped>(result)
        assertEquals("tool_loop_revoked", stopped.failure?.code)
        assertEquals("", stopped.lastText)
        assertEquals(1, requests)
    }

    private class Fixture {
        val kernel = PluginKernel()
        val loops = WorkspaceToolLoops()
        val workspaceId = PluginInstanceId("test-loop", "target")

        init {
            val host = PluginInstanceId("stock-loop")
            val stock = ToolLoopPlugin().apply { onEnable() }
            kernel.register(host, object : ContextPlugin {
                override fun start(context: PluginContext) {
                    context.provide(TOOL_LOOP_CONTRIBUTIONS, loops)
                    loops.register(context, stock)
                }
            })
            kernel.enable(host)
        }

        fun registerWorkspace(service: ToolLoopService) {
            kernel.register(workspaceId, object : ContextPlugin {
                override val requiredServices = setOf(TOOL_LOOP_CONTRIBUTIONS)
                override fun start(context: PluginContext) {
                    context.service(TOOL_LOOP_CONTRIBUTIONS).register(context, service)
                }
            })
            kernel.enable(workspaceId)
        }
    }

    private fun loop(block: (ToolInvocationContext, ToolDispatcher, (List<ModelMessage>) -> ToolLoopRound) -> ToolLoopOutcome) =
        object : ToolLoopService {
            override fun run(
                context: ToolInvocationContext,
                initialMessages: List<ModelMessage>,
                dispatcher: ToolDispatcher,
                finalizeWithoutTools: ((List<ModelMessage>) -> ToolLoopRound)?,
                send: (List<ModelMessage>) -> ToolLoopRound,
            ) = block(context, dispatcher, send)
        }
}
