package com.cordiact.app.ui.window.chat

import androidx.lifecycle.ViewModel
import com.cordiact.core.action.ApprovalDecision
import com.cordiact.core.action.ApprovalGateway
import com.cordiact.core.action.ApprovalRequest
import com.cordiact.core.conversation.ModelEvent
import com.cordiact.core.conversation.ModelId
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 渲染表面 Artifact 描述符（为 Phase 3 分面渲染保留的 UI 协议缺口）
 */
data class RenderArtifact(
    val id: String,
    val type: ArtifactType,
    val title: String,
    val content: String,
) {
    enum class ArtifactType { HTML, SVG, CODE, MARKDOWN }
}

/**
 * 对话窗口 ViewModel
 * - 管理工具步骤状态（长任务中途汇报覆盖显示）
 * - 管理流式输出状态与思考链
 * - 对接核心层 ModelEvent 事件流
 * - 管理人工介入审批请求（Human-in-the-Loop Gateway）
 * - 暴露分面渲染产物（Render Artifacts）
 */
class ChatViewModel(
    private val approvalGateway: ApprovalGateway? = null,
) : ViewModel() {
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

    private val _selectedModel = MutableStateFlow(ModelId("deepseek", "deepseek-flash"))
    val selectedModel: StateFlow<ModelId> = _selectedModel.asStateFlow()

    private val _pendingApprovals = MutableStateFlow<List<ApprovalRequest>>(emptyList())
    val pendingApprovals: StateFlow<List<ApprovalRequest>> = _pendingApprovals.asStateFlow()

    private val _extractedArtifacts = MutableStateFlow<List<RenderArtifact>>(emptyList())
    val extractedArtifacts: StateFlow<List<RenderArtifact>> = _extractedArtifacts.asStateFlow()

    /**
     * 核心事件流分发处理
     */
    fun onModelEvent(event: ModelEvent) {
        when (event) {
            is ModelEvent.Delta -> {
                _isStreaming.value = true
                _streamingText.value += event.text
                detectArtifacts(_streamingText.value)
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
                detectArtifacts(_streamingText.value)
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
        syncPendingApprovals()
    }

    /**
     * 同步待处理的人工审批请求
     */
    fun syncPendingApprovals() {
        approvalGateway?.let {
            _pendingApprovals.value = it.pendingRequests()
        }
    }

    /**
     * 响应人工审批决策
     */
    fun approveRequest(requestId: String, remember: Boolean = false) {
        approvalGateway?.respond(requestId, ApprovalDecision(approved = true, rememberChoice = remember))
        syncPendingApprovals()
    }

    fun rejectRequest(requestId: String, reason: String? = null) {
        approvalGateway?.respond(requestId, ApprovalDecision(approved = false, reason = reason))
        syncPendingApprovals()
    }

    /**
     * 切换当前模型
     */
    fun selectModel(modelId: ModelId) {
        _selectedModel.value = modelId
    }

    /**
     * 轻量提取代码/HTML/SVG独立产物（为独立Render Canvas预留数据流）
     */
    private fun detectArtifacts(text: String) {
        val artifacts = mutableListOf<RenderArtifact>()
        if (text.contains("```html") && text.contains("```")) {
            val start = text.indexOf("```html") + 7
            val end = text.indexOf("```", start)
            if (end > start) {
                artifacts += RenderArtifact(
                    id = "html_preview",
                    type = RenderArtifact.ArtifactType.HTML,
                    title = "Web 预览表面",
                    content = text.substring(start, end).trim()
                )
            }
        }
        if (text.contains("<svg") && text.contains("</svg>")) {
            val start = text.indexOf("<svg")
            val end = text.indexOf("</svg>", start) + 6
            artifacts += RenderArtifact(
                id = "svg_preview",
                type = RenderArtifact.ArtifactType.SVG,
                title = "矢量图预览",
                content = text.substring(start, end).trim()
            )
        }
        _extractedArtifacts.value = artifacts
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
        _extractedArtifacts.value = emptyList()
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


