package com.cordiact.core.automation

import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException

/** How a task decides when it should fire. */
enum class TriggerKind { ONCE, INTERVAL, DAILY, WEEKLY }

/**
 * A task schedule. All wall-clock fields use [zoneId]; [atMillis] and the
 * service's persisted fire times are epoch milliseconds.
 *
 * [weekdays] uses ISO numbering: Monday = 1 ... Sunday = 7.
 */
data class TaskTrigger(
    val kind: TriggerKind,
    val atMillis: Long? = null,
    val intervalMinutes: Long? = null,
    val localTime: String = "09:00",
    val weekdays: Set<Int> = setOf(1),
    val zoneId: String = "Asia/Shanghai",
)

/** Where a run sends its prompt. */
enum class TaskDestination { NEW_SESSION, EXISTING_SESSION }

/** Lifecycle of one scheduled execution. */
enum class TaskRunState { QUEUED, RUNNING, SUCCEEDED, FAILED, CANCELLED, INTERRUPTED, SKIPPED }

/** Last durable boundary, not a resumable program counter or proof of external success. */
enum class TaskExecutionStage { PREPARING, MODEL, TOOL, WAITING_USER }
data class TaskExecutionCheckpoint(
    val stage: TaskExecutionStage,
    val updatedAt: Long,
    val pendingToolToken: String? = null,
)

/** A durable automation definition. */
data class AutomationTask(
    val id: String,
    val workspaceId: String,
    val name: String,
    val prompt: String,
    val trigger: TaskTrigger,
    val destination: TaskDestination = TaskDestination.NEW_SESSION,
    val targetSessionId: String? = null,
    val enabled: Boolean = true,
    val nextFireAt: Long? = null,
    val revision: Long = 1,
    val maxDurationMinutes: Int = 10,
    val maxToolRounds: Int = 12,
    val createdAt: Long = 0,
    val updatedAt: Long = 0,
)

/**
 * One execution of an [AutomationTask]. [taskSnapshot] is intentionally a full
 * copy: editing or deleting the task later must not rewrite history.
 */
data class AutomationRun(
    val id: String,
    val taskId: String,
    val workspaceId: String,
    val taskRevision: Long,
    val scheduledAt: Long,
    val createdAt: Long,
    val state: TaskRunState = TaskRunState.QUEUED,
    val sessionId: String? = null,
    val startedAt: Long? = null,
    val finishedAt: Long? = null,
    val diagnostic: String? = null,
    val summary: String? = null,
    val taskSnapshot: AutomationTask,
    val executionCheckpoint: TaskExecutionCheckpoint? = null,
) {
    val externalResultUnknown: Boolean
        get() = when (state) {
            TaskRunState.INTERRUPTED, TaskRunState.FAILED, TaskRunState.CANCELLED -> executionCheckpoint?.pendingToolToken != null
            else -> false
        }
}

class UnconfirmedAutomationResultException : IllegalStateException("Previous tool result is unknown; confirm in the app before starting a new run")

/** Thrown when a compare-and-set edit is based on a stale task revision. */
class TaskRevisionConflictException(
    val taskId: String,
    val expectedRevision: Long,
    val actualRevision: Long,
) : IllegalStateException(
    "Task $taskId revision conflict: expected $expectedRevision but was $actualRevision",
)

/**
 * Validation shared by the service on both save and load. Kept out of the data
 * class constructors so Gson can materialise old documents before they are
 * checked (a malformed document must be rejected, not half-applied).
 */
object TaskValidation {

    private val TIME_PATTERN: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

    fun validate(task: AutomationTask) {
        require(task.id.isNotBlank()) { "Task id must not be blank" }
        require(task.workspaceId.isNotBlank()) { "Task workspaceId must not be blank" }
        require(task.name.isNotBlank()) { "Task name must not be blank" }
        require(task.prompt.isNotBlank()) { "Task prompt must not be blank" }
        require(task.revision >= 1) { "Task revision must be >= 1" }
        require(task.maxDurationMinutes in 1..60) {
            "maxDurationMinutes must be between 1 and 60"
        }
        require(task.maxToolRounds in 1..50) { "maxToolRounds must be between 1 and 50" }
        if (task.destination == TaskDestination.EXISTING_SESSION) {
            require(!task.targetSessionId.isNullOrBlank()) {
                "EXISTING_SESSION tasks require a targetSessionId"
            }
        }
        validate(task.trigger)
    }

    fun validate(trigger: TaskTrigger) {
        require(runCatching { ZoneId.of(trigger.zoneId) }.isSuccess) {
            "Invalid trigger zoneId: ${trigger.zoneId}"
        }
        parseLocalTime(trigger.localTime)
        require(trigger.weekdays.all { it in 1..7 }) {
            "weekdays must be ISO numbers 1..7"
        }
        when (trigger.kind) {
            TriggerKind.ONCE -> {
                requireNotNull(trigger.atMillis) { "ONCE triggers require atMillis" }
                require(trigger.atMillis > 0L) { "ONCE atMillis must be positive" }
            }

            TriggerKind.INTERVAL -> {
                val minutes = requireNotNull(trigger.intervalMinutes) {
                    "INTERVAL triggers require intervalMinutes"
                }
                require(minutes >= 15L) { "INTERVAL intervalMinutes must be >= 15" }
            }

            TriggerKind.DAILY -> Unit

            TriggerKind.WEEKLY -> {
                require(trigger.weekdays.isNotEmpty()) {
                    "WEEKLY triggers require at least one weekday"
                }
            }
        }
    }

    private fun parseLocalTime(raw: String): LocalTime = try {
        LocalTime.parse(raw, TIME_PATTERN)
    } catch (e: DateTimeParseException) {
        throw IllegalArgumentException("Invalid localTime (expected HH:mm): $raw", e)
    }
}
