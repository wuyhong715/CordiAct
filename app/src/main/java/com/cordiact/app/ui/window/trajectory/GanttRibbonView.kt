package com.cordiact.app.ui.window.trajectory

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.abs

/**
 * 时空甘特泳道（Micro-Gantt Ribbon）
 * 
 * 设计要点：
 * - 分层泳道（Lane 1: 主 Agent / Lane 2: 子 Agent / Lane 3: 后台任务）
 * - 水平捏合手势缩放时间轴密度
 * - 轻触任务条，下方因果树自动高亮定位
 */
@Composable
fun GanttRibbonView(
    state: TimelineState,
    zoomLevel: Float,
    onZoomChange: (Float) -> Unit,
    onActivitySelected: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    var scale by remember { mutableStateOf(1f) }
    var offsetX by remember { mutableStateOf(0f) }
    
    val textMeasurer = rememberTextMeasurer()
    
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .height(180.dp)
            .pointerInput(Unit) {
                detectTransformGestures { _, pan, zoom, _ ->
                    scale = (scale * zoom).coerceIn(0.2f, 5f)
                    offsetX += pan.x
                    onZoomChange(scale)
                }
            },
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        tonalElevation = 1.dp
    ) {
        Column(
            modifier = Modifier.padding(8.dp)
        ) {
            // 标题与时间范围
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "时空甘特泳道",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                )
                Text(
                    text = "缩放: ${String.format("%.1f", scale)}x",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                )
            }
            
            Spacer(modifier = Modifier.height(8.dp))
            
            // 甘特图画布
            Canvas(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            ) {
                val totalDuration = (state.endTimeMs - state.startTimeMs).toFloat()
                val laneHeight = size.height / state.lanes.size
                
                state.lanes.forEachIndexed { laneIndex, lane ->
                    val laneY = laneIndex * laneHeight
                    
                    // 绘制泳道背景
                    drawRect(
                        color = if (laneIndex % 2 == 0) 
                            Color(0xFF1E1E1E) 
                        else 
                            Color(0xFF252525),
                        topLeft = Offset(0f, laneY),
                        size = Size(size.width, laneHeight)
                    )
                    
                    // 绘制泳道名称
                    drawText(
                        textMeasurer = textMeasurer,
                        text = lane.name,
                        topLeft = Offset(8f, laneY + 8f),
                        style = TextStyle(
                            color = Color.White.copy(alpha = 0.6f),
                            fontSize = 11.sp
                        )
                    )
                    
                    // 绘制活动条
                    lane.activities.forEach { activity ->
                        val startX = ((activity.startMs - state.startTimeMs) / totalDuration) * 
                                    size.width * scale + offsetX
                        val width = ((activity.endMs - activity.startMs) / totalDuration) * 
                                   size.width * scale
                        
                        if (startX + width > 0 && startX < size.width) {
                            drawActivityBar(
                                activity = activity,
                                x = startX.coerceAtLeast(0f),
                                y = laneY + 32f,
                                width = width.coerceAtMost(size.width - startX.coerceAtLeast(0f)),
                                height = laneHeight - 40f,
                                textMeasurer = textMeasurer
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun DrawScope.drawActivityBar(
    activity: TimelineActivity,
    x: Float,
    y: Float,
    width: Float,
    height: Float,
    textMeasurer: androidx.compose.ui.text.TextMeasurer
) {
    val color = when (activity.type) {
        ActivityType.THINKING -> Color(0xFF9C27B0)
        ActivityType.TOOL_CALL -> Color(0xFF2196F3)
        ActivityType.WAITING -> Color(0xFFFF9800)
        ActivityType.SUBAGENT -> Color(0xFF4CAF50)
        ActivityType.BACKGROUND_JOB -> Color(0xFFF44336)
    }
    
    // 绘制活动条
    drawRoundRect(
        color = color.copy(alpha = if (activity.isRunning) 0.8f else 0.6f),
        topLeft = Offset(x, y),
        size = Size(width, height),
        cornerRadius = CornerRadius(4f, 4f)
    )
    
    // 进度条（如果有）
    activity.progress?.let { progress ->
        drawRoundRect(
            color = color,
            topLeft = Offset(x, y),
            size = Size(width * progress, height),
            cornerRadius = CornerRadius(4f, 4f)
        )
    }
    
    // 绘制标签（如果宽度足够）
    if (width > 40f) {
        drawText(
            textMeasurer = textMeasurer,
            text = activity.label,
            topLeft = Offset(x + 6f, y + height / 2 - 6f),
            style = TextStyle(
                color = Color.White,
                fontSize = 10.sp
            )
        )
    }
}
