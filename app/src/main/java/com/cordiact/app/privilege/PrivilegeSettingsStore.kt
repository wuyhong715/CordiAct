package com.cordiact.app.privilege

import android.content.Context
import com.cordiact.core.security.PermissionPreset

/** Global preferences never bypass the current Workspace security profile. */
class PrivilegeSettingsStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("privilege_settings", Context.MODE_PRIVATE)
    data class Snapshot(val root: Boolean, val advanced: Boolean, val revision: Long)
    fun read() = synchronized(LOCK) { Snapshot(prefs.getBoolean("root", false), prefs.getBoolean("advanced", false), prefs.getLong("revision", 0)) }
    fun commit(expectedRevision: Long, root: Boolean, advanced: Boolean): Long = synchronized(LOCK) {
        check(read().revision == expectedRevision) { "SETTINGS_REVISION_CONFLICT" }
        require(expectedRevision < Long.MAX_VALUE)
        val next = expectedRevision + 1
        check(prefs.edit().putBoolean("root", root).putBoolean("advanced", advanced).putLong("revision", next).commit()) { "SETTINGS_WRITE_FAILED" }
        next
    }
    companion object { private val LOCK = Any() }
    fun effective(preset: PermissionPreset): PrivilegeService.PrivilegeSettings {
        val value = read()
        val full = preset == PermissionPreset.FULL_ACCESS
        return PrivilegeService.PrivilegeSettings(rootEnabled = full && value.root, advancedCommandsEnabled = full && value.advanced)
    }
}
