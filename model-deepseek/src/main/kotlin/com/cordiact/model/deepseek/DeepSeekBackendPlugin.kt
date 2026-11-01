package com.cordiact.model.deepseek

import com.cordiact.core.conversation.MODEL_ADAPTER_PLUGIN_ID
import com.cordiact.core.conversation.ModelAdapterPlugin
import com.cordiact.core.plugin.ManagedPlugin
import com.cordiact.core.plugin.PluginSpec
import com.cordiact.core.plugin.PluginTarget

const val DEEPSEEK_BACKEND_PLUGIN_ID = "model.deepseek"

fun deepSeekBackendPluginSpec() = PluginSpec(
    id = DEEPSEEK_BACKEND_PLUGIN_ID,
    version = "0.1.0",
    target = PluginTarget.ANDROID_NATIVE,
    dependencies = setOf(MODEL_ADAPTER_PLUGIN_ID),
)

/** Registers and revokes the provider backend through the protected Model Adapter service. */
class DeepSeekBackendPlugin(
    private val adapter: ModelAdapterPlugin,
    private val backend: DeepSeekBackend,
) : ManagedPlugin {
    override val spec = deepSeekBackendPluginSpec()

    override fun onEnable() {
        adapter.attachBackend(spec.id, backend)
    }

    override fun onDisable() {
        backend.cancelActiveCalls()
        adapter.detachBackend(spec.id, backend.providerId)
    }

    override fun onUninstall() {
        onDisable()
        backend.close()
    }
}
