package com.cordiact.core.plugin

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PluginManagerPresenterTest {

    private val spec = PluginSpec("core.mcp.demo", "0.1.0", PluginTarget.ANDROID_NATIVE, config = "{}")

    @Test
    fun aNonAppliedChangeAlwaysCarriesANextStep() {
        // The whole point of the result shape: a model must not be able to report success for a
        // change the host only saved.
        val result = PluginChangeResult(
            pluginId = spec.id,
            application = PluginChangeResult.Application.RESTART_REQUIRED,
            reason = "the plugin host only reloads at startup",
            nextStep = "Tell the user to restart the app.",
            entry = PluginInventoryEntry(spec, PluginOrigin.USER_INSTALLED, PluginState.ENABLED),
        )
        val text = PluginManagerPresenter.present(result)

        assertTrue(text.contains("application=restart_required"))
        assertTrue(text.contains("nextStep: Tell the user to restart the app."))
        assertTrue(text.contains("state=enabled"))
        assertTrue(text.contains("origin=user_installed"))
    }

    @Test
    fun anAppliedChangeOmitsTheNextStepLine() {
        val text = PluginManagerPresenter.present(
            PluginChangeResult(spec.id, PluginChangeResult.Application.APPLIED),
        )
        assertEquals("plugin=core.mcp.demo application=applied", text)
    }

    @Test
    fun inventoryShowsReadOnlyAndErrorState() {
        val text = PluginManagerPresenter.presentInventory(
            listOf(
                PluginInventoryEntry(
                    spec = PluginSpec("core.session-manager", "0.1.0", PluginTarget.ANDROID_NATIVE),
                    origin = PluginOrigin.BUNDLED_SYSTEM,
                    state = PluginState.ENABLED,
                    publishedToolIds = listOf("a", "b"),
                    readOnlyReason = PluginInventoryEntry.ReadOnlyReason.MANAGEMENT_REQUIRED,
                ),
                PluginInventoryEntry(
                    spec = spec,
                    origin = PluginOrigin.USER_INSTALLED,
                    state = PluginState.FAILED,
                    lastError = "unreachable",
                ),
            ),
        )
        assertTrue(text.contains("core.session-manager 0.1.0 enabled bundled_system readOnly=management_required tools=2"))
        assertTrue(text.contains("core.mcp.demo 0.1.0 failed user_installed lastError=\"unreachable\""))
    }

    @Test
    fun anEmptyInventorySaysSoInsteadOfReturningNothing() {
        assertEquals("No plugins installed.", PluginManagerPresenter.presentInventory(emptyList()))
    }
}
