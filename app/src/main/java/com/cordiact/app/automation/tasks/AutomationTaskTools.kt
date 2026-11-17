package com.cordiact.app.automation.tasks

import com.cordiact.core.automation.AutomationRun
import com.cordiact.core.automation.AutomationService
import com.cordiact.core.automation.AutomationTask
import com.cordiact.core.automation.TaskDestination
import com.cordiact.core.automation.TaskTrigger
import com.cordiact.core.automation.TriggerKind
import com.cordiact.core.conversation.SessionManagerService
import com.cordiact.core.plugin.PluginSpec
import com.cordiact.core.plugin.PluginTarget
import com.cordiact.core.tool.ToolArguments
import com.cordiact.core.tool.ToolDefinition
import com.cordiact.core.tool.ToolDefinitionRegistry
import com.cordiact.core.tool.ToolDescriptor
import com.cordiact.core.tool.ToolExecutor
import com.cordiact.core.tool.ToolInputSchema
import com.cordiact.core.tool.ToolInvocationContext
import com.cordiact.core.tool.ToolParameter
import com.cordiact.core.tool.ToolResult
import com.cordiact.core.tool.ToolValue
import com.cordiact.core.tool.ToolValueType
import java.time.Instant
import java.time.LocalTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.lang.reflect.Modifier
import java.util.UUID

const val TASK_PLUGIN_ID = "automation-tasks"
const val TASK_CAPABILITY = "automation.tasks"
private const val TASK_TOOL_ID = "automation.tasks"
private const val TASK_TOOL_NAME = "automation_tasks"
private const val TASK_PLUGIN_VERSION = "0.1.0"
private const val DEFAULT_TIMEZONE = "Asia/Shanghai"
private const val DEFAULT_LOCAL_TIME = "09:00"
private const val DEFAULT_MAX_DURATION_MINUTES = 8
private const val DEFAULT_MAX_TOOL_ROUNDS = 12
private const val MAX_HISTORY_ITEMS = 50

/** Plugin declaration for the automation task tool surface. */
fun automationTasksPluginSpec(): PluginSpec = PluginSpec(
    id = TASK_PLUGIN_ID,
    version = TASK_PLUGIN_VERSION,
    target = PluginTarget.ANDROID_NATIVE,
    requestedCapabilities = setOf(TASK_CAPABILITY),
    dependencies = setOf("core.tool-manager"),
)

/**
 * Registers the single `automation_tasks` tool. The parent app owns the runtime lifecycle; this
 * file never constructs a scheduler and never stores workspace state of its own.
 */
fun registerAutomationTaskTools(
    registry: ToolDefinitionRegistry,
    runtime: () -> AutomationTaskRuntime,
    sessions: SessionManagerService,
) {
    registry.register(
        ToolDefinition(
            descriptor = automationTasksDescriptor(),
            executor = ToolExecutor { context, arguments ->
                executeAutomationTasks(context, arguments, runtime, sessions)
            },
        ),
    )
}

