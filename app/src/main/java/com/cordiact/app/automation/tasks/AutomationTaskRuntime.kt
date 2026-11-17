package com.cordiact.app.automation.tasks

import android.content.Context
import androidx.work.*
import com.cordiact.app.CordiActApplication
import com.cordiact.core.automation.*
import com.cordiact.core.conversation.*
import com.cordiact.core.plugin.PluginState
import com.cordiact.core.tool.ToolInvocationContext
import com.cordiact.core.toolloop.*
import kotlinx.coroutines.*
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/** Root task execution, never impersonates a delegated parent session. */
class AutomationTaskRuntime(private val app: CordiActApplication) {
    val service = AutomationService(File(app.filesDir, "automation/tasks-v1.json"))
    private val draining = kotlinx.coroutines.sync.Mutex()
    private val active = ConcurrentHashMap<String, String>()
    private val sessionLocks = ConcurrentHashMap<String, Any>()
    init { service.recoverInterrupted() }

    fun kick() { AutomationTaskScheduling.kick(app) }
    fun cancel(workspaceId: String, runId: String): AutomationRun? {
        val result = service.cancel(workspaceId, runId)
        active[runId]?.let(app.turnService::cancelTurn)
        return result
    }
    fun enabled(workspaceId: String): Boolean = app.pluginManagerService().inventory(workspaceId).any {
        it.spec.id == TASK_PLUGIN_ID && it.state == PluginState.ENABLED && it.workspaceEnabled != false
    }

    suspend fun drain() = withContext(Dispatchers.IO) {
        if (!draining.tryLock()) return@withContext
        try {
        val workspaces = app.workspaceManager.list(false)
        service.enqueueDue { id -> workspaces.any { it.id == id } && enabled(id) }
        val deadline = System.currentTimeMillis() + TimeUnit.MINUTES.toMillis(8)
        var moreQueued = false
        runLoop@ for (workspace in workspaces) {
            if (!enabled(workspace.id)) continue
            for (candidate in service.runs(workspace.id).filter { it.state == TaskRunState.QUEUED }.sortedBy { it.createdAt }) {
                if (System.currentTimeMillis() >= deadline) { moreQueued = true; break@runLoop }
                ensureActive()
                val run = service.claim(candidate.id) ?: continue
                val sessionId = requireNotNull(run.sessionId)
                runInterruptible {
                    synchronized(sessionLocks.getOrPut(sessionId) { Any() }) { execute(run) }
                }
            }
        }
        if (moreQueued) AutomationTaskScheduling.kick(app)
        } finally { draining.unlock() }
    }

