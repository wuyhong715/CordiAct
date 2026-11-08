package com.cordiact.app.ui.window

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * 窗口描述契约
 * 
 * 每个窗口提供：
 * - 唯一 ID、标题、图标
 * - 自定义角标（显示未读计数、后台任务数等）
 * - 内容渲染
 */
interface WindowDescriptor {
    val id: String
    val title: String
    val icon: ImageVector?
    
    /**
     * 自定义角标（可选）
     * 例如：对话窗口显示未读消息数，轨迹窗口显示运行中的任务数
     */
    @Composable
    fun Badge(sessionId: String) {
        // 默认无角标
    }
    
    /**
     * 窗口内容
     * @param sessionId 当前会话 ID
     * @param onNavigateToWindow 切换到指定窗口的回调
     */
    @Composable
    fun Content(
        sessionId: String,
        onNavigateToWindow: (windowId: String) -> Unit
    )
}
