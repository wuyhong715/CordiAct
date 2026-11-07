package com.cordiact.core.jobs

import java.util.concurrent.ConcurrentHashMap

/**
 * In-memory [JobStore] used by tests and by hosts that do not want durable job rows.
 *
 * Mirrors the SQLite implementation's reconciliation rules exactly, so the classification of a
 * job left behind by a restart is testable without an Android database.
 */
class InMemoryJobStore : JobStore {

    private val rows = ConcurrentHashMap<String, JobSnapshot>()

    override fun insert(snapshot: JobSnapshot) {
        rows[snapshot.id] = snapshot
    }

    override fun update(snapshot: JobSnapshot) {
        rows[snapshot.id] = snapshot
    }

    override fun find(jobId: String): JobSnapshot? = rows[jobId]

    override fun listByOwner(ownerSessionId: String): List<JobSnapshot> =
        rows.values.filter { it.ownerSessionId == ownerSessionId }.sortedByDescending { it.startedAtMillis }

    override fun listActive(): List<JobSnapshot> = rows.values.filter { !it.isTerminal }

    override fun updateHeartbeat(jobId: String, timestamp: Long) {
        rows.computeIfPresent(jobId) { _, existing -> existing.copy(lastHeartbeatAtMillis = timestamp) }
    }

    override fun reconcileOnStartup(): List<JobSnapshot> {
        val stranded = listActive()
        val now = System.currentTimeMillis()
        return stranded.map { job ->
            val updated = if (job.survival == JobSurvival.PROCESS_LOCAL) {
                job.copy(
                    state = JobState.ABORTED,
                    finishedAtMillis = now,
                    diagnostic = "The app was closed while this job was running; it did not survive.",
                )
            } else {
                job.copy(
                    state = JobState.DETACHED_UNKNOWN,
                    finishedAtMillis = now,
                    diagnostic = "The app restarted. The remote process may still be running; " +
                        "use job_probe to check before assuming an outcome.",
                )
            }
            update(updated)
            updated
        }
    }
}
