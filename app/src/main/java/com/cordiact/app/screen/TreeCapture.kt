package com.cordiact.app.screen

import android.accessibilityservice.AccessibilityService
import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import com.cordiact.core.automation.ScreenElement
import com.cordiact.core.automation.ScreenError
import com.cordiact.core.automation.ScreenErrorCodes
import com.cordiact.core.automation.ScreenHandle
import com.cordiact.core.automation.ScreenRect
import com.cordiact.core.automation.ScreenSnapshot

/**
 * Walks an [AccessibilityNodeInfo] tree and turns it into the flat element
 * list used by [com.cordiact.core.automation.ScreenFormat].
 *
 * Only nodes that carry text or can be acted on are kept. Empty layout
 * containers are dropped: they are the bulk of the tree and carry no signal
 * for the model.
 */
class TreeCapture {

    fun capture(
        service: AccessibilityService,
        handle: ScreenHandle,
        maxNodes: Int = 60,
    ): ScreenSnapshot {
        val root = service.rootInActiveWindow
            ?: return empty(
                handle = handle,
                packageName = service.packageName?.toString().orEmpty(),
                error = ScreenError(
                    ScreenErrorCodes.SERVICE_DISCONNECTED,
                    "No active window is available",
                    "Open the target app and retry; if this persists, re-enable the accessibility service.",
                ),
            )
        return captureFrom(
            root = root,
            handle = handle,
            maxNodes = maxNodes,
            fallbackWidth = service.resources.displayMetrics.widthPixels,
            fallbackHeight = service.resources.displayMetrics.heightPixels,
        )
    }

    /**
     * Walks a root the caller already resolved. The privileged path uses this
     * to read a virtual-display window through `UiAutomation`, where there is
     * no [AccessibilityService] to ask for the active window.
     */
    fun captureFrom(
        root: AccessibilityNodeInfo,
        handle: ScreenHandle,
        maxNodes: Int = 60,
        fallbackWidth: Int = 0,
        fallbackHeight: Int = 0,
    ): ScreenSnapshot {
        val windowRect = Rect().also { root.getBoundsInScreen(it) }
        val width = windowRect.width().takeIf { it > 0 } ?: fallbackWidth
        val height = windowRect.height().takeIf { it > 0 } ?: fallbackHeight

        val elements = ArrayList<ScreenElement>(maxNodes)
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        var visited = 0
        var index = 1

        while (queue.isNotEmpty() && elements.size < maxNodes && visited < MAX_VISITED) {
            val node = queue.removeFirst()
            visited++

            val bounds = Rect().also { node.getBoundsInScreen(it) }
            val role = node.className?.toString()?.substringAfterLast('.') ?: "View"
            val label = (node.text?.toString() ?: node.contentDescription?.toString())
                ?.trim()
                ?.replace('\n', ' ')
                .orEmpty()
            val actionable = node.isClickable || node.isEditable || node.isScrollable

            if (!bounds.isEmpty && node.isVisibleToUser && (label.isNotEmpty() || actionable)) {
                elements += ScreenElement(
                    index = index++,
                    fingerprint = fingerprint(node, bounds, role, label),
                    role = role,
                    label = label,
                    bounds = ScreenRect(bounds.left, bounds.top, bounds.right, bounds.bottom),
                    clickable = node.isClickable,
                    editable = node.isEditable,
                    scrollable = node.isScrollable,
                    checkable = node.isCheckable,
                    checked = node.isChecked,
                    enabled = node.isEnabled,
                    password = node.isPassword,
                    sensitive = node.isPassword,
                )
            }

            for (child in 0 until node.childCount) {
                node.getChild(child)?.let(queue::add)
            }
        }

        val title = elements
            .firstOrNull { it.bounds.top < height * 0.14f && it.label.isNotBlank() && !it.clickable }
            ?.label

        return ScreenSnapshot(
            handle = handle.value,
            packageName = root.packageName?.toString().orEmpty(),
            title = title,
            width = width,
            height = height,
            elements = elements,
            capturedAtMillis = System.currentTimeMillis(),
            truncated = elements.size >= maxNodes,
        )
    }

    fun empty(handle: ScreenHandle, packageName: String, error: ScreenError): ScreenSnapshot =
        ScreenSnapshot(
            handle = handle.value,
            packageName = packageName,
            title = null,
            width = 0,
            height = 0,
            elements = emptyList(),
            capturedAtMillis = System.currentTimeMillis(),
            error = error,
        )

    private fun fingerprint(
        node: AccessibilityNodeInfo,
        bounds: Rect,
        role: String,
        label: String,
    ): String = buildString {
        append(node.windowId).append('|')
        append(bounds.left).append(',').append(bounds.top).append(',')
        append(bounds.right).append(',').append(bounds.bottom).append('|')
        append(role).append('|')
        append(label.take(80)).append('|')
        append(node.isChecked)
    }

    private companion object {
        /** Hard ceiling so a pathological page cannot pin a worker thread. */
        const val MAX_VISITED = 4_000
    }
}
