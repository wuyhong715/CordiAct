package com.cordiact.interaction

import com.cordiact.core.plugin.PluginContext
import com.cordiact.core.plugin.PluginInstanceId
import java.util.Collections
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Application-owned slot for trusted renderers. The directory owns visibility without granting
 * navigation or execution permission.
 */
class TrustedWorkbenchTabs<T : Any> {
    class Publication<T : Any> internal constructor(
        val id: String,
        val owner: PluginInstanceId,
        val content: T,
        internal val order: Int,
        private val active: () -> Boolean,
    ) {
        internal fun isActive() = active()

        internal fun matches(workspaceId: String?, sessionId: String?): Boolean =
            owner.workspaceId == null || (owner.workspaceId == workspaceId && (owner.sessionId == null || owner.sessionId == sessionId))
    }

    @ConsistentCopyVisibility
    data class Snapshot<T : Any> internal constructor(private val entries: List<Publication<T>>) {
        fun visible(workspaceId: String?, sessionId: String?): List<Publication<T>> =
            Collections.unmodifiableList(entries.filter { it.matches(workspaceId, sessionId) && it.isActive() })
    }

    private val lock = Any()
    private val registrations = mutableListOf<Publication<T>>()
    private val mutable = MutableStateFlow(Snapshot<T>(emptyList()))
    val snapshot = mutable.asStateFlow()

    fun register(context: PluginContext, id: String, content: T, order: Int = 0, admitted: () -> Boolean = { true }) {
        context.scope.requireOpen()
        require(tabId.matches(id)) { "Invalid workbench tab ID" }
        val owner = context.id
        val publication = Publication(id, owner, content, order) { context.isContributionActive() && admitted() }
        synchronized(lock) {
            check(registrations.none { it.id == id && overlaps(it.owner, owner) }) {
                "Workbench tab '$id' is already registered in an overlapping scope"
            }
            registrations.add(publication)
        }
        context.effect {
            synchronized(lock) { registrations.remove(publication) }
            refresh()
        }
    }

    /** Call on kernel publication edges, including before potentially blocking disposers. */
    fun refresh() = synchronized(lock) {
        val published = registrations.asSequence().filter(Publication<T>::isActive)
            .sortedWith(compareBy({ it.order }, { it.id }, { it.owner.workspaceId }, { it.owner.sessionId }))
            .toList()
        mutable.value = Snapshot(Collections.unmodifiableList(published))
    }

    /** A snapshot is render data; clicks and host callbacks must pass this live identity check. */
    fun isCurrent(publication: Publication<T>, workspaceId: String?, sessionId: String?): Boolean = synchronized(lock) {
        publication.matches(workspaceId, sessionId) && publication in registrations && publication.isActive()
    }

    private fun overlaps(left: PluginInstanceId, right: PluginInstanceId): Boolean =
        left.workspaceId == null || right.workspaceId == null || (left.workspaceId == right.workspaceId &&
            (left.sessionId == null || right.sessionId == null || left.sessionId == right.sessionId))

    private companion object { val tabId = Regex("[a-z0-9._-]{1,48}") }
}
