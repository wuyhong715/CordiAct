package com.cordiact.app.experience.settings

import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.ui.platform.LocalContext
import com.cordiact.app.CordiActApplication
import com.cordiact.core.settings.WorkspaceInstructions
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun ProjectInstructionsPage(state: SettingsSnapshot, onDirty: (Boolean) -> Unit, onBusy: (Boolean) -> Unit) {
    val id = state.capabilityWorkspaceId
    if (id.isBlank()) { Text("正在读取工作区…"); return }
    val app = LocalContext.current.applicationContext as CordiActApplication
    val files = remember(app) { WorkspaceInstructions(app.conversationCatalog.fileManager) }
    val scope = rememberCoroutineScope()
    key(id) {
        var draft by rememberSaveable { mutableStateOf<String?>(null) }
        // Tie restored drafts to the revision they were based on, not the reload revision.
        var baseRevision by rememberSaveable { mutableStateOf<String?>(null) }
        var latest by remember { mutableStateOf<WorkspaceInstructions.Snapshot?>(null) }
        var isBusy by remember { mutableStateOf(false) }
        var error by remember { mutableStateOf<String?>(null) }
        suspend fun runIo(action: suspend () -> Unit) {
            isBusy = true; onBusy(true); error = null
            try { action() }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { error = "无法读写 AGENTS.md。文件可能已改动、不可访问或超过大小限制；草稿未覆盖原文件。" }
            finally { isBusy = false; onBusy(false) }
        }
        LaunchedEffect(id) {
            onDirty(draft != null)
            runIo { latest = withContext(Dispatchers.IO) { files.read(id) } }
        }
        Text("AGENTS.md", style = MaterialTheme.typography.titleMedium)
        Text("当前工作区指令。新会话读取快照，不修改已有会话，也不改变运行权限。")
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        if (latest != null || draft != null) {
            OutlinedTextField(draft ?: latest?.text.orEmpty(), { text ->
                if (draft == null) baseRevision = latest?.revision
                draft = text
                onDirty(true)
            }, label = { Text("项目指令") }, minLines = 6, maxLines = 14, enabled = !isBusy && latest != null,
                modifier = Modifier.fillMaxWidth())
            Button(enabled = draft != null && !isBusy && latest != null, onClick = {
                val text = draft ?: return@Button
                val revision = baseRevision
                scope.launch { runIo {
                    latest = withContext(Dispatchers.IO) { files.write(id, revision, text) }
                    draft = null; baseRevision = null; onDirty(false)
                } }
            }) { Text("保存到工作区") }
        }
        TextButton(enabled = !isBusy, onClick = {
            scope.launch { runIo {
                val read = withContext(Dispatchers.IO) { files.read(id) }
                latest = read
                // Reloading never silently rebases a draft or discards it.
                if (draft != null && baseRevision != read.revision) error = "已读取最新文件，草稿基于较旧版本。请核对后放弃草稿，再重新编辑。"
            } }
        }) { Text("读取最新文件") }
        if (draft != null) TextButton(enabled = !isBusy, onClick = {
            draft = null; baseRevision = null; onDirty(false); error = null
        }) { Text("放弃草稿") }
    }
}
