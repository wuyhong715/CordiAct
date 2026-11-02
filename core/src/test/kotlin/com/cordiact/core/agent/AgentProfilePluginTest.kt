package com.cordiact.core.agent

import com.cordiact.core.conversation.GenerationParameters
import com.cordiact.core.workspace.Workspace
import com.cordiact.core.workspace.WorkspaceManagerService
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val WORKSPACE = "ws-1"
private const val OTHER_WORKSPACE = "ws-2"
private const val PROFILE = "reviewer"

class AgentProfilePluginTest {

    @Test
    fun resolvesInstructionsInDeclarationOrder() {
        val plugin = plugin(
            profiles = listOf(profile(instructions = listOf(
                InstructionSource.Inline("first"),
                InstructionSource.WorkspaceFile("prompts/second.md"),
            ))),
            files = mapOf("prompts/second.md" to "second"),
        )

        val resolved = plugin.resolve(WORKSPACE, AgentProfileId(PROFILE))

        assertEquals(listOf("first", "second"), resolved.instructions)
    }

    @Test
    fun toolRefsIntersectAndNeverGrant() {
        val plugin = plugin(
            profiles = listOf(profile(toolRefs = setOf("file.read", "shell.exec"))),
            registered = setOf("file.read", "shell.exec"),
            enabled = setOf("file.read"),
        )

        val resolved = plugin.resolve(WORKSPACE, AgentProfileId(PROFILE))

        // The profile asked for shell.exec; it is registered but not enabled, so it stays unavailable.
        assertEquals(setOf("file.read"), resolved.enabledTools)
        assertEquals(setOf("shell.exec"), resolved.unavailableTools)
    }

    @Test
    fun danglingToolReferenceFailsClosed() {
        val plugin = plugin(
            profiles = listOf(profile(toolRefs = setOf("does.not.exist"))),
            registered = setOf("file.read"),
            enabled = setOf("file.read"),
        )

        val error = assertFailsWith<AgentProfileException> {
            plugin.resolve(WORKSPACE, AgentProfileId(PROFILE))
        }
        assertEquals(AgentProfileException.Code.TOOL_NOT_REGISTERED, error.code)
    }

    @Test
    fun missingCapabilityRejectsTheProfile() {
        val plugin = plugin(
            profiles = listOf(profile(requiredCapabilities = setOf("core.shell"))),
            granted = setOf("core.files"),
        )

        val error = assertFailsWith<AgentProfileException> {
            plugin.resolve(WORKSPACE, AgentProfileId(PROFILE))
        }
        assertEquals(AgentProfileException.Code.CAPABILITY_DENIED, error.code)
    }

    @Test
    fun unregisteredModelRejectsTheProfile() {
        val plugin = plugin(
            profiles = listOf(profile(modelRef = "gone-model")),
            models = setOf("deepseek-flash"),
        )

        val error = assertFailsWith<AgentProfileException> {
            plugin.resolve(WORKSPACE, AgentProfileId(PROFILE))
        }
        assertEquals(AgentProfileException.Code.MODEL_NOT_REGISTERED, error.code)
    }

    @Test
    fun instructionPathTraversalIsRejected() {
        for (path in listOf("../outside.md", "prompts/../../outside.md", "/etc/passwd")) {
            val plugin = plugin(
                profiles = listOf(profile(instructions = listOf(InstructionSource.WorkspaceFile(path)))),
            )
            val error = assertFailsWith<AgentProfileException>("expected rejection for $path") {
                plugin.resolve(WORKSPACE, AgentProfileId(PROFILE))
            }
            assertEquals(AgentProfileException.Code.INSTRUCTION_PATH_OUTSIDE_WORKSPACE, error.code)
        }
    }

    @Test
    fun hiddenInstructionPathsAreRejected() {
        // The Workspace root is also a git repository, so .git must never be readable as instructions.
        for (path in listOf(".git/config", "prompts/.env", ".gitignore")) {
            val plugin = plugin(
                profiles = listOf(profile(instructions = listOf(InstructionSource.WorkspaceFile(path)))),
            )
            val error = assertFailsWith<AgentProfileException>("expected rejection for $path") {
                plugin.resolve(WORKSPACE, AgentProfileId(PROFILE))
            }
            assertEquals(AgentProfileException.Code.INSTRUCTION_PATH_OUTSIDE_WORKSPACE, error.code)
        }
    }

