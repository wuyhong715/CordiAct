package com.cordiact.app.search

import com.cordiact.core.search.SearchMode
import com.cordiact.core.search.SearchProviderRegistry
import com.cordiact.core.search.WorkspaceSearchPolicy

/** One provider row in the settings screen. */
data class SearchProviderChoice(
    val id: String,
    val label: String,
    val native: Boolean,
    val configured: Boolean,
    val priceNote: String?,
)

/** Everything the search settings screen needs, assembled without touching Android UI. */
data class SearchSettingsState(
    val policy: WorkspaceSearchPolicy,
    val searchProviders: List<SearchProviderChoice>,
    val fetchProviders: List<SearchProviderChoice>,
    val usedThisMonth: Int,
    val budgetLabel: String?,
) {
    /**
     * A concrete reason the user must choose a provider, or null.
     *
     * Auto mode silently picking one of several configured providers would make
     * cost and result quality unpredictable, so the screen surfaces the choice.
     */
    fun selectionWarning(): String? {
        if (policy.mode == SearchMode.OFF) return null
        if (policy.searchProviderId != null || policy.mode == SearchMode.NATIVE) return null
        val usable = searchProviders.count { it.configured }
        return when {
            usable > 1 -> "多个搜索服务可用，请指定一个，否则每次搜索都会报歧义错误"
            usable == 0 -> "还没有可用的搜索服务，请先填写 API key"
            else -> null
        }
    }
}

fun buildSearchSettingsState(
    policy: WorkspaceSearchPolicy,
    registry: SearchProviderRegistry,
    usedThisMonth: Int,
    providerHasKey: (String) -> Boolean,
): SearchSettingsState {
    // available()而非 ready(route): 原生 provider 的 ready 需要具体的模型路由，
    // 设置页只关心“这个服务是否已配置”。
    val search = registry.searchProviders().map { provider ->
        SearchProviderChoice(
            id = provider.id,
            label = labelFor(provider.id),
            native = provider.native,
            configured = provider.available() && providerHasKey(provider.id),
            priceNote = provider.pricing.note,
        )
    }.sortedWith(compareByDescending<SearchProviderChoice> { it.native }.thenBy { it.label })

    val fetch = registry.fetchProviders().map { provider ->
        SearchProviderChoice(
            id = provider.id,
            label = labelFor(provider.id),
            native = false,
            configured = provider.available() && providerHasKey(provider.id),
            priceNote = provider.pricing.note,
        )
    }.sortedBy { it.label }

    return SearchSettingsState(
        policy = policy,
        searchProviders = search,
        fetchProviders = fetch,
        usedThisMonth = usedThisMonth,
        budgetLabel = policy.monthlyBudgetCalls?.let { "本月已用 $usedThisMonth / $it 次" },
    )
}

private fun labelFor(providerId: String): String = when (providerId) {
    "deepseek-native" -> "DeepSeek 原生搜索"
    "deepseek-search" -> "DeepSeek Search API"
    "deepseek-fetch" -> "DeepSeek Fetch API"
    "tavily" -> "Tavily"
    "brave" -> "Brave Search"
    "exa" -> "Exa"
    "bocha" -> "博查 Bocha"
    "searxng" -> "SearXNG（自建）"
    else -> providerId
}