private fun automationTasksDescriptor(): ToolDescriptor = ToolDescriptor(
    id = TASK_TOOL_ID,
    name = TASK_TOOL_NAME,
    description = "Manage scheduled automation tasks for the current workspace. " +
        "Actions: list, create, edit, pause, resume, run_now, history, cancel, delete. " +
        "Triggers support once, interval, daily and weekly schedules. " +
        "DESTRUCTIVE: delete permanently removes the task and its run history; delete requires confirm=true.",
    inputSchema = ToolInputSchema(
        parameters = listOf(
            ToolParameter(
                name = "action",
                type = ToolValueType.STRING,
                required = true,
                description = "Operation to perform.",
                enumValues = listOf(
                    "list",
                    "create",
                    "edit",
                    "pause",
                    "resume",
                    "run_now",
                    "history",
                    "cancel",
                    "delete",
                ),
            ),
            ToolParameter(
                name = "id",
                type = ToolValueType.STRING,
                description = "Automation task id. Required for edit, pause, resume, run_now and delete; optional for history.",
            ),
            ToolParameter(
                name = "run_id",
                type = ToolValueType.STRING,
                description = "Automation run id, required for cancel.",
            ),
            ToolParameter(
                name = "name",
                type = ToolValueType.STRING,
                description = "Human-readable task name (create) or replacement name (edit).",
            ),
            ToolParameter(
                name = "prompt",
                type = ToolValueType.STRING,
                description = "Prompt sent to the agent when the task fires.",
            ),
            ToolParameter(
                name = "trigger_kind",
                type = ToolValueType.STRING,
                description = "Schedule kind: once, interval, daily or weekly.",
                enumValues = listOf("once", "interval", "daily", "weekly"),
            ),
            ToolParameter(
                name = "at",
                type = ToolValueType.STRING,
                description = "For a once trigger: ISO-8601 Instant, for example 2026-11-18T01:00:00Z.",
            ),
            ToolParameter(
                name = "interval_minutes",
                type = ToolValueType.INTEGER,
                description = "For an interval trigger: minutes between runs (minimum 15).",
                minimum = 15.0,
            ),
            ToolParameter(
                name = "local_time",
                type = ToolValueType.STRING,
                description = "For daily/weekly triggers: local wall-clock time in HH:mm form.",
            ),
            ToolParameter(
                name = "weekdays",
                type = ToolValueType.ARRAY,
                description = "For a weekly trigger: ISO weekdays, 1=Monday through 7=Sunday.",
                itemType = ToolValueType.INTEGER,
                minItems = 1,
                maxItems = 7,
            ),
            ToolParameter(
                name = "timezone",
                type = ToolValueType.STRING,
                description = "IANA timezone id for daily/weekly triggers. Default Asia/Shanghai.",
            ),
            ToolParameter(
                name = "destination",
                type = ToolValueType.STRING,
                description = "Where runs go: new for a fresh session, existing for target_session_id.",
                enumValues = listOf("new", "existing"),
            ),
            ToolParameter(
                name = "target_session_id",
                type = ToolValueType.STRING,
                description = "Existing, unarchived session id in this workspace. Required when destination=existing.",
            ),
            ToolParameter(
                name = "max_duration_minutes",
                type = ToolValueType.INTEGER,
                description = "Maximum run duration in minutes (1-8 on Android background).",
                minimum = 1.0,
                maximum = 8.0,
            ),
            ToolParameter(
                name = "max_tool_rounds",
                type = ToolValueType.INTEGER,
                description = "Maximum tool rounds per run (1-50).",
                minimum = 1.0,
                maximum = 50.0,
            ),
            ToolParameter(
                name = "expected_revision",
                type = ToolValueType.INTEGER,
                description = "Required for edit: revision returned by list/create. Guards against lost updates.",
                minimum = 1.0,
            ),
            ToolParameter(
                name = "confirm",
                type = ToolValueType.BOOLEAN,
                description = "Required for delete. Must be exactly true to permanently delete the task.",
            ),
        ),
        allowAdditional = false,
    ),
    ownerPluginId = TASK_PLUGIN_ID,
    defaultEnabled = true,
    requiredCapabilities = setOf(TASK_CAPABILITY),
    timeoutMs = 30_000L,
    concurrencySafe = false,
)

private fun executeAutomationTasks(
    context: ToolInvocationContext,
    arguments: ToolArguments,
    runtime: () -> AutomationTaskRuntime,
    sessions: SessionManagerService,
): ToolResult {
    val action = stringArg(arguments, "action")?.trim()?.lowercase()
        ?: return failure("invalid_argument", "action is required")

    delegatedFailure(context, sessions)?.let { return it }

    return try {
        when (action) {
            "list" -> listTasks(context.workspaceId, runtime().service)
            "create" -> createTask(context.workspaceId, arguments, runtime, sessions)
            "edit" -> editTask(context.workspaceId, arguments, runtime, sessions)
            "pause" -> pauseTask(context.workspaceId, arguments, runtime)
            "resume" -> resumeTask(context.workspaceId, arguments, runtime, sessions)
            "run_now" -> runTaskNow(context.workspaceId, arguments, runtime, sessions)
            "history" -> taskHistory(context.workspaceId, arguments, runtime().service)
            "cancel" -> cancelRun(context.workspaceId, arguments, runtime)
            "delete" -> deleteTask(context.workspaceId, arguments, runtime)
            else -> failure("invalid_argument", "Unsupported action '$action'")
        }
    } catch (failure: IllegalArgumentException) {
        failure("invalid_argument", safeMessage(failure))
    } catch (failure: Exception) {
        val message = safeMessage(failure)
        if (message.contains("revision", ignoreCase = true)) {
            failure("revision_conflict", message)
        } else {
            failure("task_operation_failed", message)
        }
    }
}

