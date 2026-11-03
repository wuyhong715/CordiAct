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
        return runCatching { parse(raw) }.getOrDefault(DEFAULT)
    }

    fun save(workspaceId: String, policy: WorkspaceSearchPolicy) {
        store.putPluginSetting(workspaceId, SEARCH_PLUGIN_ID, KEY, encode(policy).toString())
    }

    private fun parse(raw: String): WorkspaceSearchPolicy {
        val json = JSONObject(raw)
        val mode = runCatching { SearchMode.valueOf(json.optString("mode", SearchMode.AUTO.name)) }
            .getOrDefault(SearchMode.AUTO)
        return WorkspaceSearchPolicy(
            mode = mode,
            searchProviderId = json.optString("search_provider").takeIf { it.isNotBlank() },
            fetchProviderId = json.optString("fetch_provider").takeIf { it.isNotBlank() },
        )
    }

    private fun encode(policy: WorkspaceSearchPolicy): JSONObject = JSONObject().apply {
        put("mode", policy.mode.name)
        policy.searchProviderId?.let { put("search_provider", it) }
        policy.fetchProviderId?.let { put("fetch_provider", it) }
    }

    companion object {
        private const val KEY = "policy"
        val DEFAULT = WorkspaceSearchPolicy(mode = SearchMode.AUTO)
    }
}
