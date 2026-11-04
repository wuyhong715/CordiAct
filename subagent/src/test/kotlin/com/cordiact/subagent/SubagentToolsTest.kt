package com.cordiact.subagent

import com.cordiact.core.conversation.ConversationMessage
import com.cordiact.core.conversation.ConversationSession
import com.cordiact.core.conversation.ConversationSessionStore
import com.cordiact.core.conversation.MessageRole
import com.cordiact.core.conversation.ModelToolSpec
import com.cordiact.core.conversation.SessionKind
import com.cordiact.core.conversation.SessionManagerPlugin
import com.cordiact.core.subagent.ChildToolPlanner
import com.cordiact.core.subagent.ChildTurnRunner
import com.cordiact.core.subagent.SUBAGENT_DELEGATE_CAPABILITY
import com.cordiact.core.subagent.SUBAGENT_LIST_TOOL_ID
import com.cordiact.core.subagent.SUBAGENT_PLUGIN_ID
import com.cordiact.core.subagent.SUBAGENT_TOOL_ID
import com.cordiact.core.tool.InMemoryToolDefinitionRegistry
import com.cordiact.core.tool.ToolInvocationContext
import com.cordiact.core.tool.ToolValue
import com.cordiact.core.workspace.Workspace
import com.cordiact.core.workspace.WorkspaceSessionResolver
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

private const val WS = "ws-1"

private class Store : ConversationSessionStore {
    val sessions = linkedMapOf<String, ConversationSession>()
    override fun create(
        id: String, workspaceId: String, title: String, nowMillis: Long,
        parentSessionId: String?, kind: SessionKind, agentLabel: String?, depth: Int,
    ): ConversationSession = ConversationSession(
        id, workspaceId, title, nowMillis, nowMillis, parentSessionId, kind, agentLabel, depth,
    ).also { sessions[id] = it }

    override fun get(sessionId: String) = sessions[sessionId]
    override fun list(workspaceId: String) =
        sessions.values.filter { it.workspaceId == workspaceId && it.kind == SessionKind.USER }
    override fun listChildren(parentSessionId: String) =
        sessions.values.filter { it.parentSessionId == parentSessionId }
    override fun append(sessionId: String, role: MessageRole, content: String, nowMillis: Long, metadataJson: String?) =
        ConversationMessage("m", sessionId, role, content, nowMillis, metadataJson)
    override fun messages(sessionId: String) = emptyList<ConversationMessage>()
}

class SubagentToolsTest {

    private fun registryWithRuntime(): Pair<InMemoryToolDefinitionRegistry, SessionManagerPlugin> {
        val store = Store()
        val sessions = SessionManagerPlugin(
            store,
            WorkspaceSessionResolver { id -> if (id == WS) Workspace(id, id, 1L, 1L) else null },
        ).apply { onEnable() }
        val runtime = SubagentRuntime(
            sessions = sessions,
            runner = ChildTurnRunner { com.cordiact.core.subagent.ChildTurnOutcome("ok", com.cordiact.core.toolloop.ToolLoopStop.COMPLETED) },
            toolPlanner = ChildToolPlanner { _, _ -> emptyList() },
        ).apply { onEnable() }
        val registry = InMemoryToolDefinitionRegistry()
        registerSubagentTools(registry, runtime)
        return registry to sessions
    }

    @Test
    fun `both tools are registered under the subagent plugin`() {
        val (registry, _) = registryWithRuntime()
        assertNotNull(registry.get(SUBAGENT_TOOL_ID))
        assertNotNull(registry.get(SUBAGENT_LIST_TOOL_ID))
        assertEquals(SUBAGENT_PLUGIN_ID, registry.get(SUBAGENT_TOOL_ID)!!.descriptor.ownerPluginId)
    }

    @Test
    fun `delegation declares a host timeout`() {
        val (registry, _) = registryWithRuntime()
        // Without a declared timeout the tool manager runs the tool inline on the caller's
        // thread — which on Android is the UI thread, for the whole child turn.
        assertNotNull(
            registry.get(SUBAGENT_TOOL_ID)!!.descriptor.timeoutMs,
            "delegate_task must declare timeoutMs or it will block the caller's thread",
        )
        assertNotNull(registry.get(SUBAGENT_LIST_TOOL_ID)!!.descriptor.timeoutMs)
    }

