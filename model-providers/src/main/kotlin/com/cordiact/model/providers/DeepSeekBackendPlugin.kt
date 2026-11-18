package com.cordiact.model.providers

import com.cordiact.core.conversation.MODEL_ADAPTER_PLUGIN_ID
import com.cordiact.core.conversation.ModelAdapterPlugin
import com.cordiact.core.plugin.ManagedPlugin
import com.cordiact.core.plugin.PluginScope
import com.cordiact.core.plugin.PluginSpec
import com.cordiact.core.plugin.PluginTarget

const val DEEPSEEK_BACKEND_PLUGIN_ID = "model.deepseek"

fun deepSeekBackendPluginSpec() = PluginSpec(
    id = DEEPSEEK_BACKEND_PLUGIN_ID,
    version = "0.1.0",
    target = PluginTarget.ANDROID_NATIVE,
    dependencies = setOf(MODEL_ADAPTER_PLUGIN_ID),
)

/**
 * Registers and revokes the bundled DeepSeek provider through the protected Model Adapter service.
 * The backend is an ordinary [ConfigurableModelBackend] preset; there is no DeepSeek-specific transport.
 */
class DeepSeekBackendPlugin(
    private val adapter: ModelAdapterPlugin,
    private val backend: ConfigurableModelBackend,
) : ManagedPlugin {
    override val spec = deepSeekBackendPluginSpec()

    init {
        require(backend.providerId == DeepSeekPreset.PROVIDER_ID) {
            "DeepSeek plugin requires the '${DeepSeekPreset.PROVIDER_ID}' provider"
        }
    }

    private var resources: PluginScope? = null

    @Synchronized override fun onEnable() {
        if (resources?.isOpen == true) return
        resources?.let { it.close(); resources = null }
        val scope = PluginScope()
        resources = scope
        try {
            adapter.attachBackend(spec.id, backend)
            // Only acquire cleanup ownership after attach succeeds: a conflicting owner must
            // never have its backend detached by our failed initialization.
            scope.effect { adapter.detachBackend(spec.id, backend.providerId) }
            scope.effect { backend.cancelActiveCalls() }
        } catch (failure: Exception) {
            try { scope.close(); resources = null } catch (cleanup: Exception) { failure.addSuppressed(cleanup) }
            throw failure
        }
    }

    @Synchronized override fun onDisable() {
        resources?.close()
        resources = null
    }

    override fun onUninstall() {
        onDisable()
        backend.close()
    }
}
