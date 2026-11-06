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
                    "flow" -> probeFlow(app)
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

    /** End-to-end P0: create -> launch -> capture -> tap -> capture -> key -> destroy. */
    private fun probeFlow(app: CordiActApplication): JSONObject {
        val binder = app.automationRuntime.shizukuChannel.binder()
            ?: return JSONObject().put("ok", false).put("stage", "bind")
        val created = binder.createDisplay(720, 1280, 320)
        val id = created.getInt("displayId", -1)
        if (id <= 0) {
            val fields = JSONObject()
            for (key in created.keySet()) fields.put(key, created.get(key))
            return JSONObject().put("ok", false).put("stage", "create").put("raw", fields)
        }
        val steps = JSONObject()
        try {
            val launch = binder.launchOnDisplay(id, "com.android.settings", null)
            steps.put("launchOk", launch.getBoolean("ok"))
            steps.put("launchMessage", launch.getString("message") ?: launch.getString("stdout"))
            Thread.sleep(2_500)

            val first = binder.captureDisplay(id)
            steps.put("capture1", first?.let { "${it.width}x${it.height}" })
            steps.put("sample1", first?.let { sample(it) })

            val tapped = binder.tap(id, 360, 240, 60L)
            steps.put("tap", tapped)
            Thread.sleep(1_200)
            val second = binder.captureDisplay(id)
            steps.put("sample2", second?.let { sample(it) })
            steps.put("changedAfterTap", steps.opt("sample1") != steps.opt("sample2"))

            val backOk = binder.key(id, 4)
            steps.put("key", backOk)
            Thread.sleep(1_200)
            val third = binder.captureDisplay(id)
            steps.put("sample3", third?.let { sample(it) })

            val tree = binder.dumpDisplay(id)
            steps.put("treeError", tree.getString("error"))

            steps.put("visible", displayVisible(id))
        } finally {
            runCatching { binder.destroyDisplay(id) }
            steps.put("destroyed", !displayVisible(id))
        }
        return JSONObject().put("ok", true).put("displayId", id).put("steps", steps)
    }

    private fun sample(bitmap: android.graphics.Bitmap): String {
        val software = bitmap.copy(android.graphics.Bitmap.Config.ARGB_8888, false) ?: return "copy_failed"
        val points = listOf(
            software.width / 4 to software.height / 4,
            software.width / 2 to software.height / 2,
            software.width / 4 to software.height * 3 / 4,
            software.width * 3 / 4 to software.height / 4,
        )
        val sum = points.sumOf { (x, y) -> software.getPixel(x, y) }
        val text = software.width.toString() + "x" + software.height + ":" + Integer.toHexString(sum)
        software.recycle()
        return text
    }

    private fun displayVisible(id: Int): Boolean = runCatching {
        val process = ProcessBuilder("/system/bin/sh", "-c", "dumpsys display | grep -c 'mDisplayId=$id'")
            .start()
        process.waitFor()
        process.inputStream.bufferedReader().readText().trim().toIntOrNull() ?: 0
    }.getOrDefault(0) > 0

    companion object {
        const val ACTION_PROBE = "com.cordiact.app.PROBE"
    }
}
