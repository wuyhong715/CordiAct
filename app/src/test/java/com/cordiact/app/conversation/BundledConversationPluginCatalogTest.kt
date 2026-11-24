package com.cordiact.app.conversation

import com.cordiact.core.skill.skillPluginSpec
import android.content.Context
import com.cordiact.app.plugin.AndroidPluginLifecycleStore
import com.cordiact.app.plugin.AndroidPluginResourceLedger
import com.cordiact.app.plugin.AndroidPluginRuntimeResources
import com.cordiact.app.plugin.AndroidPluginStorage
import com.cordiact.core.conversation.modelAdapterPluginSpec
import com.cordiact.model.providers.modelProviderRegistryPluginSpec
import com.cordiact.app.model.AndroidModelProviderConfigStore
import com.cordiact.model.providers.ModelProviderPresets
import com.cordiact.core.conversation.sessionManagerPluginSpec
import com.cordiact.core.workspace.workspaceManagerPluginSpec
import com.cordiact.core.sandbox.sandboxManagerPluginSpec
import com.cordiact.core.plugin.BundledPluginVerifier
import com.cordiact.core.plugin.PluginLifecycleException
import com.cordiact.core.plugin.PluginLifecycleManager
import com.cordiact.core.plugin.PluginOrigin
import com.cordiact.core.plugin.PluginOwnedData
import com.cordiact.core.plugin.PluginState
import com.cordiact.core.plugin.ExtensionPackageChange
import com.cordiact.core.plugin.ExtensionPackageInstaller
import com.cordiact.core.plugin.WorkspaceExtensionPackages
import com.cordiact.core.tool.ToolPluginStateLookup
import com.cordiact.core.tool.ToolActivationContext
import com.cordiact.core.tool.ToolInvocationContext
import com.cordiact.core.tool.ToolArguments
import com.cordiact.core.tool.ToolManagerException
import com.cordiact.core.tool.ToolValue
import com.cordiact.core.security.EffectiveSecurityProfile
import com.cordiact.core.security.Principal
import com.cordiact.core.security.SecurityContext
import com.cordiact.interaction.InteractionContext
import com.cordiact.interaction.InteractionModelSubmitter
import com.cordiact.core.conversation.ModelId
import com.cordiact.core.conversation.SessionManagerService
import com.cordiact.app.plugin.AndroidExtensionPackageStore
import org.junit.Assert.assertThrows
import com.cordiact.core.sandbox.SandboxLaunchRequest
import com.cordiact.core.sandbox.SandboxLimits
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class BundledConversationPluginCatalogTest {
    private lateinit var context: Context
    private lateinit var lifecycleStore: AndroidPluginLifecycleStore
    private lateinit var conversationStore: AndroidConversationSessionStore
    private lateinit var manager: PluginLifecycleManager

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        context.deleteDatabase(LIFECYCLE_DB)
        context.deleteDatabase(CONVERSATION_DB)
        lifecycleStore = AndroidPluginLifecycleStore(context, LIFECYCLE_DB)
        conversationStore = AndroidConversationSessionStore(context, CONVERSATION_DB)
        val storage = AndroidPluginStorage(context)
        val ledger = AndroidPluginResourceLedger(lifecycleStore, emptyMap())
        val runtime = AndroidPluginRuntimeResources(storage, ledger, lifecycleStore)
        manager = PluginLifecycleManager(
            lifecycleStore,
            runtime,
            lifecycleStore,
            object : PluginOwnedData { override fun eraseAll(id: String) = Unit },
            BundledConversationPluginCatalog.verifier,
        )
    }

    @After
    fun tearDown() {
        lifecycleStore.close()
        conversationStore.close()
        context.deleteDatabase(LIFECYCLE_DB)
        context.deleteDatabase(CONVERSATION_DB)
    }

    @Test
    fun providerConfigurationPersistsWithoutApiCredentials() {
        val preferencesName = "model_provider_config"
        context.getSharedPreferences(preferencesName, Context.MODE_PRIVATE).edit().clear().commit()
        val store = AndroidModelProviderConfigStore(context)
        val provider = ModelProviderPresets.openAiResponses(
            models = listOf("custom-model"),
            providerId = "custom-provider",
            displayName = "Private gateway",
            endpoint = "https://models.example.test/v1/responses",
        )
        store.save(provider)

        assertEquals(listOf(provider), AndroidModelProviderConfigStore(context).load())
        val raw = context.getSharedPreferences(preferencesName, Context.MODE_PRIVATE).getString("providers_json", "")
        assertFalse(raw.orEmpty().contains("apiKey"))
        assertFalse(raw.orEmpty().contains("secret"))

        store.remove(provider.providerId)
        assertTrue(store.load().isEmpty())
    }

    @Test
    fun firstConversationPluginsRegisterEnableAndRemainProtected() {
        val catalog = BundledConversationPlugins.create(conversationStore, context.noBackupFilesDir)
        val plugins = catalog.create(
            stateLookup = { pluginId ->
                if (pluginId == "core.workspace-manager") PluginState.ENABLED else null
            },
        )
        assertEquals(
            listOf("core.workspace-manager", "core.file-manager", "core.skill", "core.tool-manager", "core.search", "core.search.provider.tavily", "core.search.provider.brave", "core.model-adapter", "core.session-manager", "model.providers", "core.interaction", "pkg.core-quick-notes", "core.workbench-tabs", "core.tool-result-cards"),
            plugins.map { it.spec.id },
        )

        plugins.forEachIndexed { index, plugin ->
            if (index == 3) assertEquals(PluginState.ENABLED, manager.get("core.workspace-manager")?.state)
            assertEquals(PluginState.REGISTERED, manager.registerBundledSystem(plugin).state)
            assertEquals(PluginState.ENABLED, manager.enable(plugin.spec.id).state)
            assertEquals(PluginOrigin.BUNDLED_SYSTEM, manager.get(plugin.spec.id)?.origin)
        }

        val search = requireNotNull(catalog.searchRegistry)
        val originalTavily = requireNotNull(search.searchProvider("tavily"))
        val originalBrave = requireNotNull(search.searchProvider("brave"))
        manager.disable("core.search.provider.tavily")
        assertEquals(null, search.searchProvider("tavily"))
        assertTrue(!originalTavily.ready(null))
        assertTrue(search.searchProvider("brave") === originalBrave)
        manager.enable("core.search.provider.tavily")
        assertTrue(search.searchProvider("tavily") !== originalTavily)
        assertTrue(!originalTavily.available())

        val directory = catalog.workbenchTabs
        val original = directory.snapshot.value.visible("workspace", "session")
        assertEquals(listOf("cordiact.trace", "cordiact.artifacts", "cordiact.git-tree"), original.map { it.id })
        manager.disable("core.workbench-tabs")
        assertTrue(directory.snapshot.value.visible("workspace", "session").isEmpty())
        manager.enable("core.workbench-tabs")
        assertTrue(!directory.isCurrent(original.first(), "workspace", "session"))
        assertEquals(original.map { it.id }, directory.snapshot.value.visible("workspace", "session").map { it.id })
        val cards = catalog.toolResultCards
        val keys = cards.snapshot.value.keys.toList()
        assertEquals(setOf("extension.pkg.core-quick-notes.checklist", "core.subagent.list-children"), keys.map { it.toolId }.toSet())
        manager.disable("core.tool-result-cards")
        assertTrue(cards.snapshot.value.isEmpty())
        assertTrue(keys.all { cards.resolve(it) == null })
        manager.enable("core.tool-result-cards")
        assertTrue(keys.all { cards.resolve(it) != null })

        plugins.forEach { plugin ->
            try {
                manager.uninstall(plugin.spec.id)
                throw AssertionError("Expected bundled plugin to be protected")
            } catch (_: PluginLifecycleException) {
                assertEquals(PluginState.ENABLED, manager.get(plugin.spec.id)?.state)
            }
        }
    }

    @Test
    fun installedWorkspacePackageDispatchesThroughAppRegistriesAndWithdrawsAllContributions() {
        val catalog = BundledConversationPlugins.create(conversationStore, context.noBackupFilesDir)
        AndroidExtensionPackageStore(context).use { store ->
            val service = WorkspaceExtensionPackages(
                ExtensionPackageInstaller(store),
                { id -> catalog.workspaceManager.findSessionWorkspace(id)?.let { EffectiveSecurityProfile.fullAccess(id) } },
                runtime = catalog.workspacePackageRuntime(),
            )
            val plugins = catalog.create(stateLookup = object : ToolPluginStateLookup {
                override fun state(pluginId: String) = manager.get(pluginId)?.state
                override fun scopedState(pluginId: String, workspaceId: String, sessionId: String?) =
                    service.scopedOwnerState(pluginId, workspaceId, sessionId)
            })
            plugins.forEach { manager.registerBundledSystem(it); manager.enable(it.spec.id) }
            val workspace = catalog.workspaceManager.create("Plugin smoke")
            val other = catalog.workspaceManager.create("Other workspace")
            val sessions = plugins.filterIsInstance<SessionManagerService>().single()
            val session = sessions.createSession(workspace.id, "Plugins")
            val otherSession = sessions.createSession(other.id, "Other")
            val owner = InteractionContext(session.id, workspace.id)
            val toolId = "extension.pkg.checkpoint.message"
            val tools = catalog.toolManagerPlugin
            val interaction = catalog.interactionService
            interaction.attachModelSubmitter(InteractionModelSubmitter { _, _, _, _ -> error("Local command must not submit a model call") })
            fun bindings(sessionId: String, workspaceId: String) = tools.resolveForTurn(ToolActivationContext(workspaceId, sessionId = sessionId))
                .filter { it.descriptor.id == toolId }.associate { it.descriptor.id to requireNotNull(it.registration) }
            fun commands(ctx: InteractionContext) = interaction.commandCandidates(ctx).map { it.name }
            val page = service.openPage(workspace.id)
            service.resume(Any())
            val preview = page.preview("""
                {"schemaVersion":2,"id":"checkpoint","version":"1.0.0","title":"Checkpoint","description":"Test fixture",
                 "host":{"api":1,"minVersion":"0.1.0"},"capabilities":["tool.publish","command.publish","ui.settings.publish","ui.session-action.publish"],
                 "tools":[{"name":"message","description":"Read fixture","kind":"static_text","text":"fixture tool output"}],
                 "commands":[{"name":"package-checkpoint","aliases":[],"description":"Show fixture","text":"fixture command output"}],
                 "client":{"settingsPage":{"title":"Checkpoint guide","description":"Local fixture"},
                           "sessionActions":[{"id":"insert","label":"Insert command","command":"package-checkpoint"}]}}
            """.trimIndent().byteInputStream())
            val installed = page.approveInstall(preview).commit() as ExtensionPackageChange.Applied
            assertFalse(installed.record.enableIntent)
            assertTrue(bindings(session.id, workspace.id).isEmpty())
            assertFalse("package-checkpoint" in commands(owner))
            val client = catalog.workspaceClientContributions
            assertTrue(client.snapshot.value.settingsPages(workspace.id).isEmpty())
            val enabled = page.approveEnable(installed.record, true).commit() as ExtensionPackageChange.Applied
            assertEquals("Checkpoint guide", client.snapshot.value.settingsPages(workspace.id).single().description.title)
            assertTrue(client.snapshot.value.settingsPages(other.id).isEmpty())
            val offer = client.snapshot.value.sessionActions(owner).single()
            assertEquals("/package-checkpoint", client.draftInsertion(offer, owner, "", false))
            val context = ToolInvocationContext(workspace.id, sessionId = session.id,
                toolRegistrations = bindings(session.id, workspace.id),
                securityContext = SecurityContext(workspace.id, session.id, "run", "turn", "call", listOf(Principal("main")), EffectiveSecurityProfile.fullAccess(workspace.id)))
            assertEquals(ToolValue.StringValue("fixture tool output"), tools.invoke(context, toolId, ToolArguments()).values["text"])
            val command = interaction.prepare("/package-checkpoint", owner)
            assertTrue(command.valid)
            assertEquals("fixture command output", interaction.dispatch(command, ModelId("test", "fixture")) { }.message)
            val local = requireNotNull(interaction.prepareLocalCommand("/package-checkpoint", owner))
            assertEquals("fixture command output", interaction.dispatchLocalCommand(local).message)
            assertTrue(bindings(otherSession.id, other.id).isEmpty())
            assertFalse("package-checkpoint" in commands(InteractionContext(otherSession.id, other.id)))
            page.approveEnable(enabled.record, false).commit()
            assertTrue(client.snapshot.value.settingsPages(workspace.id).isEmpty())
            assertTrue(client.snapshot.value.sessionActions(owner).isEmpty())
            assertEquals(null, client.draftInsertion(offer, owner, "", false))
            assertTrue(bindings(session.id, workspace.id).isEmpty())
            assertFalse("package-checkpoint" in commands(owner))
            assertThrows(ToolManagerException::class.java) { tools.invoke(context, toolId, ToolArguments()) }
            assertThrows(IllegalStateException::class.java) { interaction.dispatch(command, ModelId("test", "fixture")) { } }
            assertThrows(IllegalStateException::class.java) { interaction.dispatchLocalCommand(local) }
        }
    }

    @Test
    fun sandboxManagerIsRegisteredAsProtectedButLocalExecutionRemainsClosed() {
        val sandboxManager = BundledConversationPluginCatalog.createSandboxManager(context.applicationInfo.uid)
        assertEquals(PluginState.REGISTERED, manager.registerBundledSystem(sandboxManager).state)
        assertEquals(PluginState.ENABLED, manager.enable(sandboxManager.spec.id).state)
        val launch = SandboxLaunchRequest(
            plugin = com.cordiact.core.sandbox.BundledPluginIdentity("plugin.example", "1.0.0", "a".repeat(64)),
            workspaceId = "workspace-1",
            requestedCapabilities = emptySet(),
            limits = SandboxLimits(1_000, 1024, 1024, 1),
        )
        try {
            sandboxManager.launch(launch)
            throw AssertionError("Local runtime must stay closed until isolated service is implemented")
        } catch (expected: IllegalStateException) {
            assertTrue(expected.message!!.contains("verified APK-bundled"))
        }
        assertEquals(PluginState.ENABLED, manager.get(sandboxManager.spec.id)?.state)
    }

    @Test
    fun catalogRejectsAlteredBundledManifest() {
        val modelSpec = modelAdapterPluginSpec()
        assertTrue(BundledConversationPluginCatalog.verifier.isTrustedBundled(modelSpec))
        assertFalse(BundledConversationPluginCatalog.verifier.isTrustedBundled(modelSpec.copy(version = "99.0")))
        assertFalse(BundledConversationPluginCatalog.verifier.isTrustedBundled(modelSpec.copy(requestedCapabilities = setOf("android.shell"))))
        val untrusted = BundledPluginVerifier { false }
        assertFalse(untrusted.isTrustedBundled(modelSpec))
        assertTrue(BundledConversationPluginCatalog.verifier.isTrustedBundled(skillPluginSpec()))
        assertTrue(BundledConversationPluginCatalog.verifier.isTrustedBundled(workspaceManagerPluginSpec()))
        assertTrue(BundledConversationPluginCatalog.verifier.isTrustedBundled(sessionManagerPluginSpec()))
        assertTrue(BundledConversationPluginCatalog.verifier.isTrustedBundled(modelProviderRegistryPluginSpec()))
        assertTrue(BundledConversationPluginCatalog.verifier.isTrustedBundled(sandboxManagerPluginSpec()))
    }

    private companion object {
        const val LIFECYCLE_DB = "cordiact-system-plugin-lifecycle-test.db"
        const val CONVERSATION_DB = "cordiact-system-plugin-conversation-test.db"
    }
}
