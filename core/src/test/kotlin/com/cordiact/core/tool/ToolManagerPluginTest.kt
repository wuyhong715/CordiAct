package com.cordiact.core.tool

import com.cordiact.core.plugin.PluginState
import com.cordiact.core.workspace.Workspace
import com.cordiact.core.workspace.WorkspaceManagerService
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ToolManagerPluginTest {
    @Test
    fun dispatchIsWorkspaceScopedAndRechecksOwnerState() {
        val workspaces = FakeWorkspaceManager()
        val states = mutableMapOf(
            TOOL_MANAGER_PLUGIN_ID to PluginState.ENABLED,
            OWNER_ID to PluginState.ENABLED,
        )
        val preferences = FakePreferences()
        var invocations = 0
        val registry = InMemoryToolDefinitionRegistry().apply {
            register(
                ToolDefinition(
                    descriptor = descriptor(),
                    executor = { _, args ->
                        invocations++
                        ToolResult(mapOf("path" to args.values.getValue("path")))
                    },
                ),
            )
        }
        val plugin = manager(registry, preferences, workspaces) { states[it] }
        plugin.onEnable()

        assertEquals(false, plugin.list(WORKSPACE_A).single().enabledForWorkspace)
        plugin.setEnabled(WORKSPACE_A, TOOL_ID, true)
        assertEquals(true, plugin.list(WORKSPACE_A).single().dispatchable)
        assertEquals(false, plugin.list(WORKSPACE_B).single().enabledForWorkspace)
        assertEquals(1, (plugin.invoke(context(WORKSPACE_A), TOOL_ID, validArgs()).values.size))
        assertEquals(1, invocations)

        states[OWNER_ID] = PluginState.DISABLED
        assertFailsWith<ToolManagerException> { plugin.invoke(context(WORKSPACE_A), TOOL_ID, validArgs()) }
        assertEquals(1, invocations)
    }

    @Test
    fun archivedWorkspaceAndInvalidArgumentsNeverReachExecutor() {
        val workspaces = FakeWorkspaceManager()
        val preferences = FakePreferences().apply { setEnabled(WORKSPACE_A, TOOL_ID, true) }
        var invocations = 0
        val registry = InMemoryToolDefinitionRegistry().apply {
            register(ToolDefinition(descriptor(), ToolExecutor { _, _ -> invocations++; ToolResult() }))
        }
        val plugin = manager(registry, preferences, workspaces) {
            when (it) {
                TOOL_MANAGER_PLUGIN_ID, OWNER_ID -> PluginState.ENABLED
                else -> null
            }
        }
        plugin.onEnable()

        assertFailsWith<ToolManagerException> {
            plugin.invoke(context(WORKSPACE_A), TOOL_ID, ToolArguments(mapOf("path" to ToolValue.NumberValue(1.0))))
        }
        assertEquals(0, invocations)

        workspaces.workspaces[WORKSPACE_A] = workspaces.workspaces.getValue(WORKSPACE_A).copy(archivedAtMillis = 9L)
        assertFailsWith<ToolManagerException> { plugin.setEnabled(WORKSPACE_A, TOOL_ID, false) }
        assertFailsWith<ToolManagerException> { plugin.invoke(context(WORKSPACE_A), TOOL_ID, validArgs()) }
        assertEquals(0, invocations)
    }

    @Test
    fun missingCapabilityAndUnknownToolFailClosed() {
        val workspaces = FakeWorkspaceManager()
        val preferences = FakePreferences().apply { setEnabled(WORKSPACE_A, TOOL_ID, true) }
        var invocations = 0
        val registry = InMemoryToolDefinitionRegistry().apply {
            register(
                ToolDefinition(
                    descriptor().copy(requiredCapabilities = setOf("fs.write")),
                    ToolExecutor { _, _ -> invocations++; ToolResult() },
                ),
            )
        }
        val plugin = ToolManagerPlugin(
            registry,
            preferences,
            workspaces,
            ToolPluginStateLookup { PluginState.ENABLED },
            ToolCapabilityGate { _, _ -> false },
        )
        plugin.onEnable()

        assertFailsWith<ToolManagerException> { plugin.invoke(context(WORKSPACE_A), TOOL_ID, validArgs()) }
        assertFailsWith<ToolManagerException> { plugin.setEnabled(WORKSPACE_A, "missing.tool", true) }
        assertEquals(0, invocations)
    }

    private fun manager(
        registry: ToolDefinitionRegistry,
        preferences: MutableToolPreferenceStore,
        workspaces: WorkspaceManagerService,
        states: ToolPluginStateLookup,
    ) = ToolManagerPlugin(registry, preferences, workspaces, states)

    private fun descriptor() = ToolDescriptor(
        id = TOOL_ID,
        name = "Read file",
        description = "Read a file",
        inputSchema = ToolInputSchema(listOf(ToolParameter("path", ToolValueType.STRING, required = true))),
        ownerPluginId = OWNER_ID,
    )

    private fun validArgs() = ToolArguments(mapOf("path" to ToolValue.StringValue("notes.txt")))
    private fun context(workspaceId: String) = ToolInvocationContext(workspaceId)

    private class FakePreferences : MutableToolPreferenceStore {
        private val values = mutableMapOf<Pair<String, String>, Boolean?>()
        override fun isEnabled(workspaceId: String, toolId: String) = values[workspaceId to toolId]
        override fun setEnabled(workspaceId: String, toolId: String, enabled: Boolean?) {
            if (enabled == null) values.remove(workspaceId to toolId) else values[workspaceId to toolId] = enabled
        }
    }

    private class FakeWorkspaceManager : WorkspaceManagerService {
        val workspaces = mutableMapOf(
            WORKSPACE_A to Workspace(WORKSPACE_A, "A", 1L, 1L),
            WORKSPACE_B to Workspace(WORKSPACE_B, "B", 1L, 1L),
        )

        override fun findSessionWorkspace(workspaceId: String) = workspaces[workspaceId]?.takeIf { it.archivedAtMillis == null }
        override fun initializeDefaultIfEligible() = workspaces.getValue(WORKSPACE_A)
        override fun create(name: String) = error("unused")
        override fun get(workspaceId: String) = workspaces[workspaceId]
        override fun list(includeArchived: Boolean) = workspaces.values.filter { includeArchived || it.archivedAtMillis == null }
        override fun rename(workspaceId: String, name: String) = error("unused")
        override fun archive(workspaceId: String) = error("unused")
        override fun restore(workspaceId: String) = error("unused")
        override fun select(workspaceId: String) = error("unused")
        override fun activeWorkspace() = workspaces.getValue(WORKSPACE_A)
        override fun resolveRoot(workspaceId: String) = File("/tmp/$workspaceId")
        override fun resolveSessionWorkspace(workspaceId: String) = findSessionWorkspace(workspaceId)
            ?: throw IllegalArgumentException("Workspace unavailable")
    }

    private companion object {
        const val TOOL_ID = "files.read"
        const val OWNER_ID = "core.file-manager"
        const val WORKSPACE_A = "workspace-a"
        const val WORKSPACE_B = "workspace-b"
    }
}
