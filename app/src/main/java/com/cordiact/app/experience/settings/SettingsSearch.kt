package com.cordiact.app.experience.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.cordiact.app.experience.AppGlyph
import com.cordiact.app.experience.FrostedSurface
import com.cordiact.app.experience.GlyphName
import com.cordiact.app.experience.settings.components.SettingsGroup
import com.cordiact.app.experience.settings.components.SettingsRow
import java.util.Locale

internal data class SettingsSearchHit(val entry: OverviewEntry, val path: String, val section: String? = null, val field: String? = null)

private val aliases = mapOf(
    SettingsRoute.USAGE to "token 令牌 费用 计费 调用次数 输入输出 使用量", SettingsRoute.PROVIDERS to "api 模型地址 供应商",
    SettingsRoute.PERMISSIONS to "审批 沙箱 安全", SettingsRoute.CONNECTIONS to "代理 网络 Shizuku Termux 无障碍",
    SettingsRoute.SEARCH to "联网 网页 web 搜索引擎", SettingsRoute.CREDENTIALS to "密钥 Key 密码 凭证",
    SettingsRoute.MCP to "插件工具 工具服务器", SettingsRoute.SSH to "远程 终端 主机",
    SettingsRoute.ATTACHMENTS to "上传 图片 文件 大小", SettingsRoute.TASKS to "定时 计划 后台",
)

internal fun searchSettings(query: String): List<SettingsSearchHit> {
    val terms = query.trim().lowercase(Locale.ROOT).split(Regex("\\s+")).filter(String::isNotBlank)
    if (terms.isEmpty()) return emptyList()
    return settingsCatalog.flatMap { group -> group.entries.flatMap { entry ->
        val base = "${group.title} ${entry.title} ${aliases[entry.route].orEmpty()}".lowercase(Locale.ROOT)
        buildList {
            if (terms.all(base::contains)) add(SettingsSearchHit(entry, group.title))
            if (entry.route == SettingsRoute.USAGE) listOf(
                Triple("usage-overview", "调用概览", "输入 输出 token 令牌 调用次数 失败 取消"),
                Triple("usage-models", "按模型", "提供商 模型 汇总"),
                Triple("usage-recent", "最近调用", "历史 明细 查询时间"),
            ).forEach { (id, label, keywords) ->
                val match = "$label $keywords".lowercase()
                if (terms.all(match::contains)) add(SettingsSearchHit(entry.copy(title = label), "${group.title} › ${entry.title}", field = id))
            }
            settingsSections(entry.route).forEach { sec ->
                val label = "${sec.label} ${sec.id}".lowercase(Locale.ROOT)
                if (terms.all { it in base || it in label } && terms.any { it in label }) {
                    add(SettingsSearchHit(entry.copy(title = sec.label), "${group.title} › ${entry.title}", sec.id))
                }
            }
        }
    } }.sortedBy { hit ->
        val title = hit.entry.title.lowercase(Locale.ROOT)
        when { title == terms.joinToString(" ") -> 0; terms.all(title::contains) -> 1; else -> 2 }
    }
}

@Composable
internal fun SettingsSearchResults(query: String, open: (SettingsSearchHit) -> Unit) {
    val results = remember(query) { searchSettings(query) }
    SettingsSectionLabelOrEmpty(results.isEmpty())
    if (results.isNotEmpty()) SettingsGroup {
        results.forEach { result -> SettingsRow(result.entry.title, result.path, result.entry.glyph) { open(result) } }
    }
}

@Composable
private fun SettingsSectionLabelOrEmpty(empty: Boolean) {
    if (empty) Text("没有匹配的设置。试试更短的关键词。", color = MaterialTheme.colorScheme.onSurfaceVariant)
    else Text("设置搜索结果", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
internal fun SettingsSearchBar(
    active: Boolean, query: String, focus: FocusRequester, requestFocus: Boolean, onFocused: () -> Unit,
    activate: () -> Unit, edit: (String) -> Unit, dismiss: () -> Unit, modifier: Modifier,
) {
    val keyboard = LocalSoftwareKeyboardController.current
    FrostedSurface(modifier, radius = 24.dp) {
        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp)
            .then(if (!active) Modifier.clickable(onClick = activate) else Modifier)
            .padding(start = 16.dp, end = if (active) 4.dp else 16.dp), verticalAlignment = Alignment.CenterVertically) {
            val color = MaterialTheme.colorScheme.onSurfaceVariant
            AppGlyph(GlyphName.Search, tint = color, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(10.dp))
            if (active) {
                BasicTextField(query, edit, modifier = Modifier.weight(1f).focusRequester(focus)
                    .semantics { contentDescription = "搜索设置" }, singleLine = true,
                    textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { keyboard?.hide() }),
                    decorationBox = { field -> Box { if (query.isEmpty()) Text("搜索设置", color = color); field() } })
                TextButton(onClick = { if (query.isBlank()) dismiss() else edit("") }) { Text(if (query.isBlank()) "取消" else "清空") }
                LaunchedEffect(requestFocus) {
                    if (requestFocus) { focus.requestFocus(); keyboard?.show(); onFocused() }
                }
            } else Text("搜索设置", color = color, style = MaterialTheme.typography.bodyLarge)
        }
    }
}
