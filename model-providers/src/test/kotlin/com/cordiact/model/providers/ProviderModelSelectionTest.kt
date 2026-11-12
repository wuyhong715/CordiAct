package com.cordiact.model.providers

import com.cordiact.core.conversation.ModelId
import com.cordiact.core.conversation.ModelAdapterPlugin
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class ProviderModelSelectionTest {
    @Test fun perModelDefaultsRequireDeclaredCapabilityAndSurviveSelectionChanges() {
        val caps = ModelParameterCapabilities.openAi(setOf(com.cordiact.core.conversation.ReasoningEffort.HIGH))
        val base = ModelProviderConfig("custom-defaults", "D", ModelProtocol.OPENAI_RESPONSES,
            "https://example.com/v1", listOf("a"), parameterCapabilities = mapOf("a" to caps))
        val params = com.cordiact.core.conversation.GenerationParameters(reasoningEffort = com.cordiact.core.conversation.ReasoningEffort.HIGH)
        val configured = base.withModelDefaults(mapOf("a" to params))
        assertEquals(params, configured.withModels(emptyList()).withModels(listOf("a")).modelDefaults["a"])
        assertThrows(IllegalArgumentException::class.java) {
            base.withModelDefaults(mapOf("a" to params.copy(reasoningEffort = com.cordiact.core.conversation.ReasoningEffort.MAX)))
        }
        assertThrows(IllegalArgumentException::class.java) { base.withModelDefaults(mapOf("unknown" to params)) }
    }
    @Test fun commitSelectsOnlyChosenModelsAndRefreshNeverAutoEnablesNewOnes() {
        var ids = listOf("a", "b", "c")
        val keys = mutableMapOf<String, String>()
        val store = InMemoryModelProviderConfigStore()
        val adapter = ModelAdapterPlugin().also { it.onEnable() }
        val registry = ModelProviderRegistryPlugin(adapter, { id -> ApiKeyProvider { keys[id] } }, store,
            forgetCredential = { keys.remove(it) }, discovery = ModelDiscoveryService { _, _ -> ids },
            saveCredential = { id, key -> keys[id] = key })
        registry.onEnable()
        val connection = ModelProviderConnection("custom-selection", "P", ModelProtocol.OPENAI_RESPONSES, "https://example.com/v1")
        // Independent discovery cannot alter persisted state or credentials.
        assertTrue(store.load().isEmpty()); assertTrue(keys.isEmpty())
        registry.commitProvider(connection, "not-a-real-key", listOf("b"), ids)
        assertEquals(listOf("b"), registry.providers().single().models)
        assertEquals(setOf(ModelId("custom-selection", "b")), adapter.models().map { it.id }.toSet())
        ids = listOf("b", "c", "d")
        val refreshed = registry.refreshModels("custom-selection")
        assertEquals(listOf("b"), refreshed.models)
        assertEquals(ids, refreshed.discoveredModels)
        // Removing every enabled model is legitimate and exposes no tools/models.
        ids = listOf("c", "d")
        registry.refreshModels("custom-selection")
        assertTrue(registry.providers().single().models.isEmpty())
        assertTrue(adapter.models().isEmpty())
        registry.onDisable(); registry.onEnable()
        assertTrue(registry.providers().single().models.isEmpty())
        assertEquals(ids, registry.providers().single().discoveredModels)
        registry.removeProvider("custom-selection")
        assertFalse(keys.containsKey("custom-selection"))
    }
    @Test fun invalidEnabledModelsAreRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            ModelProviderConfig("custom-selection", "P", ModelProtocol.OPENAI_RESPONSES, "https://example.com/v1", listOf("missing"), discoveredModels = listOf("a"))
        }
    }
    @Test fun disablingAndReenablingPreservesDeclaredCapabilities() {
        val capability = ModelParameterCapabilities.openAi(reasoningEfforts = setOf(com.cordiact.core.conversation.ReasoningEffort.HIGH))
        val config = ModelProviderConfig("custom-selection", "P", ModelProtocol.OPENAI_RESPONSES, "https://example.com/v1", listOf("a"), parameterCapabilities = mapOf("a" to capability))
        val disabled = config.withModels(emptyList())
        assertEquals(capability, disabled.parameterCapabilities["a"])
        assertEquals(listOf("a"), disabled.withModels(listOf("a")).models)
    }
}
