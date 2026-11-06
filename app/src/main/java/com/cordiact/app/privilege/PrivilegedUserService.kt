package com.cordiact.app.privilege

import android.content.Context
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.os.Bundle
import com.cordiact.core.automation.ShellLimits
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Entry point for the Shizuku user service. Shizuku starts this class in a
 * process running as the shell uid, so everything here has shell permissions.
 *
 * The virtual display lives in *this* process on purpose: a display created by
 * the app process is private (`FLAG_PRIVATE|OWN_CONTENT_ONLY`), its frames are
 * only reachable by its creator, and `screencap -d` refuses it. Created here,
 * with the shell uid, the display is public and `screencap`/`input -d` work.
 */
class PrivilegedUserService : IPrivilegedService.Stub() {

    private val displays = ConcurrentHashMap<Int, DisplayEntry>()

    /** No Application in a Shizuku user-service process: borrow the system context. */
    private val context: Context by lazy {
        val activityThread = Class.forName("android.app.ActivityThread")
        val thread = activityThread.getMethod("systemMain").invoke(null)
        activityThread.getMethod("getSystemContext").invoke(thread) as Context
    }

    private class DisplayEntry(
        val display: VirtualDisplay,
        val reader: ImageReader,
        val width: Int,
        val height: Int,
    )

    override fun exec(command: String, timeoutMs: Long): Bundle = runCommand(command, timeoutMs)

    override fun uid(): Int = android.os.Process.myUid()

    override fun createDisplay(width: Int, height: Int, dpi: Int): Bundle = createDisplayInternal(width, height, dpi)

    override fun destroyDisplay(displayId: Int): Boolean = destroyDisplayInternal(displayId)

    override fun captureDisplay(displayId: Int): Bitmap? = captureInternal(displayId)

    override fun launchOnDisplay(displayId: Int, packageName: String, activity: String?): Bundle =
        launchInternal(displayId, packageName, activity)

    override fun tap(displayId: Int, x: Int, y: Int, durationMs: Long): Boolean =
        runCommand("input -d $displayId tap $x $y", 10_000).getInt("exitCode", -1) == 0

    override fun swipe(displayId: Int, x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Long): Boolean =
        runCommand("input -d $displayId swipe $x1 $y1 $x2 $y2 ${durationMs.coerceIn(50, 3_000)}", 15_000)
            .getInt("exitCode", -1) == 0

    override fun key(displayId: Int, keyCode: Int): Boolean =
        runCommand("input -d $displayId keyevent $keyCode", 10_000).getInt("exitCode", -1) == 0

    override fun text(displayId: Int, value: String): Boolean =
        runCommand("input -d $displayId text ${shellQuote(value)}", 15_000).getInt("exitCode", -1) == 0

    override fun dumpDisplay(displayId: Int): Bundle = Bundle().apply {
        // UiAutomation bound to a secondary display is not wired up yet; report
        // the limitation instead of returning a misleading empty tree.
        putString("error", "text_connection_unavailable")
        putString("message", "Tree read for virtual displays requires the UiAutomation display channel (Android 14+), not implemented yet.")
    }

