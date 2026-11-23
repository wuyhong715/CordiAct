package com.cordiact.app.experience.tabs

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.cordiact.app.experience.ExperienceState
import com.cordiact.app.experience.GlyphName
import com.cordiact.app.experience.model.ToolActivity

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

/** What a plugin page may ask of the shell. Kept tiny on purpose: shells evolve, plugins should not break. */
interface TabHost {
    fun inspect(step: ToolActivity)
    fun openSession(sessionId: String)
}

/**
 * Plugin tab contract. The shell owns the strip, the chrome, the material and the overview card;
 * a contributor only supplies identity, a badge and the page body.
 */
interface TabContributor {
    /** Stable, unique, `[a-z0-9._-]`. Persisted in the tab strip. */
    val id: String
    val title: String
    val glyph: GlyphName
    /** Short count shown on the tab; 0 hides it. Must be cheap: it is read on every frame of the strip. */
    fun badge(state: ExperienceState): Int = 0
    @Composable fun Content(state: ExperienceState, host: TabHost)
}

/** Process-wide registry. Built-ins register at screen start; real plugins register from their own init. */
object TabRegistry {
    private val items = mutableStateListOf<TabContributor>()
    val contributors: List<TabContributor> get() = items
    fun register(contributor: TabContributor) {
        require(contributor.id.matches(Regex("[a-z0-9._-]{1,48}"))) { "invalid tab id: ${contributor.id}" }
        val existing = items.indexOfFirst { it.id == contributor.id }
        if (existing >= 0) items[existing] = contributor else items += contributor
    }
    fun unregister(id: String) { items.removeAll { it.id == id } }
    fun find(id: String): TabContributor? = items.firstOrNull { it.id == id }
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

    /** Drop tabs whose session vanished (deleted/archived elsewhere) or whose plugin is gone. */
    fun prune(liveSessions: Set<String>, livePlugins: Set<String>) {
        if (liveSessions.isEmpty()) return
        val dead = tabs.filter {
            (it is WorkTab.Session && it.sessionId !in liveSessions) || (it is WorkTab.Plugin && it.pluginId !in livePlugins)
        }
        if (dead.isEmpty()) return
        tabs.removeAll(dead)
        if (tabs.none { it.key == activeKey }) activeKey = tabs.firstOrNull()?.key
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
