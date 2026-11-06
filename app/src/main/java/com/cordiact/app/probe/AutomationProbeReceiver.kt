package com.cordiact.app.probe

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.cordiact.app.CordiActApplication
import com.cordiact.core.automation.ScreenHandle
import org.json.JSONObject
import java.io.File
import kotlin.concurrent.thread

/**
 * Debug-only entry point used by the P0 device harness.
 *
 * The agent loop cannot be driven from adb, so this receiver runs one named
 * probe against the real plugin objects and writes a JSON result into the
 * app's external files dir (`/sdcard/Android/data/com.cordiact.app/files/`).
 *
 * Guarded by `android.permission.DUMP`: shell/adb holds it, ordinary apps do
 * not. Remove together with the debug build.
 */
class AutomationProbeReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_PROBE) return
        val app = context.applicationContext as? CordiActApplication ?: return
        val runtime = app.automationRuntime
        val case = intent.getStringExtra("case") ?: "status"
        val out = File(context.getExternalFilesDir(null), "probe-$case.json")

        val pending = goAsync()
        thread(name = "automation-probe-$case") {
            val result = try {
                when (case) {
                    "a11y" -> probeA11y(app)
                    "priv" -> probePriv(app)
                    "priv_request" -> probePrivRequest(app)
                    "display" -> probeDisplay(app)
                    else -> JSONObject().put("ok", false).put("error", "unknown case: $case")
                }
            } catch (failure: Throwable) {
                JSONObject()
                    .put("ok", false)
                    .put("error", failure::class.java.simpleName)
                    .put("message", failure.message)
            }
            out.writeText(result.toString(2))
            android.util.Log.i("CordiActProbe", "$case -> ${result}")
            pending.finish()
        }
    }

    private fun probeA11y(app: CordiActApplication): JSONObject {
        val runtime = app.automationRuntime
        val snapshot = runtime.screenService.snapshot(ScreenHandle.main(), 40)
        return JSONObject()
            .put("ok", snapshot.error == null)
            .put("connected", com.cordiact.app.screen.ScreenServiceBridge.connected)
            .put("package", snapshot.packageName)
            .put("title", snapshot.title)
            .put("width", snapshot.width)
            .put("height", snapshot.height)
            .put("elements", snapshot.elements.size)
            .put("error", snapshot.error?.code)
            .put("text", com.cordiact.core.automation.ScreenFormat.render(snapshot).take(1_500))
    }

    private fun probePriv(app: CordiActApplication): JSONObject {
        val runtime = app.automationRuntime
        val status = runtime.privilegeService.status()
        val exec = if (status.ready) runtime.privilegeService.exec("id", 5_000, advanced = false) else null
        return JSONObject()
            .put("ok", status.ready)
            .put("level", status.level.name)
            .put("backend", status.backend)
            .put("reason", status.reason)
            .put("recovery", status.recovery)
            .put("execExit", exec?.exitCode)
            .put("execOut", exec?.stdout?.trim())
            .put("execErr", exec?.stderr?.trim())
    }

    private fun probePrivRequest(app: CordiActApplication): JSONObject {
        app.automationRuntime.shizukuChannel.requestPermission { }
        return JSONObject().put("ok", true).put("requested", true)
    }

    private fun probeDisplay(app: CordiActApplication): JSONObject {
        val runtime = app.automationRuntime
        val binder = runtime.shizukuChannel.binder()
            ?: return JSONObject().put("ok", false).put("stage", "bind")
        val raw = binder.createDisplay(720, 1280, 320)
        val fields = JSONObject()
        for (key in raw.keySet()) fields.put(key, raw.get(key))
        if (raw.getInt("displayId", -1) <= 0) {
            return JSONObject().put("ok", false).put("stage", "create").put("raw", fields)
        }
        val id = raw.getInt("displayId")
        val launched = binder.launchOnDisplay(id, "com.android.settings", null)
        Thread.sleep(1_500)
        val capture = binder.captureDisplay(id)
        val result = JSONObject()
            .put("ok", true)
            .put("stage", "created")
            .put("displayId", id)
            .put("launchError", launched.getString("error"))
            .put("capture", capture?.let { "${it.width}x${it.height}" })
            .put("raw", fields)
        runCatching { binder.destroyDisplay(id) }
        return result
    }

    companion object {
        const val ACTION_PROBE = "com.cordiact.app.PROBE"
    }
}
