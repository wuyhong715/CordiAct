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

    @Test
    fun `the same page under different tracking parameters is deduplicated`() {
        val provider = FakeProvider(
            "a",
            perQuery = mapOf(
                "a" to listOf(
                    SearchSource(url = "https://www.example.com/page/?utm_source=x&id=7", snippet = "first"),
                    SearchSource(url = "https://example.com/page?id=7&fbclid=abc", snippet = "second"),
                ),
            ),
        )
        val result = service(provider).search("ws", route, listOf("a"), null, 8, 2_000)
        assertEquals(1, result.sources.size)
        assertEquals("first", result.sources.single().snippet, "the first citation wins")
    }

    @Test
    fun `different query parameters are not merged`() {
        val provider = FakeProvider(
            "a",
            perQuery = mapOf(
                "a" to listOf(
                    SearchSource(url = "https://example.com/page?id=1", snippet = "one"),
                    SearchSource(url = "https://example.com/page?id=2", snippet = "two"),
                ),
            ),
        )
        val result = service(provider).search("ws", route, listOf("a"), null, 8, 2_000)
        assertEquals(2, result.sources.size)
    }

    @Test
    fun `dedupeKey normalises host case slash and fragment`() {
        val upper = DefaultSearchService.dedupeKey("https://EXAMPLE.com/a/b?x=1")
        val messy = DefaultSearchService.dedupeKey("https://www.example.com/a/b/?x=1#section")
        assertEquals(upper, messy)
    }

    @Test
    fun `a four-query fan-out spends four billable calls`() {
        val recorded = mutableListOf<Int>()
        val service = DefaultSearchService(
            registry = SearchProviderRegistry().apply { registerSearch(FakeProvider("a", billable = true)) },
            policies = object : SearchPolicyStore {
                override fun policy(workspaceId: String) = WorkspaceSearchPolicy(monthlyBudgetCalls = 10)
            },
            usage = object : com.cordiact.core.search.SearchUsageStore {
                override fun usedThisMonth(workspaceId: String) = recorded.sum()
                override fun record(workspaceId: String, calls: Int) { recorded += calls }
            },
            maxQueries = 4,
            maxResults = 3,
            summaryMaxChars = 10,
            searchTimeoutMs = 2_000,
            fetchTimeoutMs = 2_000,
        )
        service.search("ws", route, listOf("a", "b", "c", "d"), null, 3, 2_000)
        assertEquals(listOf(4), recorded)
    }

    @Test
    fun `a fan-out larger than the remaining budget is refused before dispatch`() {
        val recorded = mutableListOf<Int>()
        var dispatched = false
        val provider = object : SearchProvider {
            override val id = "a"
            override val billable = true
            override fun available() = true
            override fun ready(route: ModelRoute?) = true
            override fun search(request: SearchRequest, signal: CancellationSignal): SearchResult {
                dispatched = true
                return SearchResult()
            }
        }
        val service = DefaultSearchService(
            registry = SearchProviderRegistry().apply { registerSearch(provider) },
            policies = object : SearchPolicyStore {
                override fun policy(workspaceId: String) = WorkspaceSearchPolicy(monthlyBudgetCalls = 10)
            },
            usage = object : com.cordiact.core.search.SearchUsageStore {
                override fun usedThisMonth(workspaceId: String) = 8
                override fun record(workspaceId: String, calls: Int) { recorded += calls }
            },
            maxQueries = 4,
            maxResults = 3,
            summaryMaxChars = 10,
            searchTimeoutMs = 2_000,
            fetchTimeoutMs = 2_000,
        )
        val failure = assertFailsWith<SearchException> {
            service.search("ws", route, listOf("a", "b", "c"), null, 3, 2_000)
        }
        assertEquals(SearchException.Code.BUDGET_EXHAUSTED, failure.code)
        assertTrue(failure.message!!.contains("needs 3"))
        assertEquals(false, dispatched, "no provider call may happen after the budget check fails")
        assertTrue(recorded.isEmpty())
    }

    @Test
    fun `an exhausted monthly budget fails loudly before dispatch`() {
        val usage = object : com.cordiact.core.search.SearchUsageStore {
            var used = 2
            override fun usedThisMonth(workspaceId: String) = used
            override fun record(workspaceId: String, calls: Int) { used += calls }
        }
        val service = DefaultSearchService(
            registry = SearchProviderRegistry().apply { registerSearch(FakeProvider("a", billable = true)) },
            policies = object : SearchPolicyStore {
                override fun policy(workspaceId: String) = WorkspaceSearchPolicy(monthlyBudgetCalls = 2)
            },
            usage = usage,
            maxQueries = 4,
            maxResults = 3,
            summaryMaxChars = 10,
            searchTimeoutMs = 2_000,
            fetchTimeoutMs = 2_000,
        )
        val failure = assertFailsWith<SearchException> {
            service.search("ws", route, listOf("q"), null, 3, 2_000)
        }
        assertEquals(SearchException.Code.BUDGET_EXHAUSTED, failure.code)
        assertEquals(2, usage.used)
    }

    @Test
    fun `a billable search records one call and reports its cost`() {
        val recorded = mutableListOf<Int>()
        val service = DefaultSearchService(
            registry = SearchProviderRegistry().apply { registerSearch(FakeProvider("a", billable = true)) },
            policies = object : SearchPolicyStore {
                override fun policy(workspaceId: String) = WorkspaceSearchPolicy(monthlyBudgetCalls = 10)
            },
            usage = object : com.cordiact.core.search.SearchUsageStore {
                override fun usedThisMonth(workspaceId: String) = recorded.sum()
                override fun record(workspaceId: String, calls: Int) { recorded += calls }
            },
            maxQueries = 4,
            maxResults = 3,
            summaryMaxChars = 10,
            searchTimeoutMs = 2_000,
            fetchTimeoutMs = 2_000,
        )
        val result = service.search("ws", route, listOf("q"), null, 3, 2_000)
        assertEquals(listOf(1), recorded)
        assertEquals(0.01, result.estimatedCostUsd)
    }

    // --- B10: providerOrder / enabledProviders / TOOL visibility / AUTO fallback ---

    @Test
    fun `a declared provider order picks the first ready third party provider`() {
        val a = FakeProvider("a")
        val b = FakeProvider("b")
        val firstB = service(a, b, policy = WorkspaceSearchPolicy(providerOrder = listOf("b", "a")))
            .search("ws", route, listOf("q"), null, 3, 2_000)
        assertEquals("b", firstB.sources.single().snippet)
        assertEquals("b", firstB.providerId)
        val firstA = service(a, b, policy = WorkspaceSearchPolicy(providerOrder = listOf("a", "b")))
            .search("ws", route, listOf("q"), null, 3, 2_000)
        assertEquals("a", firstA.sources.single().snippet)
    }

    @Test
    fun `an ordered provider that is not ready is skipped for the next one`() {
        val broken = FakeProvider("broken", ready = false)
        val working = FakeProvider("working")
        val result = service(broken, working, policy = WorkspaceSearchPolicy(providerOrder = listOf("broken", "working")))
            .search("ws", route, listOf("q"), null, 3, 2_000)
        assertEquals("working", result.sources.single().snippet)
    }

    @Test
    fun `an order without any usable entry is an unavailable error not an arbitrary pick`() {
        val failure = assertFailsWith<SearchException> {
            service(FakeProvider("a"), policy = WorkspaceSearchPolicy(providerOrder = listOf("missing")))
                .search("ws", route, listOf("q"), null, 3, 2_000)
        }
        assertEquals(SearchException.Code.PROVIDER_UNAVAILABLE, failure.code)
    }

    @Test
    fun `a provider outside enabledProviders is never selected and removes the ambiguity`() {
        val result = service(
            FakeProvider("a"),
            FakeProvider("b"),
            policy = WorkspaceSearchPolicy(enabledProviders = setOf("b")),
        ).search("ws", route, listOf("q"), null, 3, 2_000)
        assertEquals("b", result.sources.single().snippet)
    }

    @Test
    fun `an empty enabledProviders set closes every third party provider`() {
        val failure = assertFailsWith<SearchException> {
            service(FakeProvider("a"), policy = WorkspaceSearchPolicy(enabledProviders = emptySet()))
                .search("ws", route, listOf("q"), null, 3, 2_000)
        }
        assertEquals(SearchException.Code.PROVIDER_UNAVAILABLE, failure.code)
    }

    @Test
    fun `a pinned but disabled provider is rejected and does not fall back`() {
        val failure = assertFailsWith<SearchException> {
            service(
                FakeProvider("a"),
                FakeProvider("b"),
                policy = WorkspaceSearchPolicy(searchProviderId = "a", enabledProviders = setOf("b")),
            ).search("ws", route, listOf("q"), null, 3, 2_000)
        }
        assertEquals(SearchException.Code.UNSUPPORTED, failure.code)
    }

    @Test
    fun `visibility hides a pinned but disabled provider`() {
        assertFalse(
            service(
                FakeProvider("a"),
                FakeProvider("b"),
                policy = WorkspaceSearchPolicy(searchProviderId = "a", enabledProviders = setOf("b")),
            ).shouldExposeSearch("ws", route),
        )
    }

    @Test
    fun `auto still prefers native search over an ordered third party list`() {
        val native = FakeProvider("native", native = true, routeProviderId = "deepseek")
        val result = service(
            native,
            FakeProvider("external"),
            policy = WorkspaceSearchPolicy(providerOrder = listOf("external")),
        ).search("ws", route, listOf("q"), null, 3, 2_000)
        assertEquals("native", result.sources.single().snippet)
    }

    @Test
    fun `tool mode never exposes or selects native search`() {
        val native = FakeProvider("native", native = true, routeProviderId = "deepseek")
        val toolService = service(native, policy = WorkspaceSearchPolicy(mode = SearchMode.TOOL))
        assertFalse(toolService.shouldExposeSearch("ws", route))
        val failure = assertFailsWith<SearchException> {
            toolService.search("ws", route, listOf("q"), null, 3, 2_000)
        }
        assertEquals(SearchException.Code.PROVIDER_UNAVAILABLE, failure.code)
    }

    @Test
    fun `an auto native failure falls back to the third party provider once`() {
        val native = FakeProvider(
            "native",
            native = true,
            routeProviderId = "deepseek",
            failWith = SearchException(SearchException.Code.PROVIDER_ERROR, "native exploded"),
        )
        val result = service(native, FakeProvider("external"))
            .search("ws", route, listOf("q"), null, 3, 2_000)
        assertEquals("external", result.sources.single().snippet)
        assertEquals("external", result.providerId)
    }

    @Test
    fun `an auto native failure does not fall back when the failure is a cancellation`() {
        val native = FakeProvider(
            "native",
            native = true,
            routeProviderId = "deepseek",
            failWith = SearchException(SearchException.Code.CANCELLED, "cancelled"),
        )
        val failure = assertFailsWith<SearchException> {
            service(native, FakeProvider("external")).search("ws", route, listOf("q"), null, 3, 2_000)
        }
        assertEquals(SearchException.Code.CANCELLED, failure.code)
    }

    private class FakeProvider(
        override val id: String,
        override val native: Boolean = false,
        override val routeProviderId: String? = null,
        private val ready: Boolean = true,
        private val delayMs: Long = 0,
        private val perQuery: Map<String, List<SearchSource>> = emptyMap(),
        override val billable: Boolean = false,
        private val failWith: SearchException? = null,
    ) : SearchProvider {
        override val pricing = com.cordiact.core.search.SearchPricing(usdPerCall = if (billable) 0.01 else null)
        override fun available(): Boolean = ready
        override fun ready(route: ModelRoute?): Boolean = ready
        override fun search(request: SearchRequest, signal: CancellationSignal): SearchResult {
            if (delayMs > 0) Thread.sleep(delayMs)
            failWith?.let { throw it }
            val sources = perQuery[request.query] ?: listOf(source("u-$id", id))
            return SearchResult(sources = sources)
        }
    }

    private companion object {
        fun source(url: String, snippet: String) = SearchSource(url = "https://$url.example", snippet = snippet)
    }
}
