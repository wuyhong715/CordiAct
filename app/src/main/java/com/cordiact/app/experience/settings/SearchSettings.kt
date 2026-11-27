package com.cordiact.app.experience.settings

import com.cordiact.core.search.*
data class SearchProviderSummary(
    val id: String, val endpoint: String, val keySet: Boolean, val native: Boolean = false,
    val published: Boolean = true,
    val label: String = id,
    val packageManaged: Boolean = false,
)
data class SearchSettingsSnapshot(val workspaceId: String, val policy: WorkspaceSearchPolicy, val providers: List<SearchProviderSummary>, val monthlyCalls: Int, val localFetch: Boolean)

internal fun SearchSettingsSnapshot.editableOrder(): List<String> =
    (policy.providerOrder + providers.filterNot { it.native }.map { it.id }).distinct()

internal fun SearchSettingsSnapshot.editableEnabled(): Set<String> = policy.enabledProviders ?: providers
    .filter { !it.native && (!it.packageManaged || it.id in policy.providerOrder || it.id == policy.searchProviderId) }
    .mapTo(linkedSetOf()) { it.id }

interface SearchSettings {
    suspend fun snapshot(workspaceId: String): SearchSettingsSnapshot
    suspend fun savePolicy(workspaceId: String, value: WorkspaceSearchPolicy)
    suspend fun saveProvider(id: String, endpoint: String, replacementKey: String?)
    suspend fun clearKey(id: String)
    suspend fun test(workspaceId: String, id: String): List<String>
}
