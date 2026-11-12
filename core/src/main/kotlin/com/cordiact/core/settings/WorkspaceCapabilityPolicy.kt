package com.cordiact.core.settings

/** Inventory IDs are stable and independent from individual tool IDs. Unknown plugins are unchanged. */
object WorkspaceCapabilityPolicy {
    private val owners = mapOf(
        "com.cordiact.ssh" to "ssh", "com.cordiact.termux" to "termux", "com.cordiact.screen" to "screen",
        "com.cordiact.browser" to "browser", "com.cordiact.priv" to "privilege", "com.cordiact.display" to "screen",
        "core.ssh" to "ssh", "core.termux" to "termux", "core.screen" to "screen",
        "core.browser" to "browser", "core.privilege" to "privilege", "core.search" to "search",
    )
    fun capabilityFor(ownerPluginId: String): String? = owners[ownerPluginId] ?: ownerPluginId.takeIf { it.startsWith("core.mcp.") }?.removePrefix("core.")
    fun visible(ownerPluginId: String, enabled: (String) -> Boolean, ready: (String) -> Boolean): Boolean {
        val id = capabilityFor(ownerPluginId) ?: return true
        return enabled(id) && ready(id)
    }
}
