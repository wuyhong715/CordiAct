package com.cordiact.app.ui.composer

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Done
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.HourglassBottom
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

/**
 * 动态微胶囊（Floating Activity Pill）
 * 
 * 设计要点：
 * - iOS 动态岛风格（28dp 高度，半透明深色玻璃）
 * - 悬浮在输入栏上方，展示正在运行的长耗时任务、敏感操作审批进度
 * - 停留 3 秒自动渐隐，点击跳转到对应窗口
 * - 支持多种状态：运行中/审批中/已完成/错误
 */
@Composable
fun DynamicActivityPill(
    activityState: ActivityState?,
    onDismiss: () -> Unit,
    onNavigate: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    var visible by remember { mutableStateOf(false) }
    
    // 自动显示/隐藏逻辑
    LaunchedEffect(activityState) {
        if (activityState != null) {
            visible = true
            // 3 秒后自动隐藏（除非是需要确认的状态）
            if (!activityState.requiresInteraction) {
                delay(3000)
                visible = false
                delay(300) // 等待动画完成
                onDismiss()
            }
        } else {
            visible = false
        }
    }
    
    AnimatedVisibility(
        visible = visible && activityState != null,
        enter = slideInVertically(
            initialOffsetY = { it },
            animationSpec = spring(
                dampingRatio = Spring.DampingRatioMediumBouncy,
                stiffness = Spring.StiffnessMedium
            )
        ) + fadeIn(),
        exit = slideOutVertically(
            targetOffsetY = { it },
            animationSpec = spring(stiffness = Spring.StiffnessMedium)
        ) + fadeOut(),
        modifier = modifier
    ) {
        activityState?.let { state ->
            ActivityPillContent(
                state = state,
                onClick = {
                    state.targetWindow?.let { onNavigate(it) }
                    visible = false
                    onDismiss()
                }
            )
        }
    }
}

@Composable
private fun ActivityPillContent(
    state: ActivityState,
    onClick: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
            .height(28.dp)
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.9f),
        tonalElevation = 4.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    MaterialTheme.colorScheme.surface.copy(alpha = 0.3f)
                )
                .padding(horizontal = 12.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 状态图标
            Icon(
                imageVector = state.icon,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = state.tint
            )
            
            // 活动描述（流式更新文本）
            Text(
                text = state.message,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
                maxLines = 1
            )
            
            // 进度指示（可选）
            state.progress?.let { progress ->
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier
                        .width(40.dp)
                        .height(2.dp),
                    color = state.tint,
                    trackColor = MaterialTheme.colorScheme.surfaceVariant
                )
            }
        }
    }
}

/**
 * 活动状态
 * 
 * @param type 活动类型
 * @param message 活动描述
 * @param progress 进度（0.0-1.0），可选
 * @param requiresInteraction 是否需要用户交互（不自动隐藏）
 * @param targetWindow 点击跳转的目标窗口 ID
 */
data class ActivityState(
    val type: ActivityType,
    val message: String,
    val progress: Float? = null,
    val requiresInteraction: Boolean = false,
    val targetWindow: String? = null
) {
    val icon: ImageVector
        get() = when (type) {
            ActivityType.RUNNING -> Icons.Default.HourglassBottom
            ActivityType.APPROVAL -> Icons.Default.HourglassBottom
            ActivityType.COMPLETED -> Icons.Default.Done
            ActivityType.ERROR -> Icons.Default.Error
        }
    
    val tint: androidx.compose.ui.graphics.Color
        @Composable
        get() = when (type) {
            ActivityType.RUNNING -> MaterialTheme.colorScheme.primary
            ActivityType.APPROVAL -> MaterialTheme.colorScheme.tertiary
            ActivityType.COMPLETED -> MaterialTheme.colorScheme.secondary
            ActivityType.ERROR -> MaterialTheme.colorScheme.error
        }
}

enum class ActivityType {
    RUNNING,    // 运行中
    APPROVAL,   // 审批中
    COMPLETED,  // 已完成
    ERROR       // 错误
}
