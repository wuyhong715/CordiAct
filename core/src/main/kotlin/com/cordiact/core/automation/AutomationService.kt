package com.cordiact.core.automation

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonParser
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.UUID

/**
 * Durable task/run store and scheduler core.
 *
 * Every mutation is serialised through one lock, written as a complete JSON
 * document to a temporary file in the same directory, fsynced, and atomically
 * renamed over [file]. In-memory state is only replaced after the rename
 * succeeds, so a failed write leaves the previous state untouched.
 *
 * The constructor never rewrites a broken document: an unreadable file, an
 * unknown store version, or an invalid record throws [IllegalStateException].
 */
class AutomationService(
    private val file: File,
    private val now: () -> Long = { System.currentTimeMillis() },
) {

    private val lock = Any()
    private val gson: Gson = GsonBuilder().disableHtmlEscaping().create()

    private var tasks: LinkedHashMap<String, AutomationTask> = LinkedHashMap()
    private var runs: LinkedHashMap<String, AutomationRun> = LinkedHashMap()

    init {
        val loaded = readStore()
        tasks = loaded.first
        runs = loaded.second
    }

    // ---------------------------------------------------------------- queries

    fun tasks(workspaceId: String): List<AutomationTask> = synchronized(lock) {
        tasks.values
            .filter { it.workspaceId == workspaceId }
            .sortedWith(compareBy({ it.createdAt }, { it.id }))
    }

    fun runs(workspaceId: String, taskId: String? = null): List<AutomationRun> = synchronized(lock) {
        runs.values
            .filter { it.workspaceId == workspaceId && (taskId == null || it.taskId == taskId) }
            .sortedWith(compareBy({ it.createdAt }, { it.id }))
    }

    fun task(workspaceId: String, id: String): AutomationTask? = synchronized(lock) {
        tasks[taskKey(workspaceId, id)]
    }

    fun nextWakeAt(): Long? = synchronized(lock) {
        tasks.values
            .asSequence()
            .filter { it.enabled }
            .mapNotNull { it.nextFireAt }
            .minOrNull()
    }

    // --------------------------------------------------------------- mutation

    fun save(input: AutomationTask, expectedRevision: Long? = null): AutomationTask = synchronized(lock) {
        val task = if (input.id.isBlank()) input.copy(id = UUID.randomUUID().toString()) else input
        val current = now()
        TaskValidation.validate(task)
        val key = taskKey(task.workspaceId, task.id)
        val existing = tasks[key]

        if (existing == null) {
            require(tasks.values.none { it.id == task.id }) {
                "Task id ${task.id} already exists in another workspace"
            }
            val saved = task.copy(
                revision = 1,
                nextFireAt = computeNext(task.trigger, current),
                createdAt = if (task.createdAt > 0L) task.createdAt else current,
                updatedAt = current,
            )
            val nextTasks = LinkedHashMap(tasks)
            nextTasks[key] = saved
            persist(nextTasks, runs)
            tasks = nextTasks
            return saved
        }

        val expected = requireNotNull(expectedRevision) {
            "expectedRevision is required when updating an existing task"
        }
        if (existing.revision != expected) {
            throw TaskRevisionConflictException(task.id, expected, existing.revision)
        }

        val triggerChanged = existing.trigger != task.trigger
        val enabling = !existing.enabled && task.enabled
        val nextFireAt = when {
            triggerChanged -> computeNext(task.trigger, current)
            enabling -> computeNext(task.trigger, current)
            // A paused edit keeps the stored schedule verbatim, including a
            // spent one-time task's null nextFireAt.
            !existing.enabled -> existing.nextFireAt
            else -> existing.nextFireAt ?: computeNext(task.trigger, current)
        }

        val saved = task.copy(
            revision = existing.revision + 1,
            nextFireAt = nextFireAt,
            createdAt = existing.createdAt,
            updatedAt = current,
        )
        val nextTasks = LinkedHashMap(tasks)
        nextTasks[key] = saved
        val nextRuns = if (saved.enabled) runs else LinkedHashMap(runs).apply {
            entries.forEach { (runId, run) ->
                if (run.taskId == task.id && run.workspaceId == task.workspaceId && run.state == TaskRunState.QUEUED) {
                    put(runId, run.copy(state = TaskRunState.CANCELLED, finishedAt = current, diagnostic = "Task paused before the run started"))
                }
            }
        }
        persist(nextTasks, nextRuns)
        tasks = nextTasks
        runs = nextRuns
        return saved
    }

    fun setEnabled(workspaceId: String, id: String, enabled: Boolean): AutomationTask = synchronized(lock) {
        val key = taskKey(workspaceId, id)
        val existing = tasks[key] ?: throw IllegalArgumentException("Task not found: $id")
        if (existing.enabled == enabled) return existing

        val current = now()
        val saved = existing.copy(
            enabled = enabled,
            nextFireAt = if (enabled) computeNext(existing.trigger, current) else existing.nextFireAt,
            revision = existing.revision + 1,
            updatedAt = current,
        )
        val nextTasks = LinkedHashMap(tasks)
        nextTasks[key] = saved
        val nextRuns = if (enabled) runs else LinkedHashMap(runs).apply {
            entries.forEach { (runId, run) ->
                if (run.taskId == id && run.workspaceId == workspaceId && run.state == TaskRunState.QUEUED) {
                    put(runId, run.copy(state = TaskRunState.CANCELLED, finishedAt = current, diagnostic = "Task paused before the run started"))
                }
            }
        }
        persist(nextTasks, nextRuns)
        tasks = nextTasks
        runs = nextRuns
        return saved
    }

    fun delete(workspaceId: String, id: String): Unit = synchronized(lock) {
        val key = taskKey(workspaceId, id)
        if (!tasks.containsKey(key)) return
        require(runs.values.none { it.taskId == id && it.workspaceId == workspaceId && (it.state == TaskRunState.QUEUED || it.state == TaskRunState.RUNNING) }) {
            "Task has a queued or running run; cancel it before deleting"
        }
        val nextTasks = LinkedHashMap(tasks)
        nextTasks.remove(key)
        val nextRuns = LinkedHashMap(runs)
        nextRuns.entries.removeIf { it.value.taskId == id && it.value.workspaceId == workspaceId }
        persist(nextTasks, nextRuns)
        tasks = nextTasks
        runs = nextRuns
    }

    fun runNow(workspaceId: String, id: String): AutomationRun = synchronized(lock) {
        val task = tasks[taskKey(workspaceId, id)]
            ?: throw IllegalArgumentException("Task not found: $id")
        activeRunFor(task)?.let { return it }

        val current = now()
        val run = newRun(task, scheduledAt = current, createdAt = current)
        val nextRuns = LinkedHashMap(runs)
        nextRuns[run.id] = run
        persist(tasks, nextRuns)
        runs = nextRuns
        return run
    }

    /**
     * Queues one run per due task. Missed recurring occurrences collapse into a
     * single catch-up run and the schedule jumps to the next future occurrence,
     * so a long sleep never produces an unbounded burst.
     */
    fun enqueueDue(isWorkspaceEnabled: (String) -> Boolean = { true }): List<AutomationRun> = synchronized(lock) {
        val current = now()
        val due = tasks.values
            .filter { it.enabled && it.nextFireAt?.let { next -> next <= current } == true }
            .sortedBy { it.nextFireAt ?: Long.MAX_VALUE }
        if (due.isEmpty()) return emptyList()

        val nextTasks = LinkedHashMap(tasks)
        val nextRuns = LinkedHashMap(runs)
        val created = mutableListOf<AutomationRun>()
        var changed = false

        for (task in due) {
            if (!isWorkspaceEnabled(task.workspaceId)) continue
            val scheduledAt = task.nextFireAt ?: continue
            val key = taskKey(task.workspaceId, task.id)

            if (activeRunFor(task, nextRuns.values) == null) {
                val run = newRun(task, scheduledAt = scheduledAt, createdAt = current)
                nextRuns[run.id] = run
                created += run
            }
            nextTasks[key] = advanceAfterFire(task, scheduledAt, current)
            changed = true
        }

        if (!changed) return emptyList()
        val trimmed = trimRuns(nextRuns)
        persist(nextTasks, trimmed)
        tasks = nextTasks
        runs = trimmed
        return created
    }

    fun claim(runId: String): AutomationRun? = synchronized(lock) {
        val run = runs[runId] ?: return null
        if (run.state != TaskRunState.QUEUED) return null

        val updated = run.copy(state = TaskRunState.RUNNING, startedAt = now(),
            executionCheckpoint = TaskExecutionCheckpoint(TaskExecutionStage.PREPARING, now()))
        val nextRuns = LinkedHashMap(runs)
        nextRuns[runId] = updated
        persist(tasks, nextRuns)
        runs = nextRuns
        return updated
    }

    /** Persist before dispatch. No arguments, credentials or model output enter this journal. */
    fun beginTool(runId: String): String? = synchronized(lock) {
        val run = runs[runId] ?: return null
        if (run.state != TaskRunState.RUNNING) return null
        check(run.executionCheckpoint?.pendingToolToken == null) { "Unresolved tool boundary" }
        val token = UUID.randomUUID().toString()
        writeCheckpoint(run, TaskExecutionCheckpoint(TaskExecutionStage.TOOL, now(), token))
        token
    }

    /** Records only that dispatch returned; it is NOT an external success receipt. */
    fun completeTool(runId: String, token: String): Boolean = synchronized(lock) {
        val run = runs[runId] ?: return false
        if (run.executionCheckpoint?.pendingToolToken != token) return false
        writeCheckpoint(run, TaskExecutionCheckpoint(TaskExecutionStage.MODEL, now()))
        true
    }

    fun executionStage(runId: String, stage: TaskExecutionStage): Boolean = synchronized(lock) {
        val run = runs[runId] ?: return false
        if (run.state != TaskRunState.RUNNING) return false
        val previous = run.executionCheckpoint
        if (previous?.pendingToolToken != null) {
            require(stage == TaskExecutionStage.TOOL || stage == TaskExecutionStage.WAITING_USER) {
                "Cannot discard an unresolved tool boundary"
            }
        }
        if (previous?.stage == stage) return true
        writeCheckpoint(run, TaskExecutionCheckpoint(stage, now(), previous?.pendingToolToken))
        true
    }

    private fun writeCheckpoint(run: AutomationRun, checkpoint: TaskExecutionCheckpoint) {
        val next = LinkedHashMap(runs)
        next[run.id] = run.copy(executionCheckpoint = checkpoint)
        persist(tasks, next)
        runs = next
    }

    /** Only a RUNNING run can finish; a CANCELLED run wins over a late finish. */
    fun finish(
        runId: String,
        state: TaskRunState,
        diagnostic: String? = null,
        summary: String? = null,
    ): AutomationRun? = synchronized(lock) {
        require(state in TERMINAL_STATES) { "finish requires a terminal state, got $state" }
        val run = runs[runId] ?: return null
        if (run.state != TaskRunState.RUNNING) return null

        val unresolved = run.executionCheckpoint?.pendingToolToken != null
        require(state != TaskRunState.SUCCEEDED || !unresolved) { "Cannot succeed with unresolved tool dispatch" }
        val effectiveDiagnostic = if (unresolved)
            "External effects unknown; do not replay unresolved tool dispatch. ${diagnostic ?: run.diagnostic.orEmpty()}"
            else diagnostic ?: run.diagnostic
        val updated = run.copy(
            state = state,
            finishedAt = now(),
            diagnostic = effectiveDiagnostic?.take(2000),
            summary = summary?.take(8000) ?: run.summary,
        )
        val nextRuns = trimRuns(LinkedHashMap(runs).apply { put(runId, updated) })
        persist(tasks, nextRuns)
        runs = nextRuns
        return updated
    }

    fun cancel(workspaceId: String, runId: String): AutomationRun? = synchronized(lock) {
        val run = runs[runId] ?: return null
        if (run.workspaceId != workspaceId) return null
        if (run.state != TaskRunState.QUEUED && run.state != TaskRunState.RUNNING) return null

        val updated = run.copy(
            state = TaskRunState.CANCELLED,
            finishedAt = now(),
            diagnostic = run.diagnostic ?: "Cancelled",
        )
        val nextRuns = trimRuns(LinkedHashMap(runs).apply { put(runId, updated) })
        persist(tasks, nextRuns)
        runs = nextRuns
        return updated
    }

    /**
     * Startup recovery: a process death leaves RUNNING runs orphaned, while
     * QUEUED runs are still legitimate work for the executor and are kept.
     */
    fun recoverInterrupted(): Int = synchronized(lock) {
        val current = now()
        val nextRuns = LinkedHashMap(runs)
        var recovered = 0
        for ((id, run) in runs) {
            if (run.state != TaskRunState.RUNNING) continue
            nextRuns[id] = run.copy(
                state = TaskRunState.INTERRUPTED,
                finishedAt = current,
                diagnostic = if (run.executionCheckpoint?.pendingToolToken != null)
                    "Interrupted with unresolved tool dispatch; external effects unknown, do not replay"
                else run.diagnostic ?: "Interrupted by process restart; not automatically replayed",
            )
            recovered++
        }
        if (recovered == 0) return 0

        val trimmed = trimRuns(nextRuns)
        persist(tasks, trimmed)
        runs = trimmed
        return recovered
    }

    // -------------------------------------------------------------- scheduling

    private fun advanceAfterFire(task: AutomationTask, scheduledAt: Long, current: Long): AutomationTask {
        val trigger = task.trigger
        val next = when (trigger.kind) {
            TriggerKind.ONCE -> null
            TriggerKind.INTERVAL -> nextIntervalOccurrence(scheduledAt, trigger.intervalMinutes!!, current)
            TriggerKind.DAILY, TriggerKind.WEEKLY -> computeNext(trigger, current)
        }
        return task.copy(
            enabled = if (trigger.kind == TriggerKind.ONCE) false else task.enabled,
            nextFireAt = next,
            revision = task.revision + 1,
            updatedAt = current,
        )
    }

    private fun computeNext(trigger: TaskTrigger, from: Long): Long = when (trigger.kind) {
        TriggerKind.ONCE -> trigger.atMillis!!
        TriggerKind.INTERVAL -> safeAdd(from, safeMultiply(trigger.intervalMinutes!!, 60_000L))
        TriggerKind.DAILY -> nextLocalOccurrence(trigger, from, weekly = false)
        TriggerKind.WEEKLY -> nextLocalOccurrence(trigger, from, weekly = true)
    }

    private fun nextLocalOccurrence(trigger: TaskTrigger, from: Long, weekly: Boolean): Long {
        val zone = ZoneId.of(trigger.zoneId)
        val time = LocalTime.parse(trigger.localTime, TIME_FORMAT)
        val startDate = Instant.ofEpochMilli(from).atZone(zone).toLocalDate()
        for (offset in 0..8) {
            val date = startDate.plusDays(offset.toLong())
            if (weekly && date.dayOfWeek.value !in trigger.weekdays) continue
            val candidate = ZonedDateTime.of(date, time, zone).toInstant().toEpochMilli()
            if (candidate > from) return candidate
        }
        throw IllegalStateException("Unable to compute next occurrence for ${trigger.kind}")
    }

    /** Jumps from the last scheduled occurrence to the first one strictly after now. */
    private fun nextIntervalOccurrence(anchor: Long, intervalMinutes: Long, current: Long): Long {
        val step = safeMultiply(intervalMinutes, 60_000L)
        if (anchor > current) return anchor
        val delta = current - anchor
        val periods = if (delta < 0L) 1L else delta / step + 1L
        val candidate = safeAdd(anchor, safeMultiply(periods, step))
        return if (candidate > current) candidate else safeAdd(current, step)
    }

    private fun safeAdd(a: Long, b: Long): Long =
        if (b > 0L && a > Long.MAX_VALUE - b) Long.MAX_VALUE else a + b

    private fun safeMultiply(a: Long, b: Long): Long =
        if (a != 0L && b > Long.MAX_VALUE / a) Long.MAX_VALUE else a * b

    // ------------------------------------------------------------------ runs

    private fun newRun(task: AutomationTask, scheduledAt: Long, createdAt: Long): AutomationRun {
        val sessionId = when (task.destination) {
            TaskDestination.NEW_SESSION -> UUID.randomUUID().toString()
            TaskDestination.EXISTING_SESSION -> task.targetSessionId
        }
        return AutomationRun(
            id = UUID.randomUUID().toString(),
            taskId = task.id,
            workspaceId = task.workspaceId,
            taskRevision = task.revision,
            scheduledAt = scheduledAt,
            createdAt = createdAt,
            state = TaskRunState.QUEUED,
            sessionId = sessionId,
            taskSnapshot = task,
        )
    }

    private fun activeRunFor(task: AutomationTask): AutomationRun? = activeRunFor(task, runs.values)

    private fun activeRunFor(task: AutomationTask, candidates: Collection<AutomationRun>): AutomationRun? =
        candidates.firstOrNull {
            it.workspaceId == task.workspaceId &&
                it.taskId == task.id &&
                (it.state == TaskRunState.QUEUED || it.state == TaskRunState.RUNNING)
        }

    private fun trimRuns(source: Map<String, AutomationRun>): LinkedHashMap<String, AutomationRun> {
        val terminal = source.values
            .filter { it.state in TERMINAL_STATES }
            .sortedWith(compareBy({ it.finishedAt ?: it.createdAt }, { it.id }))
        val excess = terminal.size - MAX_TERMINAL_RUNS
        if (excess <= 0) return LinkedHashMap(source)

        val dropped = terminal.take(excess).mapTo(HashSet()) { it.id }
        val result = LinkedHashMap<String, AutomationRun>(source.size)
        for ((id, run) in source) {
            if (id !in dropped) result[id] = run
        }
        return result
    }

    // ------------------------------------------------------------- persistence

    private fun readStore(): Pair<LinkedHashMap<String, AutomationTask>, LinkedHashMap<String, AutomationRun>> {
        if (!file.exists()) {
            return LinkedHashMap<String, AutomationTask>() to LinkedHashMap()
        }
        if (!file.isFile) {
            throw IllegalStateException("Automation store is not a file: ${file.path}")
        }

        val text = try {
            file.readText(Charsets.UTF_8)
        } catch (e: Exception) {
            throw IllegalStateException("Unable to read automation store: ${file.path}", e)
        }
        if (text.isBlank()) {
            throw IllegalStateException("Automation store is empty: ${file.path}")
        }

        val root = try {
            JsonParser.parseString(text)
        } catch (e: Exception) {
            throw IllegalStateException("Malformed automation store JSON: ${file.path}", e)
        }
        if (!root.isJsonObject) {
            throw IllegalStateException("Automation store root is not an object")
        }
        val versionElement = root.asJsonObject.get("version")
            ?: throw IllegalStateException("Automation store has no version")
        if (!versionElement.isJsonPrimitive || !versionElement.asJsonPrimitive.isNumber) {
            throw IllegalStateException("Automation store version is not a number")
        }
        val version = try {
            versionElement.asInt
        } catch (e: Exception) {
            throw IllegalStateException("Automation store version is invalid", e)
        }
        if (version != STORE_VERSION) {
            throw IllegalStateException(
                "Unsupported automation store version $version (expected $STORE_VERSION)",
            )
        }

        val snapshot = try {
            gson.fromJson(text, StoreSnapshot::class.java)
        } catch (e: Exception) {
            throw IllegalStateException("Unable to parse automation store", e)
        } ?: throw IllegalStateException("Automation store is null")

        val storedTasks = snapshot.tasks
            ?: throw IllegalStateException("Automation store has no tasks array")
        val storedRuns = snapshot.runs
            ?: throw IllegalStateException("Automation store has no runs array")

        val taskMap = LinkedHashMap<String, AutomationTask>(storedTasks.size)
        try {
            for (task in storedTasks) {
                TaskValidation.validate(task)
                val key = taskKey(task.workspaceId, task.id)
                if (taskMap.put(key, task) != null) {
                    throw IllegalStateException("Duplicate task id ${task.id} in ${task.workspaceId}")
                }
            }
        } catch (e: Exception) {
            throw IllegalStateException("Invalid task in automation store: ${e.message}", e)
        }

        val runMap = LinkedHashMap<String, AutomationRun>(storedRuns.size)
        try {
            for (run in storedRuns) {
                if (run.id.isBlank() || run.taskId.isBlank() || run.workspaceId.isBlank()) {
                    throw IllegalStateException("Run has blank identity fields")
                }
                if (run.taskRevision < 1L) {
                    throw IllegalStateException("Run ${run.id} has invalid taskRevision")
                }
                val state: TaskRunState? = run.state
                if (state == null) {
                    throw IllegalStateException("Run ${run.id} has no state")
                }
                run.executionCheckpoint?.let { checkpoint ->
                    @Suppress("SENSELESS_COMPARISON")
                    require(checkpoint.stage != null && checkpoint.updatedAt > 0) { "Invalid execution checkpoint" }
                    require(checkpoint.pendingToolToken == null ||
                        (checkpoint.pendingToolToken.isNotBlank() && checkpoint.pendingToolToken.length <= 128 &&
                            checkpoint.stage in setOf(TaskExecutionStage.TOOL, TaskExecutionStage.WAITING_USER))) {
                        "Invalid pending tool boundary"
                    }
                }
                // Gson can leave a non-null Kotlin field null after tampering;
                // validate through a local so a bad document is still rejected.
                @Suppress("UNNECESSARY_SAFE_CALL", "USELESS_ELVIS")
                val snapshotTask = run.taskSnapshot ?: throw IllegalStateException(
                    "Run ${run.id} has no task snapshot",
                )
                TaskValidation.validate(snapshotTask)
                if (snapshotTask.id != run.taskId || snapshotTask.workspaceId != run.workspaceId) {
                    throw IllegalStateException("Run ${run.id} snapshot does not match its task")
                }
                if (runMap.put(run.id, run) != null) {
                    throw IllegalStateException("Duplicate run id ${run.id}")
                }
            }
        } catch (e: Exception) {
            throw IllegalStateException("Invalid run in automation store: ${e.message}", e)
        }

        return taskMap to trimRuns(runMap)
    }

    private fun persist(
        taskMap: Map<String, AutomationTask>,
        runMap: Map<String, AutomationRun>,
    ) {
        val snapshot = StoreSnapshot(
            version = STORE_VERSION,
            tasks = taskMap.values.toList(),
            runs = runMap.values.toList(),
        )
        val json = gson.toJson(snapshot)
        val target = file.absoluteFile
        val parent = target.parentFile
            ?: throw IOException("Automation store has no parent directory: ${target.path}")
        if (!parent.exists() && !parent.mkdirs()) {
            throw IOException("Unable to create automation store directory: ${parent.path}")
        }
        if (!parent.isDirectory) {
            throw IOException("Automation store parent is not a directory: ${parent.path}")
        }

        val temp = Files.createTempFile(parent.toPath(), "${target.name}.tmp-", ".part")
        try {
            FileOutputStream(temp.toFile()).use { out ->
                out.write(json.toByteArray(Charsets.UTF_8))
                out.flush()
                out.fd.sync()
            }
            try {
                Files.move(
                    temp,
                    target.toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING,
                )
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temp, target.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
            // Make the rename durable too. Directory fsync is best-effort on
            // platforms that do not allow opening directories.
            try {
                FileChannel.open(parent.toPath(), StandardOpenOption.READ).use { it.force(true) }
            } catch (_: Exception) {
                // best effort
            }
        } catch (e: Exception) {
            try {
                Files.deleteIfExists(temp)
            } catch (_: Exception) {
                // ignore cleanup failure; the original document is untouched
            }
            throw e
        }
    }

    // ------------------------------------------------------------------ keys

    private fun taskKey(workspaceId: String, id: String): String = "$workspaceId\u0000$id"

    private data class StoreSnapshot(
        val version: Int = 0,
        val tasks: List<AutomationTask>? = null,
        val runs: List<AutomationRun>? = null,
    )

    private companion object {
        const val STORE_VERSION = 1
        const val MAX_TERMINAL_RUNS = 1000
        val TERMINAL_STATES = setOf(
            TaskRunState.SUCCEEDED,
            TaskRunState.FAILED,
            TaskRunState.CANCELLED,
            TaskRunState.INTERRUPTED,
            TaskRunState.SKIPPED,
        )
        val TIME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
    }
}
