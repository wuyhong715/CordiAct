package com.cordiact.app.experience.settings

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.background
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.cordiact.app.experience.AppGlyph
import com.cordiact.app.experience.GlyphName
import com.cordiact.app.experience.GlyphSize

@Composable
internal fun SettingsOverview(state: SettingsSnapshot, navigate: (SettingsRoute) -> Unit, openLink: (String?) -> Unit, selectedRoute: SettingsRoute? = null, compact: Boolean = false) {
    val actions = remember(state.readiness) { settingsActions(state.readiness) }
    var expanded by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(actions.isEmpty()) { if (actions.isEmpty()) expanded = false }
    if (actions.isNotEmpty()) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("待处理 · ${actions.size}", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
            if (expanded && actions.size > 1) TextButton({ expanded = false }) { Text("收起") }
        }
        Column(Modifier.fillMaxWidth().animateContentSize(tween(250)), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (expanded || actions.size == 1) {
                actions.forEach { action -> key(action.key) {
                    Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceContainerLowest) {
                        OverviewRow(action.title, actionGlyph(action), action.urgent) { openLink(action.route) }
                    }
                } }
            } else {
                Box(Modifier.fillMaxWidth().padding(bottom = 14.dp)) {
                    Box(Modifier.matchParentSize().offset(y = 14.dp).padding(horizontal = 20.dp)
                        .background(MaterialTheme.colorScheme.surfaceContainerHighest, RoundedCornerShape(20.dp)))
                    Box(Modifier.matchParentSize().offset(y = 7.dp).padding(horizontal = 10.dp)
                        .background(MaterialTheme.colorScheme.surfaceContainer, RoundedCornerShape(20.dp)))
                    Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceContainerLowest) {
                        OverviewRow(actions.first().title, actionGlyph(actions.first()), actions.first().urgent,
                            expand = true, count = actions.size) { expanded = true }
                    }
                }
            }
        }
    }
    settingsDirectory.forEach { group -> OverviewGroup(group.title, group.entries, navigate, selectedRoute, compact) }
}

internal data class OverviewEntry(val title: String, val glyph: GlyphName, val route: SettingsRoute)
internal data class OverviewCategory(val title: String, val entries: List<OverviewEntry>)
internal val settingsCatalog = listOf(
    OverviewCategory("工作区", listOf(OverviewEntry("项目指令", GlyphName.File, SettingsRoute.PROJECT_INSTRUCTIONS),
        OverviewEntry("提供商声明", GlyphName.File, SettingsRoute.PROVIDER_DRAFTS),
        OverviewEntry("MCP 声明", GlyphName.File, SettingsRoute.MCP_DRAFTS),
        OverviewEntry("工具策略", GlyphName.File, SettingsRoute.WORKSPACE_TOOLS))),
    OverviewCategory("Agent", listOf(OverviewEntry("Agent 预设", GlyphName.AgentPreset, SettingsRoute.AGENT_PRESETS))),
    OverviewCategory("输入", listOf(OverviewEntry("预设变量", GlyphName.Code, SettingsRoute.INPUT_VARIABLES))),
    OverviewCategory("模型与服务", listOf(
        OverviewEntry("模型提供商", GlyphName.Link, SettingsRoute.PROVIDERS),
        OverviewEntry("模型角色", GlyphName.Sparkle, SettingsRoute.ROLES),
        OverviewEntry("搜索服务", GlyphName.Search, SettingsRoute.SEARCH),
        OverviewEntry("MCP 服务器", GlyphName.Code, SettingsRoute.MCP),
        OverviewEntry("用量与统计", GlyphName.Trace, SettingsRoute.USAGE))),
    OverviewCategory("连接与执行", listOf(
        OverviewEntry("连接与能力", GlyphName.Link, SettingsRoute.CONNECTIONS),
        OverviewEntry("SSH 主机", GlyphName.Terminal, SettingsRoute.SSH),
        OverviewEntry("自动化任务", GlyphName.Bolt, SettingsRoute.TASKS),
        OverviewEntry("特权执行", GlyphName.Bolt, SettingsRoute.PRIVILEGE))),
    OverviewCategory("安全与权限", listOf(
        OverviewEntry("权限档位", GlyphName.Settings, SettingsRoute.PERMISSIONS),
        OverviewEntry("已存凭据", GlyphName.Pin, SettingsRoute.CREDENTIALS),
        OverviewEntry("审计日志", GlyphName.Trace, SettingsRoute.AUDIT))),
    OverviewCategory("应用", listOf(
        OverviewEntry("附件限制", GlyphName.File, SettingsRoute.ATTACHMENTS),
        OverviewEntry("关于", GlyphName.More, SettingsRoute.ABOUT))),
)
internal val settingsDirectory = listOf(
    OverviewCategory("工作区设置", listOf(
        OverviewEntry("工作区默认", GlyphName.Folder, SettingsRoute.WORKSPACE_DEFAULTS),
        OverviewEntry("模型与提供商", GlyphName.Sparkle, SettingsRoute.MODELS),
        OverviewEntry("插件与连接", GlyphName.Link, SettingsRoute.PLUGINS),
        OverviewEntry("权限与安全", GlyphName.Settings, SettingsRoute.SECURITY),
    )),
    OverviewCategory("应用", listOf(
        OverviewEntry("应用偏好", GlyphName.File, SettingsRoute.APP_PREFERENCES),
        OverviewEntry("关于", GlyphName.More, SettingsRoute.ABOUT),
    )),
)

