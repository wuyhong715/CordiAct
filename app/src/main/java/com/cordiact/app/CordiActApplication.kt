package com.cordiact.app

import android.app.Application
import android.util.Log
import com.cordiact.app.plugin.AndroidPluginLifecycle
import com.cordiact.core.plugin.BundledPluginVerifier

/** Creates the lifecycle host and completes fail-closed recovery before any plugin dispatch starts. */
class CordiActApplication : Application() {
    lateinit var pluginLifecycle: AndroidPluginLifecycle
        private set

    override fun onCreate() {
        super.onCreate()
        pluginLifecycle = AndroidPluginLifecycle(this, BundledPluginVerifier { false })
        val needsRepair = pluginLifecycle.recoverBeforeDispatch()
        if (needsRepair.isNotEmpty()) {
            Log.e(TAG, "Plugin lifecycle recovery remains incomplete; blocked plugin ids: $needsRepair")
        }
    }

    private companion object { const val TAG = "CordiActPluginHost" }
}
