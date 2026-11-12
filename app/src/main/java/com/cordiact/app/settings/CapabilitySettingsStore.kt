package com.cordiact.app.settings

import android.content.Context
import com.cordiact.core.settings.SettingsWriter

/** Per-workspace policy. Reads on every list/invoke prevent stale tool-list bypasses. */
class CapabilitySettingsStore(context: Context) {
    private val preferences = context.getSharedPreferences("workspace_capabilities_v1", Context.MODE_PRIVATE)
    @Synchronized fun enabled(workspaceId: String, capability: String): Boolean =
        preferences.getBoolean("$workspaceId:$capability", true)
    @Synchronized fun revision(workspaceId: String): Long = preferences.getLong("$workspaceId:revision", 0)
    @Synchronized fun set(workspaceId: String, capability: String, enabled: Boolean, writer: SettingsWriter): Long {
        require(workspaceId.isNotBlank() && (capability in IDS || capability.matches(Regex("mcp\\.[a-zA-Z0-9_-]+"))))
        val previous = revision(workspaceId)
        return writer.write("capability:$workspaceId:$capability", previous, object : com.cordiact.core.settings.SettingsMutation {
            override fun validate() = Unit
            override fun commit(expectedRevision: Long): Long {
                check(revision(workspaceId) == expectedRevision) { "SETTINGS_REVISION_CONFLICT" }
                val next = expectedRevision + 1
                check(preferences.edit().putBoolean("$workspaceId:$capability", enabled)
                    .putLong("$workspaceId:revision", next).commit()) { "CAPABILITY_WRITE_FAILED" }
                return next
            }
        })
    }
    companion object { val IDS = setOf("ssh", "termux", "screen", "browser", "privilege", "search") }
}
