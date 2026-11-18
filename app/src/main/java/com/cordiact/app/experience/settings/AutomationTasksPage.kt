package com.cordiact.app.experience.settings

import com.cordiact.app.service.ExecutionPresenceHost
import com.cordiact.core.jobs.ForegroundExecutionState
import com.cordiact.core.automation.UnconfirmedAutomationResultException
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.cordiact.app.CordiActApplication
import com.cordiact.core.automation.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.*
import java.time.format.DateTimeFormatter

/** Management UI uses the same persistent service as model tools, scoped to the selected workspace. */
@Composable
internal fun AutomationTasksPage(workspaceId: String) {
    val app = LocalContext.current.applicationContext as CordiActApplication
    val runtimeResult = remember(app) { runCatching { app.automationTasks } }
    val runtime = runtimeResult.getOrNull()
    if (runtime == null) {
        Text("自动化存储无法打开，原文件已保留：${runtimeResult.exceptionOrNull()?.message}")
        return
    }
    var tasks by remember(workspaceId) { mutableStateOf(emptyList<AutomationTask>()) }
    var runs by remember(workspaceId) { mutableStateOf(emptyList<AutomationRun>()) }
    var error by remember { mutableStateOf<String?>(null) }
    var editing by remember(workspaceId) { mutableStateOf<AutomationTask?>(null) }
    var creating by remember(workspaceId) { mutableStateOf(false) }
    var retryAfterUnknown by remember(workspaceId) { mutableStateOf<Pair<AutomationTask, Set<String>>?>(null) }
    val conditions by ExecutionPresenceHost.conditions.collectAsState()
    var deleting by remember { mutableStateOf<AutomationTask?>(null) }
    val scope = rememberCoroutineScope()
    fun refresh() { tasks = runtime.service.tasks(workspaceId); runs = runtime.service.runs(workspaceId) }
    fun change(action: () -> Unit) {
        scope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { action(); runtime.kick() } }
            result.onSuccess { error = null; refresh() }.onFailure { error = if (it is UnconfirmedAutomationResultException) "发现未确认的工具结果，请重新检查后确认。" else it.message ?: "操作失败" }
        }
    }
    LaunchedEffect(workspaceId) {
        while (true) {
            val loaded = withContext(Dispatchers.IO) { runCatching { runtime.service.tasks(workspaceId) to runtime.service.runs(workspaceId) } }
            loaded.onSuccess { tasks = it.first; runs = it.second }.onFailure { error = it.message }
            delay(2000)
        }
    }
    Text("自动化任务", style = MaterialTheme.typography.titleLarge)
    Text("按工作区隔离 · 系统尽力准时，省电状态可能延后 · 任务权限沿用现有审批，不自动提权。", style = MaterialTheme.typography.bodySmall)
    if (!runtime.enabled(workspaceId)) Text("插件或工作区能力已停用；恢复后才会执行排队任务。", color = MaterialTheme.colorScheme.error)
    error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    // These are device-wide observations, not a verdict about this run or the model endpoint.
    if (runs.any { it.state == TaskRunState.RUNNING } && conditions.sampledAtMillis > 0) {
        val notices = buildList {
            if (conditions.networkValidated == false) add("系统暂未验证互联网连接")
            if (conditions.deviceLocked == true) add("设备锁定")
            if (conditions.powerSave == true) add("已开启省电模式")
            if (conditions.backgroundRestricted == true) add("系统限制应用后台运行")
            if (!conditions.notificationsAllowed) add("任务通知不可用，请在运行收件箱查看进度")
            when (conditions.foreground) {
                ForegroundExecutionState.REJECTED -> add("系统拒绝启动前台服务")
                ForegroundExecutionState.START_TIMEOUT, ForegroundExecutionState.TIMED_OUT -> add("前台服务启动或运行超时")
                else -> Unit
            }
        }
        if (notices.isNotEmpty()) Text("设备状态：${notices.joinToString(" · ")}", style = MaterialTheme.typography.bodySmall)
    }
    FilledTonalButton(onClick = { creating = true }) { Text("新建任务") }
    if (tasks.isEmpty()) Text("暂无任务。可设置一次运行、固定间隔、每日或每周。")
    tasks.forEach { task ->
        OutlinedCard(shape = RoundedCornerShape(20.dp), border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant), modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(task.name, style = MaterialTheme.typography.titleMedium)
                Text("${if (task.enabled) "已启用" else "已暂停"} · ${task.trigger.kind} · ${task.trigger.zoneId}", style = MaterialTheme.typography.labelMedium)
                Text("下次计划：${formatTaskTime(task.nextFireAt)}", style = MaterialTheme.typography.bodySmall)
                Text(task.prompt.take(180), style = MaterialTheme.typography.bodySmall)
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(onClick = { editing = task }) { Text("编辑") }
                    TextButton(onClick = { change { runtime.service.setEnabled(workspaceId, task.id, !task.enabled) } }) { Text(if (task.enabled) "暂停" else "恢复") }
                    TextButton(enabled = runtime.enabled(workspaceId), onClick = {
                        scope.launch {
                            val unknownRuns = withContext(Dispatchers.IO) {
                                runCatching { runtime.service.runs(workspaceId, task.id).filter { it.externalResultUnknown }.map { it.id }.toSet() }
                            }
                            unknownRuns.onSuccess {
                                if (it.isNotEmpty()) retryAfterUnknown = task to it
                                else change { runtime.service.runNow(workspaceId, task.id) }
                            }.onFailure { error = "无法检查历史运行，未启动新任务。" }
                        }
                    }) { Text("立即运行") }
                    TextButton(onClick = { deleting = task }) { Text("删除") }
                }
            }
        }
    }
    Text("运行收件箱", style = MaterialTheme.typography.titleMedium)
    if (runs.isEmpty()) Text("还没有运行记录。消息投递与执行成功会分开记录。", style = MaterialTheme.typography.bodySmall)
    runs.take(50).forEach { run ->
        OutlinedCard(shape = RoundedCornerShape(20.dp), border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant), modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("${run.taskSnapshot.name} · ${runStateLabel(run.state)}", style = MaterialTheme.typography.titleSmall)
                Text("计划 ${formatTaskTime(run.scheduledAt)} · 开始 ${formatTaskTime(run.startedAt)}", style = MaterialTheme.typography.bodySmall)
                if (run.externalResultUnknown) {
                    Text("工具可能已经执行，结果未知。本次运行不会自动重放；请先核对目标端，再决定是否另开一次运行。", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                } else if (run.state == TaskRunState.INTERRUPTED) {
                    Text("本次运行已中断，不会自动续跑。请查看关联会话和已完成的工具步骤。", style = MaterialTheme.typography.bodySmall)
                }
                run.diagnostic?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                run.summary?.let { Text(it.take(1200), style = MaterialTheme.typography.bodySmall) }
                run.sessionId?.let { Text("会话：$it", style = MaterialTheme.typography.labelSmall) }
                if (run.state == TaskRunState.QUEUED || run.state == TaskRunState.RUNNING) TextButton(onClick = { change { runtime.cancel(workspaceId, run.id) } }) { Text("取消本次运行") }
            }
        }
    }
    retryAfterUnknown?.let { (task, runIds) ->
        AlertDialog(
            onDismissRequest = { retryAfterUnknown = null },
            title = { Text("确认开启一次新运行") },
            text = { Text("历史运行有未确认的工具结果。新运行可能重复执行外部操作，请先核对目标端。") },
            confirmButton = { TextButton(onClick = {
                retryAfterUnknown = null
                change { runtime.service.runNow(workspaceId, task.id, acknowledgedUnknownRunIds = runIds) }
            }) { Text("已核对，开始新运行") } },
            dismissButton = { TextButton(onClick = { retryAfterUnknown = null }) { Text("取消") } },
        )
    }
    if (creating || editing != null) TaskEditor(editing, workspaceId,
        onDismiss = { creating = false; editing = null },
        onSave = { task ->
            val validTarget = if (task.destination == TaskDestination.EXISTING_SESSION) app.sessions.getSession(task.targetSessionId.orEmpty()) else null
            require(task.destination != TaskDestination.EXISTING_SESSION || validTarget?.let { it.workspaceId == workspaceId && it.archivedAtMillis == null && it.depth == 0 } == true) { "目标会话不存在、已归档或不属于当前工作区" }
            val revision = editing?.revision
            change { runtime.service.save(task, revision) }; creating = false; editing = null
        })
    deleting?.let { task ->
        AlertDialog(onDismissRequest = { deleting = null }, title = { Text("删除 ${task.name}？") },
            text = { Text("删除后不再触发；已执行的外部操作不会撤销。正在运行的任务须先取消。") },
            confirmButton = { TextButton(onClick = { change { runtime.service.delete(workspaceId, task.id) }; deleting = null }) { Text("删除任务") } },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("保留") } })
    }
}

