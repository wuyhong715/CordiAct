package com.cordiact.core.jobs

import com.cordiact.core.context.SpillStore
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean

class DefaultJobRegistry(
    private val spillStore: SpillStore,
    private val jobStore: JobStore? = null,
    private val maxRunningPerSession: Int = 4,
    private val maxRunningGlobal: Int = 8,
    private val idGenerator: () -> String = { "job-${UUID.randomUUID().toString().take(8)}" },
    private val clockMillis: () -> Long = System::currentTimeMillis,
) : JobRegistry {

    private val activeHandles = ConcurrentHashMap<String, DefaultJobHandle>()
    private val completedSnapshots = ConcurrentHashMap<String, JobSnapshot>()
    private val startedListeners = CopyOnWriteArrayList<(JobSnapshot) -> Unit>()
    private val settledListeners = CopyOnWriteArrayList<(JobSnapshot) -> Unit>()
    private val probeHandlers = ConcurrentHashMap<JobKind, (JobSnapshot) -> JobSnapshot>()

    init {
        require(maxRunningPerSession > 0) { "maxRunningPerSession must be positive" }
        require(maxRunningGlobal > 0) { "maxRunningGlobal must be positive" }
    }

    override fun registerProbeHandler(kind: JobKind, handler: (JobSnapshot) -> JobSnapshot) {
        probeHandlers[kind] = handler
    }

    override fun start(spec: JobSpec): JobHandle {
        synchronized(this) {
            val sessionActiveCount = activeHandles.values.count {
                it.ownerSessionId == spec.ownerSessionId && !it.snapshot.isTerminal
            }
            if (sessionActiveCount >= maxRunningPerSession) {
                throw JobAdmissionException(
                    "Session '${spec.ownerSessionId}' has $sessionActiveCount running/stopping jobs " +
                        "(max limit is $maxRunningPerSession). Wait for completion or kill an existing job.",
                )
            }

            val globalActiveCount = activeHandles.values.count { !it.snapshot.isTerminal }
            if (globalActiveCount >= maxRunningGlobal) {
                throw JobAdmissionException(
                    "Global job limit reached ($globalActiveCount running/stopping jobs, max is $maxRunningGlobal).",
                )
            }

            val now = clockMillis()
            val jobId = idGenerator()
            val initialSnapshot = JobSnapshot(
                id = jobId,
                kind = spec.kind,
                survival = spec.survival,
                state = JobState.RUNNING,
                label = spec.label,
                ownerSessionId = spec.ownerSessionId,
                startedAtMillis = now,
                remotePid = spec.remotePid,
                remoteHostRef = spec.remoteHostRef,
                lastHeartbeatAtMillis = now,
            )

            jobStore?.insert(initialSnapshot)

            val handle = DefaultJobHandle(
                spec = spec,
                initialSnapshot = initialSnapshot,
                spillStore = spillStore,
                jobStore = jobStore,
                clockMillis = clockMillis,
                onSettled = { settledSnapshot ->
                    completedSnapshots[settledSnapshot.id] = settledSnapshot
                    activeHandles.remove(settledSnapshot.id)
                    settledListeners.forEach { it.invoke(settledSnapshot) }
                },
            )

            activeHandles[jobId] = handle
            startedListeners.forEach { it.invoke(initialSnapshot) }
            return handle
        }
    }

    override fun listActive(): List<JobSnapshot> {
        return activeHandles.values.map { it.snapshot }.filter { !it.isTerminal }
    }

    override fun addStartedListener(listener: (JobSnapshot) -> Unit) {
        startedListeners.add(listener)
    }

    override fun list(ownerSessionId: String): List<JobSnapshot> {
        val memorySnapshots = (activeHandles.values.map { it.snapshot } + completedSnapshots.values)
            .filter { it.ownerSessionId == ownerSessionId }

        if (jobStore == null) return memorySnapshots.sortedByDescending { it.startedAtMillis }

        val persistentSnapshots = jobStore.listByOwner(ownerSessionId)
        val combinedMap = persistentSnapshots.associateBy { it.id }.toMutableMap()
        memorySnapshots.forEach { combinedMap[it.id] = it }
        return combinedMap.values.sortedByDescending { it.startedAtMillis }
    }

    override fun get(jobId: String): JobSnapshot? {
        return activeHandles[jobId]?.snapshot
            ?: completedSnapshots[jobId]
            ?: jobStore?.find(jobId)
    }

    override fun read(jobId: String, offset: Long, limitBytes: Int): JobRead? {
        val snapshot = get(jobId) ?: return null
        val uri = snapshot.outputPath
        if (uri.isNullOrBlank()) {
            return JobRead(
                snapshot = snapshot,
                text = "",
                nextOffset = 0,
                eof = snapshot.isTerminal,
            )
        }

        val result = spillStore.readSpill(uri, offset, limitBytes)
        if (result == null) {
            return JobRead(
                snapshot = snapshot,
                text = "",
                nextOffset = offset,
                eof = snapshot.isTerminal,
            )
        }

        val nextOffset = result.offset + result.bytesRead
        val eof = snapshot.isTerminal && !result.hasMore

        return JobRead(
            snapshot = snapshot,
            text = result.content,
            nextOffset = nextOffset,
            eof = eof,
        )
    }

    override fun kill(jobId: String, reason: String): JobSnapshot? {
        val handle = activeHandles[jobId]
        if (handle != null) {
            handle.cancel(reason)
            return handle.snapshot
        }
        val stored = jobStore?.find(jobId) ?: return null
        if (!stored.isTerminal) {
            val killed = stored.copy(
                state = JobState.KILLED,
                finishedAtMillis = clockMillis(),
                diagnostic = "Killed while inactive in memory: $reason",
            )
            jobStore.update(killed)
            return killed
        }
        return stored
    }

    override fun probe(jobId: String): JobSnapshot? {
        val current = get(jobId) ?: return null
        if (current.survival != JobSurvival.REMOTE_DETACHED) {
            return current
        }

        val handler = probeHandlers[current.kind] ?: return current
        val probed = handler.invoke(current)
        if (probed != current) {
            jobStore?.update(probed)
            activeHandles[jobId]?.updateSnapshot(probed)
        }
        return probed
    }

    override fun addSettledListener(listener: (JobSnapshot) -> Unit) {
        settledListeners.add(listener)
    }

    override fun reconcileOnStartup(): List<JobSnapshot> {
        val reconciled = jobStore?.reconcileOnStartup().orEmpty()
        return reconciled
    }

    private class DefaultJobHandle(
        private val spec: JobSpec,
        initialSnapshot: JobSnapshot,
        private val spillStore: SpillStore,
        private val jobStore: JobStore?,
        private val clockMillis: () -> Long,
        private val onSettled: (JobSnapshot) -> Unit,
    ) : JobHandle {

        override val id: String = initialSnapshot.id
        override val ownerSessionId: String = initialSnapshot.ownerSessionId

        @Volatile
        override var snapshot: JobSnapshot = initialSnapshot
            private set

        private val settled = AtomicBoolean(false)
        private val outputBuffer = StringBuilder()

        @Synchronized
        override fun append(text: String) {
            if (settled.get()) return
            outputBuffer.append(text)

            // Flush to spill storage
            val uri = spillStore.saveSpill(ownerSessionId, "${spec.kind.name.lowercase()}-$id", outputBuffer.toString())
            val now = clockMillis()
            snapshot = snapshot.copy(
                outputPath = uri,
                outputBytes = outputBuffer.length.toLong(),
                lastHeartbeatAtMillis = now,
            )
            jobStore?.update(snapshot)
        }

        @Synchronized
        override fun updateProgress(line: String?) {
            if (settled.get()) return
            val now = clockMillis()
            snapshot = snapshot.copy(
                progressLine = line,
                lastHeartbeatAtMillis = now,
            )
            jobStore?.update(snapshot)
        }

        @Synchronized
        override fun updateRemotePid(pid: Long) {
            snapshot = snapshot.copy(remotePid = pid)
            jobStore?.update(snapshot)
        }

        @Synchronized
        override fun heartbeat() {
            if (settled.get()) return
            val now = clockMillis()
            snapshot = snapshot.copy(lastHeartbeatAtMillis = now)
            jobStore?.updateHeartbeat(id, now)
        }

        fun updateSnapshot(newSnapshot: JobSnapshot) {
            synchronized(this) {
                snapshot = newSnapshot
            }
        }

        fun cancel(reason: String) {
            if (settled.get()) return
            snapshot = snapshot.copy(state = JobState.STOPPING, diagnostic = reason)
            jobStore?.update(snapshot)
            try {
                spec.cancel(reason)
            } finally {
                settle(JobState.KILLED, exitCode = -1, diagnostic = "Cancelled: $reason")
            }
        }

        @Synchronized
        override fun settle(state: JobState, exitCode: Int?, diagnostic: String?) {
            if (!settled.compareAndSet(false, true)) return
            val now = clockMillis()
            snapshot = snapshot.copy(
                state = state,
                exitCode = exitCode,
                diagnostic = diagnostic ?: snapshot.diagnostic,
                progressLine = null,
                finishedAtMillis = now,
                lastHeartbeatAtMillis = now,
            )
            jobStore?.update(snapshot)
            onSettled(snapshot)
        }
    }
}
