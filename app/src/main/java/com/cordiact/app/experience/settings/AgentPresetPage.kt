package com.cordiact.app.experience.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.selection.selectable
import com.cordiact.app.experience.settings.components.SettingsGroup
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.cordiact.app.experience.AppGlyph
import com.cordiact.app.experience.GlyphName
import com.cordiact.app.experience.GlyphSize
import com.cordiact.core.conversation.ModelDescriptor
import com.cordiact.core.conversation.ModelId
import com.cordiact.core.conversation.ReasoningEffort

@Composable
internal fun AgentPresetLibrary(store: AgentPresetStore, state: SettingsSnapshot, wide: Boolean, onEdit: (String) -> Unit) {
    val workspace = state.capabilityWorkspaceId
    val records = store.all()
    var picker by remember { mutableStateOf<String?>(null) }
    var revision by remember { mutableIntStateOf(0) }
    val global = remember(revision, records) { store.globalId() }
    val local = remember(workspace, revision, records) { store.workspaceId(workspace) }
    val selected = local ?: global
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) { PresetHero("Agent 预设", "为新会话预选模型与推理强度，写入系统提示词，并管理工具草稿。") }
        FilledTonalButton(onClick = { onEdit("") }, modifier = Modifier.heightIn(min = 48.dp)) {
            AppGlyph(GlyphName.Plus, size = GlyphSize.Small); Spacer(Modifier.width(5.dp)); Text("新建预设")
        }
    }
    val list: @Composable () -> Unit = {
        PresetGroup("内置预设") { records.filter { it.builtIn }.forEachIndexed { i, p ->
            if (i > 0) PresetDivider()
            PresetRow(p.name, p.description, if (p.id == selected) "默认" else null,
                if (p.id == "code") GlyphName.Code else GlyphName.Sparkle) { onEdit(p.id) }
        } }
        PresetGroup("我的预设") {
            val custom = records.filterNot { it.builtIn }
            if (custom.isEmpty()) PresetRow("暂无自定义预设", "新建，或复制一个内置预设。")
            custom.forEachIndexed { i, p ->
                if (i > 0) PresetDivider()
                PresetRow(p.name, p.description, if (p.id == selected) "默认" else null, GlyphName.AgentPreset) { onEdit(p.id) }
            }
        }
    }
    val defaults: @Composable () -> Unit = {
        PresetGroup("新会话默认") {
            if (workspace.isNotBlank()) {
                PresetRow("当前工作区", if (local == null) "跟随全局默认" else "覆盖全局默认", store.get(selected)?.name) { picker = "workspace" }
                PresetDivider()
            }
            PresetRow("全局默认", "用于未单独设置的工作区", store.get(global)?.name) { picker = "global" }
        }
        PresetHint("默认模型和受支持的推理强度仅用于新会话。现有会话保留原配置。")
    }
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        if (wide && maxWidth >= 475.dp) Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(16.dp)) { list() }
            Column(Modifier.widthIn(175.dp, 212.dp).weight(.58f), verticalArrangement = Arrangement.spacedBy(8.dp)) { defaults() }
        } else Column(verticalArrangement = Arrangement.spacedBy(16.dp)) { list(); defaults() }
    }
    picker?.let { kind ->
        PresetChoices(title = if (kind == "global") "全局默认" else "工作区默认",
            items = (if (kind == "workspace") listOf("" to "跟随全局默认") else emptyList()) + records.map { it.id to it.name },
            current = if (kind == "global") global else local.orEmpty(), onDismiss = { picker = null }) { id ->
            store.setDefault(if (kind == "global") null else workspace, id.ifBlank { null }); revision++; picker = null
        }
    }
}

private val presetCapabilities = listOf(
    "files" to "工作区文件", "terminal" to "工作区终端", "search" to "搜索服务", "mcp" to "MCP 工具", "skills" to "工作区技能",
)

