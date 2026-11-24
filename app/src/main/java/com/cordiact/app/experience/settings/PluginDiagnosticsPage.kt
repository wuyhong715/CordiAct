package com.cordiact.app.experience.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.cordiact.app.experience.settings.components.SettingsCard
import com.cordiact.core.plugin.ExtensionPackageArtifact
import com.cordiact.core.plugin.PluginDiagnosticSnapshot
import com.cordiact.core.plugin.PluginInstanceState
import com.cordiact.core.plugin.SignedCatalogDiagnosticState
import com.cordiact.core.plugin.SignedReleaseDiagnostic
import com.cordiact.core.plugin.WorkspacePackageDiagnostics
import com.cordiact.core.plugin.WorkspacePackageRuntime
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Scope selection is owned by SettingsHost; this page can only read one already-selected workspace. */
@Composable
internal fun PluginDiagnosticsPage(facade: SettingsFacade, workspace: SecurityWorkspace?, scopeBusy: Boolean) {
    Text("只读快照 · 不启动插件或检测连接", style = MaterialTheme.typography.labelLarge)
    Text("本页不读取会话试用或 MCP。", style = MaterialTheme.typography.bodySmall)
    if (scopeBusy) {
        LinearProgressIndicator(Modifier.fillMaxWidth())
        return
    }
    if (workspace == null) {
        Text("所选工作区不可用，请重新选择。")
        return
    }
    // The old state is not displayed even for one frame after a workspace or refresh change.
    var refresh by remember(facade, workspace.id) { mutableIntStateOf(0) }
    var reading by remember(facade, workspace.id, refresh) { mutableStateOf(true) }
    var snapshot by remember(facade, workspace.id, refresh) { mutableStateOf<PluginDiagnosticSnapshot?>(null) }
    LaunchedEffect(facade, workspace.id, refresh) {
        val result = try { facade.pluginDiagnostics(workspace.id) } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) { null }
        currentCoroutineContext().ensureActive()
        snapshot = result?.takeIf { it.workspaceId == workspace.id }
        reading = false
    }
    Text("工作区 · ${workspace.name}", style = MaterialTheme.typography.titleMedium)
    TextButton(enabled = !reading, onClick = { refresh++ }) { Text("刷新诊断") }
    if (reading) LinearProgressIndicator(Modifier.fillMaxWidth())
    else snapshot?.let { DiagnosticsContent(it) } ?: Text("诊断不可用。请检查当前工作区后刷新。")
}

@Composable
private fun DiagnosticsContent(snapshot: PluginDiagnosticSnapshot) {
    Text("签名目录观测：${snapshot.signedCatalog.label()}", style = MaterialTheme.typography.titleMedium)
    snapshot.observedRootRevision?.let { Text("已观测根代数：$it", style = MaterialTheme.typography.bodySmall) }
    Text("签名目录仅显示进程快照，不检查检查点、网络或当前授权。", style = MaterialTheme.typography.bodySmall)
    Text("已安装的工作区插件包", style = MaterialTheme.typography.titleMedium)
    when (val packages = snapshot.packages) {
        WorkspacePackageDiagnostics.StorageNotCreated -> Text("插件包存储尚未建立；诊断未创建存储。")
        WorkspacePackageDiagnostics.StorageUnavailable -> Text("当前插件包存储无法读取；不代表没有安装。")
        is WorkspacePackageDiagnostics.Entries -> {
            if (packages.items.isEmpty()) Text("此工作区没有已安装插件包。")
            packages.items.forEach { item ->
                val receipt = item.sourceReceipt
                DiagnosticCard(item.id, "v${item.version} · ${when (item.format) {
                    ExtensionPackageArtifact.Format.DECLARATIVE -> "声明包"
                    ExtensionPackageArtifact.Format.LEGACY_AUTHORED -> "已保存试用转包"
                }}", "启用意图：${if (item.enableIntent) "启用" else "未启用"}", "进程最近状态：${item.runtime.label()}",
                    "安装时批准的贡献：${item.approvedContributions.joinToString { it.wireName }.ifEmpty { "无" }}",
                    "回滚版本：${if (item.hasRollback) "有" else "无"}",
                    receipt?.let { "安装来源（历史）：${it.sourceId} · ${it.publisherId}" } ?: "安装来源：无签名收据",
                    receipt?.let { "已安装版本的目录观测：${item.signedRelease.label()}" })
            }
            if (packages.items.isNotEmpty()) Text("安装批准不代表当前可用、工作区授权或系统权限。", style = MaterialTheme.typography.bodySmall)
        }
    }
    Text("APK 内置插件 · 客户端级", style = MaterialTheme.typography.titleMedium)
    if (snapshot.bundled.isEmpty()) Text("未取得 APK 内置清单。")
    snapshot.bundled.forEach { item ->
        DiagnosticCard(item.id, "v${item.version} · APK 随包", "进程运行：${item.runtime.label()}")
    }
    Text("仅展示内置目录和进程观测，不读取全局启用记录。内置状态不代表工作区授权或连通性。",
        style = MaterialTheme.typography.bodySmall)
}

