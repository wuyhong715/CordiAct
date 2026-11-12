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
        SearchSettingsSnapshot(workspaceId, policies.policy(workspaceId), IDS.map { SearchProviderSummary(it, providerSettings.baseUrl(configId(it)) ?: "", it == "deepseek-search", com.cordiact.app.security.AndroidSystemCredentials.get(app).has("search", configId(it))) },
            app.conversationCatalog.searchUsageStore.usedThisMonth(workspaceId), providerSettings.localFetchEnabled())
    }
    override suspend fun savePolicy(workspaceId: String, value: WorkspaceSearchPolicy) = withContext(Dispatchers.IO) {
        require(value.providerOrder.distinct().size == value.providerOrder.size && value.providerOrder.all { it in THIRD_PARTY })
        require(value.enabledProviders == null || value.enabledProviders!!.all { it in THIRD_PARTY })
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
        require(id in THIRD_PARTY)
        val provider = checkNotNull(app.conversationCatalog.searchRegistry).searchProvider(id) ?: error("SEARCH_PROVIDER_UNAVAILABLE")
        require(provider.ready(null)) { "SEARCH_PROVIDER_NOT_CONFIGURED" }
        require(policies.policy(workspaceId).mode != SearchMode.OFF) { "SEARCH_DISABLED" }
        require(policies.policy(workspaceId).enabledProviders?.contains(id) != false) { "SEARCH_PROVIDER_DISABLED" }
        require(com.cordiact.app.settings.CapabilitySettingsStore(app).enabled(workspaceId, "search")) { "SEARCH_CAPABILITY_DISABLED" }
        // Explicit user test, same budget/time enforcement, no temporary persistent policy changes.
        val testPolicy = policies.policy(workspaceId).copy(mode = SearchMode.TOOL, searchProviderId = id, enabledProviders = setOf(id), providerOrder = listOf(id))
        val service = com.cordiact.search.providers.DefaultSearchService(checkNotNull(app.conversationCatalog.searchRegistry), object : SearchPolicyStore { override fun policy(workspaceId: String) = testPolicy }, app.conversationCatalog.searchUsageStore)
        service.search(workspaceId, null, listOf("CordiAct connectivity test"), null, 3, 10_000).sources.take(3).map { it.title ?: it.url }
    }
    private fun configId(id: String) = if (id == "deepseek-search") "deepseek" else id
    companion object {
        val THIRD_PARTY = listOf("tavily", "brave", "exa", "bocha", "searxng")
        val IDS = listOf("deepseek-search") + THIRD_PARTY
    }
}