    private fun execute(run: AutomationRun) {
        val task = run.taskSnapshot
        val sessionId = requireNotNull(run.sessionId)
        try {
            check(service.runs(run.workspaceId, run.taskId).any { it.id == run.id && it.state == TaskRunState.RUNNING }) { "运行已取消" }
            check(enabled(run.workspaceId)) { "自动化插件已停用" }
            check(app.workspaceManager.list(false).any { it.id == run.workspaceId }) { "工作区不可用或已归档" }
            val existing = app.sessions.getSession(sessionId)
            val session = if (task.destination == TaskDestination.EXISTING_SESSION) {
                requireNotNull(existing) { "目标会话已删除" }.also {
                    check(it.workspaceId == run.workspaceId && it.archivedAtMillis == null && it.depth == 0) { "目标会话不属于工作区、已归档或非用户会话" }
                    check(!app.turnService.isTurnActive(sessionId)) { "目标会话正在运行，请稍后重试" }
                }
            } else existing?.also {
                check(it.workspaceId == run.workspaceId && it.archivedAtMillis == null) { "预约会话不可用" }
            } ?: app.conversationCatalog.conversationStore.create(
                id = sessionId, workspaceId = run.workspaceId, title = "自动化 · ${task.name}",
                nowMillis = System.currentTimeMillis(),
            )
            val model = com.cordiact.app.model.ConversationModelSelectionStore(app).forSession(session.id)
                ?: app.modelAdapter.models().firstOrNull()?.id
                ?: error("没有可执行的模型，请先配置模型")
            val security = app.credentialVaultRuntime.newSecurityContext(run.workspaceId, sessionId, model.providerId, model.modelId)
            val context = ToolInvocationContext(workspaceId = run.workspaceId, sessionId = sessionId,
                modelProviderId = model.providerId, modelId = model.modelId, securityContext = security)
            val loop = ToolLoopPlugin(ToolLoopLimits(maxRounds = task.maxToolRounds)).also { it.onEnable() }
            val timeout = java.util.concurrent.Executors.newSingleThreadScheduledExecutor()
            active[run.id] = sessionId
            val expired = java.util.concurrent.atomic.AtomicBoolean(false)
            val timer = timeout.schedule({ expired.set(true); app.turnService.cancelTurn(sessionId) }, task.maxDurationMinutes.coerceAtMost(8).toLong(), TimeUnit.MINUTES)
            try {
                // Tool definitions and dispatch both retain the normal workspace and approval gates.
                val outcome = app.turnService.runTurnToCompletion(sessionId, model, task.prompt,
                    app.automationToolSpecs(run.workspaceId, model).filterNot { it.name == "automation_tasks" }, loop,
                    ToolDispatcher { ctx, call ->
                        check(!expired.get() && service.runs(run.workspaceId, run.taskId).any { it.id == run.id && it.state == TaskRunState.RUNNING }) { "运行已结束，拒绝后续工具调用" }
                        app.automationToolDispatcher().dispatch(ctx, call)
                    }, context,
                    systemContext = "此轮来源为自动化任务。仅在当前工作区执行，不创建递归调度；没有用户在场不代表获得额外权限。",
                    sourceMetadataJson = "{\"source\":\"automation\",\"taskId\":\"${task.id}\",\"runId\":\"${run.id}\"}")
                when {
                    expired.get() -> service.finish(run.id, TaskRunState.FAILED, "达到后台任务时长上限（本地执行最多8分钟）")
                    outcome is ToolLoopOutcome.Completed -> service.finish(run.id, TaskRunState.SUCCEEDED, summary = outcome.text)
                    outcome is ToolLoopOutcome.Stopped -> service.finish(run.id, TaskRunState.FAILED, outcome.reason.name, outcome.lastText)
                }
            } finally {
                timer.cancel(false); timeout.shutdownNow(); active.remove(run.id); loop.onDisable()
            }
        } catch (error: kotlinx.coroutines.CancellationException) {
            app.turnService.cancelTurn(sessionId)
            service.finish(run.id, TaskRunState.INTERRUPTED, "系统停止后台工作，未自动重放")
            throw error
        } catch (error: InterruptedException) {
            app.turnService.cancelTurn(sessionId)
            service.finish(run.id, TaskRunState.INTERRUPTED, "系统停止后台工作，未自动重放")
            throw error
        } catch (error: Exception) {
            service.finish(run.id, TaskRunState.FAILED, error.message?.take(500) ?: error.javaClass.simpleName)
        }
    }

    fun interruptActive() {
        active.toMap().forEach { (id, session) ->
            app.turnService.cancelTurn(session)
            service.finish(id, TaskRunState.INTERRUPTED, "Android 后台工作被系统停止；未自动重放可能含副作用的运行")
        }
    }
}

/** Best-effort clock wake plus a persistent watchdog; no exact-alarm permission. */
object AutomationTaskScheduling {
    private const val PERIODIC = "automation-watchdog-v1"
    private const val MANUAL = "automation-kick-v1"
    private const val PREFS = "automation-wake-v1"
    fun install(context: Context) {
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<AutomationTaskWorker>(15, TimeUnit.MINUTES).build())
        kick(context)
    }
    fun kick(context: Context) {
        WorkManager.getInstance(context).enqueueUniqueWork(MANUAL, ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<AutomationTaskWorker>().build())
    }
    @Synchronized fun plan(context: Context, at: Long?) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val old = prefs.getString("name", null)
        val name = at?.let { "automation-wake-$it" }
        if (old != null && old != name) WorkManager.getInstance(context).cancelUniqueWork(old)
        if (name == null) { prefs.edit().remove("name").apply(); return }
        prefs.edit().putString("name", name).apply()
        WorkManager.getInstance(context).enqueueUniqueWork(name, ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<AutomationTaskWorker>()
                .setInitialDelay((at - System.currentTimeMillis()).let { if (it <= 0) TimeUnit.MINUTES.toMillis(15) else it }, TimeUnit.MILLISECONDS).build())
    }
    @Synchronized fun consuming(context: Context) {
        // The previous wake is no longer a future alarm. Do not cancel this running worker.
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove("name").apply()
    }
}

class AutomationTaskWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    private var runtime: AutomationTaskRuntime? = null
    override suspend fun doWork(): Result {
        return try {
            val app = applicationContext as CordiActApplication
            val tasks = app.automationTasks.also { runtime = it }
            AutomationTaskScheduling.consuming(app)
            tasks.drain()
            AutomationTaskScheduling.plan(app, tasks.service.nextWakeAt())
            Result.success()
        } catch (error: CancellationException) { runtime?.interruptActive(); throw error }
        catch (_: Exception) { Result.failure() } // fail closed: a damaged store is never replaced with empty data
    }
}
