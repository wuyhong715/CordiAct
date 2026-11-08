package com.cordiact.app.ui.window

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier

/**
 * 多窗口横向分页容器
 * 
 * 设计要点：
 * - HorizontalPager 实现流畅横滑
 * - 所有窗口在后台保持状态（不销毁 ViewModel）
 * - 支持双向数据绑定（selectedWindowId ↔ pagerState.currentPage）
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun WindowPager(
    windows: List<WindowDescriptor>,
    selectedWindowId: String,
    sessionId: String,
    onSelectWindow: (String) -> Unit,
    onNavigateToWindow: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val selectedIndex = windows.indexOfFirst { it.id == selectedWindowId }.coerceAtLeast(0)
    val pagerState = rememberPagerState(
        initialPage = selectedIndex,
        pageCount = { windows.size }
    )
    
    // 双向绑定：pagerState.currentPage 变化时，同步到 selectedWindowId
    LaunchedEffect(pagerState.currentPage, pagerState.isScrollInProgress) {
        if (!pagerState.isScrollInProgress && pagerState.currentPage != selectedIndex) {
            val newWindowId = windows.getOrNull(pagerState.currentPage)?.id
            if (newWindowId != null && newWindowId != selectedWindowId) {
                onSelectWindow(newWindowId)
            }
        }
    }
    
    // 双向绑定：selectedWindowId 变化时，跳转到对应页面
    LaunchedEffect(selectedWindowId) {
        val targetIndex = windows.indexOfFirst { it.id == selectedWindowId }
        if (targetIndex >= 0 && targetIndex != pagerState.currentPage) {
            pagerState.animateScrollToPage(targetIndex)
        }
    }
    
    HorizontalPager(
        state = pagerState,
        modifier = modifier.fillMaxSize(),
        key = { windows[it].id }
    ) { pageIndex ->
        val window = windows[pageIndex]
        Box(modifier = Modifier.fillMaxSize()) {
            window.Content(
                sessionId = sessionId,
                onNavigateToWindow = onNavigateToWindow
            )
        }
    }
}
