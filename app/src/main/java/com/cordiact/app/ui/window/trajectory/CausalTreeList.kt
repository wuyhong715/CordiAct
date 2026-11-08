package com.cordiact.app.ui.window.trajectory

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp

/**
 * 因果活动树（Causal Activity Tree）
 * 
 * 设计要点：
 * - 按照自然因果关系展开
 * - 分支连线节点（思考/工具/子 Agent/后台任务）
 * - 支持折叠/展开，点击定位到甘特图
 */
@Composable
fun CausalTreeList(
    state: CausalTreeState,
    onActivitySelected: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.surface
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            items(state.turns, key = { it.id }) { turn ->
                TurnCard(
                    turn = turn,
                    selectedActivityId = state.selectedActivityId,
                    onActivitySelected = onActivitySelected
                )
            }
        }
    }
}

@Composable
private fun TurnCard(
    turn: TurnNode,
    selectedActivityId: String?,
    onActivitySelected: (String) -> Unit
) {
    var expanded by remember { mutableStateOf(true) }
    
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        tonalElevation = 2.dp
    ) {
        Column(
            modifier = Modifier.padding(12.dp)
        ) {
            // Turn 标题（可折叠）
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded },
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = if (expanded) Icons.Default.ExpandMore else Icons.Default.ChevronRight,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = turn.title,
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
                Text(
                    text = "${turn.children.size} 个活动",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                )
            }
            
            // 活动列表（折叠/展开）
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
                    modifier = Modifier.padding(start = 28.dp, top = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    turn.children.forEach { activity ->
                        ActivityNodeCard(
                            node = activity,
                            isSelected = activity.id == selectedActivityId,
                            onClick = { onActivitySelected(activity.id) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ActivityNodeCard(
    node: ActivityNode,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(8.dp),
        color = if (isSelected) 
            MaterialTheme.colorScheme.primaryContainer
        else 
            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        tonalElevation = if (isSelected) 4.dp else 0.dp
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 节点图标
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .clip(CircleShape)
                    .background(node.color),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = node.icon,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.onPrimary
                )
            }
            
            // 节点内容
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                when (node) {
                    is ActivityNode.Thinking -> {
                        Text(
                            text = "🧠 深度思考 (${node.duration}s)",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = node.summary,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                        )
                    }
                    is ActivityNode.ToolGroup -> {
                        Text(
                            text = "⚡ 连续操作 (${node.count} 步，${node.duration}s)",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        node.operations.forEach { op ->
                            Text(
                                text = "  • $op",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                            )
                        }
                    }
                    is ActivityNode.SubAgent -> {
                        Text(
                            text = "🌿 委派子任务: ${node.role} (${node.status})",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = "交付物: ${node.deliverable}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                        )
                    }
                    is ActivityNode.BackgroundJob -> {
                        Text(
                            text = "🚀 触发后台 Job: ${node.name}",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = "${node.description} · ${node.status}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                        )
                        Text(
                            text = "已运行 ${node.duration.toInt()}s，吞吐 ${node.throughput}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                        )
                    }
                }
            }
        }
    }
}

private val ActivityNode.icon: ImageVector
    get() = when (this) {
        is ActivityNode.Thinking -> Icons.Default.Lightbulb
        is ActivityNode.ToolGroup -> Icons.Default.Build
        is ActivityNode.SubAgent -> Icons.Default.Group
        is ActivityNode.BackgroundJob -> Icons.Default.CloudQueue
    }

private val ActivityNode.color: androidx.compose.ui.graphics.Color
    @Composable
    get() = when (this) {
        is ActivityNode.Thinking -> androidx.compose.ui.graphics.Color(0xFF9C27B0)
        is ActivityNode.ToolGroup -> androidx.compose.ui.graphics.Color(0xFF2196F3)
        is ActivityNode.SubAgent -> androidx.compose.ui.graphics.Color(0xFF4CAF50)
        is ActivityNode.BackgroundJob -> androidx.compose.ui.graphics.Color(0xFFF44336)
    }
