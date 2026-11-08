package com.cordiact.app.ui.window.chat

import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 对话窗口 ViewModel
 * - 管理工具步骤状态（长任务中途汇报覆盖显示）
 * - 管理流式输出状态
 */
class ChatViewModel : ViewModel() {
    private val _currentSteps = MutableStateFlow<List<ToolStep>>(emptyList())
    val currentSteps: StateFlow<List<ToolStep>> = _currentSteps.asStateFlow()
    
    private val _isStreaming = MutableStateFlow(false)
    val isStreaming: StateFlow<Boolean> = _isStreaming.asStateFlow()
    
    /**
     * 工具开始执行
     */
    fun onToolStart(toolName: String, args: String) {
        _currentSteps.value += ToolStep(
            summary = "$toolName: $args",
            status = ToolStatus.RUNNING
        )
    }
    
    /**
     * 工具执行完成
     */
    fun onToolComplete(success: Boolean) {
        if (_currentSteps.value.isEmpty()) return
        
        _currentSteps.value = _currentSteps.value.dropLast(1) + 
            _currentSteps.value.last().copy(
                status = if (success) ToolStatus.SUCCESS else ToolStatus.FAILED
            )
    }
    
    /**
     * 重置工具步骤（新回合开始）
     */
    fun resetSteps() {
        _currentSteps.value = emptyList()
    }
    
    /**
     * 流式输出开始
     */
    fun startStreaming() {
        _isStreaming.value = true
    }
    
    /**
     * 流式输出结束
     */
    fun stopStreaming() {
        _isStreaming.value = false
    }
}
