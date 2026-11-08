package com.cordiact.app.ui

import androidx.lifecycle.ViewModel
import com.cordiact.app.ui.composer.ModelOption
import com.cordiact.app.ui.drawer.SessionItem
import com.cordiact.app.ui.drawer.WorkspaceItem
import com.cordiact.app.ui.settings.ModelItem
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 主界面 ViewModel
 * - 管理工作区列表
 * - 管理模型列表
 * - 管理工具开关状态
 * - 管理审批策略
 * 
 * TODO: 连接真实数据源（ConversationRepository / ModelRegistry / ToolManager）
 */
class MainViewModel : ViewModel() {
    // 当前工作区
    private val _currentWorkspace = MutableStateFlow("default")
    val currentWorkspace: StateFlow<String> = _currentWorkspace.asStateFlow()
    
    // 工作区列表（演示数据）
    private val _workspaces = MutableStateFlow(
        listOf(
            WorkspaceItem(
                id = "default",
                name = "默认工作区",
                sessions = listOf(
                    SessionItem("session-1", "CordiAct UI 设计"),
                    SessionItem("session-2", "SSH 后端调试")
                )
            ),
            WorkspaceItem(
                id = "dev",
                name = "开发环境",
                sessions = listOf(
                    SessionItem("session-3", "Android 编译问题排查")
                )
            )
        )
    )
    val workspaces: StateFlow<List<WorkspaceItem>> = _workspaces.asStateFlow()
    
    // 模型列表（演示数据）
    private val _models = MutableStateFlow(
        listOf(
            ModelItem("deepseek-chat", "DeepSeek Chat", "DeepSeek"),
            ModelItem("claude-opus-5", "Claude Opus 5", "Anthropic"),
            ModelItem("gpt-4o", "GPT-4o", "OpenAI")
        )
    )
    val models: StateFlow<List<ModelItem>> = _models.asStateFlow()
    
    // 当前模型（演示数据）
    private val _currentModel = MutableStateFlow("deepseek-chat")
    val currentModel: StateFlow<String> = _currentModel.asStateFlow()
    
    // 工具开关状态（演示数据）
    private val _toolStates = MutableStateFlow(
        mapOf(
            "ssh" to true,
            "termux" to false,
            "screen" to true,
            "browser" to true
        )
    )
    val toolStates: StateFlow<Map<String, Boolean>> = _toolStates.asStateFlow()
    
    // 审批策略（演示数据）
    private val _approvalPolicy = MutableStateFlow("总是审批")
    val approvalPolicy: StateFlow<String> = _approvalPolicy.asStateFlow()
    
    // 模型选项（供 ModelSelector 使用）
    val modelOptions: StateFlow<List<ModelOption>> = MutableStateFlow(
        listOf(
            ModelOption("deepseek-chat", "DeepSeek Chat", "DeepSeek"),
            ModelOption("claude-opus-5", "Claude Opus 5", "Anthropic"),
            ModelOption("gpt-4o", "GPT-4o", "OpenAI")
        )
    ).asStateFlow()
    
    // === 操作方法 ===
    
    fun selectWorkspace(workspaceId: String) {
        _currentWorkspace.value = workspaceId
    }
    
    fun selectModel(modelId: String) {
        _currentModel.value = modelId
    }
    
    fun toggleTool(toolName: String, enabled: Boolean) {
        _toolStates.value = _toolStates.value.toMutableMap().apply {
            put(toolName, enabled)
        }
    }
    
    fun setDefaultModel(modelId: String) {
        _currentModel.value = modelId
    }
}
