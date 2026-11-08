package com.cordiact.app.ui.window.chat

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.*
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * 智能贴底滚动控制器（Smart Pinned Scroll）
 *
 * 设计要点：
 * - 当模型正在高频流式输出字符或步骤更新时，列表紧贴底部
 * - 用户只要手指往上滑哪怕 10 个像素，贴底锁定立即解除
 * - 底部右下角出现一个带未读行数角标的悬浮小药丸 `↓ 3条新动态`，轻点一下瞬间平滑贴底
 */
class SmartScrollController(
    private val listState: LazyListState
) {
    private val scope = CoroutineScope(Dispatchers.Main)

    var isPinned by mutableStateOf(true)
        private set

    var unreadCount by mutableStateOf(0)
        private set

    private var lastItemCount = 0

    /**
     * 监听列表项数变化，自动贴底（如果未解除贴底锁定）
     */
    fun onItemsChanged(newItemCount: Int) {
        if (newItemCount > lastItemCount) {
            unreadCount += (newItemCount - lastItemCount)
            if (isPinned && newItemCount > 0) {
                scope.launch {
                    listState.animateScrollToItem(newItemCount - 1)
                    unreadCount = 0
                }
            }
        }
        lastItemCount = newItemCount
    }

    /**
     * 用户手动滚动到底部，重置未读计数并重新启用贴底
     */
    fun scrollToBottom() {
        if (lastItemCount <= 0) return
        scope.launch {
            listState.animateScrollToItem(lastItemCount - 1)
            isPinned = true
            unreadCount = 0
        }
    }

    /**
     * 检测用户手指上滑，解除贴底锁定
     */
    val nestedScrollConnection = object : NestedScrollConnection {
        override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
            // 用户手指上滑（available.y < 0）至少 10 像素，解除贴底
            if (available.y < -10f && source == NestedScrollSource.UserInput) {
                if (isPinned) {
                    isPinned = false
                }
            }
            return Offset.Zero
        }
    }
}
