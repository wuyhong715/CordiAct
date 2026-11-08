package com.cordiact.app.ui.window.trajectory

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Timeline
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.cordiact.app.ui.window.WindowDescriptor

/**
 * 轨迹窗口
 * 
 * 展示多 Agent 与后台任务的时空可视化：
 * - 顶部：时空甘特泳道（主会话/子 Agent/后台 Job 的时间条）
 * - 下部：因果活动树（思考/工具/子任务/后台任务的嵌套展开）
 * - 支持水平捏合缩放、轻触定位
 */
class TrajectoryWindow : WindowDescriptor {
    override val id: String = "trajectory"
    override val title: String = "轨迹"
    override val icon: ImageVector = Icons.Default.Timeline
    
    @Composable
    override fun Badge(sessionId: String) {
        // TODO: 显示后台任务数或子 Agent 数
        val viewModel: TrajectoryViewModel = viewModel()
        val timelineState by viewModel.timelineState.collectAsState()
        
        val runningCount = timelineState.lanes.sumOf { lane ->
            lane.activities.count { it.isRunning }
        }
        
        if (runningCount > 0) {
            Badge(
                containerColor = MaterialTheme.colorScheme.error,
                contentColor = MaterialTheme.colorScheme.onError
            ) {
                Text(
                    text = runningCount.toString(),
                    style = MaterialTheme.typography.labelSmall
                )
            }
        }
    }
    
    @Composable
    override fun Content(
        sessionId: String,
        onNavigateToWindow: (windowId: String) -> Unit
    ) {
        val viewModel: TrajectoryViewModel = viewModel()
        
        val timelineState by viewModel.timelineState.collectAsState()
        val causalTreeState by viewModel.causalTreeState.collectAsState()
        val zoomLevel by viewModel.zoomLevel.collectAsState()
        
        Column(
            modifier = Modifier.fillMaxSize()
        ) {
            // 顶部：时空甘特泳道（180dp 固定高度）
            GanttRibbonView(
                state = timelineState,
                zoomLevel = zoomLevel,
                onZoomChange = { viewModel.setZoom(it) },
                onActivitySelected = { activityId ->
                    viewModel.selectActivity(activityId)
                },
                modifier = Modifier.fillMaxWidth()
            )
            
            Divider(
                modifier = Modifier.fillMaxWidth(),
                thickness = 1.dp,
                color = MaterialTheme.colorScheme.outlineVariant
            )
            
            // 下部：因果活动树（占据剩余空间）
            CausalTreeList(
                state = causalTreeState,
                onActivitySelected = { activityId ->
                    viewModel.selectActivity(activityId)
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            )
        }
    }
}