    @Test
    fun `delegation declares the capability the host must grant`() {
        val (registry, _) = registryWithRuntime()
        assertEquals(
            setOf(SUBAGENT_DELEGATE_CAPABILITY),
            registry.get(SUBAGENT_TOOL_ID)!!.descriptor.requiredCapabilities,
        )
    }

    @Test
    fun `an empty label is refused without starting a child`() {
        val (registry, sessions) = registryWithRuntime()
        val session = sessions.createSession(WS, "root")

        val result = registry.get(SUBAGENT_TOOL_ID)!!.executor.invoke(
            ToolInvocationContext(workspaceId = WS, sessionId = session.id, modelProviderId = "p", modelId = "m"),
            com.cordiact.core.tool.ToolArguments(
                mapOf(
                    "label" to ToolValue.StringValue("  "),
                    "prompt" to ToolValue.StringValue("do it"),
                ),
            ),
        )

        assertEquals(false, (result.values["ok"] as ToolValue.BooleanValue).value)
        assertTrue(sessions.listChildSessions(session.id).isEmpty())
    }

    @Test
    fun `a delegation without a calling Session is refused as data`() {
        val (registry, _) = registryWithRuntime()

        val result = registry.get(SUBAGENT_TOOL_ID)!!.executor.invoke(
            ToolInvocationContext(workspaceId = WS, sessionId = null, modelProviderId = "p", modelId = "m"),
            com.cordiact.core.tool.ToolArguments(
                mapOf(
                    "label" to ToolValue.StringValue("task"),
                    "prompt" to ToolValue.StringValue("do it"),
                ),
            ),
        )

        assertEquals("no_session", (result.values["error"] as ToolValue.StringValue).value)
    }

    @Test
    fun `a successful delegation reports the child Session id and stop reason`() {
        val (registry, sessions) = registryWithRuntime()
        val session = sessions.createSession(WS, "root")

        val result = registry.get(SUBAGENT_TOOL_ID)!!.executor.invoke(
            ToolInvocationContext(workspaceId = WS, sessionId = session.id, modelProviderId = "p", modelId = "m"),
            com.cordiact.core.tool.ToolArguments(
                mapOf(
                    "label" to ToolValue.StringValue("task"),
                    "prompt" to ToolValue.StringValue("do it"),
                ),
            ),
        )

        assertEquals(true, (result.values["ok"] as ToolValue.BooleanValue).value)
        val childId = (result.values["childSessionId"] as ToolValue.StringValue).value
        assertTrue(childId.isNotBlank())
        assertEquals("COMPLETED", (result.values["stop"] as ToolValue.StringValue).value)
        assertEquals(1, sessions.listChildSessions(session.id).size)
    }

    @Test
    fun `list_children is empty before any delegation and reports what exists after`() {
        val (registry, sessions) = registryWithRuntime()
        val session = sessions.createSession(WS, "root")
        val context = ToolInvocationContext(workspaceId = WS, sessionId = session.id)

        val before = registry.get(SUBAGENT_LIST_TOOL_ID)!!.executor.invoke(context, com.cordiact.core.tool.ToolArguments())
        assertEquals(0, (before.values["children"] as ToolValue.ArrayValue).values.size)

        registry.get(SUBAGENT_TOOL_ID)!!.executor.invoke(
            context.copy(modelProviderId = "p", modelId = "m"),
            com.cordiact.core.tool.ToolArguments(
                mapOf(
                    "label" to ToolValue.StringValue("task"),
                    "prompt" to ToolValue.StringValue("do it"),
                ),
            ),
        )

        val after = registry.get(SUBAGENT_LIST_TOOL_ID)!!.executor.invoke(context, com.cordiact.core.tool.ToolArguments())
        val children = (after.values["children"] as ToolValue.ArrayValue).values
        assertEquals(1, children.size)
        val entry = children.single() as ToolValue.ObjectValue
        assertEquals("task", (entry.values["label"] as ToolValue.StringValue).value)
    }
}