/** Automation is root-turn only; delegated sub-agents must not schedule host automation. */
private fun delegatedFailure(context: ToolInvocationContext, sessions: SessionManagerService): ToolResult? {
    val sessionId = context.sessionId ?: return null
    val depth = try {
        sessions.getSession(sessionId)?.depth ?: 0
    } catch (_: Exception) {
        0
    }
    return if (depth > 0) {
        failure("delegated_session_forbidden", "automation_tasks cannot be used from a delegated sub-agent session")
    } else {
        null
    }
}

private fun listTasks(workspaceId: String, service: AutomationService): ToolResult {
    val tasks = service.tasks(workspaceId)
    return success(
        action = "list",
        message = "Found ${tasks.size} automation task(s).",
        extra = linkedMapOf(
            "tasks" to ToolValue.ArrayValue(tasks.map(::taskValue)),
            "count" to ToolValue.NumberValue(tasks.size.toDouble()),
        ),
    )
}

private fun createTask(
    workspaceId: String,
    arguments: ToolArguments,
    runtime: () -> AutomationTaskRuntime,
    sessions: SessionManagerService,
): ToolResult {
    val name = requiredString(arguments, "name")
    val prompt = requiredString(arguments, "prompt")
    val trigger = parseTrigger(arguments, base = null)
    val destination = parseDestination(arguments, base = null)
    val targetSessionId = stringArg(arguments, "target_session_id")?.trim()?.takeIf { it.isNotEmpty() }

    if (destination == TaskDestination.EXISTING_SESSION) {
        validateTargetSession(workspaceId, targetSessionId, sessions)?.let {
            return failure("invalid_target_session", it)
        }
    } else if (targetSessionId != null) {
        return failure(
            "invalid_target_session",
            "target_session_id is only valid when destination=existing",
        )
    }

    val now = System.currentTimeMillis()
    val task = AutomationTask(
        id = "task-${UUID.randomUUID()}",
        workspaceId = workspaceId,
        name = name,
        prompt = prompt,
        trigger = trigger,
        destination = destination,
        targetSessionId = targetSessionId.takeIf { destination == TaskDestination.EXISTING_SESSION },
        enabled = true,
        nextFireAt = null,
        revision = 1L,
        maxDurationMinutes = intArg(arguments, "max_duration_minutes") ?: DEFAULT_MAX_DURATION_MINUTES,
        maxToolRounds = intArg(arguments, "max_tool_rounds") ?: DEFAULT_MAX_TOOL_ROUNDS,
        createdAt = now,
        updatedAt = now,
    )

    val saved = runtime().service.save(task, expectedRevision = null)
    runtime().kick()
    return success(
        action = "create",
        message = "Created automation task '${saved.name}' (${saved.id}).",
        extra = linkedMapOf("task" to taskValue(saved), "json" to ToolValue.StringValue(toolValueJson(taskValue(saved)))),
    )
}

