package com.cordiact.app.search

import android.content.Context

/**
 * Non-secret search provider settings.
 *
 * API keys are never stored here; they live in the Keystore-backed
 * AndroidSearchApiKeyStore. Values are read on every provider call, so a
 * settings change applies to the next search without re-registering providers.
 */
class AndroidSearchProviderSettings(context: Context) {
    private val preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    fun baseUrl(providerId: String): String? =
        preferences.getString(key(providerId, "base_url"), null)?.takeIf { it.isNotBlank() }

    fun putBaseUrl(providerId: String, value: String?) {
        val editor = preferences.edit()
        if (value.isNullOrBlank()) editor.remove(key(providerId, "base_url"))
        else editor.putString(key(providerId, "base_url"), value.trim())
        editor.apply()
    }

    fun searchDepth(): String = preferences.getString("tavily.search_depth", null) ?: "basic"

    fun putSearchDepth(value: String) {
        preferences.edit().putString("tavily.search_depth", value).apply()
    }

    fun includeAnswer(): Boolean = preferences.getBoolean("tavily.include_answer", true)

    fun putIncludeAnswer(value: Boolean) {
        preferences.edit().putBoolean("tavily.include_answer", value).apply()
    }

    fun braveCountry(): String? = preferences.getString("brave.country", null)?.takeIf { it.isNotBlank() }

    fun putBraveCountry(value: String?) {
        val editor = preferences.edit()
        if (value.isNullOrBlank()) editor.remove("brave.country") else editor.putString("brave.country", value.trim())
        editor.apply()
    }

    fun exaType(): String = preferences.getString("exa.type", null) ?: "auto"

    fun putExaType(value: String) {
        preferences.edit().putString("exa.type", value).apply()
    }

    fun exaContents(): Boolean = preferences.getBoolean("exa.contents", true)

    fun putExaContents(value: Boolean) {
        preferences.edit().putBoolean("exa.contents", value).apply()
    }

    fun searxngEngines(): String? = preferences.getString("searxng.engines", null)?.takeIf { it.isNotBlank() }

    fun putSearxngEngines(value: String?) {
        val editor = preferences.edit()
        if (value.isNullOrBlank()) editor.remove("searxng.engines") else editor.putString("searxng.engines", value.trim())
        editor.apply()
    }

    fun bochaFreshness(): String = preferences.getString("bocha.freshness", null) ?: "noLimit"

    fun putBochaFreshness(value: String) {
        preferences.edit().putString("bocha.freshness", value).apply()
    }

    private fun key(providerId: String, field: String) = "$providerId.$field"

    private companion object {
        const val PREFERENCES = "cordiact.search.providers"
    }
}