    @Test
    fun unreadableInstructionFileFailsClosed() {
        val plugin = plugin(
            profiles = listOf(profile(instructions = listOf(InstructionSource.WorkspaceFile("missing.md")))),
            files = emptyMap(),
        )

        val error = assertFailsWith<AgentProfileException> {
            plugin.resolve(WORKSPACE, AgentProfileId(PROFILE))
        }
        assertEquals(AgentProfileException.Code.INSTRUCTION_FILE_UNREADABLE, error.code)
    }

    @Test
    fun profilesAreWorkspaceScoped() {
        val store = InMemoryStore()
        val plugin = plugin(profiles = emptyList(), store = store)
        plugin.save(profile())

        assertEquals(1, plugin.list(WORKSPACE).size)
        assertEquals(0, plugin.list(OTHER_WORKSPACE).size)
        assertNull(plugin.get(OTHER_WORKSPACE, AgentProfileId(PROFILE)))
        assertEquals(
            AgentProfileException.Code.PROFILE_NOT_FOUND,
            assertFailsWith<AgentProfileException> {
                plugin.resolve(OTHER_WORKSPACE, AgentProfileId(PROFILE))
            }.code,
        )
    }

    @Test
    fun duplicateVersionIsRejected() {
        val plugin = plugin(profiles = listOf(profile()))
        val error = assertFailsWith<AgentProfileException> { plugin.save(profile()) }
        assertEquals(AgentProfileException.Code.DUPLICATE_VERSION, error.code)
    }

    @Test
    fun newerVersionSupersedesOlderButOlderStaysResolvable() {
        val plugin = plugin(profiles = listOf(profile(version = 1)))
        plugin.save(profile(version = 2, displayName = "Reviewer v2"))

        assertEquals("Reviewer v2", plugin.get(WORKSPACE, AgentProfileId(PROFILE))?.displayName)
        assertEquals("Reviewer", plugin.get(WORKSPACE, AgentProfileId(PROFILE), version = 1)?.displayName)
    }

    @Test
    fun higherOriginReplacesWholesaleRatherThanMerging() {
        val bundled = profile(
            displayName = "Bundled",
            instructions = listOf(InstructionSource.Inline("bundled prompt")),
            origin = ProfileOrigin.BUNDLED,
        )
        val override = profile(
            displayName = "Override",
            instructions = listOf(InstructionSource.Inline("override prompt")),
            origin = ProfileOrigin.SESSION_OVERRIDE,
        )
        val plugin = plugin(profiles = emptyList())

        val winner = plugin.selectByPrecedence(listOf(bundled, override))

        assertEquals("Override", winner?.displayName)
        // Whole-record replacement: the bundled instruction is gone, not concatenated.
        assertEquals(listOf(InstructionSource.Inline("override prompt")), winner?.instructions)
    }

    @Test
    fun generationDefaultsAreCarriedThrough() {
        val generation = GenerationParameters(temperature = 0.2, maxOutputTokens = 512)
        val plugin = plugin(profiles = listOf(profile(generation = generation)))

        val resolved = plugin.resolve(WORKSPACE, AgentProfileId(PROFILE))

        assertEquals(generation, resolved.generation)
        // Per-turn overrides still win field by field; the profile only supplies defaults.
        assertEquals(
            GenerationParameters(temperature = 0.9, maxOutputTokens = 512),
            resolved.generation?.mergedWith(GenerationParameters(temperature = 0.9)),
        )
    }

    @Test
    fun archivedWorkspaceIsRejected() {
        val plugin = plugin(profiles = listOf(profile()), archived = true)
        val error = assertFailsWith<AgentProfileException> {
            plugin.resolve(WORKSPACE, AgentProfileId(PROFILE))
        }
        assertEquals(AgentProfileException.Code.WORKSPACE_ARCHIVED, error.code)
    }

