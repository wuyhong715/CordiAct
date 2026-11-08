package com.cordiact.app.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cordiact.app.CordiActApplication
import com.cordiact.app.ui.composer.ModelOption
import com.cordiact.app.ui.drawer.SessionItem
import com.cordiact.app.ui.drawer.WorkspaceItem
import com.cordiact.app.ui.settings.ModelItem
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 主界面 ViewModel（连接真实数据源）
 * - 管理工作区列表（从 WorkspaceManagerService）
 * - 管理模型列表（从 ModelProviderRegistryService）
 * - 管理工具开关状态（从 ToolManagerService）
 * - 管理审批策略（从 ApprovalGateway）
 */
class MainViewModel(
    private val application: CordiActApplication
) : ViewModel() {
    // 当前工作区
    private val _currentWorkspace = MutableStateFlow("")
    val currentWorkspace: StateFlow<String> = _currentWorkspace.asStateFlow()
    
    // 工作区列表
    private val _workspaces = MutableStateFlow<List<WorkspaceItem>>(emptyList())
    val workspaces: StateFlow<List<WorkspaceItem>> = _workspaces.asStateFlow()
    
    // 模型列表
    private val _models = MutableStateFlow<List<ModelItem>>(emptyList())
    val models: StateFlow<List<ModelItem>> = _models.asStateFlow()
    
    // 当前模型
    private val _currentModel = MutableStateFlow("")
    val currentModel: StateFlow<String> = _currentModel.asStateFlow()
    
    // 工具开关状态
    private val _toolStates = MutableStateFlow<Map<String, Boolean>>(emptyMap())
    val toolStates: StateFlow<Map<String, Boolean>> = _toolStates.asStateFlow()
    
    // 审批策略
    private val _approvalPolicy = MutableStateFlow("总是审批")
    val approvalPolicy: StateFlow<String> = _approvalPolicy.asStateFlow()
    
    // 模型选项（供 ModelSelector 使用）
    private val _modelOptions = MutableStateFlow<List<ModelOption>>(emptyList())
    val modelOptions: StateFlow<List<ModelOption>> = _modelOptions.asStateFlow()
    
    init {
        loadWorkspaces()
        loadModels()
        loadToolStates()
    }
    
    // === 加载数据 ===
    
    private fun loadWorkspaces() {
        viewModelScope.launch {
            try {
                val allWorkspaces = application.workspaceManager.list()
                val activeWorkspace = allWorkspaces.firstOrNull { it.active }
                
                _currentWorkspace.value = activeWorkspace?.id ?: ""
                
                _workspaces.value = allWorkspaces.map { ws ->
                    val sessions = try {
                        application.sessions.list(ws.id).map { session ->
                            SessionItem(
                                id = session.id,
                                title = session.title.ifBlank { "未命名会话" }
                            )
                        }
                    } catch (e: Exception) {
                        emptyList()
                    }
                    
                    WorkspaceItem(
                        id = ws.id,
                        name = ws.name,
                        sessions = sessions
                    )
                }
            } catch (e: Exception) {
                // 降级：使用演示数据
                _workspaces.value = listOf(
                    WorkspaceItem("default", "默认工作区", emptyList())
                )
                _currentWorkspace.value = "default"
            }
        }
    }
    
    private fun loadModels() {
        viewModelScope.launch {
            try {
                val registry = application.modelProviders
                val providers = registry.listProviders()
                
                val modelList = providers.flatMap { provider ->
                    provider.knownModels().map { modelId ->
                        ModelItem(
                            id = modelId.id,
                            displayName = modelId.displayName ?: modelId.id,
                            provider = provider.spec.name
                        )
                    }
                }
                
                _models.value = modelList
                _modelOptions.value = modelList.map { model ->
                    ModelOption(
                        id = model.id,
                        displayName = model.displayName,
                        provider = model.provider
                    )
                }
                
                // 默认模型：取第一个
                if (modelList.isNotEmpty()) {
                    _currentModel.value = modelList.first().id
                }
            } catch (e: Exception) {
                // 降级：使用演示数据
                val demoModels = listOf(
                    ModelItem("deepseek-chat", "DeepSeek Chat", "DeepSeek"),
                    ModelItem("claude-opus-5", "Claude Opus 5", "Anthropic")
                )
                _models.value = demoModels
                _modelOptions.value = demoModels.map {
                    ModelOption(it.id, it.displayName, it.provider)
                }
                _currentModel.value = "deepseek-chat"
            }
        }
    }
    
    private fun loadToolStates() {
        viewModelScope.launch {
            try {
                val tools = application.tools
                val enabled = tools.enabledTools()
                
                _toolStates.value = mapOf(
                    "ssh" to enabled.any { it.startsWith("ssh_") },
                    "termux" to enabled.any { it.startsWith("termux_") },
                    "screen" to enabled.any { it.startsWith("tap") || it.startsWith("read_window") },
                    "browser" to enabled.any { it.startsWith("browser_") }
                )
            } catch (e: Exception) {
                // 降级：默认全部开启
                _toolStates.value = mapOf(
                    "ssh" to true,
                    "termux" to false,
                    "screen" to true,
                    "browser" to true
                )
            }
        }
    }
    
    // === 操作方法 ===
    
    fun selectWorkspace(workspaceId: String) {
        _currentWorkspace.value = workspaceId
        // TODO: 切换工作区后重新加载会话列表
    }
    
    fun selectModel(modelId: String) {
        _currentModel.value = modelId
    }
    
    fun toggleTool(toolName: String, enabled: Boolean) {
        _toolStates.value = _toolStates.value.toMutableMap().apply {
            put(toolName, enabled)
        }
        // TODO: 调用 ToolManagerService.setToolEnabled()
    }
    
    fun setDefaultModel(modelId: String) {
        _currentModel.value = modelId
        // TODO: 持久化默认模型配置
    }
}
