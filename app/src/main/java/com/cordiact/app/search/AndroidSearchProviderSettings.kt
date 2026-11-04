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

    /** Native search options shared by every native provider; read once per call. */
    fun nativeOptions(): com.cordiact.search.providers.NativeSearchOptions =
        com.cordiact.search.providers.NativeSearchOptions(
            maxUses = preferences.getInt("native.max_uses", 5).coerceIn(1, 20),
            maxTokens = preferences.getInt("native.max_tokens", 4096).coerceIn(256, 16_384),
            allowedDomains = csv("native.allowed_domains"),
            blockedDomains = csv("native.blocked_domains"),
            searchContextSize = preferences.getString("native.context_size", null)
                ?.takeIf { it in com.cordiact.search.providers.NativeSearchOptions.CONTEXT_SIZES }
                ?: "medium",
            geminiUrlContext = preferences.getBoolean("native.gemini_url_context", false),
            userLocation = nativeLocation(),
        )

    fun putNativeMaxUses(value: Int) = preferences.edit().putInt("native.max_uses", value.coerceIn(1, 20)).apply()

    fun putNativeMaxTokens(value: Int) =
        preferences.edit().putInt("native.max_tokens", value.coerceIn(256, 16_384)).apply()

    fun putNativeAllowedDomains(value: List<String>) {
        preferences.edit().putString("native.allowed_domains", value.joinToString(",")).apply()
    }

    fun putNativeBlockedDomains(value: List<String>) {
        preferences.edit().putString("native.blocked_domains", value.joinToString(",")).apply()
    }

    fun putNativeContextSize(value: String) {
        if (value in com.cordiact.search.providers.NativeSearchOptions.CONTEXT_SIZES) {
            preferences.edit().putString("native.context_size", value).apply()
        }
    }

    fun putNativeGeminiUrlContext(value: Boolean) {
        preferences.edit().putBoolean("native.gemini_url_context", value).apply()
    }

    fun putNativeLocation(location: com.cordiact.search.providers.SearchUserLocation?) {
        val editor = preferences.edit()
        if (location == null || location.isEmpty) {
            editor.remove("native.city").remove("native.region")
                .remove("native.country").remove("native.timezone")
        } else {
            editor.putString("native.city", location.city)
            editor.putString("native.region", location.region)
            editor.putString("native.country", location.country)
            editor.putString("native.timezone", location.timezone)
        }
        editor.apply()
    }

    /** Whether the on-device fetch provider may run; off unless explicitly enabled. */
    fun localFetchEnabled(): Boolean = preferences.getBoolean("local_fetch.enabled", false)

    fun putLocalFetchEnabled(value: Boolean) {
        preferences.edit().putBoolean("local_fetch.enabled", value).apply()
    }

    private fun nativeLocation(): com.cordiact.search.providers.SearchUserLocation? {
        val location = com.cordiact.search.providers.SearchUserLocation(
            city = preferences.getString("native.city", null)?.takeIf { it.isNotBlank() },
            region = preferences.getString("native.region", null)?.takeIf { it.isNotBlank() },
            country = preferences.getString("native.country", null)?.takeIf { it.isNotBlank() },
            timezone = preferences.getString("native.timezone", null)?.takeIf { it.isNotBlank() },
        )
        return location.takeUnless { it.isEmpty }
    }

    private fun csv(key: String): List<String> =
        preferences.getString(key, null)
            ?.split(',')
            ?.map(String::trim)
            ?.filter(String::isNotEmpty)
            .orEmpty()

    private fun key(providerId: String, field: String) = "$providerId.$field"

    private companion object {
        const val PREFERENCES = "cordiact.search.providers"
    }
}