internal fun settingsDirectoryRoute(route: SettingsRoute): SettingsRoute = when (route) {
    SettingsRoute.AGENT_PRESETS, SettingsRoute.AGENT_PRESET_EDITOR, SettingsRoute.PROJECT_INSTRUCTIONS -> SettingsRoute.WORKSPACE_DEFAULTS
    SettingsRoute.PROVIDER_DRAFTS, SettingsRoute.PROVIDERS, SettingsRoute.ADD, SettingsRoute.DETAIL, SettingsRoute.ROLES -> SettingsRoute.MODELS
    SettingsRoute.WORKSPACE_TOOLS, SettingsRoute.MCP_DRAFTS, SettingsRoute.CONNECTIONS, SettingsRoute.MCP, SettingsRoute.SSH, SettingsRoute.SEARCH -> SettingsRoute.PLUGINS
    SettingsRoute.PERMISSIONS, SettingsRoute.PRIVILEGE, SettingsRoute.CREDENTIALS, SettingsRoute.AUDIT -> SettingsRoute.SECURITY
    SettingsRoute.ATTACHMENTS, SettingsRoute.INPUT_VARIABLES, SettingsRoute.USAGE, SettingsRoute.TASKS -> SettingsRoute.APP_PREFERENCES
    else -> route
}

@Composable
internal fun SettingsBranch(route: SettingsRoute, navigate: (SettingsRoute) -> Unit) {
    val groups = when (route) {
        SettingsRoute.MODELS -> listOf(
            "当前工作区" to listOf(
                Triple(SettingsRoute.WORKSPACE_DEFAULTS, "新会话默认模型", "查看有效配置和继承来源"),
                Triple(SettingsRoute.PROVIDER_DRAFTS, "提供商声明", "查看 Agent 或用户保存的离线声明"),
            ),
            "此客户端" to listOf(
                Triple(SettingsRoute.PROVIDERS, "提供商", "管理连接、凭据状态、模型和能力声明"),
                Triple(SettingsRoute.ROLES, "模型角色", "辅助模型等客户端设置"),
            ),
        )
        SettingsRoute.PLUGINS -> listOf("插件与连接" to listOf(
            Triple(SettingsRoute.MCP_DRAFTS, "MCP 声明", "当前工作区 · 不联网、不启用"),
            Triple(SettingsRoute.MCP, "MCP", "此客户端 · 传统连接管理"),
            Triple(SettingsRoute.SEARCH, "搜索", "客户端服务与工作区搜索策略"),
            Triple(SettingsRoute.SSH, "SSH", "客户端主机库与连接检查"),
            Triple(SettingsRoute.WORKSPACE_TOOLS, "工具策略", "当前工作区 · 限制工具和搜索"),
            Triple(SettingsRoute.CONNECTIONS, "连接与能力", "管理已授权的客户端能力"),
        ))
        SettingsRoute.SECURITY -> listOf(
            "权限" to listOf(Triple(SettingsRoute.PERMISSIONS, "当前权限", "访问范围与审批档位")),
            "此客户端" to listOf(
                Triple(SettingsRoute.CREDENTIALS, "凭据库", "授权访问客户端安全存储"),
                Triple(SettingsRoute.AUDIT, "审计日志", "查看操作记录"),
                Triple(SettingsRoute.PRIVILEGE, "设备授权", "查看设备权限与执行后端"),
            ),
        )
        SettingsRoute.APP_PREFERENCES -> listOf("应用" to listOf(
            Triple(SettingsRoute.INPUT_VARIABLES, "预设变量", "输入与快捷变量"),
            Triple(SettingsRoute.ATTACHMENTS, "附件限制", "大小、数量和文件类型"),
            Triple(SettingsRoute.USAGE, "用量与统计", "调用、模型及令牌统计"),
            Triple(SettingsRoute.TASKS, "自动化任务", "查看工作区中的任务"),
        ))
        else -> emptyList()
    }
    for ((label, entries) in groups) {
        Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        com.cordiact.app.experience.settings.components.SettingsGroup {
            entries.forEach { (target, title, subtitle) ->
                com.cordiact.app.experience.settings.components.SettingsRow(title, subtitle, GlyphName.More) { navigate(target) }
            }
        }
    }
}

