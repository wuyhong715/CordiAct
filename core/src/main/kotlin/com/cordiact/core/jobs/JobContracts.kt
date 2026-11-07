package com.cordiact.core.jobs

enum class JobKind {
    SHELL,
    SSH,
    TERMUX,
    SUBAGENT,
}

/**
 * Survival expectation across host process restarts.
 */
enum class JobSurvival {
    /** Dies with the Android process; marked ABORTED on process restart. */
    PROCESS_LOCAL,
    /** Runs on a remote machine (e.g. detached SSH nohup); marked DETACHED_UNKNOWN on restart until probed. */
    REMOTE_DETACHED,
}

enum class JobState {
    PENDING,
    RUNNING,
    STOPPING,
    SUCCEEDED,
    FAILED,
    KILLED,
    ABORTED,
    DETACHED_UNKNOWN,
}

data class JobSpec(
    val kind: JobKind,
    val survival: JobSurvival,
    val label: String,
    val ownerSessionId: String,
    val outputLimitBytes: Int = 64_000,
    val remotePid: Long? = null,
    /**
     * Cancellation callback. Must return synchronously, must be idempotent,
     * and must eventually settle the handle.
     */
    val cancel: (reason: String) -> Unit = {},
)

interface JobHandle {
    val id: String
    val ownerSessionId: String
    val snapshot: JobSnapshot
    fun append(text: String)
    fun updateProgress(line: String?)
    fun updateRemotePid(pid: Long)

    /**
     * Refreshes the liveness lease without producing output.
     *
     * A worker blocked in a long call should tick this from a watchdog; a worker that is simply
     * gone stops ticking, which is what [JobReaper] is looking for.
     */
    fun heartbeat()
    fun settle(state: JobState, exitCode: Int? = null, diagnostic: String? = null)
}

data class JobSnapshot(
    val id: String,
    val kind: JobKind,
    val survival: JobSurvival,
    val state: JobState,
    val label: String,
    val ownerSessionId: String,
    val startedAtMillis: Long,
    val finishedAtMillis: Long? = null,
    val exitCode: Int? = null,
    val progressLine: String? = null,
    val outputPath: String? = null,
    val outputBytes: Long = 0,
    val remotePid: Long? = null,
    val diagnostic: String? = null,
    val lastHeartbeatAtMillis: Long = startedAtMillis,
) {
    val isTerminal: Boolean
        get() = state in setOf(
            JobState.SUCCEEDED,
            JobState.FAILED,
            JobState.KILLED,
            JobState.ABORTED,
            // A detached remote job has no local worker left to wait on. Its remote outcome is
            // unknown rather than pending, so it must not hold a quota slot or a wake lock.
            JobState.DETACHED_UNKNOWN,
        )
}

data class JobRead(
    val snapshot: JobSnapshot,
    val text: String,
    val nextOffset: Long,
    val eof: Boolean,
)

class JobAdmissionException(message: String) : IllegalStateException(message)

interface JobStore {
    fun insert(snapshot: JobSnapshot)
    fun update(snapshot: JobSnapshot)
    fun find(jobId: String): JobSnapshot?
    fun listByOwner(ownerSessionId: String): List<JobSnapshot>
    fun listActive(): List<JobSnapshot>
    fun updateHeartbeat(jobId: String, timestamp: Long)
    fun reconcileOnStartup(): List<JobSnapshot>
}

interface JobRegistry {
    fun start(spec: JobSpec): JobHandle
    fun list(ownerSessionId: String): List<JobSnapshot>
    fun listActive(): List<JobSnapshot>
    fun get(jobId: String): JobSnapshot?
    fun read(jobId: String, offset: Long = 0, limitBytes: Int = 8_000): JobRead?
    fun kill(jobId: String, reason: String = "user requested"): JobSnapshot?
    fun probe(jobId: String): JobSnapshot?
    fun addStartedListener(listener: (JobSnapshot) -> Unit)
    fun addSettledListener(listener: (JobSnapshot) -> Unit)
    fun reconcileOnStartup(): List<JobSnapshot>
}
