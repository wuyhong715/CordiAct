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
    private val completedSnapshots = java.util.Collections.synchronizedMap(
        object : LinkedHashMap<String, JobSnapshot>(16, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, JobSnapshot>?) = size > 256
        },
    )
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
                    if (jobStore == null) completedSnapshots[settledSnapshot.id] = settledSnapshot
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
        val memorySnapshots = (activeHandles.values.map { it.snapshot } + synchronized(completedSnapshots) { completedSnapshots.values.toList() })
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

        val result = spillStore.readSpillBytes(uri, offset, limitBytes)
        if (result == null) {
            return JobRead(
                snapshot = snapshot,
                text = "",
                nextOffset = offset,
                eof = snapshot.isTerminal,
            )
        }

        val nextOffset = result.nextOffset
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
        private var outputCapped = false

        @Synchronized
        override fun append(text: String) {
            if (settled.get() || text.isEmpty()) return
            val output = if (outputCapped) null else spillStore.appendSpill(
                ownerSessionId, "${spec.kind.name.lowercase()}-$id", snapshot.outputPath, text,
            )
            if (output != null) outputCapped = output.truncated
            val now = clockMillis()
            snapshot = snapshot.copy(
                outputPath = output?.uri ?: snapshot.outputPath,
                outputBytes = output?.bytesWritten ?: snapshot.outputBytes,
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
            val previous = synchronized(this) {
                if (settled.get() || snapshot.state == JobState.STOPPING) return
                snapshot.also {
                    snapshot = it.copy(state = JobState.STOPPING, diagnostic = reason)
                    jobStore?.update(snapshot)
                }
            }
            try {
                // A concurrent worker settlement wins; cancellation has no observed exit code.
                spec.cancel(reason)
                settle(JobState.KILLED, exitCode = null, diagnostic = reason)
            } catch (failure: Exception) {
                synchronized(this) {
                    if (!settled.get()) {
                        snapshot = snapshot.copy(state = previous.state, diagnostic = "Cancellation request failed")
                        jobStore?.update(snapshot)
                    }
                }
                throw failure
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