private fun editTask(
    workspaceId: String,
    arguments: ToolArguments,
    runtime: () -> AutomationTaskRuntime,
    sessions: SessionManagerService,
): ToolResult {
    val service = runtime().service
    val id = requiredString(arguments, "id")
    val expectedRevision = longArg(arguments, "expected_revision")
        ?: throw IllegalArgumentException("expected_revision is required for edit")
    val current = requireTask(service, workspaceId, id)

    val destination = if (hasArgument(arguments, "destination")) {
        parseDestination(arguments, base = current.destination)
    } else {
        current.destination
    }
    val targetSessionId = if (hasArgument(arguments, "target_session_id")) {
        stringArg(arguments, "target_session_id")?.trim()?.takeIf { it.isNotEmpty() }
    } else {
        current.targetSessionId
    }

    if (destination == TaskDestination.EXISTING_SESSION) {
        validateTargetSession(workspaceId, targetSessionId, sessions)?.let {
            return failure("invalid_target_session", it)
        }
    }

    val updated = current.copy(
        name = if (hasArgument(arguments, "name")) requiredString(arguments, "name") else current.name,
        prompt = if (hasArgument(arguments, "prompt")) requiredString(arguments, "prompt") else current.prompt,
        trigger = mergeTrigger(arguments, current.trigger),
        destination = destination,
        targetSessionId = targetSessionId.takeIf { destination == TaskDestination.EXISTING_SESSION },
        maxDurationMinutes = intArg(arguments, "max_duration_minutes") ?: current.maxDurationMinutes,
        maxToolRounds = intArg(arguments, "max_tool_rounds") ?: current.maxToolRounds,
        updatedAt = System.currentTimeMillis(),
    )

    val saved = service.save(updated, expectedRevision = expectedRevision)
    runtime().kick()
    return success(
        action = "edit",
        message = "Updated automation task '${saved.name}' (${saved.id}) at revision ${saved.revision}.",
        extra = linkedMapOf("task" to taskValue(saved), "json" to ToolValue.StringValue(toolValueJson(taskValue(saved)))),
    )
}

private fun pauseTask(
    workspaceId: String,
    arguments: ToolArguments,
    runtime: () -> AutomationTaskRuntime,
): ToolResult {
    val service = runtime().service
    val id = requiredString(arguments, "id")
    requireTask(service, workspaceId, id)
    service.setEnabled(workspaceId, id, false)
    runtime().kick()
    val task = service.task(workspaceId, id)
    return success(
        action = "pause",
        message = "Paused automation task '$id'.",
        extra = linkedMapOf(
            "task" to (task?.let(::taskValue) ?: ToolValue.NullValue),
            "json" to ToolValue.StringValue(task?.let { toolValueJson(taskValue(it)) } ?: "null"),
        ),
    )
}

private fun resumeTask(
    workspaceId: String,
    arguments: ToolArguments,
    runtime: () -> AutomationTaskRuntime,
    sessions: SessionManagerService,
): ToolResult {
    val service = runtime().service
    val id = requiredString(arguments, "id")
    val task = requireTask(service, workspaceId, id)
    if (task.destination == TaskDestination.EXISTING_SESSION) {
        validateTargetSession(workspaceId, task.targetSessionId, sessions)?.let {
            return failure("invalid_target_session", "Cannot resume task '$id': $it")
        }
    }
    service.setEnabled(workspaceId, id, true)
    runtime().kick()
    val updated = service.task(workspaceId, id)
    return success(
        action = "resume",
        message = "Resumed automation task '$id'.",
        extra = linkedMapOf(
            "task" to (updated?.let(::taskValue) ?: ToolValue.NullValue),
            "json" to ToolValue.StringValue(updated?.let { toolValueJson(taskValue(it)) } ?: "null"),
        ),
    )
}

private fun runTaskNow(
    workspaceId: String,
    arguments: ToolArguments,
    runtime: () -> AutomationTaskRuntime,
    sessions: SessionManagerService,
): ToolResult {
    val service = runtime().service
    val id = requiredString(arguments, "id")
    val task = requireTask(service, workspaceId, id)
    if (task.destination == TaskDestination.EXISTING_SESSION) {
        validateTargetSession(workspaceId, task.targetSessionId, sessions)?.let {
            return failure("invalid_target_session", "Cannot run task '$id': $it")
        }
    }
    val run = service.runNow(workspaceId, id)
    runtime().kick()
    return success(
        action = "run_now",
        message = "Queued automation task '$id' for immediate execution.",
        extra = linkedMapOf("run" to runValue(run), "json" to ToolValue.StringValue(toolValueJson(runValue(run)))),
    )
}

