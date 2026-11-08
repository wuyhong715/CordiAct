package com.cordiact.app.ui.window.chat

import androidx.lifecycle.ViewModel
import com.cordiact.core.conversation.ModelEvent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 对话窗口 ViewModel
 * - 管理工具步骤状态（长任务中途汇报覆盖显示）
 * - 管理流式输出状态与思考链
 * - 对接核心层 ModelEvent 事件流
 */
class ChatViewModel : ViewModel() {
    private val _currentSteps = MutableStateFlow<List<ToolStep>>(emptyList())
    val currentSteps: StateFlow<List<ToolStep>> = _currentSteps.asStateFlow()
    
    private val _isStreaming = MutableStateFlow(false)
    val isStreaming: StateFlow<Boolean> = _isStreaming.asStateFlow()

    private val _thinkingText = MutableStateFlow("")
    val thinkingText: StateFlow<String> = _thinkingText.asStateFlow()

    private val _streamingText = MutableStateFlow("")
    val streamingText: StateFlow<String> = _streamingText.asStateFlow()

    private val _lastCompactedInfo = MutableStateFlow<String?>(null)
    val lastCompactedInfo: StateFlow<String?> = _lastCompactedInfo.asStateFlow()

    /**
     * 核心事件流分发处理
     */
    fun onModelEvent(event: ModelEvent) {
        when (event) {
            is ModelEvent.Delta -> {
                _isStreaming.value = true
                _streamingText.value += event.text
            }
            is ModelEvent.Thinking -> {
                _thinkingText.value += event.text
            }
            is ModelEvent.ToolCallStarted -> {
                val shortArgs = event.argumentsJson.replace("\n", " ").take(60)
                _currentSteps.value += ToolStep(
                    summary = "${event.toolName}: $shortArgs",
                    status = ToolStatus.RUNNING
                )
            }
            is ModelEvent.ToolCallCompleted -> {
                if (_currentSteps.value.isNotEmpty()) {
                    val status = if (event.success) ToolStatus.SUCCESS else ToolStatus.FAILED
                    val last = _currentSteps.value.last()
                    _currentSteps.value = _currentSteps.value.dropLast(1) + last.copy(status = status)
                }
            }
            is ModelEvent.ContextCompacted -> {
                _lastCompactedInfo.value = "上下文自动压缩：节省 ${event.savedTokens} tokens"
            }
            is ModelEvent.Completed -> {
                _isStreaming.value = false
            }
            is ModelEvent.Failed -> {
                _isStreaming.value = false
                if (_currentSteps.value.isNotEmpty() && _currentSteps.value.last().status == ToolStatus.RUNNING) {
                    val last = _currentSteps.value.last()
                    _currentSteps.value = _currentSteps.value.dropLast(1) + last.copy(status = ToolStatus.FAILED)
                }
            }
            else -> Unit
        }
    }
    
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
     * 重置工具步骤与流式状态（新回合开始）
     */
    fun resetSteps() {
        _currentSteps.value = emptyList()
        _streamingText.value = ""
        _thinkingText.value = ""
        _lastCompactedInfo.value = null
        _isStreaming.value = false
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

