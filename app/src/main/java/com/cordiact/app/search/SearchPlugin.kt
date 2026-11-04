package com.cordiact.app.search

import com.cordiact.core.plugin.ManagedPlugin
import com.cordiact.core.plugin.PluginSpec
import com.cordiact.core.plugin.PluginTarget
import com.cordiact.core.search.SEARCH_PLUGIN_ID
import com.cordiact.core.search.SearchService
import com.cordiact.core.workspace.WORKSPACE_MANAGER_PLUGIN_ID

fun searchPluginSpec(): PluginSpec = PluginSpec(
    id = SEARCH_PLUGIN_ID,
    version = "0.1.0",
    target = PluginTarget.ANDROID_NATIVE,
    dependencies = setOf(WORKSPACE_MANAGER_PLUGIN_ID),
)

class SearchPlugin(
    val service: SearchService,
) : ManagedPlugin {
    override val spec: PluginSpec = searchPluginSpec()

    @Volatile
    var isEnabled: Boolean = false
        private set

    override fun onEnable() {
        isEnabled = true
    }

    override fun onDisable() {
        isEnabled = false
    }
}
