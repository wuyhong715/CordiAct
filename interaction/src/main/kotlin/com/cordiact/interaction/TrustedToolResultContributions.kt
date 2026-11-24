package com.cordiact.interaction

import com.cordiact.core.conversation.ToolHistoryProvenance
import com.cordiact.core.plugin.PluginContext
import java.util.Collections
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** A bounded presentation recipe: localized host text, never output JSON, links, or actions. */
class ToolResultCard(val title: String, lines: List<String>) {
    val lines: List<String> = Collections.unmodifiableList(lines.toList())
    init {
        require(title.isNotBlank() && title.length <= 80 && title.none(Char::isISOControl))
        require(this.lines.size in 1..4 && this.lines.all { it.isNotBlank() && it.length <= 160 && it.none(Char::isISOControl) })
    }
}

/**
 * The APK supplies the exact allowlist. History provenance is a lookup key, not permission or a
 * renderer. No package can install a callback, parse raw result JSON, or add a click target here.
 */
class TrustedToolResultContributions(allowlist: Map<ToolHistoryProvenance, String>) {
    private val allowedPublishers = allowlist.toMap()
    private class Publication(val publisher: PluginContext, val card: ToolResultCard)
    private val lock = Any()
    private val registrations = mutableMapOf<ToolHistoryProvenance, Publication>()
    private val mutable = MutableStateFlow<Map<ToolHistoryProvenance, ToolResultCard>>(emptyMap())
    val snapshot = mutable.asStateFlow()

    /** First slice: only protected APK publishers can describe their allowlisted result formats. */
    fun register(context: PluginContext, key: ToolHistoryProvenance, card: ToolResultCard) {
        context.scope.requireOpen()
        require(context.id.workspaceId == null && context.id.sessionId == null && allowedPublishers[key] == context.id.pluginId) {
            "Untrusted tool-result publisher"
        }
        val registration = Publication(context, card)
        synchronized(lock) {
            check(key !in registrations) { "Tool-result card already registered" }
            registrations[key] = registration
        }
        context.effect {
            synchronized(lock) { registrations.remove(key, registration) }
            refresh()
        }
    }

    /** Published after a successful kernel start; withdrawn before any cleanup runs. */
    fun refresh() = synchronized(lock) {
        mutable.value = Collections.unmodifiableMap(registrations.filterValues { it.publisher.isContributionActive() }
            .mapValues { it.value.card })
    }

    fun resolve(key: ToolHistoryProvenance?): ToolResultCard? = synchronized(lock) {
        registrations[key]?.takeIf { it.publisher.isContributionActive() }?.card
    }
}
