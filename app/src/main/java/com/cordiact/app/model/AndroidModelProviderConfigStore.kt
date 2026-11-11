package com.cordiact.app.model

import android.content.Context
import com.cordiact.core.attachment.AttachmentInputCapability
import com.cordiact.core.attachment.FilesApiCapability
import com.cordiact.core.attachment.Interpretation
import com.cordiact.core.conversation.GenerationParameters
import com.cordiact.core.conversation.ReasoningEffort
import com.cordiact.core.conversation.ResponseSpeed
import com.cordiact.model.providers.ModelParameterCapabilities
import com.cordiact.model.providers.ModelProviderConfig
import com.cordiact.model.providers.ModelProviderConfigStore
import com.cordiact.model.providers.ModelProtocol
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
                            reasoningEffort = defaults.optString("reasoningEffort").takeIf { it.isNotBlank() }
                                ?.let(ReasoningEffort::valueOf),
                            responseSpeed = defaults.optString("responseSpeed").takeIf { it.isNotBlank() }
                                ?.let(ResponseSpeed::valueOf),
                        )
                    } ?: GenerationParameters.NONE,
                    parameterCapabilities = item.optJSONObject("parameterCapabilities")?.let { capabilities ->
                        capabilities.keys().asSequence().associateWith { modelId ->
                            val modelCapabilities = capabilities.getJSONObject(modelId)
                            ModelParameterCapabilities(
                                reasoningEffortValues = modelCapabilities.optJSONObject("reasoningEffortValues")
                                    .toParameterMap().mapKeys { ReasoningEffort.valueOf(it.key) },
                                responseSpeedValues = modelCapabilities.optJSONObject("responseSpeedValues")
                                    .toParameterMap().mapKeys { ResponseSpeed.valueOf(it.key) },
                            )
                        }
                    }.orEmpty(),
                    attachmentCapabilities = item.optJSONObject("attachmentCapabilities")?.let { capabilities ->
                        capabilities.keys().asSequence().associateWith { modelId ->
                            val capability = capabilities.getJSONObject(modelId)
                            AttachmentInputCapability(
                                interpretations = capability.optJSONArray("interpretations").toStringSet()
                                    .map { Interpretation.valueOf(it) }
                                    .toSet(),
                                mimeTypes = capability.optJSONArray("mimeTypes").toStringSet(),
                                maxFileBytes = capability.optLong("maxFileBytes"),
                                maxRequestBytes = capability.optLong("maxRequestBytes"),
                                maxCount = capability.optInt("maxCount"),
                                filesApi = capability.optJSONObject("filesApi")?.let { files ->
                                    FilesApiCapability(
                                        uploadEndpoint = files.getString("uploadEndpoint"),
                                        maxFileBytes = files.optLong("maxFileBytes"),
                                        retentionMillis = files.optLong("retentionMillis")
                                            .takeIf { files.has("retentionMillis") },
                                    )
                                },
                            )
                        }
                    }.orEmpty(),
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
                if (defaults.temperature != null || defaults.maxOutputTokens != null || defaults.topP != null ||
                    defaults.reasoningEffort != null || defaults.responseSpeed != null
                ) {
                    put("defaults", JSONObject().apply {
                        defaults.temperature?.let { put("temperature", it) }
                        defaults.maxOutputTokens?.let { put("maxOutputTokens", it) }
                        defaults.topP?.let { put("topP", it) }
                        defaults.reasoningEffort?.let { put("reasoningEffort", it.name) }
                        defaults.responseSpeed?.let { put("responseSpeed", it.name) }
                    })
                }
                if (config.parameterCapabilities.isNotEmpty()) {
                    put("parameterCapabilities", JSONObject().apply {
                        config.parameterCapabilities.toSortedMap().forEach { (modelId, capabilities) ->
                            put(modelId, JSONObject().apply {
                                put("reasoningEffortValues", capabilities.reasoningEffortValues.toSortedMap(compareBy { it.name })
                                    .toJsonObject { it.name })
                                put("responseSpeedValues", capabilities.responseSpeedValues.toSortedMap(compareBy { it.name })
                                    .toJsonObject { it.name })
                            })
                        }
                    })
                }
                if (config.attachmentCapabilities.isNotEmpty()) {
                    put("attachmentCapabilities", JSONObject().apply {
                        config.attachmentCapabilities.toSortedMap().forEach { (modelId, capability) ->
                            put(modelId, JSONObject().apply {
                                put("interpretations", JSONArray().apply {
                                    capability.interpretations.sortedBy { it.name }.forEach { put(it.name) }
                                })
                                put("mimeTypes", JSONArray().apply {
                                    capability.mimeTypes.sorted().forEach { put(it) }
                                })
                                put("maxFileBytes", capability.maxFileBytes)
                                put("maxRequestBytes", capability.maxRequestBytes)
                                put("maxCount", capability.maxCount)
                                capability.filesApi?.let { files ->
                                    put("filesApi", JSONObject().apply {
                                        put("uploadEndpoint", files.uploadEndpoint)
                                        put("maxFileBytes", files.maxFileBytes)
                                        files.retentionMillis?.let { put("retentionMillis", it) }
                                    })
                                }
                            })
                        }
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

private fun JSONArray?.toStringSet(): Set<String> {
    if (this == null) return emptySet()
    return (0 until length()).map { getString(it) }.toSet()
}

private fun JSONObject?.toParameterMap(): Map<String, String> {
    if (this == null) return emptyMap()
    return keys().asSequence().associateWith { key -> getString(key) }
}

private fun <K> Map<K, String>.toJsonObject(keyName: (K) -> String): JSONObject = JSONObject().apply {
    entries.forEach { (key, value) -> put(keyName(key), value) }
}