@Composable
private fun OverviewGroup(title: String, entries: List<OverviewEntry>, navigate: (SettingsRoute) -> Unit,
                          selectedRoute: SettingsRoute?, compact: Boolean) {
    Text(title, Modifier.padding(start = 8.dp, top = 12.dp, bottom = 2.dp),
        style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
    if (compact) {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            entries.forEach { entry ->
                val selected = selectedRoute == entry.route
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                    .background(if (selected) MaterialTheme.colorScheme.primaryContainer else androidx.compose.ui.graphics.Color.Transparent)
                    .selectable(selected, role = Role.Tab) { navigate(entry.route) }
                    .heightIn(min = 48.dp).padding(horizontal = 10.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    AppGlyph(entry.glyph, tint = MaterialTheme.colorScheme.primary, size = GlyphSize.Small)
                    Text(entry.title, style = MaterialTheme.typography.bodyMedium, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal)
                }
            }
        }
        return
    }
    val outline = MaterialTheme.colorScheme.outlineVariant
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(26.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLowest,
        border = BorderStroke(1.dp, outline),
    ) {
        // One shared outline; dividers meet it without double borders or rounded rows.
        Column {
            entries.forEachIndexed { index, entry ->
                if (index > 0) HorizontalDivider(thickness = 1.dp, color = outline)
                OverviewRow(entry.title, entry.glyph) { navigate(entry.route) }
            }
        }
    }
}

@Composable
private fun OverviewRow(title: String, glyph: GlyphName, urgent: Boolean = false,
                        expand: Boolean = false, count: Int = 0, onClick: () -> Unit) {
    val tint = if (urgent) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
    Row(Modifier.fillMaxWidth().semantics(mergeDescendants = true) {
        if (expand) { stateDescription = "已收起，共 $count 项" }
    }.clickable(role = Role.Button, onClickLabel = if (expand) "展开待处理项" else "打开$title", onClick = onClick)
        .heightIn(min = 64.dp).padding(horizontal = 18.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(36.dp), contentAlignment = Alignment.Center) {
            AppGlyph(glyph, tint = tint, size = GlyphSize.Large)
        }
        Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Medium)
        AppGlyph(if (expand) GlyphName.ChevronDown else GlyphName.Back,
            modifier = if (expand) Modifier else Modifier.rotate(180f),
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = .55f), size = GlyphSize.Small)
    }
}

private fun actionGlyph(action: SettingsAction): GlyphName = when {
    "search" in action.route -> GlyphName.Search
    "ssh" in action.route || "termux" in action.route -> GlyphName.Terminal
    "privilege" in action.route -> GlyphName.Bolt
    "screen" in action.route -> GlyphName.Preview
    "browser" in action.route -> GlyphName.Globe
    else -> GlyphName.Settings
}
