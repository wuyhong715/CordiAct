package com.cordiact.app.display

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.UiAutomation
import android.content.Context
import android.hardware.display.DisplayManager
import android.os.Binder
import android.os.IBinder
import android.os.Bundle
import android.os.Process
import android.os.UserHandle
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import com.cordiact.app.screen.TreeCapture
import com.cordiact.core.automation.ScreenElement
import com.cordiact.core.automation.ScreenError
import com.cordiact.core.automation.ScreenErrorCodes
import com.cordiact.core.automation.ScreenHandle
import com.cordiact.core.automation.ScreenSnapshot
import com.cordiact.core.automation.ScreenWebDocument
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method
import java.lang.reflect.Proxy

/**
 * Reads the accessibility tree of a background virtual display.
 *
 * A virtual display has no accessibility service of its own, so the tree is read
 * through a [UiAutomation] bound to that display's *context* (not to the default
 * display), with a hand-rolled `IUiAutomationConnection`. The connection is
 * deliberately node-only: it never unregisters the user's real services on
 * disconnect, and `FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES` keeps them running
 * while a read is in flight.
 *
 * Call from a thread with a `Looper`: `UiAutomation` posts its window callbacks to
 * the looper of the thread that created it.
 *
 * Runs in the privileged (Shizuku shell) process, so it takes a shell-identity
 * context and all hidden API access goes through reflection.
 */
