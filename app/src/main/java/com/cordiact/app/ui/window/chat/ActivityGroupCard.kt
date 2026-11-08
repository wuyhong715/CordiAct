package com.cordiact.app.ui.window.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * 工具活动聚合卡片（Activity Group Card）
 * 
 * 设计要点：
 * - 连续的工具调用（如 3 次文件读取 + 1 次终端编译）聚合为单张卡片
 * - 默认紧凑展示：`⚡ 已检查 3 个配置文件并执行验证命令 (2.4s)  [展开明细 ▾]`
 * - 点击展开：显示微型执行条目（终端卡片/屏幕操作卡片/代码 Diff 卡片）
 * - 按需下钻，不平铺大量技术噪音
 */
@Composable
fun ActivityGroupCard(
    activities: List<ActivityItem>,
    modifier: Modifier = Modifier
) {
    var expanded by remember { mutableStateOf(false) }
    
    val totalDuration = activities.sumOf { it.durationMs } / 1000.0
    val summary = buildActivitySummary(activities)
    
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        tonalElevation = 1.dp
    ) {
        Column(
            modifier = Modifier.padding(12.dp)
        ) {
            // 紧凑汇总（可点击展开/折叠）
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded },
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    modifier = Modifier.weight(1f),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "⚡",
                        style = MaterialTheme.typography.titleMedium
                    )
                    Text(
                        text = "$summary (${String.format("%.1f", totalDuration)}s)",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
                
                Row(
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = if (expanded) "收起" else "展开明细",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Icon(
                        imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                        contentDescription = if (expanded) "收起" else "展开",
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
            }
            
            // 明细列表（展开/折叠）
            AnimatedVisibility(
                visible = expanded,
                enter = expandVertically(
                    animationSpec = spring(
                        dampingRatio = Spring.DampingRatioMediumBouncy,
                        stiffness = Spring.StiffnessMedium
                    )
                ),
                exit = shrinkVertically()
            ) {
                Column(
                    modifier = Modifier.padding(top = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    activities.forEach { activity ->
                        ActivityItemTile(activity = activity)
                    }
                }
            }
        }
    }
}

@Composable
private fun ActivityItemTile(activity: ActivityItem) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 2.dp
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(8.dp)
        ) {
            // 活动类型与耗时
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = activity.type.displayName,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary
                )
                Text(
                    text = "${activity.durationMs}ms",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                )
            }
            
            Spacer(modifier = Modifier.height(4.dp))
            
            // 活动详情
            when (activity) {
                is ActivityItem.FileRead -> {
                    Text(
                        text = "读取: ${activity.path}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f)
                    )
                }
                is ActivityItem.Terminal -> {
                    Text(
                        text = "$ ${activity.command}",
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                        ),
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f)
                    )
                    if (activity.exitCode != 0) {
                        Text(
                            text = "退出码: ${activity.exitCode}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
                is ActivityItem.ScreenAction -> {
                    Text(
                        text = "点击: ${activity.target}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f)
                    )
                }
                is ActivityItem.WebFetch -> {
                    Text(
                        text = "GET ${activity.url}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f)
                    )
                }
            }
        }
    }
}

private fun buildActivitySummary(activities: List<ActivityItem>): String {
    val fileReadCount = activities.count { it is ActivityItem.FileRead }
    val terminalCount = activities.count { it is ActivityItem.Terminal }
    val screenActionCount = activities.count { it is ActivityItem.ScreenAction }
    val webFetchCount = activities.count { it is ActivityItem.WebFetch }
    
    val parts = mutableListOf<String>()
    if (fileReadCount > 0) parts.add("读取 $fileReadCount 个文件")
    if (terminalCount > 0) parts.add("执行 $terminalCount 条命令")
    if (screenActionCount > 0) parts.add("操作屏幕 $screenActionCount 次")
    if (webFetchCount > 0) parts.add("获取 $webFetchCount 个网页")
    
    return if (parts.isEmpty()) {
        "已完成 ${activities.size} 项操作"
    } else {
        "已" + parts.joinToString("、")
    }
}

/**
 * 活动条目
 */
sealed class ActivityItem {
    abstract val durationMs: Long
    abstract val type: ActivityType
    
    data class FileRead(
        override val durationMs: Long,
        val path: String
    ) : ActivityItem() {
        override val type = ActivityType.FILE_READ
    }
    
    data class Terminal(
        override val durationMs: Long,
        val command: String,
        val exitCode: Int
    ) : ActivityItem() {
        override val type = ActivityType.TERMINAL
    }
    
    data class ScreenAction(
        override val durationMs: Long,
        val target: String
    ) : ActivityItem() {
        override val type = ActivityType.SCREEN_ACTION
    }
    
    data class WebFetch(
        override val durationMs: Long,
        val url: String
    ) : ActivityItem() {
        override val type = ActivityType.WEB_FETCH
    }
}

enum class ActivityType(val displayName: String) {
    FILE_READ("文件读取"),
    TERMINAL("终端命令"),
    SCREEN_ACTION("屏幕操作"),
    WEB_FETCH("网页获取")
}