private fun taskHistory(
    workspaceId: String,
    arguments: ToolArguments,
    service: AutomationService,
): ToolResult {
    val taskId = stringArg(arguments, "id")?.trim()?.takeIf { it.isNotEmpty() }
    if (taskId != null) {
        requireTask(service, workspaceId, taskId)
    }
    val runs = service.runs(workspaceId, taskId).take(MAX_HISTORY_ITEMS)
    return success(
        action = "history",
        message = "Found ${runs.size} run(s)" + (taskId?.let { " for task '$it'" } ?: "") + ".",
        extra = linkedMapOf(
            "runs" to ToolValue.ArrayValue(runs.map(::runValue)),
            "count" to ToolValue.NumberValue(runs.size.toDouble()),
        ),
    )
}

private fun cancelRun(
    workspaceId: String,
    arguments: ToolArguments,
    runtime: () -> AutomationTaskRuntime,
): ToolResult {
    val runId = requiredString(arguments, "run_id")
    val cancelled = runtime().cancel(workspaceId, runId)
        ?: return failure("run_not_found", "Automation run '$runId' was not found or is already finished")
    runtime().kick()
    return success(
        action = "cancel",
        message = "Cancelled automation run '$runId'.",
        extra = linkedMapOf(
            "run" to runValue(cancelled),
            "json" to ToolValue.StringValue(toolValueJson(runValue(cancelled))),
        ),
    )
}

private fun deleteTask(
    workspaceId: String,
    arguments: ToolArguments,
    runtime: () -> AutomationTaskRuntime,
): ToolResult {
    if (boolArg(arguments, "confirm") != true) {
        return failure(
            "confirmation_required",
            "delete permanently removes the automation task and its run history; pass confirm=true to proceed",
        )
    }
    val service = runtime().service
    val id = requiredString(arguments, "id")
    requireTask(service, workspaceId, id)
    service.delete(workspaceId, id)
    runtime().kick()
    return success(
        action = "delete",
        message = "Deleted automation task '$id'.",
        extra = linkedMapOf("id" to ToolValue.StringValue(id)),
    )
}

private fun requireTask(service: AutomationService, workspaceId: String, id: String): AutomationTask =
    service.task(workspaceId, id)
        ?: throw IllegalArgumentException("Automation task '$id' was not found in this workspace")

private fun validateTargetSession(
    workspaceId: String,
    targetSessionId: String?,
    sessions: SessionManagerService,
): String? {
    if (targetSessionId.isNullOrBlank()) {
        return "target_session_id is required when destination=existing"
    }
    val session = sessions.getSession(targetSessionId)
        ?: return "target session '$targetSessionId' was not found"
    if (session.workspaceId != workspaceId) {
        return "target session '$targetSessionId' does not belong to this workspace"
    }
    if (session.archivedAtMillis != null) {
        return "target session '$targetSessionId' is archived"
    }
    if (session.depth > 0) return "delegated sessions cannot be automation targets"
    return null
}

private fun parseDestination(arguments: ToolArguments, base: TaskDestination?): TaskDestination {
    val raw = stringArg(arguments, "destination")?.trim()?.uppercase() ?: return base ?: TaskDestination.NEW_SESSION
    return when (raw.replace('-', '_')) {
        "NEW", "NEW_SESSION" -> TaskDestination.NEW_SESSION
        "EXISTING", "EXISTING_SESSION" -> TaskDestination.EXISTING_SESSION
        else -> throw IllegalArgumentException("destination must be 'new' or 'existing'")
    }
}

private fun mergeTrigger(arguments: ToolArguments, base: TaskTrigger): TaskTrigger =
    parseTrigger(arguments, base)

