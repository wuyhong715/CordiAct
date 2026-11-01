package com.cordiact.app.conversation

import android.content.Context
import com.cordiact.app.plugin.AndroidPluginLifecycleStore
import com.cordiact.app.plugin.AndroidPluginResourceLedger
import com.cordiact.app.plugin.AndroidPluginRuntimeResources
import com.cordiact.app.plugin.AndroidPluginStorage
import com.cordiact.core.conversation.modelAdapterPluginSpec
import com.cordiact.model.deepseek.deepSeekBackendPluginSpec
import com.cordiact.model.deepseek.modelProviderRegistryPluginSpec
import com.cordiact.app.model.AndroidModelProviderConfigStore
import com.cordiact.model.deepseek.ModelProviderPresets
import com.cordiact.core.conversation.sessionManagerPluginSpec
import com.cordiact.core.workspace.workspaceManagerPluginSpec
import com.cordiact.core.sandbox.sandboxManagerPluginSpec
import com.cordiact.core.plugin.BundledPluginVerifier
import com.cordiact.core.plugin.PluginLifecycleException
import com.cordiact.core.plugin.PluginLifecycleManager
import com.cordiact.core.plugin.PluginOrigin
import com.cordiact.core.plugin.PluginOwnedData
import com.cordiact.core.plugin.PluginState
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
        val plugins = catalog.create { pluginId ->
            if (pluginId == "core.workspace-manager") PluginState.ENABLED else null
        }
        assertEquals(
            listOf("core.workspace-manager", "core.file-manager", "core.tool-manager", "core.model-adapter", "core.session-manager", "model.deepseek", "model.providers"),
            plugins.map { it.spec.id },
        )

        plugins.forEachIndexed { index, plugin ->
            if (index == 2) assertEquals(PluginState.ENABLED, manager.get("core.workspace-manager")?.state)
            assertEquals(PluginState.REGISTERED, manager.registerBundledSystem(plugin).state)
            assertEquals(PluginState.ENABLED, manager.enable(plugin.spec.id).state)
            assertEquals(PluginOrigin.BUNDLED_SYSTEM, manager.get(plugin.spec.id)?.origin)
        }

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
