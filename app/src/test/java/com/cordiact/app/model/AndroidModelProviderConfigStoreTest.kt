package com.cordiact.app.model

import android.content.Context
import com.cordiact.core.conversation.GenerationParameters
import com.cordiact.core.conversation.ReasoningEffort
import com.cordiact.core.conversation.ResponseSpeed
import com.cordiact.model.providers.ModelParameterCapabilities
import com.cordiact.model.providers.ModelProviderConfig
import com.cordiact.model.providers.ModelProtocol
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [35])
class AndroidModelProviderConfigStoreTest {
    private lateinit var store: AndroidModelProviderConfigStore

    @Before
    fun setUp() {
        // Mirrors the store's private PREFERENCES constant; a fresh app data dir per Robolectric run.
        RuntimeEnvironment.getApplication()
            .getSharedPreferences("model_provider_config", Context.MODE_PRIVATE)
            .edit().clear().commit()
        store = AndroidModelProviderConfigStore(RuntimeEnvironment.getApplication())
    }

    @Test fun perModelDefaultsRoundTrip() {
        val capability = ModelParameterCapabilities.openAi(setOf(ReasoningEffort.HIGH))
        val config = ModelProviderConfig("custom-settings-defaults", "D", ModelProtocol.OPENAI_RESPONSES,
            "https://example.com/v1", listOf("a"), parameterCapabilities = mapOf("a" to capability))
            .withModelDefaults(mapOf("a" to GenerationParameters(reasoningEffort = ReasoningEffort.HIGH, responseSpeed = ResponseSpeed.FAST)))
        store.save(config)
        val reopened = AndroidModelProviderConfigStore(RuntimeEnvironment.getApplication()).load().single()
        assertEquals(config, reopened)
        assertEquals(ReasoningEffort.HIGH, reopened.modelDefaults["a"]?.reasoningEffort)
    }

    @Test
    fun discoveredAndEnabledAreDistinctAndZeroEnabledSurvivesRestart() {
        val config = ModelProviderConfig("custom-settings", "Settings", ModelProtocol.OPENAI_RESPONSES,
            "https://example.com/v1", models = listOf("b"), discoveredModels = listOf("a", "b"))
        store.save(config)
        assertEquals(config, AndroidModelProviderConfigStore(RuntimeEnvironment.getApplication()).load().single())
        store.save(config.withModels(emptyList()))
        val restarted = AndroidModelProviderConfigStore(RuntimeEnvironment.getApplication()).load().single()
        assertTrue(restarted.models.isEmpty())
        assertEquals(listOf("a", "b"), restarted.discoveredModels)
    }

    @Test
    fun defaultsSurviveASaveAndReloadCycle() {
        val config = ModelProviderConfig(
            providerId = "custom",
            displayName = "Custom",
            protocol = ModelProtocol.OPENAI_RESPONSES,
            endpoint = "https://api.example.test/v1/responses",
            models = listOf("model-a", "model-b"),
            defaults = GenerationParameters(
                temperature = 0.4,
                maxOutputTokens = 2048,
                topP = 0.8,
                reasoningEffort = ReasoningEffort.AUTO,
                responseSpeed = ResponseSpeed.FAST,
            ),
            parameterCapabilities = mapOf(
                "model-a" to ModelParameterCapabilities.openAi(
                    reasoningEfforts = setOf(ReasoningEffort.NONE, ReasoningEffort.LOW, ReasoningEffort.MAX),
                    supportsFast = true,
                    ultrafastWireValue = "ultrafast",
                ),
            ),
        )

        store.save(config)

        val reloaded = AndroidModelProviderConfigStore(RuntimeEnvironment.getApplication()).load()
        assertEquals(listOf(config), reloaded)
        assertEquals(config.defaults, reloaded.single().defaults)
        assertEquals(config.parameterCapabilities, reloaded.single().parameterCapabilities)
    }

    @Test
    fun aProviderWithoutDefaultsLoadsWithNone() {
        store.save(
            ModelProviderConfig(
                providerId = "plain",
                displayName = "Plain",
                protocol = ModelProtocol.OPENAI_CHAT_COMPLETIONS,
                endpoint = "https://api.example.test/v1/chat/completions",
                models = listOf("model"),
            ),
        )

        val reloaded = store.load().single()
        assertEquals(GenerationParameters.NONE, reloaded.defaults)
        assertNull(reloaded.defaults.temperature)
        assertNull(reloaded.defaults.maxOutputTokens)
        assertNull(reloaded.defaults.topP)
    }

    @Test
    fun partialDefaultsRoundTripWithoutInventingValues() {
        store.save(
            ModelProviderConfig(
                providerId = "partial",
                displayName = "Partial",
                protocol = ModelProtocol.OPENAI_RESPONSES,
                endpoint = "https://api.example.test/v1/responses",
                models = listOf("model"),
                defaults = GenerationParameters(topP = 0.5),
            ),
        )

        val defaults = store.load().single().defaults
        assertEquals(0.5, defaults.topP)
        assertNull(defaults.temperature)
        assertNull(defaults.maxOutputTokens)
    }

    @Test
    fun savingAProviderReplacesAnyPreviousEntryForTheSameId() {
        store.save(
            ModelProviderConfig(
                providerId = "dup",
                displayName = "First",
                protocol = ModelProtocol.OPENAI_CHAT_COMPLETIONS,
                endpoint = "https://api.example.test/v1/chat/completions",
                models = listOf("a"),
                defaults = GenerationParameters(temperature = 0.1),
            ),
        )
        store.save(
            ModelProviderConfig(
                providerId = "dup",
                displayName = "Second",
                protocol = ModelProtocol.OPENAI_CHAT_COMPLETIONS,
                endpoint = "https://api.example.test/v1/chat/completions",
                models = listOf("b"),
                defaults = GenerationParameters(temperature = 0.9),
            ),
        )

        val reloaded = store.load()
        assertEquals(1, reloaded.size)
        assertEquals("Second", reloaded.single().displayName)
        assertEquals(0.9, reloaded.single().defaults.temperature)
    }

    @Test
    fun storedConfigurationsAreOrderedByProviderId() {
        listOf("zeta", "alpha", "mike").forEach { id ->
            store.save(
                ModelProviderConfig(
                    providerId = id,
                    displayName = id,
                    protocol = ModelProtocol.OPENAI_CHAT_COMPLETIONS,
                    endpoint = "https://api.example.test/v1/chat/completions",
                    models = listOf("model"),
                ),
            )
        }

        assertEquals(listOf("alpha", "mike", "zeta"), store.load().map { it.providerId })
        assertTrue(store.load().all { it.defaults == GenerationParameters.NONE })
    }

    @Test fun configRevisionPersistsAndIncrementsAcrossInstances() {
        val initial = store.revision()
        val config = ModelProviderConfig("revision-test", "R", ModelProtocol.OPENAI_RESPONSES,
            "https://example.com/v1", listOf("a"))
        store.save(config)
        val second = AndroidModelProviderConfigStore(RuntimeEnvironment.getApplication())
        assertEquals(initial + 1, second.revision())
        second.save(ModelProviderConfig(config.providerId, config.displayName, config.protocol,
            "https://changed.example/v1", config.models))
        assertEquals(initial + 2, store.revision())
        store.remove(config.providerId)
        assertEquals(initial + 3, second.revision())
    }
}
