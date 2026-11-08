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
 * - 管理模型列表（从 ModelAdapterService / ModelProviderRegistryService）
 * - 管理工具开关状态（从 ToolManagerService）
 * - 管理审批策略（从 ApprovalGateway）
 */
class MainViewModel(
    private val application: CordiActApplication
) : ViewModel() {
    // 当前工作区 ID
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
                val activeWorkspace = runCatching { application.workspaceManager.activeWorkspace() }.getOrNull()
                    ?: allWorkspaces.firstOrNull()

                _currentWorkspace.value = activeWorkspace?.id ?: ""

                _workspaces.value = allWorkspaces.map { ws ->
                    val sessions = try {
                        application.sessions.listSessions(ws.id).map { session ->
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
                val descriptors = application.modelAdapter.models()
                val providerNames = runCatching {
                    application.modelProviders.providers().associate { it.providerId to it.displayName }
                }.getOrDefault(emptyMap())

                val modelList = if (descriptors.isNotEmpty()) {
                    descriptors.map { desc ->
                        ModelItem(
                            id = desc.id.modelId,
                            displayName = desc.displayName.ifBlank { desc.id.modelId },
                            provider = providerNames[desc.id.providerId] ?: desc.id.providerId
                        )
                    }
                } else {
                    application.modelProviders.providers().flatMap { provider ->
                        provider.models.map { modelId ->
                            ModelItem(
                                id = modelId,
                                displayName = modelId,
                                provider = provider.displayName
                            )
                        }
                    }
                }

                if (modelList.isNotEmpty()) {
                    _models.value = modelList
                    _modelOptions.value = modelList.map { model ->
                        ModelOption(
                            id = model.id,
                            displayName = model.displayName,
                            provider = model.provider
                        )
                    }
                    _currentModel.value = modelList.first().id
                } else {
                    applyDemoModels()
                }
            } catch (e: Exception) {
                applyDemoModels()
            }
        }
    }

    private fun applyDemoModels() {
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

    private fun loadToolStates() {
        viewModelScope.launch {
            try {
                val workspaceId = _currentWorkspace.value.ifBlank {
                    runCatching { application.workspaceManager.activeWorkspace().id }.getOrDefault("")
                }
                val enabledIds = application.tools.resolveEnabled(workspaceId).map { it.id }

                _toolStates.value = mapOf(
                    "ssh" to enabledIds.any { it.startsWith("ssh_") },
                    "termux" to enabledIds.any { it.startsWith("termux_") },
                    "screen" to enabledIds.any { it.startsWith("tap") || it.startsWith("read_window") || it.startsWith("screen_") },
                    "browser" to enabledIds.any { it.startsWith("browser_") }
                )
            } catch (e: Exception) {
                // 降级：默认开启
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
        runCatching { application.workspaceManager.select(workspaceId) }
        loadWorkspaces()
        loadToolStates()
    }

    fun selectModel(modelId: String) {
        _currentModel.value = modelId
    }

    fun toggleTool(toolName: String, enabled: Boolean) {
        _toolStates.value = _toolStates.value.toMutableMap().apply {
            put(toolName, enabled)
        }
        val workspaceId = _currentWorkspace.value
        if (workspaceId.isNotBlank()) {
            runCatching {
                val matchingTools = application.tools.list(workspaceId)
                    .map { it.descriptor.id }
                    .filter { it.startsWith("${toolName}_") || it == toolName }
                matchingTools.forEach { toolId ->
                    application.tools.setEnabled(workspaceId, toolId, enabled)
                }
            }
        }
    }

    fun setDefaultModel(modelId: String) {
        _currentModel.value = modelId
    }
}
