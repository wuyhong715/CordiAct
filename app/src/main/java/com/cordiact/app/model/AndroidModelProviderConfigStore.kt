package com.cordiact.app.model

import android.content.Context
import com.cordiact.core.conversation.GenerationParameters
import com.cordiact.model.deepseek.ModelProviderConfig
import com.cordiact.model.deepseek.ModelProviderConfigStore
import com.cordiact.model.deepseek.ModelProtocol
import org.json.JSONArray
import org.json.JSONObject

/** Persists provider metadata only. API keys are stored separately by AndroidProviderApiKeyStore. */
class AndroidModelProviderConfigStore(context: Context) : ModelProviderConfigStore {
    private val preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    @Synchronized
    override fun load(): List<ModelProviderConfig> {
        val raw = preferences.getString(CONFIGS, null) ?: return emptyList()
        return try {
            val array = JSONArray(raw)
            require(array.length() <= MAX_PROVIDER_COUNT) { "Too many stored model providers" }
            List(array.length()) { index ->
                val item = array.getJSONObject(index)
                ModelProviderConfig(
                    providerId = item.getString("providerId"),
                    displayName = item.getString("displayName"),
                    protocol = ModelProtocol.valueOf(item.getString("protocol")),
                    endpoint = item.getString("endpoint"),
                    models = item.getJSONArray("models").let { models ->
                        List(models.length()) { modelIndex -> models.getString(modelIndex) }
                    },
                    defaults = item.optJSONObject("defaults")?.let { defaults ->
                        GenerationParameters(
                            temperature = defaults.optDouble("temperature").takeIf { defaults.has("temperature") },
                            maxOutputTokens = defaults.optInt("maxOutputTokens").takeIf { defaults.has("maxOutputTokens") },
                            topP = defaults.optDouble("topP").takeIf { defaults.has("topP") },
                        )
                    } ?: GenerationParameters.NONE,
                )
            }
        } catch (_: Exception) {
            throw IllegalStateException("Stored model provider configuration is invalid")
        }
    }

    @Synchronized
    override fun save(config: ModelProviderConfig) {
        val configs = load().filterNot { it.providerId == config.providerId } + config
        write(configs)
    }

    @Synchronized
    override fun remove(providerId: String) {
        write(load().filterNot { it.providerId == providerId })
    }

    private fun write(configs: List<ModelProviderConfig>) {
        require(configs.size <= MAX_PROVIDER_COUNT) { "At most $MAX_PROVIDER_COUNT providers may be stored" }
        val array = JSONArray()
        configs.sortedBy { it.providerId }.forEach { config ->
            array.put(JSONObject().apply {
                put("providerId", config.providerId)
                put("displayName", config.displayName)
                put("protocol", config.protocol.name)
                put("endpoint", config.endpoint)
                put("models", JSONArray().apply { config.models.forEach { put(it) } })
                val defaults = config.defaults
                if (defaults.temperature != null || defaults.maxOutputTokens != null || defaults.topP != null) {
                    put("defaults", JSONObject().apply {
                        defaults.temperature?.let { put("temperature", it) }
                        defaults.maxOutputTokens?.let { put("maxOutputTokens", it) }
                        defaults.topP?.let { put("topP", it) }
                    })
                }
            })
        }
        check(preferences.edit().putString(CONFIGS, array.toString()).commit()) {
            "Could not save model provider configuration"
        }
    }

    private companion object {
        const val MAX_PROVIDER_COUNT = 32
        const val PREFERENCES = "model_provider_config"
        const val CONFIGS = "providers_json"
    }
}
