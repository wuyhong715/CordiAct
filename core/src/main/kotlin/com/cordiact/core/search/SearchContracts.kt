package com.cordiact.core.search

const val SEARCH_PLUGIN_ID = "core.search"
const val SEARCH_TOOL_ID = "search.web"
const val FETCH_TOOL_ID = "search.fetch"
const val SEARCH_CAPABILITY = "core.search.web"
const val FETCH_CAPABILITY = "core.search.fetch"

/** The chat route a search is performed for. Only ids; no model-module dependency. */
data class ModelRoute(
    val providerId: String,
    val modelId: String,
)

/**
 * Cooperative cancellation for a blocking provider call.
 *
 * Providers must check it between blocking steps and close their connection when
 * it flips. A host timeout cannot interrupt arbitrary socket reads on Android, so
 * the HTTP layer additionally sets connect/read timeouts.
 */
fun interface CancellationSignal {
    fun isCancelled(): Boolean

    companion object {
        val NONE: CancellationSignal = CancellationSignal { false }
    }
}

enum class SearchMode { OFF, TOOL, NATIVE, AUTO }

/**
 * Workspace-scoped search policy.
 *
 * [searchProviderId] and [fetchProviderId] are explicit pins. When null the
 * service auto-selects only if exactly one usable provider exists; ambiguity is
 * an error rather than an arbitrary pick.
 *
 * [monthlyBudgetCalls] caps billable provider calls per calendar month. Null
 * means no cap. The cap is checked before dispatch and never degrades silently:
 * an exhausted budget is a structured error.
 *
 * [providerOrder] is an explicit preference list of third-party search provider
 * ids. When it is non-empty the first id that is registered, enabled and ready
 * wins, which removes the ambiguity error for multi-provider workspaces. When it
 * is empty the legacy rule applies: exactly one usable provider, ambiguity
 * otherwise.
 *
 * [enabledProviders] is the third-party allowlist. Null means "all providers, as
 * before"; an empty set disables every third-party provider. Native providers are
 * governed by [mode] instead, so an allowlist of external ids never silently
 * removes the model's own search. A disabled provider is rejected even when
 * pinned; there is no fallback that bypasses the allowlist.
 */
data class WorkspaceSearchPolicy(
    val mode: SearchMode = SearchMode.AUTO,
    val searchProviderId: String? = null,
    val fetchProviderId: String? = null,
    val monthlyBudgetCalls: Int? = null,
    val providerOrder: List<String> = emptyList(),
    val enabledProviders: Set<String>? = null,
)

interface SearchPolicyStore {
    fun policy(workspaceId: String): WorkspaceSearchPolicy
}

/** Billable-call counter, bucketed per Workspace and calendar month. */
interface SearchUsageStore {
    fun usedThisMonth(workspaceId: String): Int
    fun record(workspaceId: String, calls: Int)
}

object NoSearchUsage : SearchUsageStore {
    override fun usedThisMonth(workspaceId: String): Int = 0
    override fun record(workspaceId: String, calls: Int) = Unit
}

/** Declared cost of one billable call, for display only. Never used for enforcement. */
data class SearchPricing(
    val usdPerCall: Double? = null,
    /** Human-readable note, including the date the figure was read. */
    val note: String? = null,
)

data class SearchRequest(
    /** Exactly one query; the service fans out and merges multi-query calls. */
    val query: String,
    val maxResults: Int,
    /** The user question that triggered the tool call, for provider intent signals. */
    val oriQuestion: String? = null,
    /** The chat route this search is for, when known. */
    val route: ModelRoute? = null,
)

data class SearchSource(
    val url: String,
    val title: String? = null,
    val snippet: String? = null,
    val summary: String? = null,
    val publishedAt: String? = null,
)

data class SearchResult(
    val content: String? = null,
    val sources: List<SearchSource> = emptyList(),
    val truncated: Boolean = false,
    val providerId: String? = null,
    /** Estimated spend for this call, when the provider declares a price. */
    val estimatedCostUsd: Double? = null,
)

data class FetchRequest(val url: String)

data class FetchResult(
    val url: String,
    val statusCode: Int,
    val content: String,
    val truncated: Boolean,
)

