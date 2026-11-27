package com.cordiact.app.experience.settings

import com.cordiact.app.CordiActApplication
import com.cordiact.app.search.*
import com.cordiact.core.search.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class AndroidSearchSettings(private val app: CordiActApplication) : SearchSettings {
    private val keys = AndroidSearchApiKeyStore(app)
    private val providerSettings = AndroidSearchProviderSettings(app)
    private val policies get() = app.conversationCatalog.searchPolicyStore
    private val audit = com.cordiact.core.settings.SettingsOperationAudit(app.credentialVaultRuntime.database)
    private fun <T> write(target: String, revision: () -> Long, action: () -> T): T {
        val receipt = audit.run(target, revision, action)
        check(receipt.completionRecorded) { "SEARCH_SAVED_AUDIT_INCOMPLETE_REFRESH_BEFORE_RETRY" }
        return receipt.value
    }
    override suspend fun snapshot(workspaceId: String) = withContext(Dispatchers.IO) {
        require(app.workspaceManager.get(workspaceId) != null)
        val published = app.conversationCatalog.searchRegistry?.searchProviders(workspaceId).orEmpty().associateBy { it.id }
        val policy = policies.policy(workspaceId)
        val credentials = com.cordiact.app.security.AndroidSystemCredentials.get(app)
        val builtIn = IDS.map { id ->
            SearchProviderSummary(id = id, endpoint = providerSettings.baseUrl(configId(id)) ?: "",
                keySet = credentials.has("search", configId(id)), native = id == "deepseek-search", published = id in published)
        }
        val packageIds = published.values.filter { it.requiresExplicitSelection }.map { it.id } + savedPackageIds(policy)
        val packageProviders = packageIds.distinct().map { id ->
            val provider = published[id]
            SearchProviderSummary(id, endpoint = "", keySet = false, published = provider != null,
                label = provider?.displayName ?: id, packageManaged = true)
        }
        SearchSettingsSnapshot(workspaceId, policy, builtIn + packageProviders,
            app.conversationCatalog.searchUsageStore.usedThisMonth(workspaceId), providerSettings.localFetchEnabled())
    }
    override suspend fun savePolicy(workspaceId: String, value: WorkspaceSearchPolicy) = withContext(Dispatchers.IO) {
        require(app.workspaceManager.findSessionWorkspace(workspaceId) != null) { "SEARCH_WORKSPACE_UNAVAILABLE" }
        val active = app.conversationCatalog.searchRegistry?.searchProviders(workspaceId).orEmpty()
            .filter { it.requiresExplicitSelection }.map { it.id }
        val editableIds = (THIRD_PARTY + active + savedPackageIds(policies.policy(workspaceId))).toSet()
        require(value.providerOrder.distinct().size == value.providerOrder.size && value.providerOrder.all { it in editableIds })
        require(value.enabledProviders?.all { it in editableIds } != false)
        require(value.monthlyBudgetCalls == null || value.monthlyBudgetCalls!! >= 0)
        write("search.policy", { revision(workspaceId) }) {
            policies.save(workspaceId, value)
            app.conversationCatalog.conversationStore.putPluginSetting(workspaceId, SEARCH_PLUGIN_ID, "policy_revision", (revision(workspaceId) + 1).toString())
        }
    }
    private fun revision(workspaceId: String) = app.conversationCatalog.conversationStore.pluginSetting(workspaceId, SEARCH_PLUGIN_ID, "policy_revision")?.toLong() ?: 0
    override suspend fun saveProvider(id: String, endpoint: String, replacementKey: String?) = withContext(Dispatchers.IO) {
        require(id in THIRD_PARTY && endpoint.isNotBlank())
        // Validation precedes all writes; URLs may not contain credentials or query secrets.
        val uri = java.net.URI(endpoint.trim())
        require(uri.host != null && uri.rawUserInfo == null && uri.rawQuery == null && uri.fragment == null)
        require(uri.scheme == "https" || uri.scheme == "http" && uri.host in setOf("localhost", "127.0.0.1", "::1", "[::1]"))
        write("search.provider", providerSettings::providerRevision) {
            replacementKey?.let { require(it.isNotBlank()); keys.put(configId(id), it.trim()) }
            providerSettings.updateEndpoint(configId(id), endpoint.trim())
        }
    }
    override suspend fun clearKey(id: String) = withContext(Dispatchers.IO) {
        require(id in THIRD_PARTY)
        write("search.provider", providerSettings::providerRevision) { keys.remove(configId(id)); providerSettings.updateEndpoint(configId(id), providerSettings.baseUrl(configId(id))) }
    }
    override suspend fun test(workspaceId: String, id: String): List<String> = withContext(Dispatchers.IO) {
        require(app.workspaceManager.findSessionWorkspace(workspaceId) != null) { "SEARCH_WORKSPACE_UNAVAILABLE" }
        val registry = checkNotNull(app.conversationCatalog.searchRegistry)
        val provider = registry.searchProvider(id, workspaceId) ?: error("SEARCH_PROVIDER_UNAVAILABLE")
        require(id in THIRD_PARTY || provider.requiresExplicitSelection)
        require(provider.ready(null)) { "SEARCH_PROVIDER_NOT_CONFIGURED" }
        // Explicit one-provider test. Re-read the selected workspace's gates, without changing its saved policy.
        val probePolicy = object : SearchPolicyStore {
            override fun policy(workspaceId: String): WorkspaceSearchPolicy {
                require(app.workspaceManager.findSessionWorkspace(workspaceId) != null) { "SEARCH_WORKSPACE_UNAVAILABLE" }
                val policy = policies.policy(workspaceId)
                require(policy.mode != SearchMode.OFF) { "SEARCH_DISABLED" }
                require(policy.enabledProviders?.contains(id) != false) { "SEARCH_PROVIDER_DISABLED" }
                require(com.cordiact.app.settings.CapabilitySettingsStore(app).enabled(workspaceId, "search")) { "SEARCH_CAPABILITY_DISABLED" }
                return policy.copy(mode = SearchMode.TOOL, searchProviderId = id, enabledProviders = setOf(id), providerOrder = listOf(id))
            }
        }
        com.cordiact.search.providers.DefaultSearchService(
            registry, probePolicy,
            app.conversationCatalog.searchUsageStore, expectedSearchProvider = provider,
        ).use { service ->
            service.search(workspaceId, null, listOf("CordiAct connectivity test"), null, 3, 10_000)
                .sources.take(3).map { it.title ?: it.url }
        }
    }
    private fun savedPackageIds(policy: WorkspaceSearchPolicy): List<String> =
        (policy.providerOrder + policy.enabledProviders.orEmpty() + listOfNotNull(policy.searchProviderId))
            .filter { it.startsWith("pkg.") }

    private fun configId(id: String) = if (id == "deepseek-search") "deepseek" else id
    companion object {
        val THIRD_PARTY = listOf("tavily", "brave", "exa", "bocha", "searxng")
        val IDS = listOf("deepseek-search") + THIRD_PARTY
    }
}
