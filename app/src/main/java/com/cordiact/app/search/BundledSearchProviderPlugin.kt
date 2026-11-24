package com.cordiact.app.search

import com.cordiact.core.plugin.ContextManagedPlugin
import com.cordiact.core.plugin.ContextPlugin
import com.cordiact.core.plugin.PluginContext
import com.cordiact.core.plugin.PluginKernel
import com.cordiact.core.plugin.PluginSpec
import com.cordiact.core.plugin.PluginTarget
import com.cordiact.core.plugin.RuntimeAwarePlugin
import com.cordiact.core.search.SEARCH_PLUGIN_ID
import com.cordiact.core.search.SearchProvider
import com.cordiact.core.search.SearchProviderRegistry

internal fun bundledSearchProviderSpec(id: String): PluginSpec {
    require(id in setOf("tavily", "brave")) { "Unknown built-in search publisher" }
    return PluginSpec("core.search.provider.$id", "0.1.0", PluginTarget.ANDROID_NATIVE,
        dependencies = setOf(SEARCH_PLUGIN_ID))
}

/** Close the provider's single registration on withdrawal; endpoint and credentials stay host-owned. */
internal class BundledSearchProviderPlugin(
    override val spec: PluginSpec,
    kernel: PluginKernel,
    registry: SearchProviderRegistry,
    provider: SearchProvider,
) : RuntimeAwarePlugin {
    init { require(spec == bundledSearchProviderSpec(provider.id)) }

    private val runtime = ContextManagedPlugin(spec, kernel, object : ContextPlugin {
        override fun start(context: PluginContext) = registry.registerSearch(context, provider)
    })
    override fun onRegister() = runtime.onRegister()
    override fun onEnable() { runtime.onRegister(); runtime.onEnable() }
    override fun onDisable() = runtime.onDisable()
    override fun onUninstall() = runtime.onUninstall()
    override fun runtimeStatus() = runtime.runtimeStatus()
}