internal class DisplayTreeReader(
    private val systemContext: Context,
) {

    /**
     * The display was created under the `com.android.shell` identity, so both
     * the DisplayManager that resolves it and the context it is bound to must
     * come from that package.
     */
    private val context: Context by lazy {
        runCatching { systemContext.createPackageContext("com.android.shell", 0) }
            .getOrDefault(systemContext)
    }

    fun snapshot(
        displayId: Int,
        handle: ScreenHandle,
        maxNodes: Int = DEFAULT_MAX_NODES,
    ): ScreenSnapshot {
        if (displayId <= 0) {
            return unavailable(handle, "display_missing", "Display $displayId does not exist", "Open a background screen first.")
        }
        val display = runCatching {
            context.getSystemService(DisplayManager::class.java)?.getDisplay(displayId)
        }.getOrNull() ?: return unavailable(
            handle,
            "display_missing",
            "Display $displayId is not visible to the privileged process",
            "Reopen the background screen so a display is created again.",
        )

        val automation = try {
            open(display)
        } catch (failure: Throwable) {
            return unavailable(
                handle,
                "text_connection_unavailable",
                "Could not attach accessibility to display $displayId: ${failure.message ?: failure.javaClass.simpleName}",
                "Retry once; if it persists the display channel is unavailable on this build.",
            )
        }
        return try {
            read(automation, display, handle, maxNodes)
        } catch (failure: Throwable) {
            unavailable(
                handle,
                "text_connection_unavailable",
                "Reading display $displayId failed: ${failure.message ?: failure.javaClass.simpleName}",
                "Retry once; if it persists the display channel is unavailable on this build.",
            )
        } finally {
            runCatching { automation.javaClass.getMethod("disconnect").invoke(automation) }
        }
    }

    private fun read(
        automation: UiAutomation,
        display: Display,
        handle: ScreenHandle,
        maxNodes: Int,
    ): ScreenSnapshot {
        runCatching { automation.javaClass.getMethod("clearCache").invoke(automation) }
        val target = display.displayId
        var diagnosis = "the automation reported no window lists"
        // A freshly launched app shows up in the window list a beat after its
        // first frame, so give it a few tries before calling the channel dead.
        repeat(WINDOW_RETRIES) { attempt ->
            if (attempt > 0) Thread.sleep(WINDOW_RETRY_DELAY_MS)
            val all = invokeOrNull(automation, "getWindowsOnAllDisplays")
            val windows = all?.let { perDisplayOf(it, target) }
            val window = windows?.firstOrNull { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }
            if (window != null) return capture(window, display, handle, maxNodes)
            diagnosis = describeWindows(all, target)
        }
        // Fallback: the connection's own window list, scoped to whatever display
        // the automation attached to. Guarded by size so it can never silently
        // read the phone's main screen instead of the background one.
        val own = (invokeOrNull(automation, "getWindows") as? List<*>)
            ?.filterIsInstance<AccessibilityWindowInfo>()
        val fallback = own?.firstOrNull {
            it.type == AccessibilityWindowInfo.TYPE_APPLICATION && fits(it, display)
        }
        if (fallback != null) return capture(fallback, display, handle, maxNodes)
        return unavailable(
            handle,
            "text_connection_unavailable",
            "Display $target exposed no application window: $diagnosis",
            "Start an app on the background screen and retry; the message lists what the display channel can see.",
        )
    }

    private fun capture(
        window: AccessibilityWindowInfo,
        display: Display,
        handle: ScreenHandle,
        maxNodes: Int,
    ): ScreenSnapshot {
        val bounds = android.graphics.Rect().also { window.getBoundsInScreen(it) }
        val root: AccessibilityNodeInfo = window.root ?: return unavailable(
            handle,
            "text_connection_unavailable",
            "Window on display ${display.displayId} exposed no root node",
            "Retry after the app finishes drawing.",
        )
        return try {
            TreeCapture().captureFrom(
                root = root,
                handle = handle,
                maxNodes = maxNodes,
                fallbackWidth = bounds.width().takeIf { it > 0 } ?: modeWidth(display),
                fallbackHeight = bounds.height().takeIf { it > 0 } ?: modeHeight(display),
            )
        } finally {
            @Suppress("DEPRECATION")
            runCatching { root.recycle() }
        }
    }

    /** True when the window cannot be the phone's own (larger) screen. */
    private fun fits(window: AccessibilityWindowInfo, display: Display): Boolean {
        val bounds = android.graphics.Rect().also { window.getBoundsInScreen(it) }
        if (bounds.width() <= 0 || bounds.height() <= 0) return false
        val width = modeWidth(display)
        val height = modeHeight(display)
        if (width <= 0 || height <= 0) return true
        return bounds.width() <= width && bounds.height() <= height
    }

    /** What the automation can actually see, for the error message. */
    private fun describeWindows(all: Any?, target: Int): String {
        if (all == null) return "getWindowsOnAllDisplays returned nothing"
        val perDisplay = runCatching {
            val size = all.javaClass.getMethod("size").invoke(all) as Int
            val keyAt = all.javaClass.getMethod("keyAt", Integer.TYPE)
            val valueAt = all.javaClass.getMethod("valueAt", Integer.TYPE)
            (0 until size).joinToString(", ") { i ->
                val key = keyAt.invoke(all, i)
                val windows = valueAt.invoke(all, i) as? List<*>
                "$key=${windows?.size ?: "?"}"
            }
        }.getOrElse { "unreadable window list (${it.message})" }
        return "displays present: [$perDisplay], wanted $target"
    }

    private fun invokeOrNull(automation: UiAutomation, method: String): Any? =
        runCatching { automation.javaClass.getMethod(method).invoke(automation) }.getOrNull()

    private fun perDisplayOf(all: Any, displayId: Int): List<AccessibilityWindowInfo>? {
        // SparseArray<List<AccessibilityWindowInfo>>; reflection keeps the SDK stubs clean.
        val get = all.javaClass.getMethod("get", Integer.TYPE)
        @Suppress("UNCHECKED_CAST")
        return get.invoke(all, displayId) as? List<AccessibilityWindowInfo>
    }

    private fun open(display: Display): UiAutomation {
        val displayContext = context.createDisplayContext(display)
        val connectionType = Class.forName("android.app.IUiAutomationConnection")
        val handler = object : InvocationHandler {
            override fun invoke(proxy: Any, method: Method, args: Array<out Any?>?): Any? {
                return when (method.name) {
                    "asBinder" -> token
                    "connect" -> {
                        val client = args?.firstOrNull()
                        if (client != null) runCatching { register(client) }.onFailure { failure ->
                            lastRegistrationError = failure
                        }
                        null
                    }
                    "disconnect" -> {
                        unregister()
                        null
                    }
                    "toString" -> "CordiActDisplayConnection"
                    "hashCode" -> System.identityHashCode(proxy)
                    "equals" -> proxy === args?.firstOrNull()
                    else -> throw UnsupportedOperationException("node-only connection: ${method.name}")
                }
            }
        }
        val connection = Proxy.newProxyInstance(connectionType.classLoader, arrayOf(connectionType), handler)
        val uiType = Class.forName("android.app.UiAutomation")
        val automation = uiType.getConstructor(Context::class.java, connectionType)
            .newInstance(displayContext, connection) as UiAutomation
        val flags = dontSuppressFlags()
        uiType.getMethod("connectWithTimeout", Integer.TYPE, java.lang.Long.TYPE)
            .invoke(automation, flags, CONNECT_TIMEOUT_MS)
        lastRegistrationError?.let { failure ->
            lastRegistrationError = null
            throw IllegalStateException(failure.message ?: failure.javaClass.simpleName, failure)
        }
        return automation
    }

    private fun register(client: Any) {
        val manager = accessibilityManager()
        val managerType = Class.forName("android.view.accessibility.IAccessibilityManager")
        val clientType = Class.forName("android.accessibilityservice.IAccessibilityServiceClient")
        val info = AccessibilityServiceInfo().apply {
            packageNames = null
            eventTypes = AccessibilityEvent.TYPES_ALL_MASK
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            flags = AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or
                AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS or
                AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
        }
        // setCapabilities is hidden, and it is what makes the connection able to
        // list windows at all: without it getWindowsOnAllDisplays comes back empty.
        runCatching {
            AccessibilityServiceInfo::class.java
                .getMethod("setCapabilities", Integer.TYPE)
                .invoke(info, AccessibilityServiceInfo.CAPABILITY_CAN_RETRIEVE_WINDOW_CONTENT)
        }
        val userId = runCatching {
            UserHandle::class.java.getMethod("getUserId", Integer.TYPE)
                .invoke(null, Process.myUid()) as Int
        }.getOrDefault(0)
        managerType.getMethod(
            "registerUiTestAutomationService",
            IBinder::class.java,
            clientType,
            AccessibilityServiceInfo::class.java,
            Integer.TYPE,
            Integer.TYPE,
        ).invoke(manager, token, client, info, userId, dontSuppressFlags())
        registeredClient = client
    }

    private fun unregister() {
        val client = registeredClient ?: return
        registeredClient = null
        runCatching {
            val manager = accessibilityManager()
            Class.forName("android.view.accessibility.IAccessibilityManager")
                .getMethod("unregisterUiTestAutomationService", Class.forName("android.accessibilityservice.IAccessibilityServiceClient"))
                .invoke(manager, client)
        }
    }

    private fun modeWidth(display: Display): Int =
        runCatching { display.mode.physicalWidth }.getOrDefault(0)

    private fun modeHeight(display: Display): Int =
        runCatching { display.mode.physicalHeight }.getOrDefault(0)

    private fun accessibilityManager(): Any {
        val binder = Class.forName("android.os.ServiceManager")
            .getMethod("getService", String::class.java)
            .invoke(null, Context.ACCESSIBILITY_SERVICE) as IBinder
        return Class.forName("android.view.accessibility.IAccessibilityManager\$Stub")
            .getMethod("asInterface", IBinder::class.java)
            .invoke(null, binder) as Any
    }

    private fun dontSuppressFlags(): Int = runCatching {
        Class.forName("android.app.UiAutomation")
            .getField("FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES")
            .getInt(null)
    }.getOrDefault(FLAG_DONT_SUPPRESS_FALLBACK)

    /**
     * Result of a typing attempt. [method] says how the text got in, and
     * [observed] carries what the node reports afterwards, so a caller can tell
     * "typed" from "asked the platform nicely".
     */
    data class TypeOutcome(
        val ok: Boolean,
        val method: String,
        val packageName: String?,
        val observed: String,
        val error: String?,
    )

    /**
     * Types into the display's editable node.
     *
     * `input text` cannot carry CJK (it goes through the key character map), so
     * the text is set through accessibility instead: pick the focused editable
     * node on that display, then `ACTION_SET_TEXT`. Chinese, emoji and long
     * strings all work, and nothing needs the display to own the IME.
     */
    fun typeText(displayId: Int, value: String): TypeOutcome {
        val display = runCatching {
            context.getSystemService(DisplayManager::class.java)?.getDisplay(displayId)
        }.getOrNull() ?: return TypeOutcome(false, "none", null, "", "display $displayId is not visible")

        val automation = try {
            open(display)
        } catch (failure: Throwable) {
            return TypeOutcome(false, "none", null, "", failure.message ?: failure.javaClass.simpleName)
        }
        return try {
            val root = applicationRoot(automation, display)
                ?: return TypeOutcome(false, "none", null, "", "no application window on display $displayId")
            val target = editable(root)
                ?: return TypeOutcome(false, "none", null, "", "no editable node on display $displayId")
            val arguments = Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value)
            }
            val accepted = target.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)
            val observed = target.text?.toString().orEmpty()
            TypeOutcome(
                ok = accepted,
                method = "accessibility_set_text",
                packageName = target.packageName?.toString(),
                observed = observed,
                error = if (accepted) null else "the node refused ACTION_SET_TEXT",
            )
        } catch (failure: Throwable) {
            TypeOutcome(false, "none", null, "", failure.message ?: failure.javaClass.simpleName)
        } finally {
            runCatching { automation.javaClass.getMethod("disconnect").invoke(automation) }
        }
    }

    /** Rendered text of the WebView on the display, if the display shows one. */
    fun webDocument(displayId: Int, handle: ScreenHandle, maxChars: Int): ScreenWebDocument? {
        val display = runCatching {
            context.getSystemService(DisplayManager::class.java)?.getDisplay(displayId)
        }.getOrNull() ?: return null
        val automation = runCatching { open(display) }.getOrNull() ?: return null
        return try {
            val root = applicationRoot(automation, display)
                ?: return ScreenWebDocument(
                    handle = handle.value,
                    title = "",
                    url = null,
                    text = "",
                    links = emptyList(),
                    elements = 0,
                    error = com.cordiact.core.automation.ScreenError(
                        "no_window",
                        "Display $displayId has no application window to read",
                    ),
                )
            WebContentExtractor.fromRoot(root, handle.value, maxChars) ?: ScreenWebDocument(
                handle = handle.value,
                title = "",
                url = null,
                text = "",
                links = emptyList(),
                elements = 0,
                error = com.cordiact.core.automation.ScreenError("no_webview", classHistogram(root)),
            )
        } catch (failure: Throwable) {
            ScreenWebDocument(
                handle = handle.value,
                title = "",
                url = null,
                text = "",
                links = emptyList(),
                elements = 0,
                error = com.cordiact.core.automation.ScreenError(
                    "web_read_failed",
                    failure.message ?: failure.javaClass.simpleName,
                ),
            )
        } finally {
            runCatching { automation.javaClass.getMethod("disconnect").invoke(automation) }
        }
    }

    /** The app window's root on the display, or null when nothing is drawing. */
    private fun applicationRoot(automation: UiAutomation, display: Display): AccessibilityNodeInfo? {
        repeat(WINDOW_RETRIES) { attempt ->
            if (attempt > 0) Thread.sleep(WINDOW_RETRY_DELAY_MS)
            val all = invokeOrNull(automation, "getWindowsOnAllDisplays")
            val windows = all?.let { perDisplayOf(it, display.displayId) }
            val window = windows?.firstOrNull { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }
            window?.root?.let { return it }
        }
        return null
    }

    /** Focused editable node first, then the first editable one in the window. */
    private fun editable(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val focused = runCatching { root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) }.getOrNull()
        if (focused != null && focused.isEditable) return focused
        var visited = 0
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue += root
        while (queue.isNotEmpty() && visited++ < EDITABLE_SEARCH_LIMIT) {
            val node = queue.removeFirst()
            if (node.isEditable) return node
            for (index in 0 until node.childCount) {
                node.getChild(index)?.let(queue::addLast)
            }
        }
        return null
    }

    /** What classes the window actually contains, for "why is there no WebView". */
    private fun classHistogram(root: AccessibilityNodeInfo): String {
        val counts = linkedMapOf<String, Int>()
        var visited = 0
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue += root
        while (queue.isNotEmpty() && visited++ < 600) {
            val node = queue.removeFirst()
            val name = node.className?.toString()?.substringAfterLast('.') ?: "?"
            counts[name] = (counts[name] ?: 0) + 1
            for (index in 0 until node.childCount) {
                node.getChild(index)?.let(queue::addLast)
            }
        }
        return counts.entries.sortedByDescending { it.value }.take(12)
            .joinToString(", ") { "${it.key}x${it.value}" }
    }

    private fun unavailable(
        handle: ScreenHandle,
        code: String,
        message: String,
        hint: String,
    ): ScreenSnapshot = ScreenSnapshot(
        handle = handle.value,
        packageName = "",
        title = null,
        width = 0,
        height = 0,
        elements = emptyList<ScreenElement>(),
        capturedAtMillis = System.currentTimeMillis(),
        truncated = false,
        error = ScreenError(code, message, hint),
    )

    private val token: IBinder = Binder()
    private var registeredClient: Any? = null
    private var lastRegistrationError: Throwable? = null

    companion object {
        const val DEFAULT_MAX_NODES = 200
        private const val CONNECT_TIMEOUT_MS = 3000L
        private const val EDITABLE_SEARCH_LIMIT = 2_000
        private const val WINDOW_RETRIES = 3
        private const val WINDOW_RETRY_DELAY_MS = 400L
        private const val FLAG_DONT_SUPPRESS_FALLBACK = 1
    }
}
