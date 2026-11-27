package com.cordiact.app.experience

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.cordiact.app.CordiActApplication
import com.cordiact.app.experience.settings.components.SettingsCard
import com.cordiact.core.jobs.JobKind
import com.cordiact.core.jobs.JobRead
import com.cordiact.core.jobs.JobSnapshot
import com.cordiact.core.jobs.JobState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible

@Composable
internal fun PackageWorkflowJobs(app: CordiActApplication, workspaceId: String, blocked: Boolean) {
    val jobs = app.conversationCatalog.jobRegistry
    val scope = rememberCoroutineScope()
    var items by remember(workspaceId) { mutableStateOf(emptyList<JobSnapshot>()) }
    var output by remember(workspaceId) { mutableStateOf<JobRead?>(null) }
    var failure by remember(workspaceId) { mutableStateOf(false) }

    fun owns(job: JobSnapshot): Boolean = job.kind == JobKind.PLUGIN_WORKFLOW &&
        job.ownerSessionId == app.activeSessionId && app.sessions.getSession(job.ownerSessionId)?.workspaceId == workspaceId

    LaunchedEffect(app, workspaceId) {
        while (true) {
            try {
                items = runInterruptible(Dispatchers.IO) {
                    val session = app.activeSessionId
                    if (session == null || app.sessions.getSession(session)?.workspaceId != workspaceId) emptyList()
                    else jobs.list(session).filter { it.kind == JobKind.PLUGIN_WORKFLOW }.take(8)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                items = emptyList()
            }
            delay(1_000)
        }
    }

    fun act(job: JobSnapshot, action: () -> JobRead?) {
        failure = false
        scope.launch {
            try {
                output = runInterruptible(Dispatchers.IO) { check(owns(job)); action()?.takeIf { owns(it.snapshot) } }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                failure = true
            }
        }
    }

    if (items.isNotEmpty()) Text("当前会话工作流", style = MaterialTheme.typography.titleMedium)
    items.forEach { job ->
        SettingsCard {
            Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(job.label, style = MaterialTheme.typography.labelLarge)
                val state = when (job.state) {
                    JobState.RUNNING -> "运行中"
                    JobState.PENDING -> "等待运行"
                    JobState.STOPPING -> "取消中"
                    JobState.SUCCEEDED -> "已完成"
                    JobState.FAILED -> "运行失败"
                    JobState.KILLED -> "已取消"
                    else -> "已中断"
                }
                Text(listOfNotNull(state, job.progressLine).joinToString(" · "), style = MaterialTheme.typography.bodySmall)
                Row {
                    if (job.isTerminal) TextButton(enabled = !blocked, onClick = { act(job) { jobs.read(job.id) } }) { Text("查看结果") }
                    else TextButton(enabled = !blocked && job.state != JobState.STOPPING, onClick = {
                        act(job) { jobs.kill(job.id, "User cancelled workflow"); null }
                    }) { Text("取消运行") }
                }
            }
        }
    }
    if (failure) Text("无法读取或取消此工作流。", color = MaterialTheme.colorScheme.error)
    output?.takeIf { it.snapshot.ownerSessionId == app.activeSessionId }?.let { result ->
        AlertDialog(
            onDismissRequest = { output = null },
            title = { Text(result.snapshot.label) },
            text = { SelectionContainer {
                Text(result.text.ifEmpty { result.snapshot.diagnostic ?: "无文本输出" },
                    Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState()))
            } },
            confirmButton = { TextButton(onClick = { output = null }) { Text("关闭") } },
        )
    }
}
