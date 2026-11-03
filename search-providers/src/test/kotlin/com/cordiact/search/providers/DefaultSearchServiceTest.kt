package com.cordiact.search.providers

import com.cordiact.core.search.CancellationSignal
import com.cordiact.core.search.FetchProvider
import com.cordiact.core.search.FetchRequest
import com.cordiact.core.search.FetchResult
import com.cordiact.core.search.ModelRoute
import com.cordiact.core.search.SearchException
import com.cordiact.core.search.SearchMode
import com.cordiact.core.search.SearchPolicyStore
import com.cordiact.core.search.SearchProvider
import com.cordiact.core.search.SearchProviderRegistry
import com.cordiact.core.search.SearchRequest
import com.cordiact.core.search.SearchResult
import com.cordiact.core.search.SearchSource
import com.cordiact.core.search.WorkspaceSearchPolicy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DefaultSearchServiceTest {
    private val route = ModelRoute("deepseek", "deepseek-flash")

    private fun service(
        vararg providers: SearchProvider,
        policy: WorkspaceSearchPolicy = WorkspaceSearchPolicy(),
        fetchProviders: List<FetchProvider> = emptyList(),
    ): DefaultSearchService {
        val registry = SearchProviderRegistry()
        providers.forEach(registry::registerSearch)
        fetchProviders.forEach(registry::registerFetch)
        return DefaultSearchService(
            registry = registry,
            policies = object : SearchPolicyStore {
                override fun policy(workspaceId: String) = policy
            },
            maxQueries = 4,
            maxResults = 3,
            summaryMaxChars = 10,
            searchTimeoutMs = 2_000,
            fetchTimeoutMs = 2_000,
        )
    }

    @Test
    fun `auto prefers a native provider bound to the current route`() {
        val native = FakeProvider("native", native = true, routeProviderId = "deepseek")
        val external = FakeProvider("external")
        val result = service(native, external).search(
            "ws", route, listOf("q"), null, 3, 2_000,
        )
        assertEquals("native", result.sources.single().snippet)
    }

    @Test
    fun `auto uses the external provider when the model has no native search`() {
        val native = FakeProvider("native", native = true, routeProviderId = "openai")
        val external = FakeProvider("external")
        val result = service(native, external).search("ws", route, listOf("q"), null, 3, 2_000)
        assertEquals("external", result.sources.single().snippet)
    }

    @Test
    fun `pinned provider wins over native preference`() {
        val native = FakeProvider("native", native = true, routeProviderId = "deepseek")
        val external = FakeProvider("external")
        val result = service(
            native,
            external,
            policy = WorkspaceSearchPolicy(searchProviderId = "external"),
        ).search("ws", route, listOf("q"), null, 3, 2_000)
        assertEquals("external", result.sources.single().snippet)
    }

    @Test
    fun `multiple usable external providers without a pin are ambiguous`() {
        val failure = assertFailsWith<SearchException> {
            service(FakeProvider("a"), FakeProvider("b")).search("ws", route, listOf("q"), null, 3, 2_000)
        }
        assertEquals(SearchException.Code.PROVIDER_AMBIGUOUS, failure.code)
    }

    @Test
    fun `a pinned missing provider is unavailable`() {
        val failure = assertFailsWith<SearchException> {
            service(
                FakeProvider("a"),
                policy = WorkspaceSearchPolicy(searchProviderId = "missing"),
            ).search("ws", route, listOf("q"), null, 3, 2_000)
        }
        assertEquals(SearchException.Code.PROVIDER_UNAVAILABLE, failure.code)
    }

    @Test
    fun `a pinned unready provider is a credential error`() {
        val failure = assertFailsWith<SearchException> {
            service(
                FakeProvider("a", ready = false),
                policy = WorkspaceSearchPolicy(searchProviderId = "a"),
            ).search("ws", route, listOf("q"), null, 3, 2_000)
        }
        assertEquals(SearchException.Code.CREDENTIAL_MISSING, failure.code)
    }

    @Test
    fun `multi-query results merge round-robin and dedupe by url`() {
        val provider = FakeProvider(
            "a",
            perQuery = mapOf(
                "a" to listOf(source("u1", "a"), source("u2", "a")),
                "b" to listOf(source("u2", "b"), source("u3", "b"), source("u4", "b")),
            ),
        )
        val result = service(provider).search("ws", route, listOf("a", "b"), null, 3, 2_000)
        assertEquals(
            listOf("https://u1.example", "https://u2.example", "https://u3.example"),
            result.sources.map { it.url },
        )
        assertTrue(result.truncated)
    }

    @Test
    fun `oversized query lists are rejected before dispatch`() {
        val failure = assertFailsWith<SearchException> {
            service(FakeProvider("a")).search("ws", route, listOf("1", "2", "3", "4", "5"), null, 3, 2_000)
        }
        assertEquals(SearchException.Code.INVALID_ARGUMENT, failure.code)
    }

    @Test
    fun `a slow provider times out`() {
        val provider = FakeProvider("slow", delayMs = 5_000)
        val failure = assertFailsWith<SearchException> {
            service(provider).search("ws", route, listOf("q"), null, 3, 50)
        }
        assertEquals(SearchException.Code.TIMED_OUT, failure.code)
    }

    @Test
    fun `visibility hides a pinned provider that cannot authenticate`() {
        assertFalse(service(FakeProvider("a", ready = false), policy = WorkspaceSearchPolicy(searchProviderId = "a")).shouldExposeSearch("ws", route))
    }

    @Test
    fun `visibility keeps a missing pinned provider visible for a setup error`() {
        assertTrue(service(FakeProvider("a"), policy = WorkspaceSearchPolicy(searchProviderId = "missing")).shouldExposeSearch("ws", route))
    }

    @Test
    fun `visibility hides search when no provider can authenticate`() {
        assertFalse(service(FakeProvider("a", ready = false)).shouldExposeSearch("ws", route))
    }

    @Test
    fun `visibility is false when the workspace turned search off`() {
        assertFalse(service(FakeProvider("a"), policy = WorkspaceSearchPolicy(mode = SearchMode.OFF)).shouldExposeSearch("ws", route))
    }

    private class FakeProvider(
        override val id: String,
        override val native: Boolean = false,
        override val routeProviderId: String? = null,
        private val ready: Boolean = true,
        private val delayMs: Long = 0,
        private val perQuery: Map<String, List<SearchSource>> = emptyMap(),
    ) : SearchProvider {
        override fun available(): Boolean = ready
        override fun ready(route: ModelRoute?): Boolean = ready
        override fun search(request: SearchRequest, signal: CancellationSignal): SearchResult {
            if (delayMs > 0) Thread.sleep(delayMs)
            val sources = perQuery[request.query] ?: listOf(source("u-$id", id))
            return SearchResult(sources = sources)
        }
    }

    private companion object {
        fun source(url: String, snippet: String) = SearchSource(url = "https://$url.example", snippet = snippet)
    }
}