@Composable
internal fun AgentPresetEditor(store: AgentPresetStore, routeId: String, models: List<ModelDescriptor>, section: String,
                               onDirty: (Boolean) -> Unit, onSaved: () -> Unit, onCopy: (String) -> Unit, onPermissions: () -> Unit) {
    val sourceId = routeId.removePrefix("copy:")
    val existing = remember(routeId) { store.get(sourceId) }
    val baseline = remember(routeId) {
        if (routeId.startsWith("copy:") && existing != null) existing.copy(id = "", builtIn = false, name = (existing.name.take(30) + "副本"))
        else existing ?: AgentPreset()
    }
    var name by rememberSaveable(routeId) { mutableStateOf(baseline.name) }
    var description by rememberSaveable(routeId) { mutableStateOf(baseline.description) }
    var provider by rememberSaveable(routeId) { mutableStateOf(baseline.model?.providerId) }
    var modelId by rememberSaveable(routeId) { mutableStateOf(baseline.model?.modelId) }
    var effortName by rememberSaveable(routeId) { mutableStateOf(baseline.effort.name) }
    var instructions by rememberSaveable(routeId) { mutableStateOf(baseline.systemPrompt) }
    var capabilityIds by rememberSaveable(routeId) { mutableStateOf(ArrayList(baseline.capabilities)) }
    var scope by rememberSaveable(routeId) { mutableStateOf(baseline.scope) }
    var openPicker by remember { mutableStateOf<String?>(null) }
    val model = provider?.let { p -> modelId?.let { ModelId(p, it) } }
    val availableModels = models.filter { it.supportsStreaming }
    val currentModel = availableModels.firstOrNull { it.id == model }
    val allowedEfforts = (currentModel?.capabilities?.reasoningEfforts ?: availableModels.flatMap { it.capabilities.reasoningEfforts }.toSet()) - ReasoningEffort.AUTO
    val draft = baseline.copy(name = name, description = description, model = model,
        effort = ReasoningEffort.valueOf(effortName), systemPrompt = instructions.trim(), capabilities = capabilityIds.toSet(), scope = scope)
    val dirty = !draft.builtIn && draft != baseline
    SideEffect { onDirty(dirty) }
    if (routeId.isNotEmpty() && existing == null) {
        PresetHero("预设不可用", "预设可能已被移除。")
        TextButton(onClick = onSaved) { Text("返回预设列表") }
        return
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) { PresetHero(name.ifBlank { "新建预设" }, description.ifBlank {
            if (draft.builtIn) "内置预设 · 只读" else "配置新会话的默认模型和推理强度。"
        }) }
        FilledTonalButton(onClick = { if (draft.builtIn) onCopy(draft.id) else { store.save(draft); onSaved() } },
            enabled = draft.builtIn || name.isNotBlank(), modifier = Modifier.heightIn(min = 48.dp)) {
            Text(if (draft.builtIn) "复制预设" else "保存")
        }
    }
    when (section) {
        "capabilities" -> {
            PresetGroup("工具清单（未生效）") { presetCapabilities.forEachIndexed { i, (id, label) ->
                if (i > 0) PresetDivider()
                if (draft.builtIn) PresetRow(label, value = if (id in capabilityIds) "已列入" else "未列入")
                else Row(Modifier.fillMaxWidth().heightIn(min = 52.dp).padding(horizontal = 14.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                    Switch(checked = id in capabilityIds, onCheckedChange = { checked ->
                        capabilityIds = ArrayList(if (checked) capabilityIds + id else capabilityIds - id)
                    }, modifier = Modifier.semantics { contentDescription = label })
                }
            } }
            PresetHint("此处保存预设的工具清单，尚未接入执行时的工具过滤。实际工具仍按工作区策略授权。")
        }
        "scope" -> {
            PresetGroup("执行限制") {
                PresetRow("预设范围（未生效）", "配置草稿，不改变当前权限", scopeLabel(scope), onClick = if (draft.builtIn) null else { { openPicker = "scope" } })
                PresetDivider()
                PresetRow("审批与凭据", "沿用工作区策略与现有审批流程", glyph = GlyphName.Shield, onClick = onPermissions)
            }
            PresetHint("预设不授予权限，也不会跳过凭据审批。要限制真实会话，请使用「权限档位」。")
        }
        else -> {
            val modelCard: @Composable () -> Unit = {
                PresetGroup("运行配置") {
                    if (!draft.builtIn) {
                        PresetField("预设名称", name) { name = it.take(32) }
                        PresetField("说明", description) { description = it.take(56) }
                        PresetDivider()
                    }
                    PresetRow("默认模型", if (model != null && currentModel == null) "模型不可用，将跟随对话默认" else "新会话使用的模型",
                        currentModel?.displayName ?: if (model == null) "跟随对话默认" else model.modelId,
                        onClick = if (draft.builtIn) null else { { openPicker = "model" } })
                    PresetDivider()
                    PresetRow("推理强度", "只应用模型声明支持的档位", effortNameOf(draft.effort),
                        onClick = if (draft.builtIn) null else { { openPicker = "effort" } })
            }
            }
            val promptCard: @Composable () -> Unit = {
                PresetGroup("系统提示词") {
                    if (draft.builtIn) PresetRow("新会话指令", instructions.ifBlank { "无自定义系统指令；可复制后编辑" })
                    else PresetField("系统提示词", instructions, singleLine = false) { instructions = it.take(12_000) }
                    PresetRow("预览组装", "平台指令 → 预设系统提示词 → 会话历史", onClick = { openPicker = "prompt" })
            }
            }
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                if (maxWidth >= 610.dp) Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    Column(Modifier.weight(1.18f)) { promptCard() }
                    Column(Modifier.weight(1f)) { modelCard() }
                } else Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    promptCard()
                    modelCard()
                }
            }
            PresetHint("仅用于之后创建的会话，不改旧会话、不授予工具或审批权限。请勿填写密钥。")
            if (!draft.builtIn && draft.id.isNotEmpty()) TextButton(onClick = { openPicker = "remove" },
                modifier = Modifier.heightIn(min = 48.dp)) { Text("移除预设", color = MaterialTheme.colorScheme.error) }
        }
    }
    when (openPicker) {
        "prompt" -> AlertDialog(onDismissRequest = { openPicker = null }, title = { Text("系统提示词预览") },
            text = { Column(Modifier.verticalScroll(rememberScrollState())) {
                Text("平台指令（保留）")
                Text(instructions.ifBlank { "未添加预设指令" }, Modifier.padding(vertical = 12.dp))
                Text("之后接入会话历史")
            } }, confirmButton = { TextButton({ openPicker = null }) { Text("关闭") } })
        "model" -> PresetChoices("默认模型", listOf("" to "跟随对话默认") + availableModels.mapIndexed { i, m -> "$i" to m.displayName },
            availableModels.indexOfFirst { it.id == model }.takeIf { it >= 0 }?.toString().orEmpty(), { openPicker = null }) { key ->
            val m = key.toIntOrNull()?.let(availableModels::getOrNull); provider = m?.id?.providerId; modelId = m?.id?.modelId
            if (m != null && draft.effort !in m.capabilities.reasoningEfforts) effortName = ReasoningEffort.AUTO.name
            openPicker = null
        }
        "effort" -> PresetChoices("推理强度", (listOf(ReasoningEffort.AUTO) + allowedEfforts.sortedBy { it.ordinal }).map { it.name to effortNameOf(it) }, effortName, { openPicker = null }) { effortName = it; openPicker = null }
        "scope" -> PresetChoices("预设范围", listOf("read-only", "workspace").map { it to scopeLabel(it) }, scope, { openPicker = null }) { scope = it; openPicker = null }
        "remove" -> AlertDialog(onDismissRequest = { openPicker = null }, title = { Text("移除「${draft.name}」？") },
            text = { Text("现有会话不受影响。引用此预设的默认设置将回退。") },
            dismissButton = { TextButton(onClick = { openPicker = null }) { Text("取消") } },
            confirmButton = { TextButton(onClick = { store.remove(draft.id); onSaved() }) { Text("移除", color = MaterialTheme.colorScheme.error) } })
    }
}

