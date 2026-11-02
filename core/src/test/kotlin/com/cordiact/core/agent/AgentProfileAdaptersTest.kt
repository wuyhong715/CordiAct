package com.cordiact.core.agent

import com.cordiact.core.tool.InMemoryToolDefinitionRegistry
import com.cordiact.core.tool.ToolDefinition
import com.cordiact.core.tool.ToolDescriptor
import com.cordiact.core.tool.ToolInputSchema
import com.cordiact.core.tool.ToolManagerPlugin
import com.cordiact.core.tool.ToolPluginStateLookup
import com.cordiact.core.tool.ToolPreferenceStore
import com.cordiact.core.tool.ToolResult
import com.cordiact.core.plugin.PluginState
import com.cordiact.core.workspace.Workspace
import com.cordiact.core.workspace.WorkspaceManagerService
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private const val WORKSPACE = PROFILE_WORKSPACE
private const val OWNER = "owner-plugin"
private const val ENABLED_TOOL = "file.read"
private const val DISABLED_TOOL = "shell.exec"
private const val ORPHAN_TOOL = "image.render"

/**
 * These tests run the adapters against the real [ToolManagerPlugin] rather than a
 * stand-in, because the point of the adapter is precisely how it maps the manager's
 * three-way `dispatchable` flag onto the profile's "enabled" set. A fake would let
 * that mapping be wrong while every test still passed.
 */
class AgentProfileAdaptersTest {

    @Test
    fun onlyDispatchableToolsCountAsEnabled() {
        val visibility = ToolManagerVisibility(manager(ownerEnabled = true))

        // Enabled and healthy.
        assertTrue(ENABLED_TOOL in visibility.enabledToolIds(WORKSPACE))
    }

    @Test
    fun disabledToolIsRegisteredButNotEnabled() {
        val visibility = ToolManagerVisibility(manager(ownerEnabled = true))

        // Registered, so a profile may name it without failing the dangling check...
        assertTrue(DISABLED_TOOL in visibility.registeredToolIds(WORKSPACE))
        // ...but it is not enabled, so the intersection drops it.
        assertFalse(DISABLED_TOOL in visibility.enabledToolIds(WORKSPACE))
    }

    @Test
    fun toolWithDisabledOwnerIsNotEnabled() {
        val visibility = ToolManagerVisibility(manager(ownerEnabled = false))

        // The workspace preference says enabled, but the owner plugin is off, so dispatch
        // would fail. Reporting it as enabled would advertise a tool that cannot run.
        assertTrue(ENABLED_TOOL in visibility.registeredToolIds(WORKSPACE))
        assertFalse(ENABLED_TOOL in visibility.enabledToolIds(WORKSPACE))
    }

    @Test
    fun unknownToolIsNeitherRegisteredNorEnabled() {
        val visibility = ToolManagerVisibility(manager(ownerEnabled = true))

        assertFalse("does.not.exist" in visibility.registeredToolIds(WORKSPACE))
        assertFalse("does.not.exist" in visibility.enabledToolIds(WORKSPACE))
    }

    @Test
    fun profileIntersectionDropsUnavailableToolsEndToEnd() {
        // The full path: real manager -> adapter -> resolver.
        val manager = manager(ownerEnabled = true)
        val plugin = AgentProfilePlugin(
            workspaceManager = FakeWorkspaceManager(),
            store = InMemoryProfileStore(),
            tools = ToolManagerVisibility(manager),
            models = ProviderModelVisibility { emptyList() },
            instructions = InstructionFileReader { _, _, _ -> "" },
            grantedCapabilities = emptySet(),
        )
        plugin.onEnable()
        plugin.save(
            AgentProfile(
                id = AgentProfileId("reviewer"),
                workspaceId = WORKSPACE,
                version = 1,
                displayName = "Reviewer",
                toolRefs = setOf(ENABLED_TOOL, DISABLED_TOOL),
            ),
        )

        val resolved = plugin.resolve(WORKSPACE, AgentProfileId("reviewer"))

        assertEquals(setOf(ENABLED_TOOL), resolved.enabledTools)
        assertEquals(setOf(DISABLED_TOOL), resolved.unavailableTools)
    }

    @Test
    fun modelVisibilityMatchesAnyProviderOfferingTheModel() {
        val visibility = ProviderModelVisibility {
            listOf(
                RegisteredModel("deepseek", "deepseek-flash"),
                RegisteredModel("google", "gemini-2.5-pro"),
            )
        }

        assertTrue(visibility.isRegistered("deepseek-flash"))
        assertTrue(visibility.isRegistered("gemini-2.5-pro"))
        assertFalse(visibility.isRegistered("gpt-5"))
    }

    @Test
    fun modelVisibilityReflectsLaterRegistryChanges() {
        // The registry is mutable at runtime, so the visibility must not cache a snapshot.
        var models = listOf(RegisteredModel("deepseek", "deepseek-flash"))
        val visibility = ProviderModelVisibility { models }

        assertFalse(visibility.isRegistered("gemini-2.5-pro"))
        models = models + RegisteredModel("google", "gemini-2.5-pro")
        assertTrue(visibility.isRegistered("gemini-2.5-pro"))
    }

    // ---- helpers ----

    private fun descriptor(id: String, owner: String = OWNER) = ToolDescriptor(
        id = id,
        name = id,
        description = id,
        inputSchema = ToolInputSchema(),
        ownerPluginId = owner,
    )

    private fun manager(ownerEnabled: Boolean): ToolManagerPlugin {
        val registry = InMemoryToolDefinitionRegistry().apply {
            for (id in listOf(ENABLED_TOOL, DISABLED_TOOL, ORPHAN_TOOL)) {
                register(ToolDefinition(descriptor(id)) { _, _ -> ToolResult() })
            }
        }
        val preferences = object : ToolPreferenceStore, com.cordiact.core.tool.MutableToolPreferenceStore {
            private val values = mutableMapOf<Pair<String, String>, Boolean>(
                (WORKSPACE to ENABLED_TOOL) to true,
                (WORKSPACE to DISABLED_TOOL) to false,
                (WORKSPACE to ORPHAN_TOOL) to true,
            )

            override fun isEnabled(workspaceId: String, toolId: String) = values[workspaceId to toolId]
            override fun setEnabled(workspaceId: String, toolId: String, enabled: Boolean?) {
                if (enabled == null) values.remove(workspaceId to toolId) else values[workspaceId to toolId] = enabled
            }
        }
        val states = ToolPluginStateLookup { id ->
            if (id == OWNER) {
                if (ownerEnabled) PluginState.ENABLED else PluginState.DISABLED
            } else {
                PluginState.ENABLED
            }
        }
        return ToolManagerPlugin(registry, preferences, FakeWorkspaceManager(), states).apply { onEnable() }
    }
}
