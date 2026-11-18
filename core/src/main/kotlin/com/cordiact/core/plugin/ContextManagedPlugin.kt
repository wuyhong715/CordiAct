package com.cordiact.core.plugin

/**
 * Opt-in bridge for trusted bundled plugins. Existing durable lifecycle/authorization stays
 * authoritative; implementations migrate incrementally without replacing ManagedPlugin.
 * Durable ENABLED means enable intent; runtimeStatus separately reports ACTIVE/WAITING/FAILED.
 */
class ContextManagedPlugin(
    override val spec: PluginSpec,
    private val kernel: PluginKernel,
    private val implementation: ContextPlugin,
    private val instanceId: PluginInstanceId = PluginInstanceId(spec.id),
) : RuntimeAwarePlugin {
    init {
        require(instanceId.pluginId == spec.id)
    }
    @Volatile private var registered = false

    @Synchronized override fun onRegister() {
        if (!registered) {
            kernel.register(instanceId, implementation)
            registered = true
        }
    }

    @Synchronized override fun onEnable() {
        check(registered) { "Plugin must be registered before enable" }
        try {
            kernel.enable(instanceId)
        } catch (failure: Exception) {
            try { kernel.disable(instanceId) } catch (cleanup: Exception) { failure.addSuppressed(cleanup) }
            throw failure
        }
        val status = kernel.status(instanceId)
        if (status.state != PluginInstanceState.ACTIVE && status.state != PluginInstanceState.WAITING) {
            val failure = IllegalStateException(
                "Plugin instance not active: ${status.state}; missing=${status.missingServices}; error=${status.lastError}"
            )
            // A rejected legacy enable must not leave a desired WAITING instance that starts
            // later, behind the durable lifecycle manager's FAILED/disabled record.
            try { kernel.disable(instanceId) } catch (cleanup: Exception) { failure.addSuppressed(cleanup) }
            throw failure
        }
    }

    override fun runtimeStatus(): PluginInstanceStatus? =
        if (registered) kernel.status(instanceId) else null

    @Synchronized override fun onDisable() {
        if (registered) kernel.disable(instanceId)
    }

    @Synchronized override fun onUninstall() {
        if (registered) {
            kernel.unregister(instanceId)
            registered = false
        }
    }
}
