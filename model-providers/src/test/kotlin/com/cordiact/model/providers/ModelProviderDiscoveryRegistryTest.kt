package com.cordiact.model.providers

import com.cordiact.core.conversation.GenerationParameters
import com.cordiact.core.conversation.ReasoningEffort
import com.cordiact.core.conversation.ModelAdapterPlugin
import com.cordiact.core.conversation.ModelId
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class ModelProviderDiscoveryRegistryTest {
    private val adapter = ModelAdapterPlugin().apply { onEnable() }
    private val configs = InMemoryModelProviderConfigStore()
    private val keys = mutableMapOf<String, String>()
    private var discover: (ModelProviderConnection, String) -> List<String> = { _, _ -> listOf("a", "b") }
    private val registry = ModelProviderRegistryPlugin(
        adapter = adapter,
        credentialFor = { id -> ApiKeyProvider { keys[id] } },
        configStore = configs,
        forgetCredential = { keys.remove(it) },
        discovery = ModelDiscoveryService { connection, key -> discover(connection, key) },
        saveCredential = { id, key -> keys[id] = key },
    )
    private val connection = ModelProviderConnection("provider-test", "Example", ModelProtocol.OPENAI_RESPONSES, "https://example.com/v1")

    @Test fun discoveryCreatesOneProviderWithAllModelsAndPersistsKey() {
        registry.onEnable()
        try {
            val config = registry.discoverAndAddProvider(connection, "secret")
            assertEquals(listOf("a", "b"), config.models)
            assertEquals("https://example.com/v1/responses", config.endpoint)
            assertEquals("secret", keys[connection.providerId])
            assertEquals(1, configs.load().size)
            assertEquals(listOf(ModelId(connection.providerId, "a"), ModelId(connection.providerId, "b")), adapter.models().map { it.id })
        } finally { registry.onDisable() }
    }

    @Test fun failedDiscoveryLeavesNoConfigNoKeyNoPartialModels() {
        registry.onEnable()
        try {
            discover = { _, _ -> error("MODEL_DISCOVERY_HTTP_401") }
            assertThrows(IllegalStateException::class.java) { registry.discoverAndAddProvider(connection, "secret") }
            assertTrue(configs.load().isEmpty()); assertTrue(keys.isEmpty()); assertTrue(adapter.models().isEmpty())
        } finally { registry.onDisable() }
    }

    @Test fun attachmentFailureRollsBackStoredKeyAndProviderConfig() {
        registry.onEnable()
        val existing = ConfigurableModelBackend(connection.config(listOf("other")), ApiKeyProvider { "another-key" })
        adapter.attachBackend("another-plugin", existing)
        try {
            assertThrows(IllegalStateException::class.java) { registry.discoverAndAddProvider(connection, "secret") }
            assertTrue(configs.load().isEmpty()); assertTrue(keys.isEmpty()); assertTrue(registry.providers().isEmpty())
        } finally { registry.onDisable(); adapter.detachBackend("another-plugin", connection.providerId); existing.close() }
    }

    @Test fun refreshRetainsKnownCapabilitiesButDoesNotGuessNewOnes() {
        registry.onEnable()
        try {
            val capability = ModelParameterCapabilities(reasoningEffortValues = mapOf(ReasoningEffort.HIGH to "high"))
            val defaults = GenerationParameters(temperature = 0.3)
            val config = ModelProviderConfig(connection.providerId, connection.displayName, connection.protocol,
                connection.generationEndpoint(), listOf("a", "removed"), defaults = defaults,
                parameterCapabilities = mapOf("a" to capability, "removed" to capability))
            keys[connection.providerId] = "secret"
            registry.addProvider(config)
            val updated = registry.refreshModels(connection.providerId)
            assertEquals(listOf("a"), updated.models)
            assertEquals(listOf("a", "b"), updated.discoveredModels)
            assertEquals(defaults, updated.defaults)
            assertEquals(mapOf("a" to capability), updated.parameterCapabilities)
            assertFalse(updated.parameterCapabilities.containsKey("b"))
        } finally { registry.onDisable() }
    }

    @Test fun failedRefreshPreservesWorkingConfigurationAndModels() {
        registry.onEnable()
        try {
            registry.discoverAndAddProvider(connection, "secret")
            discover = { _, _ -> error("MODEL_DISCOVERY_INVALID_LIST") }
            assertThrows(IllegalStateException::class.java) { registry.refreshModels(connection.providerId) }
            assertEquals(listOf("a", "b"), registry.providers().single().models)
            assertEquals(2, adapter.models().size)
            assertEquals("secret", keys[connection.providerId])
        } finally { registry.onDisable() }
    }

    @Test fun refreshCannotOverwriteConcurrentUserEdit() {
        registry.onEnable()
        try {
            registry.discoverAndAddProvider(connection, "secret")
            discover = { _, _ ->
                registry.updateProvider(connection.config(listOf("edited")))
                listOf("stale")
            }
            assertThrows(IllegalStateException::class.java) { registry.refreshModels(connection.providerId) }
            assertEquals(listOf("edited"), registry.providers().single().models)
        } finally { registry.onDisable() }
    }
}
