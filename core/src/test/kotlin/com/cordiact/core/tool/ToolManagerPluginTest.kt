package com.cordiact.core.tool

import com.cordiact.core.plugin.PluginState
import com.cordiact.core.security.EffectiveSecurityProfile
import com.cordiact.core.security.EnvelopeToolAccessPolicy
import com.cordiact.core.security.Principal
import com.cordiact.core.security.SecurityContext
import com.cordiact.core.security.ToolEffectClass
import com.cordiact.core.workspace.Workspace
import com.cordiact.core.workspace.WorkspaceManagerService
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

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
    fun liveWorkspacePolicyHidesDiscoveryAndRejectsStaleInvocations() {
        val workspaces = FakeWorkspaceManager()
        val preferences = FakePreferences().apply { setEnabled(WORKSPACE_A, TOOL_ID, true); setEnabled(WORKSPACE_B, TOOL_ID, true) }
        var invocations = 0
        val registry = InMemoryToolDefinitionRegistry().apply {
            register(ToolDefinition(descriptor(), ToolExecutor { _, _ -> invocations++; ToolResult() }))
        }
        val plugin = manager(registry, preferences, workspaces) { PluginState.ENABLED }
        plugin.onEnable()
        var enabled = true
        plugin.setWorkspaceVisibilityPolicy { workspaceId, _ -> workspaceId != WORKSPACE_A || enabled }
        assertTrue(plugin.resolveEnabled(WORKSPACE_A).isNotEmpty())
        plugin.invoke(context(WORKSPACE_A), TOOL_ID, validArgs())
        enabled = false
        assertTrue(plugin.resolveEnabled(WORKSPACE_A).isEmpty())
        assertFalse(plugin.list(WORKSPACE_A).single().dispatchable)
        assertTrue(plugin.resolveEnabled(WORKSPACE_B).isNotEmpty())
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

    @Test
    fun arrayParameterValidationRejectsWrongShapes() {
        val workspaces = FakeWorkspaceManager()
        val preferences = FakePreferences().apply { setEnabled(WORKSPACE_A, TOOL_ID, true) }
        var invocations = 0
        val registry = InMemoryToolDefinitionRegistry().apply {
            register(
                ToolDefinition(
                    descriptor().copy(
                        inputSchema = ToolInputSchema(
                            listOf(
                                ToolParameter(
                                    name = "tags",
                                    type = ToolValueType.ARRAY,
                                    required = true,
                                    itemType = ToolValueType.STRING,
                                    maxItems = 2,
                                ),
                            ),
                        ),
                    ),
                    ToolExecutor { _, _ -> invocations++; ToolResult() },
                ),
            )
        }
        val plugin = manager(registry, preferences, workspaces) { PluginState.ENABLED }
        plugin.onEnable()

        assertFailsWith<ToolManagerException> {
            plugin.invoke(
                context(WORKSPACE_A),
                TOOL_ID,
                ToolArguments(mapOf("tags" to ToolValue.ArrayValue(listOf(ToolValue.NumberValue(1.0))))),
            )
        }
        assertFailsWith<ToolManagerException> {
            plugin.invoke(
                context(WORKSPACE_A),
                TOOL_ID,
                ToolArguments(
                    mapOf(
                        "tags" to ToolValue.ArrayValue(
                            listOf(ToolValue.StringValue("a"), ToolValue.StringValue("b"), ToolValue.StringValue("c")),
                        ),
                    ),
                ),
            )
        }
        plugin.invoke(
            context(WORKSPACE_A),
            TOOL_ID,
            ToolArguments(mapOf("tags" to ToolValue.ArrayValue(listOf(ToolValue.StringValue("a"))))),
        )
        assertEquals(1, invocations)
    }

    @Test
    fun timeoutIsEnforcedForTheDeclaredTool() {
        val workspaces = FakeWorkspaceManager()
        val preferences = FakePreferences().apply { setEnabled(WORKSPACE_A, TOOL_ID, true) }
        val registry = InMemoryToolDefinitionRegistry().apply {
            register(
                ToolDefinition(
                    descriptor().copy(timeoutMs = 50),
                    ToolExecutor { _, _ ->
                        Thread.sleep(1_000)
                        ToolResult()
                    },
                ),
            )
        }
        val plugin = manager(registry, preferences, workspaces) { PluginState.ENABLED }
        plugin.onEnable()

        val failure = assertFailsWith<ToolManagerException> {
            plugin.invoke(context(WORKSPACE_A), TOOL_ID, validArgs())
        }
        assertEquals(ToolManagerException.Code.TOOL_TIMEOUT, failure.code)
    }

    @Test
    fun aSlowConcurrentToolDoesNotBlockAnotherTool() {
        val workspaces = FakeWorkspaceManager()
        val preferences = FakePreferences().apply { setEnabled(WORKSPACE_A, TOOL_ID, true) }
        preferences.setEnabled(WORKSPACE_A, "files.quick", true)
        val started = java.util.concurrent.CountDownLatch(1)
        val registry = InMemoryToolDefinitionRegistry().apply {
            register(
                ToolDefinition(
                    descriptor().copy(concurrencySafe = true, timeoutMs = 5_000),
                    ToolExecutor { _, _ ->
                        started.countDown()
                        Thread.sleep(700)
                        ToolResult()
                    },
                ),
            )
            register(
                ToolDefinition(
                    descriptor().copy(id = "files.quick", name = "Quick"),
                    ToolExecutor { _, _ -> ToolResult() },
                ),
            )
        }
        val plugin = manager(registry, preferences, workspaces) { PluginState.ENABLED }
        plugin.onEnable()

        val slow = Thread {
            plugin.invoke(context(WORKSPACE_A), TOOL_ID, validArgs())
        }.apply { start() }
        started.await(2, java.util.concurrent.TimeUnit.SECONDS)
        val begin = System.nanoTime()
        plugin.invoke(context(WORKSPACE_A), "files.quick", validArgs())
        val elapsedMs = (System.nanoTime() - begin) / 1_000_000
        assertTrue(elapsedMs < 400, "quick tool was blocked for ${elapsedMs}ms")
        slow.join(2_000)
    }

    @Test
    fun visibilityGateFiltersOnlyWhenAnActivationIsSupplied() {
        val workspaces = FakeWorkspaceManager()
        val preferences = FakePreferences().apply { setEnabled(WORKSPACE_A, TOOL_ID, true) }
        val registry = InMemoryToolDefinitionRegistry().apply {
            register(ToolDefinition(descriptor(), ToolExecutor { _, _ -> ToolResult() }))
            registerVisibility(TOOL_ID) { false }
        }
        val plugin = manager(registry, preferences, workspaces) { PluginState.ENABLED }
        plugin.onEnable()

        assertEquals(1, plugin.resolveEnabled(WORKSPACE_A).size)
        assertEquals(0, plugin.resolveEnabled(WORKSPACE_A, ToolActivationContext(WORKSPACE_A)).size)
    }

    @Test
    fun aReplacedCatalogCandidateDoesNotCallTheReplacementGateOrAppearInTheOldTurn() {
        val inner = InMemoryToolDefinitionRegistry()
        val definition = ToolDefinition(descriptor(), ToolExecutor { _, _ -> ToolResult() })
        val old = inner.publishGlobalBatch(listOf(GlobalToolPublication(definition) { error("Retired gate ran") }))
        var replace = true
        var gateCalls = 0
        val registry = object : ToolDefinitionRegistry by inner {
            override fun all(): List<ToolDefinition> = inner.all().also {
                if (replace) {
                    replace = false
                    old.close()
                    inner.publishGlobalBatch(listOf(GlobalToolPublication(definition) { gateCalls++; true }))
                }
            }
        }
        val preferences = FakePreferences().apply { setEnabled(WORKSPACE_A, TOOL_ID, true) }
        val plugin = manager(registry, preferences, FakeWorkspaceManager()) { PluginState.ENABLED }
        plugin.onEnable()
        val activation = ToolActivationContext(WORKSPACE_A)
        assertTrue(plugin.resolveForTurn(activation).isEmpty())
        assertEquals(0, gateCalls)
        assertEquals(TOOL_ID, plugin.resolveForTurn(activation).single().descriptor.id)
        assertEquals(1, gateCalls)
    }

    @Test
    fun visibilityDecisionIsDiscardedIfItsGateChangesWhileRunningOutsideRegistryLock() {
        val registry = InMemoryToolDefinitionRegistry()
        val definition = ToolDefinition(descriptor(), ToolExecutor { _, _ -> ToolResult() })
        registry.register(definition)
        val candidate = assertNotNull(registry.get(TOOL_ID))
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val workers = Executors.newFixedThreadPool(2)
        try {
            registry.registerVisibility(TOOL_ID) {
                entered.countDown()
                check(release.await(5, TimeUnit.SECONDS))
                true
            }
            val activation = ToolActivationContext(WORKSPACE_A)
            val previous = workers.submit<Boolean> { registry.isVisible(candidate, activation) }
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            workers.submit { registry.registerVisibility(TOOL_ID) { true } }.get(2, TimeUnit.SECONDS)
            release.countDown()
            assertFalse(previous.get(2, TimeUnit.SECONDS))
            assertTrue(registry.isVisible(candidate, activation))
        } finally {
            release.countDown()
            workers.shutdownNow()
        }
    }

    @Test
    fun ownerAwareConsentIsCheckedAtDiscoveryAndAgainBeforeExecution() {
        val registry = InMemoryToolDefinitionRegistry()
        var calls = 0
        val owned = descriptor().copy(requiredCapabilities = setOf("test.capability"))
        registry.register(ToolDefinition(owned, ToolExecutor { _, _ -> calls++; ToolResult() }))
        val foreign = owned.copy(id = "foreign.tool", ownerPluginId = "foreign.plugin")
        registry.register(ToolDefinition(foreign, ToolExecutor { _, _ -> calls++; ToolResult() }))
        val preferences = FakePreferences().apply {
            listOf(WORKSPACE_A, WORKSPACE_B).forEach { workspace ->
                setEnabled(workspace, TOOL_ID, true)
                setEnabled(workspace, foreign.id, true)
            }
        }
        var consent = false
        val gate = object : ToolCapabilityGate {
            override fun grantsAll(workspaceId: String, capabilityIds: Set<String>) = true
            override fun grantsTool(workspaceId: String, descriptor: ToolDescriptor) =
                consent && workspaceId == WORKSPACE_A && descriptor.ownerPluginId == OWNER_ID
        }
        val plugin = ToolManagerPlugin(registry, preferences, FakeWorkspaceManager(),
            ToolPluginStateLookup { PluginState.ENABLED }, capabilities = gate)
        plugin.onEnable()
        assertTrue(plugin.resolveEnabled(WORKSPACE_A).isEmpty())
        consent = true
        assertEquals(listOf(TOOL_ID), plugin.resolveEnabled(WORKSPACE_A).map { it.id })
        assertTrue(plugin.resolveEnabled(WORKSPACE_B).isEmpty())
        plugin.invoke(context(WORKSPACE_A), TOOL_ID, validArgs())
        consent = false
        assertTrue(plugin.resolveEnabled(WORKSPACE_A).isEmpty())
        val failure = assertFailsWith<ToolManagerException> {
            plugin.invoke(context(WORKSPACE_A), TOOL_ID, validArgs())
        }
        assertEquals(ToolManagerException.Code.CAPABILITY_DENIED, failure.code)
        assertEquals(1, calls)
    }

    private fun manager(
        registry: ToolDefinitionRegistry,
        preferences: MutableToolPreferenceStore,
        workspaces: WorkspaceManagerService,
        states: ToolPluginStateLookup,
    ) = ToolManagerPlugin(registry, preferences, workspaces, states)

    @Test
    fun classifiedPluginAndMcpToolsReachExecutorOutsideReadOnly() {
        val preferences = FakePreferences().apply {
            setEnabled(WORKSPACE_A, "plugin_manager", true)
            setEnabled(WORKSPACE_A, "core.mcp.demo.echo", true)
        }
        var calls = 0
        fun tool(id: String, owner: String) = ToolDefinition(
            ToolDescriptor(
                id = id,
                name = id,
                description = id,
                inputSchema = ToolInputSchema(),
                ownerPluginId = owner,
                defaultEnabled = true,
            ),
            ToolExecutor { _, _ ->
                calls++
                ToolResult(mapOf("ok" to ToolValue.BooleanValue(true)))
            },
        )
        val registry = InMemoryToolDefinitionRegistry().apply {
            register(tool("plugin_manager", "core.plugin-manager"))
            register(tool("core.mcp.demo.echo", "core.mcp"))
        }
        val plugin = ToolManagerPlugin(
            registry,
            preferences,
            FakeWorkspaceManager(),
            ToolPluginStateLookup { PluginState.ENABLED },
            accessPolicy = EnvelopeToolAccessPolicy { id, _ ->
                if (id == "plugin_manager") ToolEffectClass.DESTRUCTIVE else ToolEffectClass.NETWORK
            },
        )
        plugin.onEnable()
        val full = ToolInvocationContext(
            WORKSPACE_A,
            securityContext = securityContext(EffectiveSecurityProfile.fullAccess(WORKSPACE_A)),
        )
        assertEquals(1, plugin.invoke(full, "plugin_manager", ToolArguments()).values.size)
        assertEquals(1, plugin.invoke(full, "core.mcp.demo.echo", ToolArguments()).values.size)
        assertEquals(2, calls)

        val readOnly = ToolInvocationContext(
            WORKSPACE_A,
            securityContext = securityContext(EffectiveSecurityProfile.readOnly(WORKSPACE_A)),
        )
        assertEquals(
            ToolManagerException.Code.APPROVAL_DENIED,
            assertFailsWith<ToolManagerException> { plugin.invoke(readOnly, "plugin_manager", ToolArguments()) }.code,
        )
        assertEquals(
            ToolManagerException.Code.APPROVAL_DENIED,
            assertFailsWith<ToolManagerException> { plugin.invoke(readOnly, "core.mcp.demo.echo", ToolArguments()) }.code,
        )
        assertEquals(2, calls)
    }

    @Test
    fun declarativeEffectIsBoundToTheHostExecutorAndDoesNotAuthorizeItsReplacement() {
        val registry = InMemoryToolDefinitionRegistry()
        val manager = ToolManagerPlugin(
            registry, FakePreferences(), FakeWorkspaceManager(), ToolPluginStateLookup { PluginState.ENABLED },
            accessPolicy = EnvelopeToolAccessPolicy { _, _ -> ToolEffectClass.UNKNOWN },
            sessionWorkspace = { if (it == "session-1") WORKSPACE_A else null },
        ).also { it.onEnable() }
        val manifest = com.cordiact.core.plugin.ExtensionPackageSource.compile("""
            {"schemaVersion":1,"id":"notes","version":"1.0.0","title":"Notes","description":"Notes",
             "host":{"api":1,"minVersion":"0.1.0"},"capabilities":["tool.publish"],
             "tools":[{"name":"show","description":"Show note","kind":"static_text","text":"literal"}],"commands":[]}
        """.trimIndent().toByteArray())
        val kernel = com.cordiact.core.plugin.PluginKernel()
        val contributions = com.cordiact.core.plugin.PluginToolContributions(registry)
        val id = com.cordiact.core.plugin.PluginInstanceId("pkg.notes", WORKSPACE_A)
        val toolId = "extension.pkg.notes.show"
        kernel.register(id, object : com.cordiact.core.plugin.ContextPlugin {
            override fun start(context: com.cordiact.core.plugin.PluginContext) {
                contributions.registerDeclarativePackage(context, manifest)
            }
        })
        kernel.enable(id)
        val definition = manager.resolveForTurn(ToolActivationContext(WORKSPACE_A, sessionId = "session-1")).single()
        fun contextFor(definition: ToolDefinition) = ToolInvocationContext(
            WORKSPACE_A, sessionId = "session-1",
            securityContext = securityContext(EffectiveSecurityProfile.readOnly(WORKSPACE_A)),
            toolRegistrations = mapOf(toolId to requireNotNull(definition.registration)),
        )
        val old = contextFor(definition)
        assertEquals(ToolValue.StringValue("literal"), manager.invoke(old, toolId, ToolArguments()).values["text"])
        kernel.disable(id)
        kernel.unregister(id)
        var replacementCalls = 0
        kernel.register(id, object : com.cordiact.core.plugin.ContextPlugin {
            override fun start(context: com.cordiact.core.plugin.PluginContext) {
                contributions.register(context, definition.copy(executor = ToolExecutor { _, _ ->
                    replacementCalls++
                    ToolResult()
                }))
            }
        })
        kernel.enable(id)
        assertEquals(ToolManagerException.Code.TOOL_NOT_REGISTERED, assertFailsWith<ToolManagerException> {
            manager.invoke(old, toolId, ToolArguments())
        }.code)
        val replacement = manager.resolveForTurn(ToolActivationContext(WORKSPACE_A, sessionId = "session-1")).single()
        val refusal = assertFailsWith<ToolManagerException> {
            manager.invoke(contextFor(replacement), toolId, ToolArguments())
        }
        assertEquals(ToolManagerException.Code.APPROVAL_DENIED, refusal.code)
        assertEquals(0, replacementCalls)
    }

    private fun securityContext(profile: EffectiveSecurityProfile) = SecurityContext(
        workspaceId = WORKSPACE_A,
        sessionId = "session-1",
        runId = "run-1",
        turnId = "turn-1",
        toolCallId = "call-1",
        principalChain = listOf(Principal("main")),
        profile = profile,
    )

    @Test
    fun unregisterRetiresOneToolAndItsVisibilityGateOnly() {
        val registry = InMemoryToolDefinitionRegistry()
        registry.register(ToolDefinition(descriptor(), ToolExecutor { _, args -> ToolResult(mapOf("path" to args.values.getValue("path"))) }))
        val foreign = ToolDescriptor(
            id = "mcp.demo.echo",
            name = "echo",
            description = "Echo through a foreign server",
            inputSchema = ToolInputSchema(),
            ownerPluginId = "core.mcp",
            rawInputSchemaJson = """{"type":"object","properties":{"text":{"type":"string"}},"required":["text"]}""",
        )
        registry.register(ToolDefinition(foreign, ToolExecutor { _, _ -> ToolResult() }))
        registry.registerVisibility(TOOL_ID) { true }

        assertEquals(2, registry.all().size)
        assertTrue(registry.unregister("mcp.demo.echo"))
        assertNull(registry.get("mcp.demo.echo"))
        assertNotNull(registry.get(TOOL_ID))
        assertNotNull(registry.visibility(TOOL_ID))

        assertTrue(registry.unregister(TOOL_ID))
        assertNull(registry.visibility(TOOL_ID))
        // Repeating it is a no-op rather than an error: a bridge may retire a generation twice.
        assertFalse(registry.unregister(TOOL_ID))
        assertEquals(0, registry.all().size)
    }

    @Test
    fun globalBatchValidatesEveryEntryBeforePublishingAnyDefinitionsOrGates() {
        val registry = InMemoryToolDefinitionRegistry()
        val executor = ToolExecutor { _, _ -> ToolResult() }
        val first = GlobalToolPublication(ToolDefinition(descriptor(), executor)) { false }
        val invalid = GlobalToolPublication(ToolDefinition(descriptor().copy(id = "bad id"), executor))
        assertFailsWith<IllegalArgumentException> { registry.publishGlobalBatch(listOf(first, invalid)) }
        assertFailsWith<IllegalArgumentException> { registry.publishGlobalBatch(listOf(first, first)) }
        assertTrue(registry.all().isEmpty())
        assertNull(registry.visibility(TOOL_ID))
    }

    @Test
    fun globalBatchCannotOverlapAnExistingScopedContribution() {
        val registry = InMemoryToolDefinitionRegistry()
        val executor = ToolExecutor { _, _ -> ToolResult() }
        val scoped = ToolDefinition(descriptor().copy(id = "test.scoped"), executor)
        registry.publish(scoped, ToolContributionScope(workspaceId = WORKSPACE_A)) { true }.use {
            val original = assertNotNull(registry.getIn("test.scoped", WORKSPACE_A, null))
            val first = GlobalToolPublication(ToolDefinition(descriptor(), executor)) { false }
            assertFailsWith<IllegalArgumentException> {
                registry.publishGlobalBatch(listOf(first, GlobalToolPublication(scoped)))
            }
            assertNull(registry.get(TOOL_ID))
            assertNull(registry.visibility(TOOL_ID))
            assertNull(registry.get("test.scoped"))
            assertEquals(original, registry.getIn("test.scoped", WORKSPACE_A, null))
        }
    }

    @Test
    fun rawSchemaMustLookLikeAJsonObject() {
        val registry = InMemoryToolDefinitionRegistry()
        val arrayRoot = ToolDescriptor(
            id = "mcp.demo.bad",
            name = "bad",
            description = "Array-root schema",
            inputSchema = ToolInputSchema(),
            ownerPluginId = "core.mcp",
            rawInputSchemaJson = """[{"type":"object"}]""",
        )
        assertFailsWith<IllegalArgumentException> { registry.register(ToolDefinition(arrayRoot, ToolExecutor { _, _ -> ToolResult() })) }

        val oversized = arrayRoot.copy(rawInputSchemaJson = "{" + " ".repeat(40_000) + "}")
        assertFailsWith<IllegalArgumentException> { registry.register(ToolDefinition(oversized, ToolExecutor { _, _ -> ToolResult() })) }
    }

    @Test
    fun scopedToolsUseExactLiveSessionAndRejectAnOldCatalogAfterRestart() {
        val registry = InMemoryToolDefinitionRegistry()
        val contributions = com.cordiact.core.plugin.PluginToolContributions(registry)
        val kernel = com.cordiact.core.plugin.PluginKernel()
        val workspace = com.cordiact.core.plugin.PluginInstanceId(OWNER_ID, WORKSPACE_A)
        val first = com.cordiact.core.plugin.PluginInstanceId(OWNER_ID, WORKSPACE_A, "first")
        val other = com.cordiact.core.plugin.PluginInstanceId(OWNER_ID, WORKSPACE_B, "other")
        val owners = mutableMapOf("first" to WORKSPACE_A, "sibling" to WORKSPACE_A, "other" to WORKSPACE_B)
        val plugin = ToolManagerPlugin(registry, FakePreferences(), FakeWorkspaceManager(),
            ToolPluginStateLookup { PluginState.ENABLED }, sessionWorkspace = owners::get)
        plugin.onEnable()
        val sharedId = "extension.$OWNER_ID.read"
        val wideId = "extension.$OWNER_ID.catalog"
        var calls = 0
        fun contributor(id: String, result: String) = object : com.cordiact.core.plugin.ContextPlugin {
            override fun start(context: com.cordiact.core.plugin.PluginContext) {
                val definition = ToolDefinition(descriptor().copy(id = id, defaultEnabled = true), ToolExecutor { _, _ ->
                    calls++
                    ToolResult(mapOf("source" to ToolValue.StringValue(result)))
                })
                contributions.register(context, definition)
                assertNull(registry.getIn(id, context.id.workspaceId!!, context.id.sessionId))
            }
        }
        kernel.register(first, contributor(sharedId, "first"))
        kernel.register(other, contributor(sharedId, "other"))
        kernel.register(workspace, contributor(wideId, "workspace"))
        kernel.enable(first); kernel.enable(other); kernel.enable(workspace)

        fun catalog(workspaceId: String, sessionId: String?) = plugin.resolveForTurn(ToolActivationContext(workspaceId, sessionId = sessionId))
        fun caller(workspaceId: String, sessionId: String) = ToolInvocationContext(workspaceId, sessionId = sessionId,
            toolRegistrations = catalog(workspaceId, sessionId).associate { it.descriptor.id to requireNotNull(it.registration) })
        val old = caller(WORKSPACE_A, "first")
        val b = caller(WORKSPACE_B, "other")
        fun run(context: ToolInvocationContext, id: String) = plugin.invoke(context, id, validArgs()).values["source"]
        assertEquals(setOf(sharedId, wideId), old.toolRegistrations.keys)
        assertEquals(listOf(wideId), catalog(WORKSPACE_A, "sibling").map { it.descriptor.id })
        assertTrue(catalog(WORKSPACE_A, "other").isEmpty())
        assertTrue(catalog(WORKSPACE_A, null).isEmpty())
        assertTrue(plugin.list(WORKSPACE_A).isEmpty())
        assertTrue(registry.all().isEmpty())
        assertFailsWith<ToolManagerException> { plugin.setEnabled(WORKSPACE_A, wideId, true) }
        assertFailsWith<ToolManagerException> { run(old.copy(toolRegistrations = emptyMap()), sharedId) }
        assertFailsWith<ToolManagerException> { run(old.copy(sessionId = "sibling"), sharedId) }
        assertFailsWith<ToolManagerException> { run(old.copy(workspaceId = WORKSPACE_B), sharedId) }
        assertFailsWith<ToolManagerException> { run(b.copy(grantedScopes = listOf(GrantedScope(WORKSPACE_A))), wideId) }
        assertEquals(0, calls)
        assertEquals(ToolValue.StringValue("first"), run(old, sharedId))
        assertEquals(ToolValue.StringValue("other"), run(b, sharedId))
        assertEquals(ToolValue.StringValue("workspace"), run(caller(WORKSPACE_A, "sibling"), wideId))
        kernel.restart(first)
        assertFailsWith<ToolManagerException> { run(old, sharedId) }
        assertEquals(3, calls)
        assertEquals(ToolValue.StringValue("first"), run(caller(WORKSPACE_A, "first"), sharedId))
        kernel.disable(first)
        assertEquals(ToolValue.StringValue("other"), run(b, sharedId))
        owners.remove("other")
        assertFailsWith<ToolManagerException> { run(b, sharedId) }
        assertEquals(5, calls)
    }

    @Test
    fun withdrawalHidesContributionBeforeDisposalAndSuppressesAnInFlightResult() {
        val registry = InMemoryToolDefinitionRegistry()
        val kernel = com.cordiact.core.plugin.PluginKernel()
        val contributions = com.cordiact.core.plugin.PluginToolContributions(registry)
        val instance = com.cordiact.core.plugin.PluginInstanceId(OWNER_ID, WORKSPACE_A, "first")
        val id = "extension.$OWNER_ID.blocking"
        val entered = java.util.concurrent.CountDownLatch(1)
        val finish = java.util.concurrent.CountDownLatch(1)
        val disposing = java.util.concurrent.CountDownLatch(1)
        val finishDisposal = java.util.concurrent.CountDownLatch(1)
        val pool = java.util.concurrent.Executors.newFixedThreadPool(2)
        val unit = java.util.concurrent.TimeUnit.SECONDS
        val plugin = ToolManagerPlugin(registry, FakePreferences(), FakeWorkspaceManager(), { PluginState.ENABLED },
            sessionWorkspace = { WORKSPACE_A }).also { it.onEnable() }
        kernel.register(instance, object : com.cordiact.core.plugin.ContextPlugin {
            override fun start(context: com.cordiact.core.plugin.PluginContext) {
                contributions.register(context, ToolDefinition(descriptor().copy(id = id, defaultEnabled = true)) { _, _ ->
                    entered.countDown()
                    check(finish.await(5, unit))
                    ToolResult(mapOf("old" to ToolValue.StringValue("stale")))
                })
                // Reverse cleanup order: registry withdrawal runs after this blocking disposer.
                context.effect { disposing.countDown(); check(finishDisposal.await(5, unit)) }
            }
        })
        kernel.enable(instance)
        val activation = ToolActivationContext(WORKSPACE_A, sessionId = "first")
        val token = requireNotNull(plugin.resolveForTurn(activation).single().registration)
        val caller = ToolInvocationContext(WORKSPACE_A, sessionId = "first", toolRegistrations = mapOf(id to token))
        try {
            val invocation = pool.submit<ToolManagerException.Code> {
                assertFailsWith<ToolManagerException> { plugin.invoke(caller, id, validArgs()) }.code
            }
            assertTrue(entered.await(5, unit))
            val disable = pool.submit { kernel.disable(instance) }
            assertTrue(disposing.await(5, unit))
            assertTrue(plugin.resolveForTurn(activation).isEmpty())
            val refused = assertFailsWith<ToolManagerException> { plugin.invoke(caller, id, validArgs()) }
            assertEquals(ToolManagerException.Code.TOOL_NOT_REGISTERED, refused.code)
            finish.countDown()
            assertEquals(ToolManagerException.Code.APPROVAL_DENIED, invocation.get(5, unit))
            finishDisposal.countDown()
            disable.get(5, unit)
        } finally {
            finish.countDown()
            finishDisposal.countDown()
            pool.shutdownNow()
        }
    }

    @Test
    fun scopedPublicationRejectsOverlapsAndFailedStartLeavesNoDefinition() {
        val registry = InMemoryToolDefinitionRegistry()
        val kernel = com.cordiact.core.plugin.PluginKernel()
        val contributions = com.cordiact.core.plugin.PluginToolContributions(registry)
        val id = "extension.$OWNER_ID.example"
        val definition = ToolDefinition(descriptor().copy(id = id), ToolExecutor { _, _ -> ToolResult() })
        fun plugin(fail: Boolean = false) = object : com.cordiact.core.plugin.ContextPlugin {
            override fun start(context: com.cordiact.core.plugin.PluginContext) {
                contributions.register(context, definition)
                check(!fail) { "startup failed" }
            }
        }
        val workspace = com.cordiact.core.plugin.PluginInstanceId(OWNER_ID, WORKSPACE_A)
        val session = com.cordiact.core.plugin.PluginInstanceId(OWNER_ID, WORKSPACE_A, "first")
        kernel.register(workspace, plugin(true))
        kernel.enable(workspace)
        assertNull(registry.getIn(id, WORKSPACE_A, "first"))
        kernel.register(session, plugin())
        kernel.enable(session)
        assertNotNull(registry.getIn(id, WORKSPACE_A, "first"))
        assertFailsWith<IllegalArgumentException> { registry.register(definition) }
        kernel.unregister(workspace)
        kernel.register(workspace, plugin())
        kernel.enable(workspace)
        assertEquals(com.cordiact.core.plugin.PluginInstanceState.FAILED, kernel.status(workspace).state)
        assertNotNull(registry.getIn(id, WORKSPACE_A, "first"))
        kernel.disable(session)
        assertNull(registry.getIn(id, WORKSPACE_A, "first"))
        registry.register(definition)
        assertNotNull(registry.get(id))
    }

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
