package com.cordiact.core.jobs

/**
 * Sweeps active jobs and reaps PROCESS_LOCAL jobs whose heartbeat lease has expired.
 *
 * Catches tasks whose coroutines or threads stalled indefinitely without progressing.
 */
class JobReaper(
    private val registry: JobRegistry,
    private val maxHeartbeatLagMillis: Long = 15_000L,
    private val clockMillis: () -> Long = System::currentTimeMillis,
) {
    fun sweep(): List<JobSnapshot> {
        val now = clockMillis()
        val reaped = mutableListOf<JobSnapshot>()
        for (job in registry.listActive()) {
            if (job.survival == JobSurvival.PROCESS_LOCAL) {
                val lag = now - job.lastHeartbeatAtMillis
                if (lag > maxHeartbeatLagMillis) {
                    val killed = registry.kill(
                        job.id,
                        "Heartbeat lease expired (inactive for ${lag}ms, limit is ${maxHeartbeatLagMillis}ms)",
                    )
                    if (killed != null) {
                        reaped.add(killed)
                    }
                }
            }
        }
        return reaped
    }
}
