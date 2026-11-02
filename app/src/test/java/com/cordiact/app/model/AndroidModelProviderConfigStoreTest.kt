package com.cordiact.app.model

import android.content.Context
import com.cordiact.core.conversation.GenerationParameters
import com.cordiact.model.deepseek.ModelProviderConfig
import com.cordiact.model.deepseek.ModelProtocol
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

    @Test
    fun defaultsSurviveASaveAndReloadCycle() {
        val config = ModelProviderConfig(
            providerId = "custom",
            displayName = "Custom",
            protocol = ModelProtocol.ANTHROPIC_MESSAGES,
            endpoint = "https://api.example.test/v1/messages",
            models = listOf("model-a", "model-b"),
            defaults = GenerationParameters(temperature = 0.4, maxOutputTokens = 2048, topP = 0.8),
        )

        store.save(config)

        val reloaded = AndroidModelProviderConfigStore(RuntimeEnvironment.getApplication()).load()
        assertEquals(listOf(config), reloaded)
        assertEquals(GenerationParameters(temperature = 0.4, maxOutputTokens = 2048, topP = 0.8), reloaded.single().defaults)
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
}
