package com.cordiact.app.search

import com.cordiact.core.plugin.ContextManagedPlugin
import com.cordiact.core.plugin.ContextPlugin
import com.cordiact.core.plugin.PluginContext
import com.cordiact.core.plugin.PluginKernel
import com.cordiact.core.plugin.ServiceKey
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
    kernel: PluginKernel = PluginKernel(),
) : com.cordiact.core.plugin.RuntimeAwarePlugin {
    override val spec: PluginSpec = searchPluginSpec()

    @Volatile
    var isEnabled: Boolean = false
        private set

    private val runtime = ContextManagedPlugin(spec, kernel, object : ContextPlugin {
        override fun start(context: PluginContext) {
            context.provide(SERVICE, service)
            context.effect { isEnabled = false }
            isEnabled = true
        }
    })

    override fun runtimeStatus() = runtime.runtimeStatus()
    override fun onRegister() = runtime.onRegister()
    override fun onEnable() { runtime.onRegister(); runtime.onEnable() }
    override fun onDisable() = runtime.onDisable()
    override fun onUninstall() = runtime.onUninstall()

    companion object {
        val SERVICE = ServiceKey("core.search.service", SearchService::class.java)
    }
}
