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
import com.cordiact.core.search.WorkspaceSearchPolicy
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
 * Selection is deliberately strict: an explicit provider wins (unless it is disabled);
 * otherwise a native provider bound to the route wins in NATIVE/AUTO, then a single
 * third-party provider chosen by [com.cordiact.core.search.WorkspaceSearchPolicy.providerOrder]
 * or, without an order, by the legacy exactly-one rule. More than one usable provider is
 * an error, never an arbitrary pick. The only fallback is the single AUTO native-failure
 * retry in [autoFallback]; there is no general cross-provider chain.
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
        val policy = policies.policy(workspaceId)
        val provider = resolveSearch(workspaceId, route)
        val budget = timeoutMs.takeIf { it > 0 } ?: searchTimeoutMs
        return try {
            runSearch(workspaceId, route, provider, normalized, cap, oriQuestion, budget)
        } catch (failure: SearchException) {
            val fallback = autoFallback(policy, route, provider, failure) ?: throw failure
            runSearch(workspaceId, route, fallback, normalized, cap, oriQuestion, budget)
        }
    }

    /** One dispatch plus merge for [provider]; the budget is reserved before anything goes out. */
    private fun runSearch(
        workspaceId: String,
        route: ModelRoute?,
        provider: SearchProvider,
        normalized: List<String>,
        cap: Int,
        oriQuestion: String?,
        budget: Long,
    ): SearchResult {
        // Fan-out means one tool call can spend several billable calls; reserve them all up front.
        enforceBudget(workspaceId, provider, normalized.size)
        val tasks = normalized.map { query ->
            Callable {
                provider.search(
                    SearchRequest(query = query, maxResults = cap, oriQuestion = oriQuestion, route = route),
                    CancellationSignal.NONE,
                )
            }
        }
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
     *
     * [calls] is how many billable calls this operation will make, not how many
     * tool calls the model made. A four-query fan-out costs four.
     */
    private fun enforceBudget(workspaceId: String, provider: SearchProvider, calls: Int) {
        if (!provider.billable || calls <= 0) return
        val limit = policies.policy(workspaceId).monthlyBudgetCalls ?: return
        val used = usage.usedThisMonth(workspaceId)
        val remaining = limit - used
        if (remaining < calls) {
            throw SearchException(
                SearchException.Code.BUDGET_EXHAUSTED,
                "Monthly search budget of " + limit + " calls has " + remaining.coerceAtLeast(0) +
                    " left, but this search needs " + calls,
            )
        }
        usage.record(workspaceId, calls)
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
        val remaining = limit - usage.usedThisMonth(workspaceId)
        if (remaining < 1) {
            throw SearchException(
                SearchException.Code.BUDGET_EXHAUSTED,
                "Monthly search budget of " + limit + " calls is used up",
            )
        }
        usage.record(workspaceId, 1)
    }

    override fun shouldExposeSearch(workspaceId: String, route: ModelRoute?): Boolean {
        val policy = policies.policy(workspaceId)
        if (policy.mode == SearchMode.OFF) return false
        policy.searchProviderId?.let { pinned ->
            // A configured provider that is registered but cannot authenticate is hidden; a
            // configured-but-missing provider stays visible so the call returns a setup error.
            val provider = registry.searchProvider(pinned) ?: return true
            if (!isEnabled(policy, provider)) return false
            if (provider.native && (policy.mode == SearchMode.TOOL || provider.routeProviderId != route?.providerId)) return false
            return provider.ready(route)
        }
        val usable = readyNatives(policy, route).isNotEmpty() ||
            registry.searchProviders().any { !it.native && isEnabled(policy, it) && it.ready(route) }
        if (usable) return true
        // Nothing usable at all: stay visible only when no provider is configured, so the call
        // can report the setup problem instead of the tool silently disappearing.
        return registry.searchProviders().none { isEnabled(policy, it) }
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

    /**
     * Third-party providers are the only ones an allowlist can close; native search is
     * driven by [WorkspaceSearchPolicy.mode] so an external-id allowlist never removes it.
     */
    private fun isEnabled(policy: WorkspaceSearchPolicy, provider: SearchProvider): Boolean =
        provider.native || (policy.enabledProviders?.contains(provider.id) ?: true)

    /** Native providers bound to [route]; empty outside NATIVE/AUTO, so TOOL never uses them. */
    private fun readyNatives(policy: WorkspaceSearchPolicy, route: ModelRoute?): List<SearchProvider> {
        if (policy.mode != SearchMode.NATIVE && policy.mode != SearchMode.AUTO) return emptyList()
        return registry.searchProviders().filter {
            it.native && it.routeProviderId == route?.providerId && it.ready(route)
        }
    }

    /**
     * Single third-party pick. A declared [WorkspaceSearchPolicy.providerOrder] takes the first
     * registered, enabled and ready id in order; without one the legacy rule stands (exactly one
     * usable provider, ambiguity otherwise). Disabled providers are never a candidate.
     */
    private fun resolveExternal(policy: WorkspaceSearchPolicy, route: ModelRoute?): SearchProvider {
        val order = policy.providerOrder
        if (order.isNotEmpty()) {
            val ordered = order.mapNotNull { id ->
                registry.searchProvider(id)?.takeIf { !it.native && isEnabled(policy, it) && it.ready(route) }
            }
            return ordered.firstOrNull() ?: throw SearchException(
                SearchException.Code.PROVIDER_UNAVAILABLE,
                "None of the ordered search providers is available: " + order.joinToString(", "),
            )
        }
        val usable = registry.searchProviders().filter { !it.native && isEnabled(policy, it) && it.ready(route) }
        return when (usable.size) {
            1 -> usable.first()
            0 -> throw SearchException(SearchException.Code.PROVIDER_UNAVAILABLE, "No search provider is configured")
            else -> throw SearchException(SearchException.Code.PROVIDER_AMBIGUOUS, "Multiple search providers are configured")
        }
    }

    /**
     * One safe fallback for AUTO: only when the model's own native search was picked, only on a
     * provider failure or timeout, and only when that native call was not billable — so the
     * retry can never bill twice. Cancellation, budget, credentials and unsupported errors are
     * never retried, and there is no second fallback after this one.
     */
    private fun autoFallback(
        policy: WorkspaceSearchPolicy,
        route: ModelRoute?,
        failed: SearchProvider,
        failure: SearchException,
    ): SearchProvider? {
        if (policy.mode != SearchMode.AUTO || policy.searchProviderId != null) return null
        if (!failed.native || failed.billable) return null
        if (failure.code != SearchException.Code.PROVIDER_ERROR && failure.code != SearchException.Code.TIMED_OUT) return null
        return runCatching { resolveExternal(policy, route) }.getOrNull()?.takeIf { it.id != failed.id }
    }

    private fun resolveSearch(workspaceId: String, route: ModelRoute?): SearchProvider {
        val policy = policies.policy(workspaceId)
        if (policy.mode == SearchMode.OFF) {
            throw SearchException(SearchException.Code.UNSUPPORTED, "Web search is disabled for this workspace")
        }
        policy.searchProviderId?.let { pinned ->
            val provider = registry.searchProvider(pinned)
                ?: throw SearchException(SearchException.Code.PROVIDER_UNAVAILABLE, "Search provider '$pinned' is not available")
            // A disabled provider stays rejected even when pinned: no path bypasses the allowlist.
            if (!isEnabled(policy, provider)) {
                throw SearchException(SearchException.Code.UNSUPPORTED, "Search provider '$pinned' is disabled for this workspace")
            }
            if (provider.native && policy.mode == SearchMode.TOOL) {
                throw SearchException(SearchException.Code.UNSUPPORTED, "Tool mode does not use native web search")
            }
            if (provider.native && provider.routeProviderId != route?.providerId) {
                throw SearchException(SearchException.Code.UNSUPPORTED, "Native search provider does not match the current model")
            }
            if (!provider.ready(route)) {
                throw SearchException(SearchException.Code.CREDENTIAL_MISSING, "Search provider '$pinned' is not configured")
            }
            return provider
        }

        val natives = readyNatives(policy, route)
        if (natives.size > 1) {
            throw SearchException(SearchException.Code.PROVIDER_AMBIGUOUS, "Multiple native search providers are configured")
        }
        if (natives.size == 1) return natives.first()
        if (policy.mode == SearchMode.NATIVE) {
            throw SearchException(SearchException.Code.UNSUPPORTED, "The current model has no native web search")
        }

        return resolveExternal(policy, route)
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
                if (seen.add(dedupeKey(source.url))) {
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

    companion object {
        private const val MAX_QUERY_LENGTH = 400

        /** Parameters that never change which page is being cited. */
        private val TRACKING_PARAMETERS = setOf(
            "fbclid", "gclid", "msclkid", "yclid", "mc_cid", "mc_eid",
            "ref", "ref_src", "ref_url", "source", "spm", "share_token",
            "utm_source", "utm_medium", "utm_campaign", "utm_term", "utm_content",
            "utm_id", "utm_name", "utm_reader", "utm_referrer",
        )

        /**
         * URL identity for deduplication.
         *
         * Search APIs routinely return the same page with tracking parameters, a trailing
         * slash, a fragment or a different host case. Comparing raw strings would let one page
         * occupy several of the eight result slots.
         */
        internal fun dedupeKey(url: String): String {
            val uri = runCatching { URI(url) }.getOrNull() ?: return url
            val host = uri.host?.lowercase()?.removePrefix("www.") ?: return url
            val path = uri.path.orEmpty().trimEnd('/').ifEmpty { "/" }
            val kept = uri.rawQuery
                ?.split('&')
                ?.filterNot { it.isEmpty() || it.substringBefore('=').lowercase() in TRACKING_PARAMETERS }
                ?.joinToString("&")
                .orEmpty()
            return buildString {
                append(host)
                append(path)
                if (kept.isNotEmpty()) append('?').append(kept)
            }
        }
    }
}
