package com.cordiact.app.privilege

import android.content.Context
import android.content.Intent
import android.os.Looper
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.os.Bundle
import android.view.KeyEvent
import android.os.Handler
import android.os.HandlerThread
import com.cordiact.app.display.DisplayTreeReader
import com.cordiact.app.display.SnapshotBundle
import com.cordiact.core.automation.ScreenHandle
import com.cordiact.core.automation.ShellLimits
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
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

    /** Looper thread for UiAutomation reads: the automation posts to its creator's looper. */
    private val treeThread: HandlerThread by lazy {
        HandlerThread("cordiact-tree").apply { start() }
    }
    private val treeHandler: Handler by lazy { Handler(treeThread.looper) }

    /**
     * No Application exists in a Shizuku user-service process, so borrow the
     * system context. `ActivityThread.systemMain()` builds a Handler from the
     * *calling* thread's Looper, and binder threads have none — prepare one
     * first, then fall back to an existing ActivityThread if systemMain refuses.
     */
    private val context: Context by lazy {
        if (Looper.myLooper() == null) Looper.prepare()
        val activityThread = Class.forName("android.app.ActivityThread")
        val thread = runCatching { activityThread.getMethod("systemMain").invoke(null) }
            .getOrElse { activityThread.getMethod("currentActivityThread").invoke(null) }
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

    /**
     * `input text` goes through the key character map and simply cannot carry
     * Chinese; accessibility can, so that is the primary path and the shell
     * command stays as a fallback for displays with no readable node.
     */
    override fun text(displayId: Int, value: String): Boolean {
        val typed = typeText(displayId, value, submit = false)
        if (typed.getBoolean("ok")) return true
        return runCommand("input -d $displayId text ${shellQuote(value)}", 15_000).getInt("exitCode", -1) == 0
    }

    override fun typeText(displayId: Int, value: String, submit: Boolean): Bundle {
        if (!ownsDisplay(displayId)) return errorBundle("display_missing", missingDisplayMessage(displayId))
        val outcome = onTreeThread(DUMP_TIMEOUT_MS) { reader -> reader.typeText(displayId, value) }
            ?: return errorBundle("timeout", "Typing into display $displayId timed out after ${DUMP_TIMEOUT_MS}ms")
        if (submit && outcome.ok) {
            key(displayId, KeyEvent.KEYCODE_ENTER)
        }
        return Bundle().apply {
            putBoolean("ok", outcome.ok)
            putString("method", outcome.method)
            putString("package", outcome.packageName)
            putString("observed", outcome.observed)
            outcome.error?.let { putString("message", it) }
        }
    }

    override fun dumpWeb(displayId: Int, maxChars: Int): Bundle {
        // Display 0 is the phone's own screen: readable through the same channel,
        // and useful when the app's accessibility service is off.
        if (displayId != android.view.Display.DEFAULT_DISPLAY && !ownsDisplay(displayId)) {
            return errorBundle("display_missing", missingDisplayMessage(displayId))
        }
        val document = onTreeThread(DUMP_TIMEOUT_MS) { reader ->
            reader.webDocument(displayId, ScreenHandle.background(0), maxChars.coerceIn(500, 20_000))
        }
        if (document == null) {
            return errorBundle("no_webview", "Display $displayId shows no readable WebView")
        }
        document.error?.let { failure ->
            return errorBundle(failure.code, failure.message)
        }
        return Bundle().apply {
            putBoolean("ok", true)
            putString("title", document.title)
            putString("url", document.url)
            putString("text", document.text)
            putInt("elements", document.elements)
            putBoolean("truncated", document.truncated)
            putInt("links", document.links.size)
            putString("linkLabels", document.links.take(10).joinToString(" | ") { it.label })
        }
    }

    private fun ownsDisplay(displayId: Int): Boolean = displayId > 0 && displays.containsKey(displayId)

    private fun missingDisplayMessage(displayId: Int): String =
        if (displayId <= 0) "Display $displayId does not exist"
        else "Display $displayId is not owned by this process"

    /**
     * Reads the accessibility tree of a background display.
     *
     * The virtual display has no accessibility service; the tree comes from a
     * UiAutomation bound to *that display's* context. UiAutomation delivers its
     * window callbacks through the looper of the thread that created it, so the
     * whole read runs on a dedicated HandlerThread and this binder thread only
     * waits for the result.
     */
    override fun dumpDisplay(displayId: Int): Bundle {
        if (!ownsDisplay(displayId)) return errorBundle("display_missing", missingDisplayMessage(displayId))
        val result = onTreeThread(DUMP_TIMEOUT_MS) { reader ->
            reader.snapshot(displayId, ScreenHandle.background(0))
        } ?: return errorBundle(
            "text_connection_unavailable",
            "Timed out reading the tree of display $displayId after ${DUMP_TIMEOUT_MS}ms",
        )
        return SnapshotBundle.encode(result)
    }

    /**
     * Runs an accessibility read on the dedicated tree thread. UiAutomation
     * dispatches its callbacks through the looper of the thread that created it,
     * so the work must not run on this binder thread.
     */
    private fun <T> onTreeThread(timeoutMs: Long, block: (DisplayTreeReader) -> T): T? {
        val latch = CountDownLatch(1)
        val holder = arrayOfNulls<Any>(1)
        val posted = treeHandler.post {
            try {
                holder[0] = block(DisplayTreeReader(context))
            } catch (failure: Throwable) {
                holder[0] = null
            } finally {
                latch.countDown()
            }
        }
        if (!posted || !latch.await(timeoutMs, TimeUnit.MILLISECONDS)) return null
        @Suppress("UNCHECKED_CAST")
        return holder[0] as? T
    }

    private fun errorBundle(code: String, message: String): Bundle = Bundle().apply {
        putString("error", code)
        putString("message", message)
    }

    override fun handoff(displayId: Int): Boolean =
        runCommand("am display move-stack $displayId 0", 10_000).getInt("exitCode", -1) == 0

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
        // The user service runs as uid 2000 (shell) but a system context reports
        // opPackageName "android" (uid 1000); DisplayManagerService rejects that
        // with "packageName must match the calling uid". Borrow the shell package.
        val displayContext = runCatching {
            context.createPackageContext("com.android.shell", 0)
        }.getOrDefault(context)
        val manager = displayContext.getSystemService(DisplayManager::class.java)
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
        // context.startActivity would carry the system context's identity, which
        // ActivityManager rejects for uid 2000; `am` runs with the shell identity.
        val command = if (activity != null) {
            "am start --display $displayId -n $packageName/$activity"
        } else {
            "am start --display $displayId -a android.intent.action.MAIN " +
                "-c android.intent.category.LAUNCHER -p $packageName"
        }
        val result = runCommand(command, 20_000)
        if (result.getInt("exitCode", -1) == 0 && !result.getString("stdout").orEmpty().contains("Error")) {
            putBoolean("ok", true)
            putString("stdout", result.getString("stdout"))
        } else {
            putString("error", "launch_failed")
            putString("message", (result.getString("stdout").orEmpty() + result.getString("stderr").orEmpty()).trim())
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
        const val DUMP_TIMEOUT_MS = 15_000L
    }
}