private fun parseTrigger(arguments: ToolArguments, base: TaskTrigger?): TaskTrigger {
    val kindText = stringArg(arguments, "trigger_kind")?.trim()?.uppercase()
        ?: base?.kind?.name
        ?: if (hasArgument(arguments, "at")) "ONCE" else throw IllegalArgumentException("trigger_kind is required")
    val kind = when (kindText) {
        "ONCE" -> TriggerKind.ONCE
        "INTERVAL" -> TriggerKind.INTERVAL
        "DAILY" -> TriggerKind.DAILY
        "WEEKLY" -> TriggerKind.WEEKLY
        else -> throw IllegalArgumentException("trigger_kind must be one of once, interval, daily, weekly")
    }

    val atMillis = if (hasArgument(arguments, "at")) {
        parseInstantMillis(stringArg(arguments, "at"))
    } else {
        base?.atMillis
    }
    val intervalMinutes = if (hasArgument(arguments, "interval_minutes")) {
        longArg(arguments, "interval_minutes")
    } else {
        base?.intervalMinutes
    }
    val localTime = (stringArg(arguments, "local_time")?.trim() ?: base?.localTime ?: DEFAULT_LOCAL_TIME)
    val timezone = (stringArg(arguments, "timezone")?.trim() ?: base?.zoneId ?: DEFAULT_TIMEZONE)
    val weekdays = if (hasArgument(arguments, "weekdays")) {
        parseWeekdays(arguments)
    } else {
        base?.weekdays ?: setOf(1)
    }

    validateTrigger(kind, atMillis, intervalMinutes, localTime, weekdays, timezone)

    return TaskTrigger(
        kind = kind,
        atMillis = atMillis,
        intervalMinutes = intervalMinutes,
        localTime = localTime,
        weekdays = weekdays,
        zoneId = timezone,
    )
}

private fun validateTrigger(
    kind: TriggerKind,
    atMillis: Long?,
    intervalMinutes: Long?,
    localTime: String,
    weekdays: Set<Int>,
    timezone: String,
) {
    when (kind) {
        TriggerKind.ONCE -> {
            require(atMillis != null && atMillis > 0L) { "at (ISO-8601 Instant) is required for a once trigger" }
        }
        TriggerKind.INTERVAL -> {
            require(intervalMinutes != null && intervalMinutes >= 15L) {
                "interval_minutes must be at least 15 for an interval trigger"
            }
        }
        TriggerKind.DAILY, TriggerKind.WEEKLY -> {
            try {
                LocalTime.parse(localTime, TIME_FORMAT)
            } catch (_: Exception) {
                throw IllegalArgumentException("local_time must use HH:mm format")
            }
            try {
                ZoneId.of(timezone)
            } catch (_: Exception) {
                throw IllegalArgumentException("timezone must be a valid IANA timezone id")
            }
            if (kind == TriggerKind.WEEKLY) {
                require(weekdays.isNotEmpty() && weekdays.all { it in 1..7 }) {
                    "weekdays must contain ISO weekdays 1..7"
                }
            }
        }
    }
}

private fun parseInstantMillis(raw: String?): Long {
    val value = raw?.trim().takeUnless { it.isNullOrEmpty() }
        ?: throw IllegalArgumentException("at must be an ISO-8601 Instant")
    value.toLongOrNull()?.let { return it }
    return try {
        Instant.parse(value).toEpochMilli()
    } catch (_: Exception) {
        try {
            OffsetDateTime.parse(value).toInstant().toEpochMilli()
        } catch (_: Exception) {
            throw IllegalArgumentException("at must be an ISO-8601 Instant, for example 2026-11-18T01:00:00Z")
        }
    }
}

private fun parseWeekdays(arguments: ToolArguments): Set<Int> {
    val value = arguments.values["weekdays"] ?: return setOf(1)
    val values = when (value) {
        is ToolValue.ArrayValue -> value.values
        is ToolValue.NumberValue -> listOf(value)
        is ToolValue.StringValue -> value.value.split(',').map { ToolValue.StringValue(it.trim()) }
        else -> throw IllegalArgumentException("weekdays must be an array of ISO weekdays 1..7")
    }
    val weekdays = values.map { item ->
        when (item) {
            is ToolValue.NumberValue -> item.value.toInt()
            is ToolValue.StringValue -> item.value.toIntOrNull()
                ?: throw IllegalArgumentException("weekdays must contain integers 1..7")
            else -> throw IllegalArgumentException("weekdays must contain integers 1..7")
        }
    }.toSet()
    require(weekdays.isNotEmpty() && weekdays.all { it in 1..7 }) {
        "weekdays must contain ISO weekdays 1..7"
    }
    return weekdays
}

