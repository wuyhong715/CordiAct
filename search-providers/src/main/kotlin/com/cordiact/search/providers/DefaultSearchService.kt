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
import com.cordiact.core.search.SearchService
import com.cordiact.core.search.SearchSource
import com.cordiact.core.search.SearchUsageStore
import com.cordiact.core.search.NoSearchUsage
import java.net.URI
import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * Provider selection plus multi-query orchestration.
 *
 * Selection is deliberately strict: an explicit provider wins; otherwise a
 * single usable provider is auto-selected. More than one usable provider is an
 * error, never an arbitrary pick, and there is no cross-provider fallback chain.
 */
class DefaultSearchService(
    private val registry: SearchProviderRegistry,
    private val policies: SearchPolicyStore,
    private val usage: SearchUsageStore = NoSearchUsage,
    private val maxQueries: Int = 4,
    private val maxResults: Int = 8,
    private val summaryMaxChars: Int = 800,
    private val searchTimeoutMs: Long = 30_000,
    private val fetchTimeoutMs: Long = 30_000,
) : SearchService {
    private val executor: ExecutorService = Executors.newFixedThreadPool(maxOf(2, maxQueries)) { runnable ->
        Thread(runnable, "cordiact-search").apply { isDaemon = true }
    }

    override fun search(
        workspaceId: String,
        route: ModelRoute?,
        queries: List<String>,
        oriQuestion: String?,
        maxResults: Int,
        timeoutMs: Long,
    ): SearchResult {
        val normalized = normalizeQueries(queries)
        val cap = maxResults.coerceIn(1, this.maxResults)
        val provider = resolveSearch(workspaceId, route)
        enforceBudget(workspaceId, provider)
        val tasks = normalized.map { query ->
            Callable {
                provider.search(
                    SearchRequest(query = query, maxResults = cap, oriQuestion = oriQuestion, route = route),
                    CancellationSignal.NONE,
                )
            }
        }
        val budget = timeoutMs.takeIf { it > 0 } ?: searchTimeoutMs
        val futures: List<Future<SearchResult>> = try {
            executor.invokeAll(tasks, budget, TimeUnit.MILLISECONDS)
        } catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()
            throw SearchException(SearchException.Code.CANCELLED, "Search was interrupted", cause = interrupted)
        }

        val results = ArrayList<SearchResult>(futures.size)
        for (future in futures) {
            if (future.isCancelled) {
                throw SearchException(SearchException.Code.TIMED_OUT, "Search timed out", retryable = true)
            }
            try {
                results += future.get()
            } catch (failure: ExecutionException) {
                when (val cause = failure.cause) {
                    is SearchException -> throw cause
                    else -> throw SearchException(
                        SearchException.Code.PROVIDER_ERROR,
                        cause?.message ?: "Search provider failed",
                        retryable = true,
                        cause = cause,
                    )
                }
            } catch (interrupted: InterruptedException) {
                Thread.currentThread().interrupt()
                throw SearchException(SearchException.Code.CANCELLED, "Search was interrupted", cause = interrupted)
            }
        }
        return merge(normalized, results, cap).copy(
            providerId = provider.id,
            estimatedCostUsd = provider.pricing.usdPerCall?.let { it * normalized.size },
        )
    }

    /**
     * Budget is checked before dispatch: an exhausted budget fails loudly rather
     * than silently returning fewer or empty results.
     */
    private fun enforceBudget(workspaceId: String, provider: SearchProvider) {
        if (!provider.billable) return
        val limit = policies.policy(workspaceId).monthlyBudgetCalls ?: return
        val used = usage.usedThisMonth(workspaceId)
        if (used >= limit) {
            throw SearchException(
                SearchException.Code.BUDGET_EXHAUSTED,
                "Monthly search budget of $limit calls is used up",
            )
        }
        usage.record(workspaceId, 1)
    }

    override fun fetch(
        workspaceId: String,
        route: ModelRoute?,
        url: String,
        timeoutMs: Long,
    ): FetchResult {
        validateHttpUrl(url)
        val provider = resolveFetch(workspaceId, route)
        enforceFetchBudget(workspaceId, provider)
        val budget = timeoutMs.takeIf { it > 0 } ?: fetchTimeoutMs
        return withTimeout(budget) {
            provider.fetch(FetchRequest(url), CancellationSignal.NONE)
        }
    }

    private fun enforceFetchBudget(workspaceId: String, provider: FetchProvider) {
        if (!provider.billable) return
        val limit = policies.policy(workspaceId).monthlyBudgetCalls ?: return
        if (usage.usedThisMonth(workspaceId) >= limit) {
            throw SearchException(
                SearchException.Code.BUDGET_EXHAUSTED,
                "Monthly search budget of $limit calls is used up",
            )
        }
        usage.record(workspaceId, 1)
    }

    override fun shouldExposeSearch(workspaceId: String, route: ModelRoute?): Boolean {
        val policy = policies.policy(workspaceId)
        if (policy.mode == SearchMode.OFF) return false
        val pinned = policy.searchProviderId ?: run {
            val providers = registry.searchProviders()
            // No provider at all: keep the tool visible so the call returns a setup error.
            // Providers exist but none can authenticate: hide the tool for this request.
            return providers.isEmpty() || providers.any { it.ready(route) }
        }
        val provider = registry.searchProvider(pinned) ?: return true
        if (provider.native && provider.routeProviderId != route?.providerId) return false
        return provider.ready(route)
    }

    override fun shouldExposeFetch(workspaceId: String, route: ModelRoute?): Boolean {
        val policy = policies.policy(workspaceId)
        if (policy.mode == SearchMode.OFF) return false
        val pinned = policy.fetchProviderId ?: run {
            val providers = registry.fetchProviders()
            return providers.isEmpty() || providers.any { it.ready(route) }
        }
        val provider = registry.fetchProvider(pinned) ?: return true
        return provider.ready(route)
    }

    private fun resolveSearch(workspaceId: String, route: ModelRoute?): SearchProvider {
        val policy = policies.policy(workspaceId)
        if (policy.mode == SearchMode.OFF) {
            throw SearchException(SearchException.Code.UNSUPPORTED, "Web search is disabled for this workspace")
        }
        policy.searchProviderId?.let { pinned ->
            val provider = registry.searchProvider(pinned)
                ?: throw SearchException(SearchException.Code.PROVIDER_UNAVAILABLE, "Search provider '$pinned' is not available")
            if (provider.native && provider.routeProviderId != route?.providerId) {
                throw SearchException(SearchException.Code.UNSUPPORTED, "Native search provider does not match the current model")
            }
            if (!provider.ready(route)) {
                throw SearchException(SearchException.Code.CREDENTIAL_MISSING, "Search provider '$pinned' is not configured")
            }
            return provider
        }

        if (policy.mode == SearchMode.NATIVE || policy.mode == SearchMode.AUTO) {
            val natives = registry.searchProviders().filter {
                it.native && it.routeProviderId == route?.providerId
            }
            val ready = natives.filter { it.ready(route) }
            if (ready.size > 1) {
                throw SearchException(SearchException.Code.PROVIDER_AMBIGUOUS, "Multiple native search providers are configured")
            }
            if (ready.size == 1) return ready.first()
            if (policy.mode == SearchMode.NATIVE) {
                throw SearchException(SearchException.Code.UNSUPPORTED, "The current model has no native web search")
            }
        }

        val external = registry.searchProviders().filter { !it.native && it.ready(route) }
        return when (external.size) {
            1 -> external.first()
            0 -> throw SearchException(SearchException.Code.PROVIDER_UNAVAILABLE, "No search provider is configured")
            else -> throw SearchException(SearchException.Code.PROVIDER_AMBIGUOUS, "Multiple search providers are configured")
        }
    }

    private fun resolveFetch(workspaceId: String, route: ModelRoute?): FetchProvider {
        val policy = policies.policy(workspaceId)
        if (policy.mode == SearchMode.OFF) {
            throw SearchException(SearchException.Code.UNSUPPORTED, "Web fetch is disabled for this workspace")
        }
        policy.fetchProviderId?.let { pinned ->
            val provider = registry.fetchProvider(pinned)
                ?: throw SearchException(SearchException.Code.PROVIDER_UNAVAILABLE, "Fetch provider '$pinned' is not available")
            if (!provider.ready(route)) {
                throw SearchException(SearchException.Code.CREDENTIAL_MISSING, "Fetch provider '$pinned' is not configured")
            }
            return provider
        }
        val usable = registry.fetchProviders().filter { it.ready(route) }
        return when (usable.size) {
            1 -> usable.first()
            0 -> throw SearchException(SearchException.Code.PROVIDER_UNAVAILABLE, "No fetch provider is configured")
            else -> throw SearchException(SearchException.Code.PROVIDER_AMBIGUOUS, "Multiple fetch providers are configured")
        }
    }

    private fun normalizeQueries(queries: List<String>): List<String> {
        if (queries.isEmpty()) throw SearchException(SearchException.Code.INVALID_ARGUMENT, "At least one query is required")
        if (queries.size > maxQueries) {
            throw SearchException(SearchException.Code.INVALID_ARGUMENT, "At most $maxQueries queries are allowed")
        }
        val normalized = queries.map { it.trim() }
        if (normalized.any(String::isEmpty)) {
            throw SearchException(SearchException.Code.INVALID_ARGUMENT, "Queries must not be blank")
        }
        if (normalized.any { it.length > MAX_QUERY_LENGTH }) {
            throw SearchException(SearchException.Code.INVALID_ARGUMENT, "Query is too long")
        }
        return normalized.distinct()
    }

    private fun merge(queries: List<String>, results: List<SearchResult>, cap: Int): SearchResult {
        val merged = mutableListOf<SearchSource>()
        val seen = mutableSetOf<String>()
        var index = 0
        while (merged.size < cap) {
            var added = false
            for (result in results) {
                val source = result.sources.getOrNull(index) ?: continue
                added = true
                if (seen.add(source.url)) {
                    merged += source.copy(
                        snippet = source.snippet?.take(1_200),
                        summary = source.summary?.take(summaryMaxChars),
                    )
                    if (merged.size == cap) break
                }
            }
            if (!added) break
            index++
        }
        val truncated = results.any(SearchResult::truncated) || results.sumOf { it.sources.size } > merged.size
        val content = results.mapNotNull { it.content?.takeIf(String::isNotBlank) }
            .distinct()
            .takeIf { it.isNotEmpty() }
            ?.joinToString("\n\n")
        return SearchResult(content = content, sources = merged, truncated = truncated)
    }

    private fun <T> withTimeout(timeoutMs: Long, block: () -> T): T {
        val task = executor.submit(Callable(block))
        return try {
            task.get(timeoutMs.coerceAtLeast(1), TimeUnit.MILLISECONDS)
        } catch (timeout: TimeoutException) {
            task.cancel(true)
            throw SearchException(SearchException.Code.TIMED_OUT, "Fetch timed out", retryable = true, cause = timeout)
        } catch (failure: ExecutionException) {
            when (val cause = failure.cause) {
                is SearchException -> throw cause
                else -> throw SearchException(
                    SearchException.Code.PROVIDER_ERROR,
                    cause?.message ?: "Fetch provider failed",
                    retryable = true,
                    cause = cause,
                )
            }
        } catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()
            throw SearchException(SearchException.Code.CANCELLED, "Fetch was interrupted", cause = interrupted)
        }
    }

    private fun validateHttpUrl(url: String) {
        val uri = runCatching { URI(url) }.getOrNull()
            ?: throw SearchException(SearchException.Code.INVALID_URL, "URL is invalid")
        if ((uri.scheme != "https" && uri.scheme != "http") || uri.host.isNullOrBlank() || uri.userInfo != null) {
            throw SearchException(SearchException.Code.INVALID_URL, "URL must be an HTTP(S) URL without credentials")
        }
    }

    private companion object {
        const val MAX_QUERY_LENGTH = 400
    }
}