@Composable
private fun DiagnosticCard(id: String, vararg details: String?) {
    SettingsCard(modifier = Modifier.testTag("plugin-diagnostic:$id")) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(id, style = MaterialTheme.typography.titleSmall)
            details.forEach { if (it != null) Text(it, style = MaterialTheme.typography.bodySmall) }
        }
    }
}

private fun SignedCatalogDiagnosticState?.label(): String = when (this) {
    SignedCatalogDiagnosticState.NOT_CONFIGURED -> "此 APK 未配置可信来源"
    SignedCatalogDiagnosticState.INVALID_POLICY -> "APK 内置策略无效"
    SignedCatalogDiagnosticState.NOT_OPENED -> "本进程尚未打开目录"
    SignedCatalogDiagnosticState.RETIRED -> "本进程目录已失效"
    SignedCatalogDiagnosticState.NOT_REFRESHED -> "尚无已验签的根清单"
    SignedCatalogDiagnosticState.EXPIRED -> "已观测的根清单已过期"
    SignedCatalogDiagnosticState.OBSERVED -> "有已验签的根清单快照"
    null -> "未观测"
}

private fun SignedReleaseDiagnostic?.label(): String = when (this) {
    SignedReleaseDiagnostic.CATALOG_UNAVAILABLE -> "目录快照不可用"
    SignedReleaseDiagnostic.ROOT_NOT_REFRESHED -> "尚无已验签的根清单"
    SignedReleaseDiagnostic.ROOT_EXPIRED -> "已观测的根清单已过期"
    SignedReleaseDiagnostic.PUBLISHER_NOT_GRANTED -> "该发布者不在观测到的根清单中"
    SignedReleaseDiagnostic.INDEX_NOT_REFRESHED -> "尚无该发布者的已验签索引"
    SignedReleaseDiagnostic.INDEX_EXPIRED -> "已观测的来源索引已过期"
    SignedReleaseDiagnostic.RELEASE_NOT_LISTED -> "未找到与安装记录匹配的版本和摘要"
    SignedReleaseDiagnostic.RELEASE_MATCHES -> "版本和摘要与快照匹配"
    null -> "未观测"
}

private fun WorkspacePackageRuntime.Status?.label(): String = when (this) {
    WorkspacePackageRuntime.Status.INACTIVE -> "未运行"
    WorkspacePackageRuntime.Status.STARTING -> "启动中"
    WorkspacePackageRuntime.Status.ACTIVE -> "运行中"
    WorkspacePackageRuntime.Status.FAILED -> "启动失败"
    WorkspacePackageRuntime.Status.CLEANUP_PENDING -> "已撤回，待清理"
    WorkspacePackageRuntime.Status.TRUST_WITHDRAWN -> "签名依据已失效，已撤回"
    null -> "未观测"
}

private fun PluginInstanceState?.label(): String = when (this) {
    PluginInstanceState.DISABLED -> "未运行"
    PluginInstanceState.WAITING -> "等待依赖"
    PluginInstanceState.STARTING -> "启动中"
    PluginInstanceState.ACTIVE -> "运行中"
    PluginInstanceState.FAILED -> "启动失败"
    null -> "未观测"
}