private fun taskValue(task: AutomationTask): ToolValue.ObjectValue = ToolValue.ObjectValue(
    linkedMapOf(
        "id" to ToolValue.StringValue(task.id),
        "workspace_id" to ToolValue.StringValue(task.workspaceId),
        "name" to ToolValue.StringValue(task.name),
        "prompt" to ToolValue.StringValue(task.prompt),
        "trigger" to triggerValue(task.trigger),
        "destination" to ToolValue.StringValue(task.destination.name),
        "target_session_id" to nullableString(task.targetSessionId),
        "enabled" to ToolValue.BooleanValue(task.enabled),
        "next_fire_at" to nullableLong(task.nextFireAt),
        "revision" to ToolValue.NumberValue(task.revision.toDouble()),
        "max_duration_minutes" to ToolValue.NumberValue(task.maxDurationMinutes.toDouble()),
        "max_tool_rounds" to ToolValue.NumberValue(task.maxToolRounds.toDouble()),
        "created_at" to ToolValue.NumberValue(task.createdAt.toDouble()),
        "updated_at" to ToolValue.NumberValue(task.updatedAt.toDouble()),
    ),
)

private fun triggerValue(trigger: TaskTrigger): ToolValue.ObjectValue = ToolValue.ObjectValue(
    linkedMapOf(
        "kind" to ToolValue.StringValue(trigger.kind.name),
        "at_millis" to nullableLong(trigger.atMillis),
        "interval_minutes" to nullableLong(trigger.intervalMinutes),
        "local_time" to ToolValue.StringValue(trigger.localTime),
        "weekdays" to ToolValue.ArrayValue(
            trigger.weekdays.sorted().map { ToolValue.NumberValue(it.toDouble()) },
        ),
        "timezone" to ToolValue.StringValue(trigger.zoneId),
    ),
)

private fun runValue(run: AutomationRun): ToolValue = toToolValue(run)

private fun nullableString(value: String?): ToolValue =
    value?.let(ToolValue::StringValue) ?: ToolValue.NullValue

private fun nullableLong(value: Long?): ToolValue =
    value?.let { ToolValue.NumberValue(it.toDouble()) } ?: ToolValue.NullValue

private fun hasArgument(arguments: ToolArguments, name: String): Boolean = arguments.values.containsKey(name)

private fun stringArg(arguments: ToolArguments, name: String): String? = when (val value = arguments.values[name]) {
    is ToolValue.StringValue -> value.value
    ToolValue.NullValue -> null
    else -> null
}

private fun requiredString(arguments: ToolArguments, name: String): String {
    val value = stringArg(arguments, name)?.trim()
    require(!value.isNullOrEmpty()) { "$name is required" }
    return value
}

private fun intArg(arguments: ToolArguments, name: String): Int? = when (val value = arguments.values[name]) {
    null, ToolValue.NullValue -> null
    is ToolValue.NumberValue -> value.value.toInt()
    is ToolValue.StringValue -> value.value.toIntOrNull()
        ?: throw IllegalArgumentException("$name must be an integer")
    else -> throw IllegalArgumentException("$name must be an integer")
}

private fun longArg(arguments: ToolArguments, name: String): Long? = when (val value = arguments.values[name]) {
    null, ToolValue.NullValue -> null
    is ToolValue.NumberValue -> value.value.toLong()
    is ToolValue.StringValue -> value.value.toLongOrNull()
        ?: throw IllegalArgumentException("$name must be a long")
    else -> throw IllegalArgumentException("$name must be a long")
}

private fun boolArg(arguments: ToolArguments, name: String): Boolean? = when (val value = arguments.values[name]) {
    null, ToolValue.NullValue -> null
    is ToolValue.BooleanValue -> value.value
    is ToolValue.StringValue -> value.value.toBooleanStrictOrNull()
    else -> null
}

private fun success(
    action: String,
    message: String,
    extra: Map<String, ToolValue> = emptyMap(),
): ToolResult {
    val values = linkedMapOf<String, ToolValue>(
        "ok" to ToolValue.BooleanValue(true),
        "action" to ToolValue.StringValue(action),
        "message" to ToolValue.StringValue(message),
    )
    values.putAll(extra)
    return ToolResult(values = values)
}