@Composable
private fun TaskEditor(original: AutomationTask?, workspaceId: String, onDismiss: () -> Unit, onSave: (AutomationTask) -> Unit) {
    var name by remember { mutableStateOf(original?.name.orEmpty()) }
    var prompt by remember { mutableStateOf(original?.prompt.orEmpty()) }
    var kind by remember { mutableStateOf(original?.trigger?.kind ?: TriggerKind.DAILY) }
    var time by remember { mutableStateOf(original?.trigger?.localTime ?: "09:00") }
    var date by remember { mutableStateOf(original?.trigger?.atMillis?.let { Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()).toLocalDateTime().toString() } ?: LocalDateTime.now().plusHours(1).withSecond(0).withNano(0).toString()) }
    var interval by remember { mutableStateOf((original?.trigger?.intervalMinutes ?: 60).toString()) }
    var zone by remember { mutableStateOf(original?.trigger?.zoneId ?: ZoneId.systemDefault().id) }
    var days by remember { mutableStateOf(original?.trigger?.weekdays?.sorted()?.joinToString(",") ?: "1") }
    var target by remember { mutableStateOf(original?.targetSessionId.orEmpty()) }
    var duration by remember { mutableStateOf((original?.maxDurationMinutes ?: 8).coerceAtMost(8).toString()) }
    var rounds by remember { mutableStateOf((original?.maxToolRounds ?: 12).toString()) }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(if (original == null) "新建自动化任务" else "编辑任务") },
        text = {
            Column(Modifier.heightIn(max = 500.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(name, { name = it }, label = { Text("名称") }, singleLine = true)
                OutlinedTextField(prompt, { prompt = it }, label = { Text("要完成的工作") }, minLines = 2, maxLines = 3)
                Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) { TriggerKind.entries.forEach { value ->
                    FilterChip(selected = kind == value, onClick = { kind = value }, label = { Text(when (value) { TriggerKind.ONCE -> "一次"; TriggerKind.INTERVAL -> "间隔"; TriggerKind.DAILY -> "每日"; TriggerKind.WEEKLY -> "每周" }) })
                } }
                if (kind == TriggerKind.ONCE) OutlinedTextField(date, { date = it }, label = { Text("本地日期时间：2026-11-18T09:00") }, singleLine = true)
                if (kind == TriggerKind.INTERVAL) OutlinedTextField(interval, { interval = it }, label = { Text("间隔分钟，最少 15") }, singleLine = true)
                if (kind == TriggerKind.DAILY || kind == TriggerKind.WEEKLY) OutlinedTextField(time, { time = it }, label = { Text("时间 HH:mm") }, singleLine = true)
                if (kind == TriggerKind.WEEKLY) OutlinedTextField(days, { days = it }, label = { Text("星期 1–7，逗号分隔") }, singleLine = true)
                OutlinedTextField(zone, { zone = it }, label = { Text("时区，如 Asia/Shanghai") }, singleLine = true)
                OutlinedTextField(target, { target = it }, label = { Text("目标会话 ID，留空则每次新建") }, singleLine = true)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(duration, { duration = it }, label = { Text("最长分钟，后台≤8") }, modifier = Modifier.weight(1f), singleLine = true)
                    OutlinedTextField(rounds, { rounds = it }, label = { Text("工具轮数") }, modifier = Modifier.weight(1f), singleLine = true)
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = { TextButton(onClick = {
            runCatching {
                val trigger = TaskTrigger(kind,
                    atMillis = if (kind == TriggerKind.ONCE) LocalDateTime.parse(date).atZone(ZoneId.of(zone)).toInstant().toEpochMilli() else null,
                    intervalMinutes = if (kind == TriggerKind.INTERVAL) interval.toLong() else null,
                    localTime = time, weekdays = if (kind == TriggerKind.WEEKLY) days.split(',').map { it.trim().toInt() }.toSet() else setOf(1), zoneId = zone)
                val value = (original ?: AutomationTask("", workspaceId, name, prompt, trigger)).copy(
                    name = name, prompt = prompt, trigger = trigger,
                    destination = if (target.isBlank()) TaskDestination.NEW_SESSION else TaskDestination.EXISTING_SESSION,
                    targetSessionId = target.takeIf { it.isNotBlank() }, maxDurationMinutes = duration.toInt(), maxToolRounds = rounds.toInt())
                onSave(value)
            }.onFailure { error = it.message ?: "输入无效" }
        }) { Text("保存") } }, dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } })
}

private fun formatTaskTime(millis: Long?): String = millis?.let { Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("MM-dd HH:mm")) } ?: "—"
private fun runStateLabel(state: TaskRunState): String = when (state) {
    TaskRunState.QUEUED -> "排队"; TaskRunState.RUNNING -> "运行中"; TaskRunState.SUCCEEDED -> "成功"
    TaskRunState.FAILED -> "失败"; TaskRunState.CANCELLED -> "已取消"; TaskRunState.INTERRUPTED -> "已中断"; TaskRunState.SKIPPED -> "已跳过"
}
