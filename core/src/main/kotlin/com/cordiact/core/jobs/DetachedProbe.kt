package com.cordiact.core.jobs

/**
 * What a probe of a detached remote process reported.
 *
 * Kept separate from the SSH machinery so the mapping from "what the shell said" to "what we are
 * willing to claim" is testable without a network, and so no other backend has to re-derive it.
 */
sealed interface DetachedProbeResult {
    /** `kill -0` succeeded: the PID exists. */
    data object Alive : DetachedProbeResult

    /** `kill -0` reported no such process. */
    data object Gone : DetachedProbeResult

    /**
     * The probe itself failed: host unreachable, auth rejected, timeout.
     *
     * Distinct from [Gone] on purpose. "I could not ask" and "I asked and it is not there" are
     * different facts, and collapsing them would let a network blip read as a completed deploy.
     */
    data class Indeterminate(val reason: String) : DetachedProbeResult

    companion object {
        /**
         * Classifies the output of `kill -0 <pid> && echo ALIVE || echo GONE`.
         *
         * Anything that is neither an explicit ALIVE nor an explicit GONE is indeterminate: a
         * partial read, an empty body, a timeout. Guessing here is exactly the failure this probe
         * exists to avoid.
         */
        fun classify(stdout: String, timedOut: Boolean, failedToRun: Boolean): DetachedProbeResult {
            if (failedToRun) return Indeterminate("the probe command could not be executed")
            if (timedOut) return Indeterminate("the probe timed out")

            val text = stdout.uppercase()
            return when {
                text.contains("ALIVE") -> Alive
                text.contains("GONE") -> Gone
                else -> Indeterminate("the probe returned an unreadable answer: ${stdout.take(120)}")
            }
        }
    }
}

/** Maps a probe result onto the job state and diagnostic the model will read. */
object DetachedProbeStates {

    fun apply(snapshot: JobSnapshot, result: DetachedProbeResult, nowMillis: Long): JobSnapshot {
        val pid = snapshot.remotePid?.toString() ?: "unknown"
        return when (result) {
            DetachedProbeResult.Alive -> snapshot.copy(
                state = JobState.DETACHED_RUNNING,
                diagnostic = "Probed at $nowMillis: remote PID $pid still exists. " +
                    "Its exit code cannot be observed from here.",
                lastHeartbeatAtMillis = nowMillis,
            )

            DetachedProbeResult.Gone -> snapshot.copy(
                state = JobState.DETACHED_FINISHED,
                diagnostic = "Probed at $nowMillis: remote PID $pid is gone. " +
                    "It finished or was killed; its exit code was never observed.",
                lastHeartbeatAtMillis = nowMillis,
            )

            is DetachedProbeResult.Indeterminate -> snapshot.copy(
                state = JobState.DETACHED_UNKNOWN,
                diagnostic = "Probe at $nowMillis failed (${result.reason}). " +
                    "The remote process may still be running.",
                lastHeartbeatAtMillis = nowMillis,
            )
        }
    }
}