private fun failure(code: String, message: String): ToolResult = ToolResult(
    values = linkedMapOf(
        "ok" to ToolValue.BooleanValue(false),
        "error" to ToolValue.ObjectValue(
            linkedMapOf(
                "code" to ToolValue.StringValue(code),
                "message" to ToolValue.StringValue(message),
            ),
        ),
    ),
    metadata = mapOf("error" to ToolValue.StringValue(code)),
)

private fun safeMessage(failure: Throwable): String {
    val raw = failure.message?.takeIf { it.isNotBlank() } ?: failure.javaClass.simpleName
    return raw
        .replace(Regex("(?i)(password|token|secret|api[_-]?key)\\s*[=:]\\s*\\S+"), "$1=***")
        .replace(Regex("[\\r\\n]+"), " ")
        .take(500)
}

private val TIME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

/** Converts arbitrary runtime values (including AutomationRun) into the tool value tree. */
private fun toToolValue(value: Any?, depth: Int = 0): ToolValue = when {
    value == null -> ToolValue.NullValue
    value is ToolValue -> value
    value is String -> ToolValue.StringValue(value)
    value is Boolean -> ToolValue.BooleanValue(value)
    value is Number -> ToolValue.NumberValue(value.toDouble())
    value is Enum<*> -> ToolValue.StringValue(value.name)
    value is Map<*, *> && depth < MAX_VALUE_DEPTH -> ToolValue.ObjectValue(
        value.entries.associateTo(linkedMapOf()) { (key, item) ->
            key.toString() to toToolValue(item, depth + 1)
        },
    )
    value is Iterable<*> && depth < MAX_VALUE_DEPTH -> ToolValue.ArrayValue(
        value.map { toToolValue(it, depth + 1) },
    )
    value is Array<*> && depth < MAX_VALUE_DEPTH -> ToolValue.ArrayValue(
        value.map { toToolValue(it, depth + 1) },
    )
    depth < MAX_VALUE_DEPTH -> reflectiveObject(value, depth)
    else -> ToolValue.StringValue(value.toString())
}

private fun reflectiveObject(value: Any, depth: Int): ToolValue.ObjectValue {
    val values = linkedMapOf<String, ToolValue>()
    value.javaClass.declaredFields
        .filterNot { Modifier.isStatic(it.modifiers) || it.isSynthetic }
        .forEach { field ->
            try {
                field.isAccessible = true
                values[field.name] = toToolValue(field.get(value), depth + 1)
            } catch (_: Exception) {
                // Host implementation detail; skip values the JVM refuses to expose.
            }
        }
    return ToolValue.ObjectValue(values)
}

private const val MAX_VALUE_DEPTH = 5

/** Small JSON serializer used for the human/model-readable `json` mirror; no secrets are added. */
private fun toolValueJson(value: ToolValue): String = buildString { appendToolValue(this, value) }

private fun appendToolValue(out: StringBuilder, value: ToolValue) {
    when (value) {
        ToolValue.NullValue -> out.append("null")
        is ToolValue.StringValue -> out.append(jsonQuote(value.value))
        is ToolValue.NumberValue -> {
            val number = value.value
            if (number.isFinite() && number == number.toLong().toDouble()) {
                out.append(number.toLong())
            } else {
                out.append(number)
            }
        }
        is ToolValue.BooleanValue -> out.append(value.value)
        is ToolValue.ArrayValue -> {
            out.append('[')
            value.values.forEachIndexed { index, item ->
                if (index > 0) out.append(',')
                appendToolValue(out, item)
            }
            out.append(']')
        }
        is ToolValue.ObjectValue -> {
            out.append('{')
            var first = true
            value.values.forEach { (key, item) ->
                if (!first) out.append(',')
                first = false
                out.append(jsonQuote(key)).append(':')
                appendToolValue(out, item)
            }
            out.append('}')
        }
    }
}

private fun jsonQuote(value: String): String = buildString {
    append('"')
    value.forEach { character ->
        when (character) {
            '"' -> append("\\\"")
            '\\' -> append("\\\\")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            else -> if (character.code < 0x20) append("\\u%04x".format(character.code)) else append(character)
        }
    }
    append('"')
}
