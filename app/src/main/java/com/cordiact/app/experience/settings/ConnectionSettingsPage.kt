package com.cordiact.app.experience.settings

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.cordiact.core.settings.ReadinessState

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
internal fun ConnectionSettingsPage(state: SettingsSnapshot, vm: SettingsViewModel, busy: Boolean, initialCapabilityId: String? = null, onToggle: ((String, CapabilitySummary, Boolean) -> Unit)? = null) {
    val context = LocalContext.current
    WorkspaceScopePicker(state, busy, vm::selectWorkspace)
    Text("关闭后，模型不再看到对应工具；已有调用请求也会重新检查。", style = MaterialTheme.typography.bodySmall)
    val targetRequester = remember { BringIntoViewRequester() }
    LaunchedEffect(initialCapabilityId, state.capabilities.map { it.id }) {
        if (initialCapabilityId != null && state.capabilities.any { it.id == initialCapabilityId }) {
            withFrameNanos { }
            targetRequester.bringIntoView()
        }
    }
    state.capabilities.forEach { capability ->
        ElevatedCard(if (capability.id == initialCapabilityId) Modifier.fillMaxWidth().bringIntoViewRequester(targetRequester) else Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(capability.label, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                    Switch(checked = capability.enabled, enabled = !busy && (capability.enabled || capability.readiness.state == ReadinessState.READY),
                        onCheckedChange = { next -> onToggle?.invoke(state.capabilityWorkspaceId, capability, next) ?: vm.setCapability(state.capabilityWorkspaceId, capability.id, next) })
                }
                Text((if (!capability.enabled) "已关闭 · " else "") + capability.readiness.summary)
                capability.readiness.steps.forEach { step -> Text("${if (step.complete) "✓" else "○"} ${step.summary}") }
                when (capability.id) {
                    "privilege" -> {
                        TextButton(enabled = !busy, onClick = { context.packageManager.getLaunchIntentForPackage("moe.shizuku.privileged.api")?.let(context::startActivity) }) { Text("打开 Shizuku") }
                        TextButton(enabled = !busy, onClick = { if (rikka.shizuku.Shizuku.pingBinder()) rikka.shizuku.Shizuku.requestPermission(4102) }) { Text("请求 Shizuku 授权") }
                        TextButton(enabled = !busy, onClick = vm::testRoot) { Text("检测 Root（需完全权限档且已开启 Root）") }
                    }
                    "screen" -> {
                        Text("开启后可读取当前屏幕及执行界面操作；内容仅在实际工具调用时读取。", style = MaterialTheme.typography.bodySmall)
                        TextButton(onClick = { context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }) { Text("打开系统无障碍设置") }
                        TextButton(enabled = !busy, onClick = vm::testScreen) { Text("测试读取当前窗口") }
                    }
                    "termux" -> {
                        Text("Termux 中还需启用 allow-external-apps=true。测试会执行 /usr/bin/true，不修改终端文件。", style = MaterialTheme.typography.bodySmall)
                        val launcher = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.RequestPermission()) { vm.reload() }
                        TextButton(enabled = !busy, onClick = { launcher.launch("com.termux.permission.RUN_COMMAND") }) { Text("请求 Termux 授权") }
                        TextButton(enabled = !busy, onClick = {
                            val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                            clipboard.setPrimaryClip(android.content.ClipData.newPlainText("Termux allow-external-apps", "mkdir -p ~/.termux; printf '\nallow-external-apps=true\n' >> ~/.termux/termux.properties; termux-reload-settings"))
                        }) { Text("复制配置命令") }
                        TextButton(enabled = !busy, onClick = vm::testTermux) { Text("连通测试") }
                    }
                }
            }
        }
    }
    OutlinedButton(onClick = vm::reload, enabled = !busy) { Text("重新检查就绪状态") }
}
