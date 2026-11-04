package com.cordiact.app.search

import com.cordiact.app.conversation.AndroidConversationSessionStore
import com.cordiact.core.search.SEARCH_PLUGIN_ID
import com.cordiact.core.search.SearchUsageStore
import java.time.YearMonth

/**
 * Monthly billable-call counter.
 *
 * The month is part of the stored key, so a new month starts at zero without a
 * reset job, and an old key simply stops being read.
 */
class AndroidSearchUsageStore(
    private val store: AndroidConversationSessionStore,
) : SearchUsageStore {
    override fun usedThisMonth(workspaceId: String): Int {
        val raw = store.pluginSetting(workspaceId, SEARCH_PLUGIN_ID, key()) ?: return 0
        return raw.toIntOrNull()?.coerceAtLeast(0) ?: 0
    }

    override fun record(workspaceId: String, calls: Int) {
        if (calls <= 0) return
        synchronized(lock) {
            val next = usedThisMonth(workspaceId) + calls
            store.putPluginSetting(workspaceId, SEARCH_PLUGIN_ID, key(), next.toString())
        }
    }

    /** Drops the current month's counter; used by the settings reset action. */
    fun reset(workspaceId: String) {
        store.putPluginSetting(workspaceId, SEARCH_PLUGIN_ID, key(), null)
    }

    private fun key(): String = "usage." + YearMonth.now()

    private companion object {
        val lock = Any()
    }
}
