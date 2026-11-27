package com.cordiact.search.providers

import com.cordiact.core.plugin.ContextPlugin
import com.cordiact.core.plugin.ExtensionPackageSource
import com.cordiact.core.plugin.PackageScriptRuntime
import com.cordiact.core.plugin.PackageScriptSession
import com.cordiact.core.plugin.PackageSearchContributions
import com.cordiact.core.plugin.PluginContext
import com.cordiact.core.plugin.PluginInstanceId
import com.cordiact.core.plugin.PluginKernel
import com.cordiact.core.search.SearchException
import com.cordiact.core.search.SearchMode
import com.cordiact.core.search.SearchPolicyStore
import com.cordiact.core.search.SearchProviderRegistry
import com.cordiact.core.search.SearchRequest
import com.cordiact.core.search.WorkspaceSearchPolicy
import com.google.gson.JsonParser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PackageSearchProviderTest {
    @Test
    fun `scope and policy select the live adapter and withdrawal invalidates old references`() {
        val directory = SearchProviderRegistry()
        val contributions = PackageSearchContributions(directory)
        val kernel = PluginKernel()
        val manifest = ExtensionPackageSource.compile(MANIFEST.toByteArray())
        val closed = mutableSetOf<String>()
        var invalidResponse = false
        var withdrawDuringCall = false
        var admitted = true
        val runtime = PackageScriptRuntime { owner ->
            object : PackageScriptSession {
                override fun evaluate(source: ByteArray, input: ByteArray): ByteArray {
                    assertEquals(manifest.searchProviders.single().script, source.decodeToString())
                    val request = JsonParser.parseString(input.decodeToString()).asJsonObject
                    assertEquals(setOf("query", "maxResults"), request.keySet())
                    val url = if (invalidResponse) "file:///private" else "https://example.test/${owner.workspaceId}"
                    if (withdrawDuringCall) admitted = false
                    return """{"sources":[{"url":"$url","title":"Example"}]}""".toByteArray()
                }
                override fun close() { closed += requireNotNull(owner.workspaceId) }
            }
        }
        fun install(workspace: String): PluginInstanceId {
            val owner = PluginInstanceId("pkg.reference-search", workspace)
            kernel.register(owner, object : ContextPlugin {
                override fun start(context: PluginContext) = contributions.register(context, manifest, runtime) { workspace != "alpha" || admitted }
            })
            kernel.enable(owner)
            return owner
        }
        val alpha = install("alpha")
        val stale = assertNotNull(directory.searchProvider(ID, "alpha"))
        assertNull(directory.searchProvider(ID))
        assertNull(directory.searchProvider(ID, "beta"))
        assertFalse(directory.searchProviders("beta").any { it.id == ID })
        val beta = install("beta")
        val policies = mutableMapOf<String, WorkspaceSearchPolicy>()
        val store = object : SearchPolicyStore {
            override fun policy(workspaceId: String) = policies[workspaceId] ?: WorkspaceSearchPolicy(SearchMode.TOOL)
        }
        DefaultSearchService(directory, store).use { service ->
            fun search(workspace: String) = service.search(workspace, null, listOf("docs"), "private question", 2, 2_000)
            assertEquals(SearchException.Code.PROVIDER_UNAVAILABLE, assertFailsWith<SearchException> { search("alpha") }.code)
            policies["alpha"] = WorkspaceSearchPolicy(SearchMode.TOOL, providerOrder = listOf(ID))
            policies["beta"] = WorkspaceSearchPolicy(SearchMode.TOOL, searchProviderId = ID)
            assertEquals("https://example.test/alpha", search("alpha").sources.single().url)
            assertEquals("https://example.test/beta", search("beta").sources.single().url)
            invalidResponse = true
            assertEquals(SearchException.Code.INVALID_RESPONSE, assertFailsWith<SearchException> { search("alpha") }.code)
            invalidResponse = false
            withdrawDuringCall = true
            assertEquals(SearchException.Code.CANCELLED, assertFailsWith<SearchException> { search("alpha") }.code)
            withdrawDuringCall = false
            kernel.disable(alpha)
            assertEquals(setOf("alpha"), closed)
            assertNull(directory.searchProvider(ID, "alpha"))
            assertEquals(SearchException.Code.CANCELLED, assertFailsWith<SearchException> { stale.search(SearchRequest("docs", 1)) }.code)
            assertEquals("https://example.test/beta", search("beta").sources.single().url)
            kernel.disable(beta)
            assertTrue("beta" in closed)
        }
    }

    @Test
    fun `search declaration and grant must agree with the schema`() {
        val parsed = ExtensionPackageSource.compile(MANIFEST.toByteArray())
        assertEquals("docs", parsed.searchProviders.single().name)
        assertFailsWith<IllegalArgumentException> { ExtensionPackageSource.compile(MANIFEST.replace("\"schemaVersion\":6", "\"schemaVersion\":5").toByteArray()) }
        assertFailsWith<IllegalArgumentException> { ExtensionPackageSource.compile(MANIFEST.replace("\"provider.search.publish\"", "").toByteArray()) }
    }

    private companion object {
        const val ID = "pkg.reference-search.docs"
        val MANIFEST = """{
            "schemaVersion":6,"id":"reference-search","version":"1.0.0","title":"Reference search","description":"Search fixtures",
            "host":{"api":1,"minVersion":"0.1.0"},"capabilities":["provider.search.publish"],"tools":[],"commands":[],
            "providers":{"search":[{"name":"docs","title":"Reference docs","source":"(input) => input"}]}
        }""".trimIndent()
    }
}
