package com.cordiact.app.search

import com.cordiact.app.conversation.AndroidConversationSessionStore
import com.cordiact.core.search.SEARCH_PLUGIN_ID
import com.cordiact.core.search.SearchMode
import com.cordiact.core.search.SearchPolicyStore
import com.cordiact.core.search.WorkspaceSearchPolicy
import org.json.JSONObject

/**
 * Workspace-scoped search policy, stored in the v4
 * `workspace_plugin_settings` table. Never stores credentials.
 */
class AndroidSearchPolicyStore(
    private val store: AndroidConversationSessionStore,
) : SearchPolicyStore {
    override fun policy(workspaceId: String): WorkspaceSearchPolicy {
        val raw = store.pluginSetting(workspaceId, SEARCH_PLUGIN_ID, KEY) ?: return DEFAULT
        return parse(raw) // corrupt policy must not silently re-enable networking
    }

    fun save(workspaceId: String, policy: WorkspaceSearchPolicy) {
        store.putPluginSetting(workspaceId, SEARCH_PLUGIN_ID, KEY, encode(policy).toString())
    }

    private fun parse(raw: String): WorkspaceSearchPolicy {
        val json = JSONObject(raw)
        val mode = SearchMode.valueOf(json.getString("mode"))
        return WorkspaceSearchPolicy(
            mode = mode,
            providerOrder = json.optJSONArray("provider_order")?.let { rows -> (0 until rows.length()).map { rows.getString(it) } }.orEmpty(),
            enabledProviders = json.optJSONArray("enabled_providers")?.let { rows -> (0 until rows.length()).map { rows.getString(it) }.toSet() },
            monthlyBudgetCalls = if (json.has("monthly_budget")) json.getInt("monthly_budget") else null,
            searchProviderId = json.optString("search_provider").takeIf { it.isNotBlank() },
            fetchProviderId = json.optString("fetch_provider").takeIf { it.isNotBlank() },
        )
    }

    private fun encode(policy: WorkspaceSearchPolicy): JSONObject = JSONObject().apply {
        put("mode", policy.mode.name)
        put("provider_order", org.json.JSONArray(policy.providerOrder))
        policy.enabledProviders?.let { put("enabled_providers", org.json.JSONArray(it.toList())) }
        policy.monthlyBudgetCalls?.let { put("monthly_budget", it) }
        policy.searchProviderId?.let { put("search_provider", it) }
        policy.fetchProviderId?.let { put("fetch_provider", it) }
    }

    companion object {
        private const val KEY = "policy"
        val DEFAULT = WorkspaceSearchPolicy(mode = SearchMode.AUTO)
    }
}
