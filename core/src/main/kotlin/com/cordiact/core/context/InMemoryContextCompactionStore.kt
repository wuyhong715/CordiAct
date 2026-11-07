package com.cordiact.core.context

import java.util.concurrent.ConcurrentHashMap

/**
 * Thread-safe in-memory store for compaction records, useful for testing and transient sessions.
 */
class InMemoryContextCompactionStore : ContextCompactionStore {
    private val records = ConcurrentHashMap<String, CompactionRecord>()

    override fun insert(record: CompactionRecord) {
        records[record.id] = record
    }

    override fun listActive(sessionId: String): List<CompactionRecord> {
        return records.values
            .filter { it.sessionId == sessionId && it.active }
            .sortedBy { it.createdAt }
    }

    override fun getCompaction(id: String): CompactionRecord? = records[id]

    override fun supersede(id: String, supersededBy: String) {
        val existing = records[id] ?: return
        records[id] = existing.copy(active = false, supersededBy = supersededBy)
    }

    fun all(): List<CompactionRecord> = records.values.toList()
}
