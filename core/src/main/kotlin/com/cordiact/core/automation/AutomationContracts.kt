package com.cordiact.core.automation

/**
 * Plugin ids and capability ids for the automation batch.
 *
 * The four plugins are deliberately separate: their permission models differ
 * (accessibility vs. WebView vs. shell/root), and users must be able to revoke
 * one without revoking the others.
 */
object AutomationPlugins {
    const val PRIVILEGE = "com.cordiact.priv"
    const val DISPLAY = "com.cordiact.display"
    const val SCREEN = "com.cordiact.screen"
    const val BROWSER = "com.cordiact.browser"
}

object AutomationCapabilities {
    /** Run shell commands as the Shizuku/ADB shell user. */
    const val PRIV_SHELL = "priv.shell"

    /** Run arbitrary shell commands; off by default even when [PRIV_SHELL] is granted. */
    const val PRIV_SHELL_ADVANCED = "priv.shell_advanced"

    /** Run commands as uid 0. Separate from shell on purpose. */
    const val PRIV_ROOT = "priv.root"

    /** Create and drive background virtual displays. Requires a privileged channel. */
    const val DISPLAY_CONTROL = "display.control"

    /** Read the screen (snapshot / image). */
    const val SCREEN_OBSERVE = "screen.observe"

    /** Perform actions on the screen (tap / type / scroll / key / open). */
    const val SCREEN_ACT = "screen.act"

    /** Drive the in-app WebView browser. */
    const val BROWSER_CONTROL = "browser.control"
}

/**
 * Screen handles are opaque to the model: `s0` is the physical main screen,
 * `b0`/`b1` are background virtual screens. The model never sees a system
 * display id — that is an internal detail of the display plugin.
 */
data class ScreenHandle(val value: String) {
    init {
        require(PATTERN.matches(value)) { "Invalid screen handle: $value" }
    }

    val isMain: Boolean get() = value.startsWith('s')

    companion object {
        private val PATTERN = Regex("[sb][0-9]{1,2}")

        fun main(index: Int = 0): ScreenHandle = ScreenHandle("s$index")

        fun background(index: Int = 0): ScreenHandle = ScreenHandle("b$index")

        fun parse(raw: String): ScreenHandle? = raw.takeIf { PATTERN.matches(it) }?.let(::ScreenHandle)
    }
}

data class ScreenRect(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
) {
    val width: Int get() = (right - left).coerceAtLeast(0)
    val height: Int get() = (bottom - top).coerceAtLeast(0)
    val centerX: Int get() = left + width / 2
    val centerY: Int get() = top + height / 2
    val isEmpty: Boolean get() = width == 0 || height == 0
}

/**
 * One element in a snapshot. [fingerprint] is the only stable identity across
 * snapshots; [index] is only meaningful inside the snapshot that produced it.
 */
data class ScreenElement(
    val index: Int,
    val fingerprint: String,
    val role: String,
    val label: String,
    val bounds: ScreenRect,
    val clickable: Boolean = false,
    val editable: Boolean = false,
    val scrollable: Boolean = false,
    val checkable: Boolean = false,
    val checked: Boolean = false,
    val enabled: Boolean = true,
    val password: Boolean = false,
    val sensitive: Boolean = false,
)

data class ScreenSnapshot(
    val handle: String,
    val packageName: String,
    val title: String?,
    val width: Int,
    val height: Int,
    val elements: List<ScreenElement>,
    val capturedAtMillis: Long,
    val truncated: Boolean = false,
    val imagePngBase64: String? = null,
    val error: ScreenError? = null,
)

data class ScreenError(
    val code: String,
    val message: String,
    val recovery: String? = null,
)

object ScreenErrorCodes {
    const val PERMISSION_REQUIRED = "screen_permission_required"
    const val SERVICE_DISCONNECTED = "screen_service_disconnected"
    const val STALE_SNAPSHOT = "stale_snapshot"
    const val UNKNOWN_SCREEN = "unknown_screen"
    const val ACTION_UNSUPPORTED = "action_unsupported"
    const val PROTECTED_PAGE = "protected_page"
    const val APP_BLOCKED = "app_blocked"
    const val CONFIRMATION_REQUIRED = "confirmation_required"
    const val REQUIRES_PRIVILEGE = "requires_privilege"
    const val TEXT_CONNECTION_UNAVAILABLE = "text_connection_unavailable"
    const val SECURE_WINDOW = "secure_window"
    const val PACKAGE_NOT_FOUND = "package_not_found"
    const val TIMEOUT = "timeout"
}

sealed interface ScreenTarget {
    data class Node(val index: Int, val fingerprint: String) : ScreenTarget

    data class Point(val x: Int, val y: Int) : ScreenTarget
}

enum class ScrollDirection { FORWARD, BACKWARD, UP, DOWN, LEFT, RIGHT }

enum class ScreenKey { BACK, HOME, RECENTS, ENTER, DELETE, TAB, ESCAPE, UP, DOWN, LEFT, RIGHT }

data class ActionResult(
    val ok: Boolean,
    val changed: Boolean = false,
    val error: ScreenError? = null,
    /** Fresh snapshot taken after the action; null when the driver could not observe. */
    val snapshot: ScreenSnapshot? = null,
)

/**
 * One screen the agent can drive. Implementations:
 *  - `MainScreenDriver` (accessibility, `s*`)
 *  - `BackgroundScreenDriver` (privileged virtual display, `b*`)
 */
interface ScreenDriver {
    val handle: ScreenHandle

    fun snapshot(maxNodes: Int = 60, includeImage: Boolean = false): ScreenSnapshot

    fun tap(target: ScreenTarget): ActionResult

    fun type(index: Int, fingerprint: String, text: String, submit: Boolean = false): ActionResult

    fun scroll(index: Int?, fingerprint: String?, direction: ScrollDirection, amount: Int? = null): ActionResult

    fun key(key: ScreenKey): ActionResult

    fun open(packageName: String, activity: String? = null, forceStop: Boolean = false): ActionResult

    /** Move a background task back to the main screen; no-op on main. */
    fun handoff(): ActionResult = ActionResult(ok = false, error = ScreenError(
        ScreenErrorCodes.ACTION_UNSUPPORTED,
        "handoff is only available on background screens",
    ))

    fun release() = Unit
}

/** Resolves screen handles to drivers for the current turn. */
interface ScreenDriverRegistry {
    fun driver(handle: ScreenHandle): ScreenDriver?

    fun handles(): List<String>

    val defaultHandle: ScreenHandle get() = ScreenHandle.main()
}
