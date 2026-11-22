package com.cordiact.interaction

import com.cordiact.core.plugin.ExtensionPackageCapability
import com.cordiact.core.plugin.ExtensionPackageSource
import com.cordiact.core.plugin.PluginKernel
import com.cordiact.core.plugin.PluginToolContributions
import com.cordiact.core.tool.InMemoryToolDefinitionRegistry
import com.cordiact.core.tool.ToolArguments
import com.cordiact.core.tool.ToolInvocationContext
import com.cordiact.core.tool.ToolValue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class DeclarativePackagePluginTest {
    @Test
    fun `publishes tool and command in one workspace and withdraws both`() {
        val registry = InMemoryToolDefinitionRegistry()
        val commands = InteractionCommandRegistry { true }
        val kernel = PluginKernel()
        val plugin = DeclarativePackagePlugin(
            manifest = ExtensionPackageSource.compile(source()),
            kernel = kernel,
            tools = PluginToolContributions(registry),
            commands = PluginCommandContributions(commands),
            commandRegistry = commands,
            workspaceId = "ws-1",
        )
        plugin.onRegister()
        plugin.onEnable()

        val tool = assertNotNull(registry.getIn(plugin.toolId("checklist"), "ws-1", null))
        val result = tool.executor.invoke(ToolInvocationContext("ws-1"), ToolArguments())
        assertEquals("run tests", (result.values["text"] as ToolValue.StringValue).value)

        val command = assertNotNull(commands.find("checklist", InteractionContext("session-1", "ws-1")))
        assertEquals(plugin.spec.id, command.ownerPluginId)
        assertEquals(setOf("cl"), command.aliases)

        plugin.onDisable()
        assertNull(registry.getIn(plugin.toolId("checklist"), "ws-1", null))
        assertNull(commands.find("checklist", InteractionContext("session-1", "ws-1")))
    }

    @Test
    fun `missing capability consent leaves no contribution behind`() {
        val registry = InMemoryToolDefinitionRegistry()
        val commands = InteractionCommandRegistry { true }
        val plugin = DeclarativePackagePlugin(
            manifest = ExtensionPackageSource.compile(source()),
            kernel = PluginKernel(),
            tools = PluginToolContributions(registry),
            commands = PluginCommandContributions(commands),
            commandRegistry = commands,
            grantedCapabilities = setOf(ExtensionPackageCapability.TOOL_PUBLISH),
            workspaceId = "ws-1",
        )
        plugin.onRegister()
        assertFailsWith<IllegalStateException> { plugin.onEnable() }
        assertNull(registry.getIn(plugin.toolId("checklist"), "ws-1", null))
        assertNull(commands.find("checklist", InteractionContext("session-1", "ws-1")))
    }

    private fun source(): ByteArray = """
        {
          "schemaVersion": 1,
          "id": "quick-notes",
          "version": "1.0.0",
          "title": "Quick notes",
          "description": "Small declarative extensions",
          "host": {"api": 1, "minVersion": "0.1.0"},
          "capabilities": ["tool.publish", "command.publish"],
          "tools": [
            {"name": "checklist", "description": "Show the checklist", "kind": "static_text", "text": "run tests"}
          ],
          "commands": [
            {"name": "checklist", "aliases": ["cl"], "description": "Show a note", "text": "note"}
          ]
        }
    """.trimIndent().toByteArray()
}
