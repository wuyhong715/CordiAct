package com.cordiact.app.experience.tabs

import android.content.Context
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Browser-style tabs. A tab is either a conversation (one session) or a plugin page.
 * Keys are stable strings so the strip survives process death: `s:<sessionId>` / `p:<pluginId>`.
 */
@Immutable
sealed interface WorkTab {
    val key: String
    data class Session(val sessionId: String) : WorkTab { override val key get() = "s:$sessionId" }
    data class Plugin(val pluginId: String) : WorkTab { override val key get() = "p:$pluginId" }

    companion object {
        fun parse(key: String): WorkTab? = when {
            key.startsWith("s:") && key.length > 2 -> Session(key.substring(2))
            key.startsWith("p:") && key.length > 2 -> Plugin(key.substring(2))
            else -> null
        }
    }
}

/** Open tabs + the active one. Pure list logic lives here so it can be JVM-tested without Compose UI. */
class TabsController(private val store: TabStore) {
    val tabs = mutableStateListOf<WorkTab>()
    var activeKey by mutableStateOf<String?>(null)
        private set

    init {
        val (keys, active) = store.load()
        keys.mapNotNullTo(tabs) { WorkTab.parse(it) }
        activeKey = active?.takeIf { key -> tabs.any { it.key == key } } ?: tabs.firstOrNull()?.key
    }

    val active: WorkTab? get() = tabs.firstOrNull { it.key == activeKey }

    /** The view model moved to [sessionId] (startup, new session, deep link): make sure it is a tab. */
    fun followSession(sessionId: String?) {
        if (sessionId == null) return
        val tab = WorkTab.Session(sessionId)
        if (tabs.none { it.key == tab.key }) {
            val at = tabs.indexOfFirst { it.key == activeKey }.let { if (it < 0) tabs.size else it + 1 }
            tabs.add(at, tab)
        }
        if (active !is WorkTab.Plugin) activeKey = tab.key
        persist()
    }

    fun open(tab: WorkTab) {
        if (tabs.none { it.key == tab.key }) {
            val at = tabs.indexOfFirst { it.key == activeKey }.let { if (it < 0) tabs.size else it + 1 }
            tabs.add(at, tab)
        }
        activeKey = tab.key
        persist()
    }

    fun select(key: String) { if (tabs.any { it.key == key }) { activeKey = key; persist() } }

    /** Returns the tab that becomes active, or null when the active tab did not change. */
    fun close(key: String): WorkTab? {
        val index = tabs.indexOfFirst { it.key == key }
        if (index < 0) return null
        // The last conversation tab stays: the shell always needs one session to compose into.
        if (tabs[index] is WorkTab.Session && tabs.count { it is WorkTab.Session } == 1) return null
        tabs.removeAt(index)
        val next = if (key == activeKey) tabs.getOrNull(index.coerceAtMost(tabs.lastIndex)) ?: tabs.lastOrNull() else null
        if (next != null) activeKey = next.key
        persist()
        return next
    }

    fun move(from: Int, to: Int) {
        if (from !in tabs.indices || to !in tabs.indices || from == to) return
        tabs.add(to, tabs.removeAt(from))
        persist()
    }

    fun orderPluginTabs(orderedPanelIds: List<String>, showPanelIds: Set<String> = emptySet()) {
        val order = orderedPanelIds.withIndex().associate { (index, id) -> id to index }
        val updated = tabs.toMutableList()
        orderedPanelIds.filter { it in showPanelIds }.forEach { id ->
            val tab = WorkTab.Plugin(id)
            if (tab !in updated) updated.add(tab)
        }
        val positions = updated.indices.filter { updated[it] is WorkTab.Plugin }
        val sorted = positions.map { updated[it] as WorkTab.Plugin }.sortedBy { order[it.pluginId] ?: Int.MAX_VALUE }
        positions.zip(sorted).forEach { (index, tab) -> updated[index] = tab }
        if (updated == tabs) return
        tabs.clear()
        tabs.addAll(updated)
        persist()
    }

    /** A missing optional page returns to the loaded conversation, not an unrelated neighbor. */
    fun prune(liveSessions: Set<String>, livePlugins: Set<String>, loadedSession: String? = null) {
        val preferred = loadedSession?.let { WorkTab.Session(it) }
        val dead = tabs.filter {
            (it is WorkTab.Session && liveSessions.isNotEmpty() && it.sessionId !in liveSessions && it != preferred) ||
                (it is WorkTab.Plugin && it.pluginId !in livePlugins)
        }
        if (dead.isEmpty()) return
        tabs.removeAll(dead)
        if (tabs.none { it.key == activeKey }) {
            if (preferred != null && preferred !in tabs) tabs.add(preferred)
            activeKey = preferred?.key ?: tabs.firstOrNull()?.key
        }
        persist()
    }

    private fun persist() = store.save(tabs.map { it.key }, activeKey)
}

interface TabStore {
    fun load(): Pair<List<String>, String?>
    fun save(keys: List<String>, active: String?)
}

class PrefsTabStore(context: Context) : TabStore {
    private val prefs = context.getSharedPreferences("cordiact_work_tabs", Context.MODE_PRIVATE)
    override fun load() = (prefs.getString("keys", null)?.split('\n')?.filter { it.isNotBlank() } ?: emptyList()) to prefs.getString("active", null)
    override fun save(keys: List<String>, active: String?) { prefs.edit().putString("keys", keys.joinToString("\n")).putString("active", active).apply() }
}
