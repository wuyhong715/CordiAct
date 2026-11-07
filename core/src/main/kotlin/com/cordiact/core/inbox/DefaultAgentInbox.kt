package com.cordiact.core.inbox

import java.util.concurrent.atomic.AtomicLong

/**
 * Single FIFO per Session, ordered by one monotonic sequence.
 *
 * Deliberately has no second queue and no priority lane: the deepseek-harness settlement-ordering
 * bug came from two queues whose drain priorities differed, which let a later settlement overtake
 * an earlier message. One sequence plus one drain path makes that impossible by construction.
 */
class DefaultAgentInbox(
    private val seqGenerator: () -> Long = AtomicLong(0L)::incrementAndGet,
) : AgentInbox {

    private val queues = mutableMapOf<String, MutableList<InboxItem>>()
    private val lock = Any()

    override fun enqueue(
        kind: InboxItemKind,
        sessionId: String,
        sourceId: String,
        heading: String,
        body: String?,
        createdAtMillis: Long,
    ): InboxItem {
        val item = InboxItem(
            seq = seqGenerator(),
            kind = kind,
            sessionId = sessionId,
            sourceId = sourceId,
            heading = heading,
            body = body,
            createdAtMillis = createdAtMillis,
        )
        synchronized(lock) {
            queues.getOrPut(sessionId) { mutableListOf() } += item
        }
        return item
    }

    override fun peek(sessionId: String): List<InboxItem> = synchronized(lock) {
        queues[sessionId]?.sortedBy { it.seq }?.toList().orEmpty()
    }

    override fun drain(sessionId: String, limit: Int): List<InboxItem> {
        require(limit > 0) { "limit must be positive" }
        synchronized(lock) {
            val queue = queues[sessionId] ?: return emptyList()
            queue.sortBy { it.seq }
            val taken = queue.take(limit)
            queue.removeAll(taken.toSet())
            if (queue.isEmpty()) queues.remove(sessionId)
            return taken
        }
    }

    override fun requeue(items: List<InboxItem>) {
        if (items.isEmpty()) return
        synchronized(lock) {
            for (item in items) {
                val queue = queues.getOrPut(item.sessionId) { mutableListOf() }
                if (queue.none { it.seq == item.seq }) queue += item
                queue.sortBy { it.seq }
            }
        }
    }

    override fun size(sessionId: String): Int = synchronized(lock) { queues[sessionId]?.size ?: 0 }
}
