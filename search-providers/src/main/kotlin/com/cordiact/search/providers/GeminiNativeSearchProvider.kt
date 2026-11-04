package com.cordiact.search.providers

import com.cordiact.core.search.CancellationSignal
import com.cordiact.core.search.ModelRoute
import com.cordiact.core.search.SearchException
import com.cordiact.core.search.SearchProvider
import com.cordiact.core.search.SearchRequest
import com.cordiact.core.search.SearchResult
import com.cordiact.core.search.SearchSource
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import java.net.URI

/**
 * Gemini `google_search` grounding, run as one auxiliary generateContent call.
 *
 * Grounding returns `groundingMetadata.groundingChunks[].web` for the sources and
 * `groundingSupports[].segment.text` for the excerpt that each chunk backs. A chunk
 * may be cited by several supports; the first non-blank segment wins as the snippet.
 */
class GeminiNativeSearchProvider(
    private val apiKey: () -> String?,
    private val baseUrlTemplate: () -> String = { DEFAULT_BASE_URL },
    private val client: HttpJsonClient = HttpJsonClient(),
) : SearchProvider {
    override val id: String = PROVIDER_ID
    override val native: Boolean = true
    override val routeProviderId: String = MODEL_PROVIDER_ID

    override fun available(): Boolean {
        val endpoint = runCatching { URI(baseUrlTemplate().replace("{model}", "x")) }.getOrNull() ?: return false
        return (endpoint.scheme == "https" || endpoint.scheme == "http") &&
            !endpoint.host.isNullOrBlank() &&
            baseUrlTemplate().contains("{model}") &&
            apiKey()?.isNotBlank() == true
    }

    override fun ready(route: ModelRoute?): Boolean =
        route?.providerId == MODEL_PROVIDER_ID && available()

    override fun search(request: SearchRequest, signal: CancellationSignal): SearchResult {
        val key = apiKey()?.takeIf { it.isNotBlank() }
            ?: throw SearchException(SearchException.Code.CREDENTIAL_MISSING, "Gemini credential is missing")
        if (!available()) {
            throw SearchException(SearchException.Code.PROVIDER_UNAVAILABLE, "Gemini native search is not configured")
        }
        val model = request.route?.modelId?.takeIf { it.isNotBlank() }
            ?: throw SearchException(SearchException.Code.UNSUPPORTED, "Gemini model is unknown for this search")
        val payload = JsonObject().apply {
            add("contents", JsonArray().apply {
                add(JsonObject().apply {
                    addProperty("role", "user")
                    add("parts", JsonArray().apply {
                        add(JsonObject().apply {
                            addProperty("text", "Perform a web search for the query: " + request.query)
                        })
                    })
                })
            })
            add("tools", JsonArray().apply {
                add(JsonObject().apply { add("google_search", JsonObject()) })
            })
        }
        val url = baseUrlTemplate().replace("{model}", model)
        val response = client.postJson(
            url = url,
            headers = mapOf(
                "x-goog-api-key" to key,
                "content-type" to "application/json",
                "accept" to "application/json",
            ),
            body = payload.toString(),
            timeoutMs = SEARCH_TIMEOUT_MS,
            signal = signal,
        )
        if (response.status !in 200..299) throw nativeFailure(response.status)
        return mapResponse(response.body)
    }

    companion object {
        const val PROVIDER_ID = "gemini-native"
        const val MODEL_PROVIDER_ID = "google"
        const val DEFAULT_BASE_URL = "https://generativelanguage.googleapis.com/v1beta/models/{model}:generateContent"
        private const val SEARCH_TIMEOUT_MS = 60_000L

        private fun nativeFailure(status: Int): SearchException = when (status) {
            401, 403 -> SearchException(SearchException.Code.CREDENTIAL_MISSING, "Gemini rejected the credential")
            429 -> SearchException(SearchException.Code.RATE_LIMITED, "Gemini is rate limited", retryable = true)
            in 500..599 -> SearchException(SearchException.Code.PROVIDER_ERROR, "Gemini is unavailable", retryable = true)
            else -> SearchException(SearchException.Code.PROVIDER_ERROR, "Gemini search failed with HTTP " + status)
        }

        fun mapResponse(body: String): SearchResult {
            val root = parseObject(body)
            val candidates = root.get("candidates") as? JsonArray
                ?: throw SearchException(SearchException.Code.INVALID_RESPONSE, "Gemini returned no candidates array")
            val candidate = candidates.firstOrNull() as? JsonObject
                ?: throw SearchException(SearchException.Code.INVALID_RESPONSE, "Gemini returned no candidate")

            val answer = (candidate.get("content") as? JsonObject)
                ?.get("parts") as? JsonArray
            val answerText = answer
                ?.mapNotNull { (it as? JsonObject)?.stringOrNull("text") }
                ?.joinToString("")
                ?.takeIf { it.isNotBlank() }

            val metadata = candidate.get("groundingMetadata") as? JsonObject
                ?: return SearchResult(content = answerText, sources = emptyList())
            val chunks = metadata.get("groundingChunks") as? JsonArray ?: JsonArray()

            // url -> excerpt, taken from the first support segment that cites that chunk.
            val excerpts = linkedMapOf<String, String>()
            (metadata.get("groundingSupports") as? JsonArray)?.forEach { supportElement ->
                val support = supportElement as? JsonObject ?: return@forEach
                val text = (support.get("segment") as? JsonObject)?.stringOrNull("text")
                    ?.takeIf { it.isNotBlank() } ?: return@forEach
                val indices = support.get("groundingChunkIndices") as? JsonArray ?: return@forEach
                for (indexElement in indices) {
                    if (!indexElement.isJsonPrimitive) continue
                    val index = indexElement.asInt
                    if (index < 0 || index >= chunks.size()) continue
                    val url = (chunks[index] as? JsonObject)
                        ?.get("web") as? JsonObject
                    val uri = url?.stringOrNull("uri") ?: continue
                    excerpts.putIfAbsent(uri, text)
                }
            }

            val sources = chunks.mapNotNull { element ->
                val web = (element as? JsonObject)?.get("web") as? JsonObject ?: return@mapNotNull null
                val url = web.stringOrNull("uri") ?: return@mapNotNull null
                if (!url.startsWith("https://") && !url.startsWith("http://")) return@mapNotNull null
                SearchSource(url = url, title = web.stringOrNull("title"), snippet = excerpts[url])
            }.distinctBy { it.url }

            return SearchResult(content = answerText, sources = sources, truncated = false)
        }
    }
}
