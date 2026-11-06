package com.cordiact.app.screen

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.content.Intent
import android.graphics.Path
import android.graphics.Rect
import android.os.Bundle
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import com.cordiact.app.display.WebContentExtractor
import com.cordiact.core.automation.ActionResult
import com.cordiact.core.automation.ScreenDriver
import com.cordiact.core.automation.ScreenError
import com.cordiact.core.automation.ScreenErrorCodes
import com.cordiact.core.automation.ScreenHandle
import com.cordiact.core.automation.ScreenKey
import com.cordiact.core.automation.ScreenSnapshot
import com.cordiact.core.automation.ScreenWebDocument
import com.cordiact.core.automation.ScreenTarget
import com.cordiact.core.automation.ScrollDirection

/**
 * Drives the physical screen through the accessibility service.
 *
 * Every action follows the same pattern: resolve a fresh node (never trust the
 * snapshot's bounds blindly), act, wait a short settle time, return a fresh
 * snapshot. The model uses that snapshot to decide the next step.
 */
class MainScreenDriver(
    private val context: Context,
    override val handle: ScreenHandle = ScreenHandle.main(),
) : ScreenDriver {

    private val capture = TreeCapture()

    override fun webDocument(maxChars: Int): ScreenWebDocument? {
        val service = ScreenServiceBridge.service ?: return null
        val root = service.rootInActiveWindow ?: return null
        return WebContentExtractor.fromRoot(root, handle.value, maxChars)
    }

    override fun snapshot(maxNodes: Int, includeImage: Boolean): ScreenSnapshot {
        val service = ScreenServiceBridge.service
            ?: return capture.empty(
                handle = handle,
                packageName = "",
                error = ScreenError(
                    ScreenErrorCodes.PERMISSION_REQUIRED,
                    "Accessibility service is not connected",
                    "Settings → Accessibility → Downloaded apps → CordiAct → turn on.",
                ),
            )
        return capture.capture(service, handle, maxNodes)
    }

    override fun tap(target: ScreenTarget): ActionResult {
        val service = serviceOrError() ?: return disconnected()
        val before = capture.capture(service, handle, SNAPSHOT_FOR_ACTION)

        val point = when (target) {
            is ScreenTarget.Point -> target.x to target.y
            is ScreenTarget.Node -> {
                val element = before.elements.firstOrNull { it.index == target.index }
                    ?: return stale()
                if (element.fingerprint != target.fingerprint) return stale()
                val node = findNode(service, target.fingerprint)
                val clicked = node?.let(::clickWithAncestors) ?: false
                if (clicked) {
                    sleep(SETTLE_MS)
                    return resultAfter(service, before)
                }
                element.bounds.centerX to element.bounds.centerY
            }
        }

        val dispatched = gestureTap(service, point.first, point.second)
        if (!dispatched) {
            return ActionResult(
                ok = false,
                error = ScreenError(ScreenErrorCodes.ACTION_UNSUPPORTED, "Could not dispatch a tap gesture"),
            )
        }
        sleep(SETTLE_MS)
        return resultAfter(service, before)
    }

    override fun type(index: Int, fingerprint: String, text: String, submit: Boolean): ActionResult {
        val service = serviceOrError() ?: return disconnected()
        val before = capture.capture(service, handle, SNAPSHOT_FOR_ACTION)
        val element = before.elements.firstOrNull { it.index == index } ?: return stale()
        if (element.fingerprint != fingerprint) return stale()
        if (!element.editable) {
            return ActionResult(
                ok = false,
                error = ScreenError(ScreenErrorCodes.ACTION_UNSUPPORTED, "Element [${element.index}] is not editable"),
            )
        }

        val node = findNode(service, fingerprint)
            ?: return stale()
        node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        val set = node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        if (!set) {
            return ActionResult(
                ok = false,
                error = ScreenError(ScreenErrorCodes.ACTION_UNSUPPORTED, "Could not set text on element [${element.index}]"),
            )
        }
        if (submit && android.os.Build.VERSION.SDK_INT >= 30) {
            node.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)
        }
        sleep(SETTLE_MS)
        return resultAfter(service, before)
    }

    override fun scroll(
        index: Int?,
        fingerprint: String?,
        direction: ScrollDirection,
        amount: Int?,
    ): ActionResult {
        val service = serviceOrError() ?: return disconnected()
        val before = capture.capture(service, handle, SNAPSHOT_FOR_ACTION)

        val node = if (index != null && fingerprint != null) {
            val element = before.elements.firstOrNull { it.index == index } ?: return stale()
            if (element.fingerprint != fingerprint) return stale()
            findNode(service, fingerprint)
        } else {
            null
        }

        val action = when (direction) {
            ScrollDirection.FORWARD, ScrollDirection.UP -> AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
            ScrollDirection.BACKWARD, ScrollDirection.DOWN -> AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
            else -> AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
        }
        val scrolled = node?.performAction(action) ?: false
        if (!scrolled) {
            val height = before.height.coerceAtLeast(1)
            val startY = (height * 0.72f).toInt()
            val endY = (height * 0.28f).toInt()
            val x = before.width / 2
            gestureSwipe(service, x, startY, x, endY)
        }
        sleep(SETTLE_MS)
        return resultAfter(service, before)
    }

    override fun key(key: ScreenKey): ActionResult {
        val service = serviceOrError() ?: return disconnected()
        val before = capture.capture(service, handle, SNAPSHOT_FOR_ACTION)
        val global = when (key) {
            ScreenKey.BACK -> AccessibilityService.GLOBAL_ACTION_BACK
            ScreenKey.HOME -> AccessibilityService.GLOBAL_ACTION_HOME
            ScreenKey.RECENTS -> AccessibilityService.GLOBAL_ACTION_RECENTS
            else -> null
        }
        if (global == null) {
            return ActionResult(
                ok = false,
                error = ScreenError(ScreenErrorCodes.ACTION_UNSUPPORTED, "Key $key is not supported on the main screen"),
            )
        }
        val ok = service.performGlobalAction(global)
        sleep(SETTLE_MS)
        return if (ok) resultAfter(service, before) else ActionResult(
            ok = false,
            error = ScreenError(ScreenErrorCodes.ACTION_UNSUPPORTED, "Global action $key was rejected"),
        )
    }

    override fun open(packageName: String, activity: String?, forceStop: Boolean): ActionResult {
        val intent = if (activity != null) {
            Intent().apply {
                setClassName(packageName, activity)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        } else {
            context.packageManager.getLaunchIntentForPackage(packageName)
                ?.apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
        }
        if (intent == null) {
            return ActionResult(
                ok = false,
                error = ScreenError(ScreenErrorCodes.PACKAGE_NOT_FOUND, "No launch intent for $packageName"),
            )
        }
        return try {
            context.startActivity(intent)
            sleep(600)
            ActionResult(ok = true)
        } catch (error: Exception) {
            ActionResult(
                ok = false,
                error = ScreenError(ScreenErrorCodes.PACKAGE_NOT_FOUND, error.message ?: "Could not start $packageName"),
            )
        }
    }

    override fun release() = Unit

    private fun resultAfter(service: AccessibilityService, before: ScreenSnapshot): ActionResult {
        val after = capture.capture(service, handle, SNAPSHOT_FOR_ACTION)
        val changed = before.packageName != after.packageName ||
            before.elements.map { it.fingerprint }.toSet() != after.elements.map { it.fingerprint }.toSet()
        return ActionResult(ok = true, changed = changed, snapshot = after)
    }

    private fun disconnected() = ActionResult(
        ok = false,
        error = ScreenError(
            ScreenErrorCodes.PERMISSION_REQUIRED,
            "Accessibility service is not connected",
            "Settings → Accessibility → Downloaded apps → CordiAct → turn on.",
        ),
    )

    private fun stale() = ActionResult(
        ok = false,
        error = ScreenError(
            ScreenErrorCodes.STALE_SNAPSHOT,
            "The element changed since the last snapshot",
            "Call screen again and use the new element id.",
        ),
    )

    private fun serviceOrError(): AccessibilityService? = ScreenServiceBridge.service

    private fun findNode(service: AccessibilityService, fingerprint: String): AccessibilityNodeInfo? {
        val root = service.rootInActiveWindow ?: return null
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        var visited = 0
        while (queue.isNotEmpty() && visited < 4_000) {
            val node = queue.removeFirst()
            visited++
            val bounds = Rect().also { node.getBoundsInScreen(it) }
            val role = node.className?.toString()?.substringAfterLast('.') ?: "View"
            val label = (node.text?.toString() ?: node.contentDescription?.toString())
                ?.trim()?.replace('\n', ' ').orEmpty()
            if (!bounds.isEmpty && fingerprint(node, bounds, role, label) == fingerprint) return node
            for (child in 0 until node.childCount) node.getChild(child)?.let(queue::add)
        }
        return null
    }

    private fun clickWithAncestors(node: AccessibilityNodeInfo): Boolean {
        var current: AccessibilityNodeInfo? = node
        var depth = 0
        while (current != null && depth < 5) {
            if (current.isClickable && current.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
            current = current.parent
            depth++
        }
        return false
    }

    private fun gestureTap(service: AccessibilityService, x: Int, y: Int): Boolean {
        val path = Path().apply { moveTo(x.toFloat(), y.toFloat()) }
        val stroke = GestureDescription.StrokeDescription(path, 0, 60)
        return service.dispatchGesture(GestureDescription.Builder().addStroke(stroke).build(), null, null)
    }

    private fun gestureSwipe(service: AccessibilityService, x1: Int, y1: Int, x2: Int, y2: Int) {
        val path = Path().apply {
            moveTo(x1.toFloat(), y1.toFloat())
            lineTo(x2.toFloat(), y2.toFloat())
        }
        val stroke = GestureDescription.StrokeDescription(path, 0, 300)
        service.dispatchGesture(GestureDescription.Builder().addStroke(stroke).build(), null, null)
    }

    private fun fingerprint(
        node: AccessibilityNodeInfo,
        bounds: Rect,
        role: String,
        label: String,
    ): String = buildString {
        append(node.windowId).append('|')
        append(bounds.left).append(',').append(bounds.top).append(',')
        append(bounds.right).append(',').append(bounds.bottom).append('|')
        append(role).append('|').append(label.take(80)).append('|').append(node.isChecked)
    }

    private fun sleep(ms: Long) = SystemClock.sleep(ms)

    private companion object {
        const val SNAPSHOT_FOR_ACTION = 200
        const val SETTLE_MS = 250L
    }
}
