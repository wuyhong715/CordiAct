package com.cordiact.search.providers

import com.cordiact.core.plugin.ContextPlugin
import com.cordiact.core.plugin.PluginContext
import com.cordiact.core.plugin.PluginInstanceId
import com.cordiact.core.plugin.PluginInstanceState
import com.cordiact.core.plugin.PluginKernel
import com.cordiact.core.search.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*

class SearchProviderLifecycleTest {
    private val directory = SearchProviderRegistry()
    private val kernel = PluginKernel()

    private fun provider(key: String, block: (SearchRequest, CancellationSignal) -> SearchResult) = object : SearchProvider {
        override val id = key
        override fun available() = true
        override fun ready(route: ModelRoute?) = true
        override fun search(request: SearchRequest, signal: CancellationSignal) = block(request, signal)
    }

    private fun install(id: String, adapter: SearchProvider, failStart: Boolean = false): PluginInstanceId {
        val owner = PluginInstanceId(id)
        kernel.register(owner, object : ContextPlugin {
            override fun start(context: PluginContext) {
                directory.registerSearch(context, adapter)
                if (failStart) error("fail before commit")
            }
        })
        return owner
    }

    private fun client(policy: WorkspaceSearchPolicy, expected: SearchProvider? = null) = DefaultSearchService(
        directory, object : SearchPolicyStore { override fun policy(workspaceId: String) = policy },
        searchTimeoutMs = 2_000, expectedSearchProvider = expected,
    )

    private fun DefaultSearchService.query() = search("workspace", null, listOf("local fixture"), null, 2, 2_000)

    @Test
    fun `tool and settings consumers use one directory and explicit preferences`() {
        val callsA = AtomicInteger()
        val callsB = AtomicInteger()
        val a = install("builtin.a", provider("a") { _, _ -> callsA.incrementAndGet(); SearchResult("Result A") })
        val b = install("builtin.b", provider("b") { _, _ -> callsB.incrementAndGet(); SearchResult("Result B") })
        val toolPolicy = WorkspaceSearchPolicy(mode = SearchMode.TOOL, providerOrder = listOf("a", "b"))
        fun probe(id: String): String? {
            val selected = requireNotNull(directory.searchProvider(id))
            check(selected.ready(null))
            return client(toolPolicy.copy(searchProviderId = id), selected).use { it.query().providerId }
        }
        client(toolPolicy).use { tool ->
            assertFailsWith<SearchException> { tool.query() }
            kernel.enable(a)
            kernel.enable(b)
            assertEquals("a", tool.query().providerId)
            assertEquals("a", probe("a"))
            val old = requireNotNull(directory.searchProvider("a"))
            kernel.disable(a)
            assertFalse(old.ready(null))
            assertNull(directory.searchProvider("a"))
            assertEquals("b", tool.query().providerId)
            assertEquals("b", probe("b"))
            kernel.enable(a)
            client(toolPolicy.copy(searchProviderId = "a"), old).use {
                assertEquals(SearchException.Code.CANCELLED, assertFailsWith<SearchException> { it.query() }.code)
            }
            assertEquals(2, callsA.get())
            assertEquals(2, callsB.get())
        }
    }

    @Test
    fun `withdrawal cancels in-flight output and never restarts against a new provider`() {
        val entered = CountDownLatch(1)
        val returnResult = CountDownLatch(1)
        val replacementCalls = AtomicInteger()
        val active = install("builtin.a", provider("same") { _, signal ->
            entered.countDown()
            check(returnResult.await(3, TimeUnit.SECONDS))
            check(signal.isCancelled())
            SearchResult("late result")
        })
        val replacement = install("builtin.b", provider("same") { _, _ -> replacementCalls.incrementAndGet(); SearchResult("replacement") })
        kernel.enable(active)
        client(WorkspaceSearchPolicy(searchProviderId = "same")).use { tool ->
            val executor = Executors.newSingleThreadExecutor()
            try {
                val result = executor.submit<SearchException.Code> { assertFailsWith<SearchException> { tool.query() }.code }
                assertTrue(entered.await(1, TimeUnit.SECONDS))
                kernel.disable(active)
                kernel.enable(replacement)
                returnResult.countDown()
                assertEquals(SearchException.Code.CANCELLED, result.get(3, TimeUnit.SECONDS))
                assertEquals(0, replacementCalls.get())
                assertEquals("same", tool.query().providerId)
                assertEquals(1, replacementCalls.get())
            } finally {
                returnResult.countDown()
                executor.shutdownNow()
            }
        }
    }

    @Test
    fun `policy change after provider selection refuses dispatch without switching providers`() {
        val calls = AtomicInteger()
        val initial = WorkspaceSearchPolicy(mode = SearchMode.TOOL, searchProviderId = "a")
        val off = initial.copy(mode = SearchMode.OFF)
        val reads = AtomicInteger()
        val a = install("builtin.a", provider("a") { _, _ -> calls.incrementAndGet(); SearchResult("not used") })
        kernel.enable(a)
        DefaultSearchService(directory, object : SearchPolicyStore {
            override fun policy(workspaceId: String) = if (reads.getAndIncrement() == 0) initial else off
        }).use { tool ->
            assertEquals(SearchException.Code.CANCELLED, assertFailsWith<SearchException> { tool.query() }.code)
        }
        assertEquals(0, calls.get())
    }

    @Test
    fun `partial starts are invisible and overlapping ids cannot displace another owner`() {
        val failing = install("builtin.failing", provider("a") { _, _ -> error("not callable") }, failStart = true)
        val committed = install("builtin.ready", provider("a") { _, _ -> SearchResult("ok") })
        val conflicting = install("builtin.duplicate", provider("a") { _, _ -> error("not callable") })
        kernel.enable(failing)
        assertEquals(PluginInstanceState.FAILED, kernel.status(failing).state)
        assertNull(directory.searchProvider("a"))
        kernel.enable(committed)
        val original = directory.searchProvider("a")
        kernel.enable(conflicting)
        assertEquals(PluginInstanceState.FAILED, kernel.status(conflicting).state)
        assertSame(original, directory.searchProvider("a"))
    }
}
