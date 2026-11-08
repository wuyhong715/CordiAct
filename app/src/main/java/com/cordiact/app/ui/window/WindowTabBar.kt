package com.cordiact.app.ui.window

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp

/**
 * 顶部窗口 Tab 栏
 * 
 * 设计要点：
 * - 分段微胶囊风格（36dp 高度，半透明毛玻璃）
 * - 活动指示器带弹簧物理动效（拉伸-回弹）
 * - 每个 Tab 可显示自定义角标（红点/计数药丸）
 */
@Composable
fun WindowTabBar(
    windows: List<WindowDescriptor>,
    selectedWindowId: String,
    sessionId: String,
    onSelectWindow: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .height(48.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow.copy(alpha = 0.85f),
        tonalElevation = 2.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 12.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            windows.forEach { window ->
                val isSelected = window.id == selectedWindowId
                WindowTab(
                    window = window,
                    isSelected = isSelected,
                    sessionId = sessionId,
                    onClick = { onSelectWindow(window.id) },
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

@Composable
private fun WindowTab(
    window: WindowDescriptor,
    isSelected: Boolean,
    sessionId: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    // 弹簧缩放动效（选中时微微放大）
    val scale by animateFloatAsState(
        targetValue = if (isSelected) 1.05f else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMedium
        ),
        label = "tab_scale"
    )
    
    val alpha by animateFloatAsState(
        targetValue = if (isSelected) 1f else 0.6f,
        animationSpec = spring(stiffness = Spring.StiffnessMedium),
        label = "tab_alpha"
    )
    
    Box(
        modifier = modifier
            .height(36.dp)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .clip(RoundedCornerShape(18.dp))
            .background(
                if (isSelected) 
                    MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.9f)
                else 
                    MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            window.icon?.let {
                Icon(
                    imageVector = it,
                    contentDescription = null,
                    modifier = Modifier
                        .size(16.dp)
                        .alpha(alpha),
                    tint = if (isSelected) 
                        MaterialTheme.colorScheme.onPrimaryContainer
                    else 
                        MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            
            Text(
                text = window.title,
                style = MaterialTheme.typography.labelMedium,
                color = if (isSelected) 
                    MaterialTheme.colorScheme.onPrimaryContainer
                else 
                    MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.alpha(alpha)
            )
            
            // 自定义角标（例如未读数、任务计数）
            Box {
                window.Badge(sessionId)
            }
        }
    }
}