    override fun handoff(displayId: Int): Boolean =
        runCommand("am display move-stack $displayId 0", 10_000).getInt("exitCode", -1) == 0
    }


    // ------------------------------------------------------------- display ops

    private fun createDisplayInternal(width: Int, height: Int, dpi: Int): Bundle = Bundle().apply {
        if (width < 320 || height < 320 || dpi !in 120..640) {
            putString("error", "invalid_arguments")
            return@apply
        }
        if (displays.size >= MAX_DISPLAYS) {
            putString("error", "too_many_displays")
            return@apply
        }
        val alignedWidth = (width + 15) and 15.inv()
        val alignedHeight = (height + 15) and 15.inv()
        val manager = context.getSystemService(DisplayManager::class.java)
        val reader = ImageReader.newInstance(alignedWidth, alignedHeight, PixelFormat.RGBA_8888, 2)
        val flags = reflectionFlags()
        val display = try {
            manager.createVirtualDisplay(
                "cordiact-bg-${System.currentTimeMillis()}",
                alignedWidth,
                alignedHeight,
                dpi,
                reader.surface,
                flags,
            )
        } catch (failure: Exception) {
            reader.close()
            putString("error", "display_unsupported")
            putString("message", failure.message ?: "createVirtualDisplay failed")
            return@apply
        }
        if (display == null || display.display.displayId <= 0) {
            reader.close()
            putString("error", "display_unsupported")
            return@apply
        }
        displays[display.display.displayId] = DisplayEntry(display, reader, alignedWidth, alignedHeight)
        putInt("displayId", display.display.displayId)
        putInt("width", alignedWidth)
        putInt("height", alignedHeight)
        putInt("dpi", dpi)
    }

    private fun destroyDisplayInternal(displayId: Int): Boolean {
        val entry = displays.remove(displayId) ?: return false
        runCatching { entry.display.release() }
        runCatching { entry.reader.close() }
        return true
    }

    private fun captureInternal(displayId: Int): Bitmap? {
        val entry = displays[displayId] ?: return null
        val image = runCatching { entry.reader.acquireLatestImage() }.getOrNull() ?: return null
        return try {
            val plane = image.planes[0]
            val buffer = plane.buffer
            val pixelStride = plane.pixelStride
            val rowStride = plane.rowStride
            val rowPadding = rowStride - pixelStride * entry.width
            val paddedWidth = entry.width + rowPadding / pixelStride
            val bitmap = Bitmap.createBitmap(paddedWidth, entry.height, Bitmap.Config.ARGB_8888)
            buffer.rewind()
            bitmap.copyPixelsFromBuffer(buffer)
            if (paddedWidth == entry.width) bitmap else Bitmap.createBitmap(bitmap, 0, 0, entry.width, entry.height)
        } catch (failure: Exception) {
            null
        } finally {
            image.close()
        }
    }

    private fun launchInternal(displayId: Int, packageName: String, activity: String?): Bundle = Bundle().apply {
        if (displays[displayId] == null) {
            putString("error", "unknown_display")
            return@apply
        }
        val intent = if (activity != null) {
            Intent().apply { setClassName(packageName, activity) }
        } else {
            packageManager.getLaunchIntentForPackage(packageName)
        }
        if (intent == null) {
            putString("error", "package_not_found")
            return@apply
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
        val options = android.app.ActivityOptions.makeBasic().setLaunchDisplayId(displayId)
        try {
            startActivity(intent, options.toBundle())
            putBoolean("ok", true)
        } catch (failure: Exception) {
            putString("error", "launch_failed")
            putString("message", failure.message ?: "startActivity failed")
        }
    }

    // --------------------------------------------------------------- shell ops

    private fun runCommand(command: String, timeoutMs: Long): Bundle = Bundle().apply {
        val budget = timeoutMs.coerceIn(1_000L, ShellLimits.MAX_TIMEOUT_MS)
        val process = try {
            ProcessBuilder("/system/bin/sh", "-c", command).start()
        } catch (failure: Exception) {
            putInt("exitCode", -1)
            putString("stdout", "")
            putString("stderr", failure.message ?: "spawn failed")
            putBoolean("timedOut", false)
            putBoolean("truncated", false)
            return@apply
        }

        val finished = process.waitFor(budget, TimeUnit.MILLISECONDS)
        if (!finished) process.destroyForcibly()
        putBoolean("timedOut", !finished)

        val stdout = process.inputStream.bufferedReader().use { it.readBounded() }
        val stderr = process.errorStream.bufferedReader().use { it.readBounded() }
        putInt("exitCode", if (finished) process.exitValue() else -1)
        putString("stdout", stdout.first)
        putString("stderr", stderr.first)
        putBoolean("truncated", stdout.second || stderr.second)
    }

    private fun java.io.Reader.readBounded(): Pair<String, Boolean> {
        val buffer = CharArray(8 * 1024)
        val out = StringBuilder()
        var truncated = false
        while (true) {
            val read = read(buffer)
            if (read <= 0) break
            if (out.length + read > ShellLimits.MAX_OUTPUT_CHARS) {
                out.append(buffer, 0, (ShellLimits.MAX_OUTPUT_CHARS - out.length).coerceAtLeast(0))
                truncated = true
                break
            }
            out.append(buffer, 0, read)
        }
        return out.toString() to truncated
    }

    private fun shellQuote(value: String): String = "'" + value.replace("'", "'\\''") + "'"

    /**
     * PUBLIC is what makes the display visible to the system and to
     * `screencap`; TRUSTED/OWN_FOCUS keep input focus on the virtual screen
     * instead of stealing it from the physical one.
     */
    private fun reflectionFlags(): Int {
        val names = listOf(
            "VIRTUAL_DISPLAY_FLAG_PUBLIC",
            "VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY",
            "VIRTUAL_DISPLAY_FLAG_SUPPORTS_TOUCH",
            "VIRTUAL_DISPLAY_FLAG_DESTROY_CONTENT_ON_REMOVAL",
            "VIRTUAL_DISPLAY_FLAG_TRUSTED",
            "VIRTUAL_DISPLAY_FLAG_OWN_FOCUS",
            "VIRTUAL_DISPLAY_FLAG_STEAL_TOP_FOCUS_DISABLED",
        )
        val field = DisplayManager::class.java
        return names.fold(0) { bits, name ->
            bits or runCatching { field.getDeclaredField(name).getInt(null) }.getOrDefault(0)
        }
    }

    private companion object {
        const val MAX_DISPLAYS = 2
    }
}
