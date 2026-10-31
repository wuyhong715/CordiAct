package com.cordiact.app.plugin

import android.content.Context
import com.cordiact.core.plugin.BundledPluginVerifier
import com.cordiact.core.plugin.PluginLifecycleManager

/** Composition root. Call recoverBeforeDispatch() before exposing any plugin routing/UI actions. */
class AndroidPluginLifecycle(
    context: Context,
    bundledPluginVerifier: BundledPluginVerifier,
    resourceCleaners: Map<String, AndroidPluginResourceCleaner> = emptyMap(),
) : AutoCloseable {
    val store = AndroidPluginLifecycleStore(context)
    val storage = AndroidPluginStorage(context)
    val resourceLedger = AndroidPluginResourceLedger(store, resourceCleaners)
    val runtime = AndroidPluginRuntimeResources(storage, resourceLedger, store)
    val manager = PluginLifecycleManager(
        records = store,
        runtime = runtime,
        capabilities = store,
        ownedData = storage,
        bundledVerifier = bundledPluginVerifier,
    )

    fun recoverBeforeDispatch(): List<String> = manager.recoverAfterProcessRestart()

    override fun close() = store.close()
}
