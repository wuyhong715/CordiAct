package com.cordiact.search.providers

import com.google.gson.JsonArray
import com.google.gson.JsonObject

/**
 * Request bodies for the native search calls.
 *
 * Kept in one file so the vendor differences are visible side by side, and so each
 * body can be asserted in a test without going through a provider instance.
 */
internal object NativeSearchPayloads {
    /** Anthropic and DeepSeek share this shape; only the endpoint and headers differ. */
    fun anthropicStyle(
        model: String,
        query: String,
        options: NativeSearchOptions,
    ): JsonObject = JsonObject().apply {
        addProperty("model", model)
        addProperty("max_tokens", options.maxTokens)
        add("messages", JsonArray().apply {
            add(JsonObject().apply {
                addProperty("role", "user")
                add("content", JsonArray().apply {
                    add(JsonObject().apply {
                        addProperty("type", "text")
                        addProperty("text", "Perform a web search for the query: " + query)
                    })
                })
            })
        })
        add("tools", JsonArray().apply {
            add(JsonObject().apply {
                addProperty("type", "web_search_20250305")
                addProperty("name", "web_search")
                addProperty("max_uses", options.maxUses)
                if (options.allowedDomains.isNotEmpty()) {
                    add("allowed_domains", JsonArray().apply { options.allowedDomains.forEach(::add) })
                }
                if (options.blockedDomains.isNotEmpty()) {
                    add("blocked_domains", JsonArray().apply { options.blockedDomains.forEach(::add) })
                }
                options.userLocation?.toAnthropic()?.let { add("user_location", it) }
            })
        })
        add("thinking", JsonObject().apply { addProperty("type", "disabled") })
    }

    fun openAiResponses(model: String, query: String, options: NativeSearchOptions): JsonObject =
        JsonObject().apply {
            addProperty("model", model)
            addProperty("input", "Perform a web search for the query: " + query)
            add("tools", JsonArray().apply {
                add(JsonObject().apply {
                    addProperty("type", "web_search")
                    addProperty("search_context_size", options.searchContextSize)
                    options.userLocation?.toOpenAiApproximate()?.let { add("user_location", it) }
                })
            })
            addProperty("tool_choice", "auto")
        }

    fun gemini(query: String, options: NativeSearchOptions): JsonObject = JsonObject().apply {
        add("contents", JsonArray().apply {
            add(JsonObject().apply {
                addProperty("role", "user")
                add("parts", JsonArray().apply {
                    add(JsonObject().apply {
                        addProperty("text", "Perform a web search for the query: " + query)
                    })
                })
            })
        })
        add("tools", JsonArray().apply {
            add(JsonObject().apply { add("google_search", JsonObject()) })
            // url_context is a separate tool in the same array; the API rejects a
            // google_search-only request that also asks for url_context elsewhere.
            if (options.geminiUrlContext) {
                add(JsonObject().apply { add("url_context", JsonObject()) })
            }
        })
    }

    private fun SearchUserLocation.toAnthropic(): JsonObject = JsonObject().apply {
        addProperty("type", "approximate")
        city?.let { addProperty("city", it) }
        region?.let { addProperty("region", it) }
        country?.let { addProperty("country", it) }
        timezone?.let { addProperty("timezone", it) }
    }

    private fun SearchUserLocation.toOpenAiApproximate(): JsonObject = JsonObject().apply {
        addProperty("type", "approximate")
        city?.let { addProperty("city", it) }
        region?.let { addProperty("region", it) }
        country?.let { addProperty("country", it) }
        timezone?.let { addProperty("timezone", it) }
    }
}
