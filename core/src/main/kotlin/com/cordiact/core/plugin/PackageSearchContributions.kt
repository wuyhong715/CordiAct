package com.cordiact.core.plugin

import com.cordiact.core.search.CancellationSignal
import com.cordiact.core.search.ModelRoute
import com.cordiact.core.search.SearchException
import com.cordiact.core.search.SearchProvider
import com.cordiact.core.search.SearchProviderRegistry
import com.cordiact.core.search.SearchRequest
import com.cordiact.core.search.SearchResult
import com.cordiact.core.search.SearchSource
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.Strictness
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import java.io.StringReader
import java.net.URI
import java.nio.ByteBuffer
import java.util.concurrent.locks.ReentrantLock

/** Maps package scripts to the same typed search service consumed by tool and settings routes. */
class PackageSearchContributions(private val registry: SearchProviderRegistry) {
    fun register(context: PluginContext, manifest: ExtensionPackageManifest, runtime: PackageScriptRuntime, admitted: () -> Boolean) {
        val owner = context.id
        require(owner.pluginId == extensionPackageSpec(manifest).id && owner.workspaceId != null && owner.sessionId == null)
        require(ExtensionPackageCapability.SEARCH_PROVIDER_PUBLISH in manifest.capabilities)
        manifest.searchProviders.forEach { declaration ->
            val session = context.scope.own(runtime.open(owner))
            registry.registerSearch(context, ScriptSearchProvider("${owner.pluginId}.${declaration.name}", declaration, session), admitted)
        }
    }
}

private class ScriptSearchProvider(
    override val id: String,
    declaration: ExtensionPackageSearchProvider,
    private val session: PackageScriptSession,
) : SearchProvider {
    override val displayName = declaration.title
    private val source = declaration.script.toByteArray(Charsets.UTF_8)
    private val calls = ReentrantLock()

    override fun available() = true
    override fun ready(route: ModelRoute?) = true

    override fun search(request: SearchRequest, signal: CancellationSignal): SearchResult {
        checkActive(signal)
        val limit = request.maxResults.coerceIn(1, 8)
        val input = JsonObject().apply {
            addProperty("query", request.query)
            addProperty("maxResults", limit)
        }
        val frame = try { encodeScriptInput(input.toString()) } catch (_: Exception) {
            throw SearchException(SearchException.Code.INVALID_ARGUMENT, "Search request is too large for the plugin")
        }
        val output = try {
            calls.lockInterruptibly()
            try {
                checkActive(signal)
                session.evaluate(source.copyOf(), frame)
            } finally { calls.unlock() }
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            throw cancelled()
        } catch (_: Exception) {
            checkActive(signal)
            throw SearchException(SearchException.Code.PROVIDER_ERROR, "Plugin search provider failed")
        }
        checkActive(signal)
        return try { decodeSearch(output, limit) } catch (_: Exception) {
            throw SearchException(SearchException.Code.INVALID_RESPONSE, "Plugin search provider returned an invalid result")
        }
    }

    private fun checkActive(signal: CancellationSignal) {
        if (signal.isCancelled() || Thread.currentThread().isInterrupted) throw cancelled()
    }

    private fun cancelled() = SearchException(SearchException.Code.CANCELLED, "Plugin search provider was cancelled")
}

private fun decodeSearch(bytes: ByteArray, limit: Int): SearchResult {
    require(bytes.size in 1..PACKAGE_SCRIPT_MAX_TEXT_BYTES)
    val text = Charsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString()
    val root = JsonReader(StringReader(text)).use { reader ->
        reader.strictness = Strictness.STRICT
        JsonParser.parseReader(reader).asJsonObject.also { require(reader.peek() == JsonToken.END_DOCUMENT) }
    }
    require(root.keySet().all { it == "sources" || it == "content" })
    val sources = requireNotNull(root.getAsJsonArray("sources"))
    require(sources.size() <= limit)
    return SearchResult(content = root.string("content"), sources = sources.map { element ->
        val source = element.asJsonObject
        require(source.keySet().all { it == "url" || it == "title" || it == "snippet" })
        val url = URI(requireNotNull(source.string("url")))
        require(url.scheme in setOf("https", "http") && url.host != null && url.rawUserInfo == null)
        SearchSource(url.toASCIIString(), title = source.string("title"), snippet = source.string("snippet"))
    })
}

private fun JsonObject.string(key: String): String? = get(key)?.let { value ->
    require(value.isJsonPrimitive && value.asJsonPrimitive.isString)
    value.asString
}
