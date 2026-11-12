package com.cordiact.app.experience.settings

import com.cordiact.core.search.*
data class SearchProviderSummary(val id: String, val endpoint: String, val keySet: Boolean, val native: Boolean = false)
data class SearchSettingsSnapshot(val workspaceId: String, val policy: WorkspaceSearchPolicy, val providers: List<SearchProviderSummary>, val monthlyCalls: Int, val localFetch: Boolean)
interface SearchSettings {
    suspend fun snapshot(workspaceId: String): SearchSettingsSnapshot
    suspend fun savePolicy(workspaceId: String, value: WorkspaceSearchPolicy)
    suspend fun saveProvider(id: String, endpoint: String, replacementKey: String?)
    suspend fun clearKey(id: String)
    suspend fun test(workspaceId: String, id: String): List<String>
}
