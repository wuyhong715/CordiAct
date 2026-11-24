package com.cordiact.interaction

import com.cordiact.core.plugin.PluginContext
import com.cordiact.core.plugin.PluginInstanceId
import java.util.Collections
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Application-owned, trusted-code slot. A declaration package cannot construct a renderer or
 * register a tab. The directory owns only visibility, never navigation or execution permission.
 */
class TrustedWorkbenchTabs<T : Any> {
    class Publication<T : Any> internal constructor(
        val id: String,
        val owner: PluginInstanceId,
        val content: T,
        internal val order: Int,
    ) {
        internal fun matches(workspaceId: String?, sessionId: String?): Boolean =
            owner.workspaceId == null || (owner.workspaceId == workspaceId && (owner.sessionId == null || owner.sessionId == sessionId))
    }

    @ConsistentCopyVisibility
    data class Snapshot<T : Any> internal constructor(private val entries: List<Publication<T>>) {
        fun visible(workspaceId: String?, sessionId: String?): List<Publication<T>> =
            Collections.unmodifiableList(entries.filter { it.matches(workspaceId, sessionId) })
    }

    private class Registration<T : Any>(val context: PluginContext, val publication: Publication<T>)
    private val lock = Any()
    private val registrations = mutableListOf<Registration<T>>()
    private val mutable = MutableStateFlow(Snapshot<T>(emptyList()))
    val snapshot = mutable.asStateFlow()

    fun register(context: PluginContext, id: String, content: T, order: Int = 0) {
        context.scope.requireOpen()
        require(tabId.matches(id)) { "Invalid workbench tab ID" }
        val owner = context.id
        val registration = Registration(context, Publication(id, owner, content, order))
        synchronized(lock) {
            check(registrations.none { it.publication.id == id && overlaps(it.context.id, owner) }) {
                "Workbench tab '$id' is already registered in an overlapping scope"
            }
            registrations.add(registration)
        }
        context.effect {
            synchronized(lock) { registrations.remove(registration) }
            refresh()
        }
    }

    /** Call on kernel publication edges, including before potentially blocking disposers. */
    fun refresh() = synchronized(lock) {
        val published = registrations.asSequence().filter { it.context.isContributionActive() }.map { it.publication }
            .sortedWith(compareBy({ it.order }, { it.id }, { it.owner.workspaceId }, { it.owner.sessionId }))
            .toList()
        mutable.value = Snapshot(Collections.unmodifiableList(published))
    }

    /** A snapshot is render data; clicks and host callbacks must pass this live identity check. */
    fun isCurrent(publication: Publication<T>, workspaceId: String?, sessionId: String?): Boolean = synchronized(lock) {
        publication.matches(workspaceId, sessionId) && registrations.any {
            it.publication === publication && it.context.isContributionActive()
        }
    }

    private fun overlaps(left: PluginInstanceId, right: PluginInstanceId): Boolean =
        left.workspaceId == null || right.workspaceId == null || (left.workspaceId == right.workspaceId &&
            (left.sessionId == null || right.sessionId == null || left.sessionId == right.sessionId))

    private companion object { val tabId = Regex("[a-z0-9._-]{1,48}") }
}
