package com.cordiact.app.ui.window.trajectory

import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 轨迹窗口 ViewModel
 * 
 * 管理时空甘特图和因果树的数据状态
 */
class TrajectoryViewModel : ViewModel() {
    
    private val _timelineState = MutableStateFlow(TimelineState.demo())
    val timelineState: StateFlow<TimelineState> = _timelineState.asStateFlow()
    
    private val _causalTreeState = MutableStateFlow(CausalTreeState.demo())
    val causalTreeState: StateFlow<CausalTreeState> = _causalTreeState.asStateFlow()
    
    private val _zoomLevel = MutableStateFlow(1f) // 1.0 = 正常，0.5 = 缩小，2.0 = 放大
    val zoomLevel: StateFlow<Float> = _zoomLevel.asStateFlow()
    
    fun setZoom(level: Float) {
        _zoomLevel.value = level.coerceIn(0.2f, 5f)
    }
    
    fun selectActivity(activityId: String) {
        _causalTreeState.value = _causalTreeState.value.copy(
            selectedActivityId = activityId
        )
    }
}

/**
 * 时空甘特图状态
 */
data class TimelineState(
    val startTimeMs: Long,
    val endTimeMs: Long,
    val lanes: List<TimelineLane>
) {
    companion object {
        fun demo(): TimelineState {
            val now = System.currentTimeMillis()
            return TimelineState(
                startTimeMs = now - 180_000, // 3 分钟前
                endTimeMs = now,
                lanes = listOf(
                    TimelineLane(
                        id = "main",
                        name = "主会话",
                        activities = listOf(
                            TimelineActivity(
                                id = "main-1",
                                startMs = now - 180_000,
                                endMs = now - 120_000,
                                label = "思考中",
                                type = ActivityType.THINKING
                            ),
                            TimelineActivity(
                                id = "main-2",
                                startMs = now - 120_000,
                                endMs = now - 60_000,
                                label = "执行工具",
                                type = ActivityType.TOOL_CALL
                            ),
                            TimelineActivity(
                                id = "main-3",
                                startMs = now - 60_000,
                                endMs = now,
                                label = "等待响应",
                                type = ActivityType.WAITING,
                                isRunning = true
                            )
                        )
                    ),
                    TimelineLane(
                        id = "subagent",
                        name = "子 Agent",
                        activities = listOf(
                            TimelineActivity(
                                id = "sub-1",
                                startMs = now - 100_000,
                                endMs = now - 40_000,
                                label = "调研员",
                                type = ActivityType.SUBAGENT
                            )
                        )
                    ),
                    TimelineLane(
                        id = "background",
                        name = "后台任务",
                        activities = listOf(
                            TimelineActivity(
                                id = "bg-1",
                                startMs = now - 90_000,
                                endMs = now,
                                label = "SSH 首尔编译",
                                type = ActivityType.BACKGROUND_JOB,
                                isRunning = true,
                                progress = 0.65f
                            )
                        )
                    )
                )
            )
        }
    }
}

data class TimelineLane(
    val id: String,
    val name: String,
    val activities: List<TimelineActivity>
)

data class TimelineActivity(
    val id: String,
    val startMs: Long,
    val endMs: Long,
    val label: String,
    val type: ActivityType,
    val isRunning: Boolean = false,
    val progress: Float? = null
)

enum class ActivityType {
    THINKING,
    TOOL_CALL,
    WAITING,
    SUBAGENT,
    BACKGROUND_JOB
}

/**
 * 因果活动树状态
 */
data class CausalTreeState(
    val turns: List<TurnNode>,
    val selectedActivityId: String? = null
) {
    companion object {
        fun demo(): CausalTreeState {
            return CausalTreeState(
                turns = listOf(
                    TurnNode(
                        id = "turn-3",
                        title = "优化 SSH 隧道与网络连接",
                        children = listOf(
                            ActivityNode.Thinking(
                                id = "think-1",
                                duration = 4.2f,
                                summary = "深度推理隧道参数"
                            ),
                            ActivityNode.ToolGroup(
                                id = "tools-1",
                                count = 3,
                                duration = 1.8f,
                                operations = listOf(
                                    "读取 /workspace/ssh/config",
                                    "写入优化参数",
                                    "验证连接"
                                )
                            ),
                            ActivityNode.SubAgent(
                                id = "sub-1",
                                role = "技术调研员",
                                status = "已完成",
                                deliverable = "JSch 隧道心跳参数配置表"
                            ),
                            ActivityNode.BackgroundJob(
                                id = "job-1",
                                name = "#job-ssh-01",
                                description = "首尔 VPS 测速",
                                status = "RUNNING",
                                duration = 45f,
                                throughput = "12KB/s"
                            )
                        )
                    )
                )
            )
        }
    }
}

data class TurnNode(
    val id: String,
    val title: String,
    val children: List<ActivityNode>
)

sealed class ActivityNode {
    abstract val id: String
    
    data class Thinking(
        override val id: String,
        val duration: Float,
        val summary: String
    ) : ActivityNode()
    
    data class ToolGroup(
        override val id: String,
        val count: Int,
        val duration: Float,
        val operations: List<String>
    ) : ActivityNode()
    
    data class SubAgent(
        override val id: String,
        val role: String,
        val status: String,
        val deliverable: String
    ) : ActivityNode()
    
    data class BackgroundJob(
        override val id: String,
        val name: String,
        val description: String,
        val status: String,
        val duration: Float,
        val throughput: String
    ) : ActivityNode()
}
