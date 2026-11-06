package com.cordiact.app.screen

import com.cordiact.core.automation.ScreenDriverRegistry
import com.cordiact.core.automation.ScreenError
import com.cordiact.core.automation.ScreenErrorCodes
import com.cordiact.core.automation.ScreenHandle
import com.cordiact.core.automation.ScreenSnapshot
import com.cordiact.core.automation.ScreenTarget
import java.util.concurrent.ConcurrentHashMap

/**
 * Tool-facing façade over [ScreenDriverRegistry].
 *
 * Keeps the last snapshot per handle so a tool that receives only `node_id`
 * can turn it into a fingerprint-checked [ScreenTarget.Node]. If the app has
 * changed since that snapshot, the driver refuses with `stale_snapshot` — the
 * model must re-read the screen instead of clicking a stale coordinate.
 */
class ScreenService(
    private val registry: ScreenDriverRegistry,
) {
    private val lastSnapshots = ConcurrentHashMap<String, ScreenSnapshot>()

    fun handles(): List<String> = registry.handles()

    fun defaultHandle(): ScreenHandle = registry.defaultHandle

    fun snapshot(handle: ScreenHandle, maxNodes: Int): ScreenSnapshot {
        val driver = registry.driver(handle) ?: return unknown(handle)
        val snapshot = driver.snapshot(maxNodes = maxNodes)
        if (snapshot.error == null) lastSnapshots[handle.value] = snapshot
        return snapshot
    }

    fun remember(snapshot: ScreenSnapshot) {
        if (snapshot.error == null) lastSnapshots[snapshot.handle] = snapshot
    }

    fun resolve(handle: ScreenHandle, nodeId: Int): Result<ScreenTarget.Node> {
        val snapshot = lastSnapshots[handle.value]
            ?: return Result.failure(IllegalStateException("No snapshot for ${handle.value}; call screen first"))
        val element = snapshot.elements.firstOrNull { it.index == nodeId }
            ?: return Result.failure(IllegalStateException("Element [$nodeId] is not in the last snapshot"))
        return Result.success(ScreenTarget.Node(index = element.index, fingerprint = element.fingerprint))
    }

    fun driver(handle: ScreenHandle) = registry.driver(handle)

    fun unknown(handle: ScreenHandle) = ScreenSnapshot(
        handle = handle.value,
        packageName = "",
        title = null,
        width = 0,
        height = 0,
        elements = emptyList(),
        capturedAtMillis = System.currentTimeMillis(),
        error = ScreenError(
            code = ScreenErrorCodes.UNKNOWN_SCREEN,
            message = "Screen ${handle.value} is not available",
            recovery = "Call screen_list; background screens require the display plugin and a privileged channel.",
        ),
    )
}
