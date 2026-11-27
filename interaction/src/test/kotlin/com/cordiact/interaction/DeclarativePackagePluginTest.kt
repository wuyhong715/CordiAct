package com.cordiact.interaction

import com.cordiact.core.plugin.ExtensionPackageCapability
import com.cordiact.core.plugin.ExtensionPackageSource
import com.cordiact.core.plugin.PackageScriptRuntime
import com.cordiact.core.plugin.PackageScriptSession
import com.cordiact.core.plugin.PackageAgentContributions
import com.cordiact.core.skill.SkillFileSource
import com.cordiact.core.skill.SkillIndexRenderer
import com.cordiact.core.skill.SkillOrigin
import com.cordiact.core.skill.SkillPlugin
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
    fun `installed agent package adds a qualified skill and bounded context until withdrawal`() {
        val manifest = ExtensionPackageSource.compile(requireNotNull(javaClass.getResourceAsStream("/review-kit.json")).use { it.readBytes() })
        val contributions = PackageAgentContributions()
        val skill = SkillPlugin(object : SkillFileSource {
            override fun listSkillFiles(workspaceId: String) = emptyList<String>()
            override fun read(workspaceId: String, path: String) = null
        }, packageSkills = contributions::skills).also { it.onEnable() }
        val input = "</package> & <instruction>"
        var admitted = true
        val plugin = DeclarativePackagePlugin(
            manifest.copy(agent = manifest.agent!!.copy(context = input)), PluginKernel(),
            PluginToolContributions(InMemoryToolDefinitionRegistry()), PluginCommandContributions(InteractionCommandRegistry { true }),
            manifest.capabilities, workspaceId = "ws-1", admitted = { admitted }, agentContributions = contributions,
        )
        val name = "review-kit.change-review"
        plugin.onRegister()
        assertNull(skill.find("ws-1", name))
        assertNull(contributions.context("ws-1"))

        plugin.onEnable()
        assertEquals(SkillOrigin.PACKAGE, skill.find("ws-1", name)?.origin)
        assertEquals(listOf(name), SkillIndexRenderer().render(skill.catalog("ws-1")).included)
        assertEquals(manifest.agent!!.skills.single().body, skill.body("ws-1", name))
        assertNull(skill.body("ws-2", name))
        assertNull(contributions.context("ws-2"))
        val context = assertNotNull(contributions.context("ws-1"))
        assertEquals(listOf("review-kit@1.0.0"), context.sources)
        kotlin.test.assertTrue("&lt;/package&gt; &amp; &lt;instruction&gt;" in context.text)
        admitted = false
        assertNull(skill.find("ws-1", name))
        assertNull(skill.body("ws-1", name))
        assertNull(contributions.context("ws-1"))
        plugin.onDisable()
        admitted = true
        assertNull(skill.body("ws-1", name))
    }

    @Test
    fun `script tool grants a bounded session for one workspace and closes it on withdrawal`() {
        val registry = InMemoryToolDefinitionRegistry()
        val commands = InteractionCommandRegistry { true }
        val artifact = requireNotNull(javaClass.getResourceAsStream("/line-counter.json")).use { it.readBytes() }
        val manifest = ExtensionPackageSource.compile(artifact)
        var closed = false
        var calls = 0
        var admitted = true
        val runtime = PackageScriptRuntime { owner ->
            assertEquals("ws-1", owner.workspaceId)
            object : PackageScriptSession {
                override fun evaluate(source: ByteArray, input: ByteArray): ByteArray {
                    assertEquals(manifest.tools.single().script, source.decodeToString())
                    calls++
                    if (input.decodeToString() == "withdraw") admitted = false
                    return input
                }
                override fun close() { closed = true }
            }
        }
        fun plugin(suppliedRuntime: PackageScriptRuntime?) = DeclarativePackagePlugin(
            manifest, PluginKernel(), PluginToolContributions(registry), PluginCommandContributions(commands),
            manifest.capabilities, workspaceId = "ws-1", admitted = { admitted }, scriptRuntime = suppliedRuntime,
        ).also { it.onRegister() }
        val unavailable = plugin(null)
        assertFailsWith<IllegalStateException> { unavailable.onEnable() }
        val installed = plugin(runtime)
        installed.onEnable()
        val id = installed.toolId("count-lines")
        val tool = assertNotNull(registry.getIn(id, "ws-1", null))
        assertNull(registry.getIn(id, "ws-2", null))
        fun invoke(input: String) = tool.executor.invoke(
            ToolInvocationContext("ws-1"), ToolArguments(mapOf("text" to ToolValue.StringValue(input))),
        )

        assertEquals(ToolValue.StringValue("🌱\n"), invoke("🌱\n").values["text"])
        assertFailsWith<com.cordiact.core.tool.ToolManagerException> { invoke("界".repeat(2_000)) }
        assertEquals(1, calls)
        assertFailsWith<IllegalStateException> { invoke("withdraw") }
        assertNull(registry.getIn(id, "ws-1", null))
        installed.onDisable()
        assertEquals(true, closed)
        assertNull(registry.getIn(id, "ws-1", null))
        assertFailsWith<IllegalStateException> { invoke("stale") }
        assertEquals(2, calls)
    }

    @Test
    fun `publishes tool and command in one workspace and withdraws both`() {
        val registry = InMemoryToolDefinitionRegistry()
        val commands = InteractionCommandRegistry { true }
        val kernel = PluginKernel()
        val manifest = ExtensionPackageSource.compile(source())
        val plugin = DeclarativePackagePlugin(
            manifest = manifest,
            kernel = kernel,
            tools = PluginToolContributions(registry),
            commands = PluginCommandContributions(commands),
            grantedCapabilities = manifest.capabilities,
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
    fun `stopping the same package in one workspace leaves the other enabled`() {
        val registry = InMemoryToolDefinitionRegistry()
        val commands = InteractionCommandRegistry { true }
        val kernel = PluginKernel()
        val manifest = ExtensionPackageSource.compile(source())
        fun instance(workspaceId: String) = DeclarativePackagePlugin(
            manifest, kernel, PluginToolContributions(registry), PluginCommandContributions(commands),
            grantedCapabilities = manifest.capabilities,
            workspaceId = workspaceId,
        ).also { it.onRegister() }
        val first = instance("ws-1")
        val second = instance("ws-2")
        fun find(workspace: String) = commands.find("cl", InteractionContext("session-$workspace", workspace))
        first.onEnable()
        assertNotNull(find("ws-1"))
        assertNull(find("ws-2"))
        second.onEnable()
        val otherCommand = assertNotNull(find("ws-2"))
        val otherTool = assertNotNull(registry.getIn(second.toolId("checklist"), "ws-2", null)).registration
        first.onDisable()
        assertNull(find("ws-1"))
        assertNull(registry.getIn(first.toolId("checklist"), "ws-1", null))
        kotlin.test.assertSame(otherCommand, find("ws-2"))
        kotlin.test.assertSame(otherTool, registry.getIn(second.toolId("checklist"), "ws-2", null)?.registration)
        first.onEnable()
        second.onDisable()
        assertNotNull(find("ws-1"))
        assertNull(find("ws-2"))
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
            grantedCapabilities = setOf(ExtensionPackageCapability.TOOL_PUBLISH),
            workspaceId = "ws-1",
        )
        plugin.onRegister()
        assertFailsWith<IllegalStateException> { plugin.onEnable() }
        assertNull(registry.getIn(plugin.toolId("checklist"), "ws-1", null))
        assertNull(commands.find("checklist", InteractionContext("session-1", "ws-1")))
    }

    @Test
    fun `a reconstructed runtime restores only the explicitly enabled workspace on first entry`() {
        val store = com.cordiact.core.plugin.InMemoryExtensionPackageStore()
        val installer = com.cordiact.core.plugin.ExtensionPackageInstaller(store)
        val artifact = installer.inspect(source())
        fun install(workspace: String) = (installer.install(workspace, artifact, null, artifact.manifest.capabilities)
            as com.cordiact.core.plugin.ExtensionPackageChange.Applied).record
        val first = install("ws-1")
        val second = install("ws-2")
        assertEquals(false, first.enableIntent)
        installer.setEnableIntent(first.workspaceId, first.manifest.id, first.installationId, true)

        val registry = InMemoryToolDefinitionRegistry()
        val commands = InteractionCommandRegistry { true }
        val kernel = PluginKernel()
        val runtime = com.cordiact.core.plugin.WorkspacePackageRuntime { record, gate ->
            DeclarativePackagePlugin(record.manifest, kernel, PluginToolContributions(registry), PluginCommandContributions(commands),
                record.grantedCapabilities, workspaceId = record.workspaceId, admitted = gate)
        }
        var readOnly = false
        val service = com.cordiact.core.plugin.WorkspaceExtensionPackages(installer, { id ->
            if (readOnly) com.cordiact.core.security.EffectiveSecurityProfile.readOnly(id)
            else com.cordiact.core.security.EffectiveSecurityProfile.fullAccess(id)
        }, runtime = runtime)
        val page = service.openPage("ws-1")
        service.resume(Any())
        fun command(workspace: String) = commands.find("cl", InteractionContext("session-$workspace", workspace))
        fun tool(workspace: String) = registry.getIn("extension.pkg.quick-notes.checklist", workspace, "session-$workspace")
        assertNull(command("ws-1"))
        assertNull(tool("ws-1"))
        service.enterWorkspace(second.workspaceId)
        assertNull(command("ws-2"))
        service.enterWorkspace(first.workspaceId)
        assertNotNull(command("ws-1"))
        val published = assertNotNull(tool("ws-1"))
        service.enterWorkspace(first.workspaceId)
        kotlin.test.assertSame(published.registration, tool("ws-1")?.registration)
        assertEquals(com.cordiact.core.plugin.PluginState.ENABLED, service.scopedOwnerState("pkg.quick-notes", "ws-1", null))
        assertNull(service.scopedOwnerState("pkg.quick-notes", "ws-2", null))

        val running = page.list().single()
        assertFailsWith<IllegalStateException> { page.approveRemoval(running).commit() }
        service.whileUpdatingPolicy("ws-1") { readOnly = true }
        assertNull(command("ws-1"))
        assertNull(tool("ws-1"))
        assertEquals(true, page.list().single().enableIntent)
        page.approveEnable(running, false).commit()
        assertEquals(false, page.list().single().enableIntent)
        assertNotNull(installer.list("ws-2").single())
    }

    @Test
    fun `command conflict rolls back the tool and restarts only after explicit retry`() {
        val installer = com.cordiact.core.plugin.ExtensionPackageInstaller(com.cordiact.core.plugin.InMemoryExtensionPackageStore())
        val tools = InMemoryToolDefinitionRegistry()
        val commands = InteractionCommandRegistry { true }
        val kernel = PluginKernel()
        val runtime = com.cordiact.core.plugin.WorkspacePackageRuntime { record, gate ->
            DeclarativePackagePlugin(record.manifest, kernel, PluginToolContributions(tools), PluginCommandContributions(commands),
                record.grantedCapabilities, workspaceId = record.workspaceId, admitted = gate)
        }
        val service = com.cordiact.core.plugin.WorkspaceExtensionPackages(installer,
            { com.cordiact.core.security.EffectiveSecurityProfile.fullAccess(it) }, runtime = runtime)
        service.resume(Any())
        val page = service.openPage("ws-1")
        val session = InteractionContext("session", "ws-1")
        val blocker = commands.register(CommandDescriptor("host.block", "cl", "host", allowsEmptyBody = true,
            executor = InteractionCommandExecutor { _, _, _, _ -> CommandExecutionResult(true) }))
        commands.setOwnerEnabled("host", true)
        val installed = (page.approveInstall(page.preview(source().inputStream())).commit() as com.cordiact.core.plugin.ExtensionPackageChange.Applied).record
        val selected = (page.approveEnable(installed, true).commit() as com.cordiact.core.plugin.ExtensionPackageChange.Applied).record
        val toolId = "extension.pkg.quick-notes.checklist"
        assertEquals(com.cordiact.core.plugin.WorkspacePackageRuntime.Status.FAILED, page.status(selected))
        assertNull(tools.getIn(toolId, "ws-1", "session"))
        assertNull(commands.find("checklist", session))
        kotlin.test.assertSame(blocker, commands.find("cl", session))
        commands.unregisterRegistration(blocker)
        assertNull(tools.getIn(toolId, "ws-1", "session"))
        page.approveRetryStart(selected).commit()
        assertEquals(com.cordiact.core.plugin.WorkspacePackageRuntime.Status.ACTIVE, page.status(selected))
        assertNotNull(tools.getIn(toolId, "ws-1", "session"))
        assertNotNull(commands.find("cl", session))
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
