package com.cordiact.app.experience

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.cordiact.app.CordiActApplication
import com.cordiact.core.automation.TaskRunState
import com.cordiact.core.subagent.ChildRunState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext

/** Read the real session and task stores only while the landscape workspace is visible. */
@Composable
internal fun rememberWorkbenchActivity(app: CordiActApplication, workspaceId: String?, sessionId: String?, runningSessionIds: Set<String>): WorkbenchActivity {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val snapshot by produceState(WorkbenchActivity(), lifecycle, workspaceId, sessionId, runningSessionIds) {
        value = WorkbenchActivity()
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (isActive) {
                value = withContext(Dispatchers.IO) {
                    val children = sessionId?.let(app.conversationStore::listChildren).orEmpty()
                    val runs = sessionId?.let(app.conversationStore::runsOf).orEmpty().associateBy { it.sessionId }
                    val agents = children.map { child ->
                        val run = runs[child.id]
                        val active = run?.state == ChildRunState.RUNNING || child.id in runningSessionIds
                        val status = if (active) "运行中" else when (run?.state) {
                            ChildRunState.COMPLETED -> "已完成"
                            ChildRunState.FAILED -> "失败"
                            ChildRunState.CLOSED -> "已关闭"
                            ChildRunState.ABORTED -> "已中断"
                            else -> "空闲"
                        }
                        WorkbenchEntry(child.id, run?.label?.takeIf { it.isNotBlank() } ?: child.title.ifBlank { "子 Agent" }, status, active)
                    }
                    val jobs = workspaceId?.let { id ->
                        val service = app.automationTasks.service
                        val names = service.tasks(id).associate { it.id to it.name }
                        service.runs(id).asReversed().map { run ->
                            val status = when (run.state) {
                                TaskRunState.QUEUED -> "排队中"
                                TaskRunState.RUNNING -> "运行中"
                                TaskRunState.SUCCEEDED -> "已完成"
                                TaskRunState.FAILED -> "失败"
                                TaskRunState.CANCELLED -> "已取消"
                                TaskRunState.INTERRUPTED -> "已中断"
                                TaskRunState.SKIPPED -> "已跳过"
                            }
                            WorkbenchEntry(run.id, names[run.taskId] ?: "后台任务", status,
                                run.state == TaskRunState.QUEUED || run.state == TaskRunState.RUNNING)
                        }.sortedByDescending { it.active }.let { rows -> rows.filter { it.active } + rows.filterNot { it.active }.take(12) }
                    }.orEmpty()
                    WorkbenchActivity(agents.sortedByDescending { it.active }, jobs)
                }
                delay(2_000)
            }
        }
    }
    return snapshot
}
