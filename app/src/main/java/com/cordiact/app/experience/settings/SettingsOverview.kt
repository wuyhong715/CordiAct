package com.cordiact.app.experience.settings

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
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
import androidx.compose.ui.unit.dp
import com.cordiact.app.experience.AppGlyph
import com.cordiact.app.experience.GlyphName
import com.cordiact.app.experience.GlyphSize

@Composable
internal fun SettingsOverview(state: SettingsSnapshot, navigate: (SettingsRoute) -> Unit, openLink: (String?) -> Unit) {
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
    OverviewGroup("模型与服务", listOf(
        OverviewEntry("模型提供商", GlyphName.Link, SettingsRoute.PROVIDERS),
        OverviewEntry("模型角色", GlyphName.Sparkle, SettingsRoute.ROLES),
        OverviewEntry("搜索服务", GlyphName.Search, SettingsRoute.SEARCH),
        OverviewEntry("MCP 服务器", GlyphName.Code, SettingsRoute.MCP)), navigate)
    OverviewGroup("连接与执行", listOf(
        OverviewEntry("连接与能力", GlyphName.Link, SettingsRoute.CONNECTIONS),
        OverviewEntry("SSH 主机", GlyphName.Terminal, SettingsRoute.SSH),
        OverviewEntry("特权执行", GlyphName.Bolt, SettingsRoute.PRIVILEGE)), navigate)
    OverviewGroup("安全与权限", listOf(
        OverviewEntry("权限档位", GlyphName.Settings, SettingsRoute.PERMISSIONS),
        OverviewEntry("已存凭据", GlyphName.Pin, SettingsRoute.CREDENTIALS),
        OverviewEntry("审计日志", GlyphName.Trace, SettingsRoute.AUDIT)), navigate)
    OverviewGroup("应用", listOf(
        OverviewEntry("附件限制", GlyphName.File, SettingsRoute.ATTACHMENTS),
        OverviewEntry("关于", GlyphName.More, SettingsRoute.ABOUT)), navigate)
}

private data class OverviewEntry(val title: String, val glyph: GlyphName, val route: SettingsRoute)
@Composable
private fun OverviewGroup(title: String, entries: List<OverviewEntry>, navigate: (SettingsRoute) -> Unit) {
    Text(title, Modifier.padding(start = 8.dp, top = 12.dp, bottom = 2.dp),
        style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surfaceContainerLowest) {
        Column(Modifier.padding(vertical = 4.dp)) {
            entries.forEach { entry -> OverviewRow(entry.title, entry.glyph) { navigate(entry.route) } }
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
        .heightIn(min = 64.dp).padding(horizontal = 18.dp, vertical = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(36.dp), contentAlignment = Alignment.Center) {
            AppGlyph(glyph, tint = tint, size = GlyphSize.Large)
        }
        Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
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
