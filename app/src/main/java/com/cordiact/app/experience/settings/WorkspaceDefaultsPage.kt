package com.cordiact.app.experience.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.cordiact.app.CordiActApplication
import com.cordiact.app.experience.GlyphName
import com.cordiact.app.experience.settings.components.SettingsGroup
import com.cordiact.app.experience.settings.components.SettingsRow
import com.cordiact.app.experience.settings.components.SettingsSectionLabel
import com.cordiact.core.conversation.ReasoningEffort
import com.cordiact.core.file.WorkspaceFileException
import com.cordiact.core.settings.WorkspaceDefaults
import com.cordiact.core.settings.resolveWorkspaceConversationModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private enum class DefaultField(val title: String, val document: WorkspaceDefaults.Document) {
    PRESET("Agent 预设", WorkspaceDefaults.Document.AGENT),
    MODEL("默认模型", WorkspaceDefaults.Document.MODELS),
    EFFORT("推理强度", WorkspaceDefaults.Document.MODELS),
}

private data class DefaultChoice(val title: String, val active: Boolean, val apply: () -> Unit)

@Composable
internal fun WorkspaceDefaultsPage(
    state: SettingsSnapshot, presets: AgentPresetStore, scopeBusy: Boolean, selectWorkspace: (String) -> Unit,
) {
    val app = LocalContext.current.applicationContext as CordiActApplication
    val workspaceId = state.capabilityWorkspaceId
    if (workspaceId.isBlank()) {
        Text("正在读取工作区…", style = MaterialTheme.typography.bodyMedium)
        return
    }
    key(workspaceId) {
        val store = remember(app) { WorkspaceDefaults(app.conversationCatalog.fileManager) }
        val scope = rememberCoroutineScope()
        var data by remember { mutableStateOf<WorkspaceDefaults.Snapshot?>(null) }
        var revision by remember { mutableIntStateOf(0) }
        var busy by remember { mutableStateOf(false) }
        var error by remember { mutableStateOf<String?>(null) }
        var editing by rememberSaveable { mutableStateOf<DefaultField?>(null) }
        LaunchedEffect(store, revision) { data = withContext(Dispatchers.IO) { store.read(workspaceId) } }
        val workspace = state.workspaces.firstOrNull { it.id == workspaceId }
        SettingsSectionLabel("工作区默认 · ${workspace?.name ?: workspaceId}")
        if (state.workspaces.size > 1) WorkspaceScopePicker(state, busy || scopeBusy, selectWorkspace)
        Text("仅影响这里的新对话。Agent 与设置页编辑同一份文件；不能通过文件切换模型提供商。",
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
        val snapshot = data
        if (snapshot == null) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
        } else {
            val inheritedPreset = presets.effective(workspaceId)
            val explicitPreset = snapshot.presetId?.let(presets::get)
            val preset = explicitPreset ?: inheritedPreset
            val models = state.models.filter { it.supportsStreaming }
            val inheritedModel = resolveWorkspaceConversationModel(models, inheritedPreset.model, inheritedPreset.model,
                state.conversationDefault, null)
            val editableModels = models.filter { it.id.providerId == inheritedModel?.id?.providerId }
            val explicitModel = editableModels.firstOrNull { it.id == snapshot.model }
            val presetModel = editableModels.firstOrNull { it.id == preset.model }
            val appDefault = models.firstOrNull { it.id == state.conversationDefault }
            val effective = resolveWorkspaceConversationModel(models, inheritedPreset.model, preset.model, state.conversationDefault, snapshot.model)
            val wantedEffort = snapshot.effort ?: preset.effort
            val supportedEfforts = effective?.capabilities?.reasoningEfforts.orEmpty()
            val shownEffort = wantedEffort.takeIf { it == ReasoningEffort.AUTO || it in supportedEfforts } ?: ReasoningEffort.AUTO
            val source = when {
                explicitModel != null -> "工作区覆盖"
                presetModel != null -> "继承预设"
                appDefault != null && effective?.id == appDefault.id -> "继承应用默认"
                effective != null -> "可用模型回退"
                else -> "无可用模型"
            }
            val presetLabel = when {
                DefaultField.PRESET.document in snapshot.errors -> "配置不可读 · 暂用 ${preset.name}"
                explicitPreset != null -> "工作区覆盖 · ${preset.name}"
                snapshot.presetId != null -> "预设不可用 · 回退到 ${preset.name}"
                else -> "继承 · ${preset.name}"
            }
            val modelLabel = when {
                DefaultField.MODEL.document in snapshot.errors -> "配置不可读 · $source"
                snapshot.model != null && models.any { it.id == snapshot.model } && explicitModel == null -> "未授权更换提供商 · $source"
                snapshot.model != null && explicitModel == null -> "声明的模型不可用 · $source"
                else -> source
            }
            SettingsGroup {
                SettingsRow("Agent 预设", presetLabel, GlyphName.AgentPreset,
                    !busy && !scopeBusy && DefaultField.PRESET.document !in snapshot.errors) { editing = DefaultField.PRESET }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                SettingsRow("默认模型", "$modelLabel · ${effective?.id?.modelId ?: "尚未配置"}", GlyphName.Link,
                    !busy && !scopeBusy && DefaultField.MODEL.document !in snapshot.errors) { editing = DefaultField.MODEL }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                val effortLabel = if (shownEffort != wantedEffort) "模型不支持，回退" else if (snapshot.effort != null) "工作区覆盖" else "继承预设"
                SettingsRow("推理强度", "$effortLabel · ${shownEffort.displayName()}", GlyphName.Sparkle,
                    !busy && !scopeBusy && DefaultField.EFFORT.document !in snapshot.errors) { editing = DefaultField.EFFORT }
            }
            SettingsSectionLabel("配置来源")
            SettingsGroup {
                SettingsRow("刷新工作区文件", ".cordiact/agent.toml · .cordiact/models.toml", enabled = !busy && !scopeBusy) {
                    error = null; revision++
                }
            }
            if (snapshot.errors.isNotEmpty()) {
                val filenames = snapshot.errors.joinToString("、") { it.path }
                Text("无法读取或校验 $filenames。请修正或检查工作区访问权限后刷新，应用不会覆盖文件。",
                    color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
            }
            val field = editing
            if (field != null && field.document !in snapshot.errors) {
                fun change(update: (String?) -> Unit) {
                    editing = null
                    busy = true
                    error = null
                    scope.launch {
                        try {
                            val result = withContext(Dispatchers.IO) {
                                runCatching { update(snapshot.revisions[field.document]) } to store.read(workspaceId)
                            }
                            data = result.second
                            error = result.first.exceptionOrNull()?.let {
                                if ((it as? WorkspaceFileException)?.code == WorkspaceFileException.Code.REVISION_CONFLICT)
                                    "文件已被其他编辑器修改，已刷新。请确认后重试。"
                                else "未能保存到工作区；请检查文件状态与访问权限。"
                            }
                        } finally { busy = false }
                    }
                }
                val choices = when (field) {
                    DefaultField.PRESET -> listOf(DefaultChoice("继承应用与旧版默认", snapshot.presetId == null) { change { store.setPreset(workspaceId, it, null) } }) + presets.all().map { item ->
                        DefaultChoice(item.name, snapshot.presetId == item.id) { change { store.setPreset(workspaceId, it, item.id) } }
                    }
                    DefaultField.MODEL -> listOf(DefaultChoice("继承预设与应用默认", snapshot.model == null) { change { store.setModel(workspaceId, it, null) } }) + editableModels.map { model ->
                        DefaultChoice("${model.id.modelId} · ${model.id.providerId}", snapshot.model == model.id) { change { store.setModel(workspaceId, it, model.id) } }
                    }
                    DefaultField.EFFORT -> listOf(DefaultChoice("继承预设", snapshot.effort == null) { change { store.setEffort(workspaceId, it, null) } }) +
                        ReasoningEffort.entries.filter { it == ReasoningEffort.AUTO || it in supportedEfforts }.map { effort ->
                            DefaultChoice(effort.displayName(), snapshot.effort == effort) { change { store.setEffort(workspaceId, it, effort) } }
                        }
                }
                AlertDialog(onDismissRequest = { editing = null }, title = { Text(field.title) }, text = {
                    LazyColumn(Modifier.fillMaxWidth().heightIn(max = 320.dp)) {
                        items(choices) { choice -> SettingsRow(choice.title, if (choice.active) "当前选择" else "", onClick = choice.apply) }
                    }
                }, confirmButton = { TextButton(onClick = { editing = null }) { Text("关闭") } })
            }
        }
    }
}

private fun ReasoningEffort.displayName(): String = if (this == ReasoningEffort.AUTO) "自动" else name.lowercase()
