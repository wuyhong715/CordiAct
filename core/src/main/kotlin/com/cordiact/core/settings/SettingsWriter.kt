package com.cordiact.core.settings

/** Revision commit belongs to the same storage transaction as the value write. */
interface SettingsMutation {
    fun validate()
    fun commit(expectedRevision: Long): Long
}
data class SettingsChange(val target: String, val revision: Long)
/** Serializes trusted settings writes. Audit failure is reported, never silently treated as success. */
class SettingsWriter(
    private val audit: (SettingsChange) -> Unit,
    private val changed: (SettingsChange) -> Unit = {},
) {
    @Synchronized
    fun write(target: String, expectedRevision: Long, mutation: SettingsMutation): Long {
        require(target.matches(Regex("[A-Za-z0-9_.:-]{1,120}")))
        require(expectedRevision >= 0 && expectedRevision < Long.MAX_VALUE)
        mutation.validate()
        val revision = mutation.commit(expectedRevision)
        check(revision == expectedRevision + 1) { "SETTINGS_REVISION_CONFLICT" }
        val change = SettingsChange(target, revision)
        // Notify even if audit fails: a committed security revision must invalidate stale grants.
        try { audit(change) } finally { changed(change) }
        return revision
    }
}
