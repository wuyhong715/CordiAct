package com.cordiact.app.screen

import com.cordiact.core.automation.ScreenDriver
import com.cordiact.core.automation.ScreenDriverRegistry
import com.cordiact.core.automation.ScreenHandle

/**
 * Resolves opaque screen handles to drivers.
 *
 * `s*` is always the accessibility-driven main screen. `b*` handles are created
 * on first use by [backgroundDriver], which the display plugin supplies. Without
 * a display plugin the factory returns null and `screen_list` simply does not
 * advertise background screens — a missing capability, not a silent failure.
 */
class ScreenRegistryImpl(
    private val mainDriver: ScreenDriver,
    private val backgroundDriver: (ScreenHandle) -> ScreenDriver? = { null },
) : ScreenDriverRegistry {

    private val known = LinkedHashMap<String, ScreenDriver>()

    override fun driver(handle: ScreenHandle): ScreenDriver? {
        if (handle.isMain) return mainDriver.takeIf { it.handle == handle }
        known[handle.value]?.let { return it }
        return backgroundDriver(handle)?.also { known[handle.value] = it }
    }

    override fun handles(): List<String> =
        buildList {
            add(mainDriver.handle.value)
            addAll(known.keys.filter { it.startsWith('b') })
        }

    override val defaultHandle: ScreenHandle get() = mainDriver.handle
}