@Composable private fun PresetHero(title: String, subtitle: String) {
    Text(title, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)
    Text(subtitle, Modifier.padding(top = 4.dp), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
}
@Composable private fun PresetGroup(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(title, Modifier.padding(start = 7.dp), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelLarge)
        SettingsGroup {
            Surface(color = MaterialTheme.colorScheme.surfaceContainerLowest) { Column(content = content) }
        }
    }
}
@Composable private fun PresetDivider() = HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
@Composable private fun PresetHint(text: String) {
    Text(text, Modifier.padding(horizontal = 7.dp), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
}
@Composable private fun PresetRow(title: String, detail: String = "", value: String? = null, glyph: GlyphName? = null, onClick: (() -> Unit)? = null) {
    Row(Modifier.fillMaxWidth().then(if (onClick == null) Modifier else Modifier.clickable(role = Role.Button, onClick = onClick))
        .heightIn(min = 53.dp).padding(horizontal = 13.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        if (glyph != null) AppGlyph(glyph, tint = MaterialTheme.colorScheme.primary, size = GlyphSize.Small)
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
            if (detail.isNotBlank()) Text(detail, Modifier.padding(top = 2.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (value != null) Text(value, Modifier.widthIn(max = 120.dp), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
        if (onClick != null) AppGlyph(GlyphName.ChevronRight, tint = MaterialTheme.colorScheme.onSurfaceVariant, size = GlyphSize.Small)
    }
}
@Composable private fun PresetField(label: String, value: String, singleLine: Boolean = true, change: (String) -> Unit) {
    OutlinedTextField(value, change, Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 7.dp),
        label = { Text(label) }, singleLine = singleLine, minLines = if (singleLine) 1 else 4, maxLines = if (singleLine) 1 else 10,
        shape = RoundedCornerShape(12.dp))
}
@Composable private fun PresetChoices(title: String, items: List<Pair<String, String>>, current: String, onDismiss: () -> Unit, onSelect: (String) -> Unit) {
    AlertDialog(onDismissRequest = onDismiss, title = { Text(title) }, text = {
        Column(Modifier.heightIn(max = 264.dp).verticalScroll(rememberScrollState())) {
            items.forEach { (id, label) -> Row(Modifier.fillMaxWidth().selectable(id == current, role = Role.RadioButton) { onSelect(id) }.heightIn(min = 48.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                RadioButton(selected = id == current, onClick = null); Text(label)
            } }
        }
    }, confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } })
}
private fun scopeLabel(v: String) = if (v == "workspace") "仅工作区" else "只读"
private fun effortNameOf(e: ReasoningEffort) = when (e.name) {
    "AUTO" -> "跟随模型默认"; "NONE" -> "关闭"; "MINIMAL" -> "极低"; "LOW" -> "低"; "MEDIUM" -> "中"
    "HIGH" -> "高"; "XHIGH" -> "极高"; "MAX" -> "最高"; else -> e.name
}