interface SearchProvider {
    val id: String

    /** True when this provider performs the model vendor's own search. */
    val native: Boolean
        get() = false

    /** For native providers: the model provider id this backend is bound to. */
    val routeProviderId: String?
        get() = null

    /** Declared cost per billable call, for display. */
    val pricing: SearchPricing
        get() = SearchPricing()

    /** True when a search call is billed. Fetch pricing is declared separately. */
    val billable: Boolean
        get() = false

    /** Cheap local check; must not make network calls. */
    fun available(): Boolean

    /**
     * Whether this provider can authenticate for [route]. Configuration only;
     * no network calls. A false answer hides the tool from that request.
     */
    fun ready(route: ModelRoute?): Boolean

    fun search(request: SearchRequest, signal: CancellationSignal = CancellationSignal.NONE): SearchResult
}

interface FetchProvider {
    val id: String
    val pricing: SearchPricing
        get() = SearchPricing()
    val billable: Boolean
        get() = false
    fun available(): Boolean
    fun ready(route: ModelRoute?): Boolean
    fun fetch(request: FetchRequest, signal: CancellationSignal = CancellationSignal.NONE): FetchResult
}

class SearchException(
    val code: Code,
    message: String,
    val retryable: Boolean = false,
    cause: Throwable? = null,
) : RuntimeException(message, cause) {
    enum class Code {
        INVALID_ARGUMENT,
        PROVIDER_UNAVAILABLE,
        CREDENTIAL_MISSING,
        PROVIDER_AMBIGUOUS,
        UNSUPPORTED,
        RATE_LIMITED,
        TIMED_OUT,
        CANCELLED,
        INVALID_URL,
        PROVIDER_ERROR,
        INVALID_RESPONSE,
        BUDGET_EXHAUSTED,
    }
}

/** The choice a tool call would make, exposed for request-time visibility. */
data class SearchSelection(
    val providerId: String,
    val native: Boolean,
)

interface SearchService {
    /**
     * Run one search for [workspaceId] on [route]. [queries] is already
     * user-supplied; the service validates count, deduplicates and fans out.
     */
    fun search(
        workspaceId: String,
        route: ModelRoute?,
        queries: List<String>,
        oriQuestion: String?,
        maxResults: Int,
        timeoutMs: Long,
    ): SearchResult

    fun fetch(
        workspaceId: String,
        route: ModelRoute?,
        url: String,
        timeoutMs: Long,
    ): FetchResult

    /**
     * True when the tool should be offered on this request.
     *
     * A configured provider that is registered but cannot authenticate is
     * hidden. A configured-but-missing provider stays visible so the call can
     * return a structured setup error instead of silently disappearing.
     */
    fun shouldExposeSearch(workspaceId: String, route: ModelRoute?): Boolean
    fun shouldExposeFetch(workspaceId: String, route: ModelRoute?): Boolean
}

/** Thread-safe provider registry. Ids are unique within each capability kind. */
class SearchProviderRegistry {
    private val searchProviders = linkedMapOf<String, SearchProvider>()
    private val fetchProviders = linkedMapOf<String, FetchProvider>()

    @Synchronized
    fun registerSearch(provider: SearchProvider) {
        require(provider.id.isNotBlank()) { "Search provider id must not be blank" }
        require(searchProviders.putIfAbsent(provider.id, provider) == null) {
            "Search provider '${provider.id}' is already registered"
        }
    }

    @Synchronized
    fun registerFetch(provider: FetchProvider) {
        require(provider.id.isNotBlank()) { "Fetch provider id must not be blank" }
        require(fetchProviders.putIfAbsent(provider.id, provider) == null) {
            "Fetch provider '${provider.id}' is already registered"
        }
    }

    @Synchronized
    fun searchProviders(): List<SearchProvider> = searchProviders.values.toList()

    @Synchronized
    fun fetchProviders(): List<FetchProvider> = fetchProviders.values.toList()

    @Synchronized
    fun searchProvider(id: String): SearchProvider? = searchProviders[id]

    @Synchronized
    fun fetchProvider(id: String): FetchProvider? = fetchProviders[id]
}
