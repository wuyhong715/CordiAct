package com.cordiact.app.conversation

import android.content.Context
import com.cordiact.app.plugin.AndroidPluginLifecycleStore
import com.cordiact.app.plugin.AndroidPluginResourceLedger
import com.cordiact.app.plugin.AndroidPluginRuntimeResources
import com.cordiact.app.plugin.AndroidPluginStorage
import com.cordiact.core.conversation.modelAdapterPluginSpec
import com.cordiact.core.conversation.sessionManagerPluginSpec
import com.cordiact.core.plugin.BundledPluginVerifier
import com.cordiact.core.plugin.PluginLifecycleException
import com.cordiact.core.plugin.PluginLifecycleManager
import com.cordiact.core.plugin.PluginOrigin
import com.cordiact.core.plugin.PluginOwnedData
import com.cordiact.core.plugin.PluginState
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
    fun firstConversationPluginsRegisterEnableAndRemainProtected() {
        val plugins = BundledConversationPluginCatalog.create(conversationStore)
        assertEquals(setOf("core.model-adapter", "core.session-manager"), plugins.map { it.spec.id }.toSet())

        plugins.forEach { plugin ->
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
    fun catalogRejectsAlteredBundledManifest() {
        val plugin = BundledConversationPluginCatalog.create(conversationStore).first()
        assertTrue(BundledConversationPluginCatalog.verifier.isTrustedBundled(plugin.spec))
        assertFalse(BundledConversationPluginCatalog.verifier.isTrustedBundled(plugin.spec.copy(version = "99.0")))
        assertFalse(BundledConversationPluginCatalog.verifier.isTrustedBundled(plugin.spec.copy(requestedCapabilities = setOf("android.shell"))))
        val untrusted = BundledPluginVerifier { false }
        assertFalse(untrusted.isTrustedBundled(modelAdapterPluginSpec()))
        assertTrue(BundledConversationPluginCatalog.verifier.isTrustedBundled(sessionManagerPluginSpec()))
    }

    private companion object {
        const val LIFECYCLE_DB = "cordiact-system-plugin-lifecycle-test.db"
        const val CONVERSATION_DB = "cordiact-system-plugin-conversation-test.db"
    }
}
