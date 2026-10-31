package com.cordiact.app.plugin

/** Cleanup intent persisted before a plugin creates a process-external/Android resource. */
data class AndroidPluginResource(
    val pluginId: String,
    val kind: String,
    val key: String,
    val payload: String,
)

/** A host-owned cleanup adapter for one allowlisted resource kind. Implement cleanup idempotently. */
fun interface AndroidPluginResourceCleaner {
    fun cleanup(resource: AndroidPluginResource)
}

/**
 * Durable cleanup ledger. Unknown kinds and failed cleaners remain recorded and block final uninstall;
 * rows are removed only after the corresponding external cleanup succeeds.
 */
class AndroidPluginResourceLedger(
    private val store: AndroidPluginLifecycleStore,
    private val cleaners: Map<String, AndroidPluginResourceCleaner>,
) {
    fun track(pluginId: String, kind: String, key: String, payload: String = "") {
        store.recordOwnedResource(pluginId, kind, key, payload)
    }

    fun forget(pluginId: String, kind: String, key: String) {
        store.forgetOwnedResource(pluginId, kind, key)
    }

    fun cleanupAll(pluginId: String) {
        val failures = mutableListOf<Exception>()
        store.ownedResources(pluginId).forEach { resource ->
            try {
                val cleaner = cleaners[resource.kind]
                    ?: throw IllegalStateException("No trusted cleaner registered for '${resource.kind}'")
                cleaner.cleanup(resource)
                store.forgetOwnedResource(pluginId, resource.kind, resource.key)
            } catch (failure: Exception) {
                failures += failure
            }
        }
        if (failures.isNotEmpty()) {
            val error = IllegalStateException(
                "${failures.size} plugin-owned resource(s) could not be cleaned for '$pluginId'",
                failures.first(),
            )
            failures.drop(1).forEach(error::addSuppressed)
            throw error
        }
    }
}
