package com.cordiact.app.plugin

import com.cordiact.core.plugin.PluginRecordStore
import com.cordiact.core.plugin.PluginRuntimeResources
import com.cordiact.core.plugin.PluginState
import java.util.concurrent.ConcurrentHashMap

/** A live native/JS runtime instance. Implementations must make cleanup operations idempotent. */
interface AndroidPluginRuntimeController {
    fun quiesce()
    fun resume()
    fun stopAndDrain()
    fun detach()
}

/**
 * Host-owned controller registry. Controllers cancel their own jobs/subscriptions; the adapter
 * removes them only after successful detach so a tombstoned uninstall can retry cleanup.
 */
class AndroidPluginRuntimeResources(
    private val storage: AndroidPluginStorage,
    private val resourceLedger: AndroidPluginResourceLedger,
    private val records: PluginRecordStore,
) : PluginRuntimeResources {
    private val controllers = ConcurrentHashMap<String, AndroidPluginRuntimeController>()

    fun attach(pluginId: String, controller: AndroidPluginRuntimeController) {
        requirePluginId(pluginId)
        val state = records.get(pluginId)?.state
        check(state == PluginState.ENABLING || state == PluginState.ENABLED) {
            "Cannot attach runtime for '$pluginId' while it is $state"
        }
        check(controllers.putIfAbsent(pluginId, controller) == null) {
            "A runtime controller is already attached for '$pluginId'"
        }
    }

    override fun quiesce(id: String) {
        requirePluginId(id)
        controllers[id]?.quiesce()
    }

    override fun resume(id: String) {
        requirePluginId(id)
        controllers[id]?.resume()
    }

    override fun stopAndDrain(id: String) {
        requirePluginId(id)
        var failure: Exception? = null
        try { controllers[id]?.stopAndDrain() } catch (caught: Exception) { failure = caught }
        // Also cancel durable host resources (scheduled work, alarms, observers, etc.) so a
        // disabled plugin cannot continue acting after its in-process runtime has drained.
        try { resourceLedger.cleanupAll(id) } catch (caught: Exception) {
            val previous = failure
            if (previous == null) failure = caught else previous.addSuppressed(caught)
        }
        failure?.let { throw it }
    }

    override fun detach(id: String) {
        requirePluginId(id)
        val controller = controllers[id]
        var failure: Exception? = null
        try { controller?.detach() } catch (caught: Exception) { failure = caught }
        failure?.let { throw it }
        if (controller != null) controllers.remove(id, controller)
    }

    override fun removeInstalledArtifacts(id: String) {
        storage.removeArtifacts(id)
    }

    private fun requirePluginId(id: String) {
        require(id.matches(PLUGIN_ID_PATTERN)) { "Invalid plugin id '$id'" }
    }

    private companion object {
        val PLUGIN_ID_PATTERN = Regex("[a-z0-9][a-z0-9._-]{1,127}")
    }
}
