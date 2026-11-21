package com.cordiact.app.experience.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.cordiact.app.experience.settings.components.SettingsGroup
import com.cordiact.core.attachment.Interpretation
import com.cordiact.core.conversation.ReasoningEffort
import com.cordiact.model.providers.ModelProtocol

@Composable
internal fun ProviderModelEditor(provider: ProviderSummary, model: String, vm: SettingsViewModel, busy: Boolean, onDirty: (Boolean) -> Unit, back: () -> Unit) {
    val initialWindow = provider.contextWindows[model]?.toString().orEmpty()
    val initialOutput = provider.modelDefaults[model]?.maxOutputTokens?.toString().orEmpty()
    var window by rememberSaveable(provider.id, model) { mutableStateOf(initialWindow) }
    var output by rememberSaveable(provider.id, model) { mutableStateOf(initialOutput) }
    val maxWindow = parseModelTokenLimit(window)
    val maxOutput = parseModelTokenLimit(output)
    val invalidWindow = window.isNotBlank() && maxWindow == null
    val invalidOutput = output.isNotBlank() && (maxOutput == null || maxWindow != null && maxOutput > maxWindow)
    val initialVision = Interpretation.VISION in provider.attachments[model]?.interpretations.orEmpty()
    val initialEfforts = provider.capabilities[model]?.reasoningEffortValues?.keys.orEmpty() - ReasoningEffort.AUTO
    val initialDefault = provider.modelDefaults[model]?.reasoningEffort ?: ReasoningEffort.AUTO
    val chat = provider.protocol != ModelProtocol.OPENAI_IMAGES
    val canMap = provider.protocol in setOf(ModelProtocol.OPENAI_CHAT_COMPLETIONS, ModelProtocol.OPENAI_RESPONSES)
    var vision by rememberSaveable(provider.id, model) { mutableStateOf(initialVision) }
    var names by rememberSaveable(provider.id, model) { mutableStateOf(initialEfforts.joinToString(",") { it.name }) }
    var defaultName by rememberSaveable(provider.id, model) { mutableStateOf(initialDefault.name) }
    var leave by remember { mutableStateOf(false) }
    val efforts = ReasoningEffort.entries.filter { it != ReasoningEffort.AUTO && it.name in names.split(',') }.toSet()
    val default = ReasoningEffort.valueOf(defaultName)
    val changed = vision != initialVision || efforts != initialEfforts || default != initialDefault || window != initialWindow || output != initialOutput
    LaunchedEffect(changed) { onDirty(changed) }
    val goBack = { if (!busy) { if (changed) leave = true else back() } }
    BackHandler(onBack = goBack)
    TextButton(goBack, enabled = !busy) { Text("‹ 返回 ${provider.name} 的模型") }
    Text(model, style = MaterialTheme.typography.headlineSmall)
    Text("模型声明不会验证真实服务。仅打开接口已支持的能力。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    if (chat) {
        Text("Token 限额", style = MaterialTheme.typography.titleSmall)
        SettingsGroup {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(window, { window = it.take(12) }, Modifier.fillMaxWidth(), enabled = !busy,
                    label = { Text("上下文窗口") }, singleLine = true, isError = invalidWindow,
                    supportingText = { Text(if (invalidWindow) "请输入正整数、128K 或 1M" else "留空使用默认，例如 128K") })
                OutlinedTextField(output, { output = it.take(12) }, Modifier.fillMaxWidth(), enabled = !busy,
                    label = { Text("最大输出") }, singleLine = true, isError = invalidOutput,
                    supportingText = { Text(if (invalidOutput) "请输入有效值，且不能超过上下文窗口" else "留空继承提供商默认") })
            }
        }
        Text("输入能力", style = MaterialTheme.typography.titleSmall)
        SettingsGroup {
            Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 5.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("图片输入")
                    Text(if (initialVision) "已有声明 · PNG / JPEG 等以实际配置为准" else "未声明 · 默认不发送图片", style = MaterialTheme.typography.bodySmall)
                }
                Switch(vision, { vision = it }, enabled = !busy)
            }
        }
        Text("推理设置", style = MaterialTheme.typography.titleSmall)
        SettingsGroup {
            if (canMap) ReasoningEffort.entries.filter { it != ReasoningEffort.AUTO }.forEach { value ->
                Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Checkbox(value in efforts, { on ->
                        val updated = if (on) efforts + value else efforts - value
                        names = updated.joinToString(",") { it.name }
                        if (default != ReasoningEffort.AUTO && default !in updated) defaultName = ReasoningEffort.AUTO.name
                    }, enabled = !busy)
                    Text(value.name)
                }
            }
            if (efforts.isEmpty()) Text("未声明推理档位", Modifier.padding(12.dp), style = MaterialTheme.typography.bodySmall)
            else {
                var expanded by remember { mutableStateOf(false) }
                Box(Modifier.padding(8.dp)) {
                    TextButton({ expanded = true }, enabled = !busy) { Text("默认推理：${if (default == ReasoningEffort.AUTO) "自动" else default.name}") }
                    DropdownMenu(expanded, { expanded = false }) {
                        (listOf(ReasoningEffort.AUTO) + efforts).forEach { value ->
                            DropdownMenuItem(text = { Text(if (value == ReasoningEffort.AUTO) "自动" else value.name) }, onClick = { defaultName = value.name; expanded = false })
                        }
                    }
                }
            }
        }
    } else Text("图像生成端点，不提供对话推理或图片输入声明。")
    Button({ vm.perform(success = { onDirty(false); back() }) {
        vm.facade.setModelDeclaration(provider.id, model, vision.takeIf { it != initialVision },
            efforts.takeIf { it != initialEfforts }, maxWindow, maxOutput, default.takeUnless { it == ReasoningEffort.AUTO })
        vm.notice.value = "已保存模型声明"
    } }, enabled = changed && !busy && chat && !invalidWindow && !invalidOutput) { Text("保存模型") }
    if (leave) AlertDialog(onDismissRequest = { leave = false }, title = { Text("放弃未保存的模型声明？") },
        confirmButton = { TextButton({ onDirty(false); back() }) { Text("放弃") } }, dismissButton = { TextButton({ leave = false }) { Text("继续编辑") } })
}

internal fun parseModelTokenLimit(text: String): Int? {
    val match = Regex("""^(\d+)\s*([kKmM])?$""").matchEntire(text.trim()) ?: return null
    val factor = when (match.groupValues[2].lowercase()) { "k" -> 1024; "m" -> 1048576; else -> 1 }
    val value = match.groupValues[1].toIntOrNull() ?: return null
    return if (value in 1..(16_777_216 / factor)) value * factor else null
}
