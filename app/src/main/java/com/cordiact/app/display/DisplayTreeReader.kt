package com.cordiact.app.display

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.UiAutomation
import android.content.Context
import android.hardware.display.DisplayManager
import android.os.Binder
import android.os.IBinder
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
        val windows = windowsOn(automation, display.displayId)
            ?: return unavailable(
                handle,
                "text_connection_unavailable",
                "No accessibility windows reported for display ${display.displayId}",
                "The display has no readable window yet; wait for the app to draw and retry.",
            )
        val application = windows.firstOrNull { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }
            ?: windows.firstOrNull()
            ?: return unavailable(
                handle,
                "text_connection_unavailable",
                "Display ${display.displayId} has no window to read",
                "Start an app on the background screen, then retry.",
            )
        val bounds = android.graphics.Rect().also { application.getBoundsInScreen(it) }
        val root: AccessibilityNodeInfo = application.root ?: return unavailable(
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

    /** Windows on every display, filtered to the one that belongs to [displayId]. */
    private fun windowsOn(automation: UiAutomation, displayId: Int): List<AccessibilityWindowInfo>? {
        val all = runCatching {
            @Suppress("UNCHECKED_CAST")
            automation.javaClass.getMethod("getWindowsOnAllDisplays").invoke(automation)
        }.getOrNull() ?: return null
        val perDisplay = runCatching { perDisplayOf(all, displayId) }.getOrNull() ?: return null
        return perDisplay
    }

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
        private const val FLAG_DONT_SUPPRESS_FALLBACK = 1
    }
}
