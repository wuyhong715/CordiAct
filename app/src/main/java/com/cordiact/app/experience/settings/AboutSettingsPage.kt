package com.cordiact.app.experience.settings

import android.content.pm.PackageManager
import android.os.Build
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
internal fun AboutSettingsPage(onOpenProviders: () -> Unit) {
    val context = LocalContext.current
    val info = remember(context) {
        @Suppress("DEPRECATION")
        if (Build.VERSION.SDK_INT >= 33) context.packageManager.getPackageInfo(context.packageName, PackageManager.PackageInfoFlags.of(0))
        else context.packageManager.getPackageInfo(context.packageName, 0)
    }
    @Suppress("DEPRECATION")
    val versionCode = if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong()
    val diagnostics = remember(context, info) {
        listOf(
            "CordiAct ${info.versionName} ($versionCode)",
            "应用：${context.packageName}",
            "Android API：${Build.VERSION.SDK_INT}",
            "设备：${Build.MANUFACTURER} ${Build.MODEL}",
        ).joinToString("\n")
    }
    var copied by remember { mutableStateOf(false) }
    var license by remember { mutableStateOf<String?>(null) }
    var text by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf(false) }
    LaunchedEffect(license) {
        text = null; error = false
        val path = license ?: return@LaunchedEffect
        val result = withContext(Dispatchers.IO) {
            try { context.assets.open(path).bufferedReader().use { it.readText() } to false }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { "许可文件暂不可读" to true }
        }
        text = result.first; error = result.second
    }
    if (context.packageName == "com.cordiact.app.beta") {
        Text("公测包与旧调试包并存，不会覆盖或自动迁移数据。请保留旧包，按需重新配置模型和工作区。", style = MaterialTheme.typography.bodySmall)
    }
    Text("首次使用：先建工作区、配置模型提供商，再回到会话发一条消息。暂不需要打开设备权限。", style = MaterialTheme.typography.bodySmall)
    TextButton(onClick = onOpenProviders) { Text("配置模型提供商") }
    TextButton(onClick = {
        val clipboard = context.getSystemService(android.content.ClipboardManager::class.java)
        clipboard?.setPrimaryClip(android.content.ClipData.newPlainText("CordiAct 诊断概要", diagnostics))
        copied = clipboard != null
    }) { Text(if (copied) "已复制诊断概要" else "复制诊断概要") }
    Text("只包含应用版本、系统和设备型号；不包含日志、对话、文件或凭证。反馈时可另附复现步骤。", style = MaterialTheme.typography.bodySmall)
    Text("CordiAct", style = MaterialTheme.typography.headlineMedium)
    Text("版本 ${info.versionName.orEmpty()} · 构建 $versionCode")
    Text("应用 ID：${context.packageName}", style = MaterialTheme.typography.bodySmall)
    Text("开源许可", style = MaterialTheme.typography.titleMedium)
    Text("以下展示当前随应用打包的许可原文，不代表已完成全部依赖的许可清单整理。", style = MaterialTheme.typography.bodySmall)
    OutlinedButton(onClick = { license = "licenses/CordiAct-Apache-2.0.txt" }) { Text("CordiAct · Apache 2.0") }
    OutlinedButton(onClick = { license = "licenses/quickjs-ng-MIT.txt" }) { Text("QuickJS-ng · MIT") }
    if (license != null) {
        if (text == null) LinearProgressIndicator(Modifier.fillMaxWidth())
        TextButton(onClick = { license = null }) { Text("收起许可") }
        text?.let { value ->
            SelectionContainer { Text(value, color = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.bodySmall) }
        }
    }
}
