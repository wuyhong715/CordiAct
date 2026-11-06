package com.cordiact.app.display

import com.cordiact.app.privilege.IPrivilegedService
import com.cordiact.app.privilege.ShizukuChannel
import com.cordiact.core.automation.ActionResult
import com.cordiact.core.automation.PrivilegeLevel
import com.cordiact.core.automation.ScreenDriver
import com.cordiact.core.automation.ScreenError
import com.cordiact.core.automation.ScreenErrorCodes
import com.cordiact.core.automation.ScreenHandle
import com.cordiact.core.automation.ScreenKey
import com.cordiact.core.automation.ScreenSnapshot
import com.cordiact.core.automation.ScreenTarget
import com.cordiact.core.automation.ScrollDirection

/**
 * Drives a background virtual screen.
 *
 * The display is created inside the privileged shell process (see
 * `PrivilegedUserService`). A display created by the app process is private:
 * its frames are only reachable by its creator and `screencap -d` refuses it.
 * Keeping creation, capture and input in the same shell process avoids that
 * whole class of failure.
 *
 * Tree reads are not wired yet: UiAutomation bound to a secondary display needs
 * a dedicated channel (API 34+). Until then [snapshot] returns a structured
 * `text_connection_unavailable` error instead of pretending the screen is empty.
 */
class BackgroundScreenDriver(
    private val shizuku: ShizukuChannel,
    override val handle: ScreenHandle,
    private val width: Int = DEFAULT_WIDTH,
    private val height: Int = DEFAULT_HEIGHT,
    private val densityDpi: Int = DEFAULT_DPI,
) : ScreenDriver {

    @Volatile
    private var displayId: Int = -1

    override fun snapshot(maxNodes: Int, includeImage: Boolean): ScreenSnapshot {
        val id = ensureDisplay()
            ?: return failure(
                ScreenErrorCodes.REQUIRES_PRIVILEGE,
                "Background screens need a privileged channel",
                "Enable the privilege plugin and start Shizuku (or root), then retry.",
            )

        val dump = runCatching { service()?.dumpDisplay(id) }.getOrNull()
        val error = dump?.getString("error")
        if (error != null) {
            return failure(
                ScreenErrorCodes.TEXT_CONNECTION_UNAVAILABLE,
                "Display $id is running, but its accessibility tree is not readable yet",
                "Use screen_open and coordinate taps; tree reads land with the UiAutomation channel.",
            )
        }
        return failure(
            ScreenErrorCodes.TEXT_CONNECTION_UNAVAILABLE,
            "Display $id returned no tree",
            "Retry after the app draws its first frame.",
        )
    }

    override fun tap(target: ScreenTarget): ActionResult {
        val id = ensureDisplay() ?: return notPrivileged()
        val (x, y) = when (target) {
            is ScreenTarget.Point -> target.x to target.y
            is ScreenTarget.Node -> return ActionResult(
                ok = false,
                error = ScreenError(
                    ScreenErrorCodes.TEXT_CONNECTION_UNAVAILABLE,
                    "Node taps need a readable tree; use coordinates on background screens",
                ),
            )
        }
        val ok = runCatching { service()?.tap(id, x, y, 60L) }.getOrDefault(false) == true
        return okOrError(ok, "tap")
    }

    override fun type(index: Int, fingerprint: String, text: String, submit: Boolean): ActionResult {
        val id = ensureDisplay() ?: return notPrivileged()
        val ok = runCatching { service()?.text(id, text) }.getOrDefault(false) == true
        if (!ok) return okOrError(false, "type")
        if (submit) runCatching { service()?.key(id, 66) }
        return ActionResult(ok = true)
    }

    override fun scroll(
        index: Int?,
        fingerprint: String?,
        direction: ScrollDirection,
        amount: Int?,
    ): ActionResult {
        val id = ensureDisplay() ?: return notPrivileged()
        val x = width / 2
        val start = (height * 0.75f).toInt()
        val end = (height * 0.25f).toInt()
        val (y1, y2) = when (direction) {
            ScrollDirection.FORWARD, ScrollDirection.UP -> start to end
            ScrollDirection.BACKWARD, ScrollDirection.DOWN -> end to start
            else -> start to end
        }
        val ok = runCatching { service()?.swipe(id, x, y1, x, y2, 300L) }.getOrDefault(false) == true
        return okOrError(ok, "scroll")
    }

    override fun key(key: ScreenKey): ActionResult {
        val id = ensureDisplay() ?: return notPrivileged()
        val code = when (key) {
            ScreenKey.BACK -> 4
            ScreenKey.HOME -> 3
            ScreenKey.RECENTS -> 187
            ScreenKey.ENTER -> 66
            ScreenKey.DELETE -> 67
            ScreenKey.TAB -> 61
            ScreenKey.ESCAPE -> 111
            ScreenKey.UP -> 19
            ScreenKey.DOWN -> 20
            ScreenKey.LEFT -> 21
            ScreenKey.RIGHT -> 22
        }
        val ok = runCatching { service()?.key(id, code) }.getOrDefault(false) == true
        return okOrError(ok, "key")
    }

    override fun open(packageName: String, activity: String?, forceStop: Boolean): ActionResult {
        val id = ensureDisplay() ?: return notPrivileged()
        if (forceStop) runCatching { service()?.exec("am force-stop $packageName", 10_000L) }
        val bundle = runCatching { service()?.launchOnDisplay(id, packageName, activity) }.getOrNull()
        val error = bundle?.getString("error")
        return if (error == null) ActionResult(ok = true, changed = true) else ActionResult(
            ok = false,
            error = ScreenError(error, bundle?.getString("message") ?: "launch failed"),
        )
    }

    override fun handoff(): ActionResult {
        val id = displayId.takeIf { it > 0 } ?: return notPrivileged()
        val ok = runCatching { service()?.handoff(id) }.getOrDefault(false) == true
        return okOrError(ok, "handoff")
    }

    override fun release() {
        val id = displayId.takeIf { it > 0 } ?: return
        runCatching { service()?.destroyDisplay(id) }
        displayId = -1
    }

    private fun ensureDisplay(): Int? {
        if (displayId > 0) return displayId
        if (shizuku.status().level == PrivilegeLevel.NONE) return null
        val bundle = runCatching { service()?.createDisplay(width, height, densityDpi) }.getOrNull()
            ?: return null
        if (bundle.getString("error") != null) return null
        val id = bundle.getInt("displayId", -1)
        if (id > 0) displayId = id
        return displayId.takeIf { it > 0 }
    }

    private fun service(): IPrivilegedService? = shizuku.binder()

    private fun notPrivileged() = ActionResult(
        ok = false,
        error = ScreenError(
            ScreenErrorCodes.REQUIRES_PRIVILEGE,
            "Background screens need a privileged channel",
            "Enable the privilege plugin and start Shizuku (or root), then retry.",
        ),
    )

    private fun okOrError(ok: Boolean, action: String) = ActionResult(
        ok = ok,
        error = if (ok) null else ScreenError(
            ScreenErrorCodes.ACTION_UNSUPPORTED,
            "$action on ${handle.value} failed",
        ),
    )

    private fun failure(code: String, message: String, recovery: String?) = ScreenSnapshot(
        handle = handle.value,
        packageName = "",
        title = null,
        width = width,
        height = height,
        elements = emptyList(),
        capturedAtMillis = System.currentTimeMillis(),
        error = ScreenError(code, message, recovery),
    )

    private companion object {
        const val DEFAULT_WIDTH = 720
        const val DEFAULT_HEIGHT = 1280
        const val DEFAULT_DPI = 320
    }
}