    @Test
    fun blankProfileIdIsRejected() {
        assertFailsWith<IllegalArgumentException> { AgentProfileId(" ") }
    }

    // ---- helpers ----

    private fun profile(
        version: Int = 1,
        displayName: String = "Reviewer",
        instructions: List<InstructionSource> = emptyList(),
        modelRef: String? = null,
        generation: GenerationParameters? = null,
        toolRefs: Set<String> = emptySet(),
        requiredCapabilities: Set<String> = emptySet(),
        origin: ProfileOrigin = ProfileOrigin.WORKSPACE,
        workspaceId: String = WORKSPACE,
    ) = AgentProfile(
        id = AgentProfileId(PROFILE),
        workspaceId = workspaceId,
        version = version,
        displayName = displayName,
        instructions = instructions,
        modelRef = modelRef,
        generation = generation,
        toolRefs = toolRefs,
        requiredCapabilities = requiredCapabilities,
        origin = origin,
    )

    private fun plugin(
        profiles: List<AgentProfile>,
        store: InMemoryStore = InMemoryStore().apply { profiles.forEach(::insert) },
        files: Map<String, String> = emptyMap(),
        registered: Set<String> = emptySet(),
        enabled: Set<String> = emptySet(),
        models: Set<String> = emptySet(),
        granted: Set<String> = emptySet(),
        archived: Boolean = false,
    ): AgentProfilePlugin = AgentProfilePlugin(
        workspaceManager = FakeWorkspaces(archived),
        store = store,
        tools = object : ToolVisibility {
            override fun enabledToolIds(workspaceId: String) = enabled
            override fun registeredToolIds(workspaceId: String) = registered
        },
        models = ModelVisibility { it in models },
        instructions = InstructionFileReader { _, path, _ ->
            files[path] ?: throw IllegalStateException("missing file $path")
        },
        grantedCapabilities = granted,
    ).also { it.onEnable() }
}

private class InMemoryStore : AgentProfileStore {
    private val rows = mutableMapOf<Triple<String, String, Int>, AgentProfile>()

    override fun list(workspaceId: String) =
        rows.values.filter { it.workspaceId == workspaceId }

    override fun get(workspaceId: String, id: AgentProfileId, version: Int) =
        rows[Triple(workspaceId, id.value, version)]

    override fun latest(workspaceId: String, id: AgentProfileId) =
        rows.values.filter { it.workspaceId == workspaceId && it.id == id }.maxByOrNull { it.version }

    override fun insert(profile: AgentProfile): AgentProfile {
        rows[Triple(profile.workspaceId, profile.id.value, profile.version)] = profile
        return profile
    }

    override fun exists(workspaceId: String, id: AgentProfileId, version: Int) =
        rows.containsKey(Triple(workspaceId, id.value, version))
}

private class FakeWorkspaces(private val archived: Boolean) : WorkspaceManagerService {
    private fun workspace(id: String) = Workspace(
        id = id,
        name = id,
        createdAtMillis = 0,
        updatedAtMillis = 0,
        archivedAtMillis = if (archived) 1 else null,
    )

    override fun initializeDefaultIfEligible() = workspace(WORKSPACE)
    override fun create(name: String) = workspace(WORKSPACE)
    override fun get(workspaceId: String) = if (workspaceId == WORKSPACE || workspaceId == OTHER_WORKSPACE) workspace(workspaceId) else null
    override fun list(includeArchived: Boolean) = listOf(workspace(WORKSPACE))
    override fun rename(workspaceId: String, name: String) = workspace(workspaceId)
    override fun archive(workspaceId: String) = workspace(workspaceId)
    override fun restore(workspaceId: String) = workspace(workspaceId)
    override fun select(workspaceId: String) = workspace(workspaceId)
    override fun activeWorkspace() = workspace(WORKSPACE)
    override fun resolveRoot(workspaceId: String): File = File(System.getProperty("java.io.tmpdir"), workspaceId)
    override fun resolveSessionWorkspace(workspaceId: String) = workspace(workspaceId)
    override fun findSessionWorkspace(workspaceId: String) =
        if (workspaceId == WORKSPACE || workspaceId == OTHER_WORKSPACE) workspace(workspaceId) else null
}
