package com.cordiact.search.providers

import com.cordiact.core.search.SearchException
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.net.URI

data class DeepSeekWebConfig(
    val baseUrl: String = "https://api.deepseek.com/v1/tools",
    val apiKey: () -> String? = { null },
) {
    fun keyOrNull(): String? = apiKey()?.trim()?.takeIf { it.isNotEmpty() }

    fun available(): Boolean {
        val uri = runCatching { URI(baseUrl) }.getOrNull() ?: return false
        return (uri.scheme == "https" || uri.scheme == "http") && !uri.host.isNullOrBlank()
    }
}

internal fun deepSeekFailure(status: Int): SearchException = when (status) {
    401, 403 -> SearchException(SearchException.Code.CREDENTIAL_MISSING, "DeepSeek rejected the search credential")
    429 -> SearchException(SearchException.Code.RATE_LIMITED, "DeepSeek search is rate limited", retryable = true)
    in 300..399 -> SearchException(SearchException.Code.PROVIDER_ERROR, "DeepSeek search redirected unexpectedly")
    in 500..599 -> SearchException(SearchException.Code.PROVIDER_ERROR, "DeepSeek search is unavailable", retryable = true)
    else -> SearchException(SearchException.Code.PROVIDER_ERROR, "DeepSeek search failed with HTTP $status")
}

internal fun parseObject(body: String): JsonObject {
    val element: JsonElement = runCatching { JsonParser.parseString(body) }.getOrNull()
        ?: throw SearchException(SearchException.Code.INVALID_RESPONSE, "Provider returned invalid JSON")
    return element as? JsonObject
        ?: throw SearchException(SearchException.Code.INVALID_RESPONSE, "Provider returned a non-object JSON body")
}

internal fun JsonObject.stringOrNull(name: String): String? {
    val element = get(name) ?: return null
    if (element.isJsonNull) return null
    if (!element.isJsonPrimitive || !element.asJsonPrimitive.isString) return null
    return element.asString.takeIf { it.isNotEmpty() }
}
