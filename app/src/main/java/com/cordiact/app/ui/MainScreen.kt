package com.cordiact.app.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import com.cordiact.app.CordiActApplication
import com.cordiact.app.ui.composer.ModelSelector
import com.cordiact.app.ui.drawer.DrawerContent
import com.cordiact.app.ui.settings.SettingsScreen
import com.cordiact.app.ui.window.chat.CompactChatDemo
import kotlinx.coroutines.launch

/**
 * 主界面（带侧边抽屉 + 设置）
 * - 左滑或点击抽屉图标打开侧边栏
 * - 侧边栏显示工作区列表 + 快捷入口
 * - 点击设置进入设置页
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    application: CordiActApplication
) {
    val viewModel: MainViewModel = viewModel(
        factory = MainViewModelFactory(application)
    )
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    
    val currentWorkspace by viewModel.currentWorkspace.collectAsState()
    val workspaces by viewModel.workspaces.collectAsState()
    val models by viewModel.models.collectAsState()
    val currentModel by viewModel.currentModel.collectAsState()
    val toolStates by viewModel.toolStates.collectAsState()
    val approvalPolicy by viewModel.approvalPolicy.collectAsState()
    val modelOptions by viewModel.modelOptions.collectAsState()
    
    var showSettings by remember { mutableStateOf(false) }
    
    if (showSettings) {
        // 设置页
        SettingsScreen(
            models = models,
            defaultModelId = currentModel,
            toolStates = toolStates,
            approvalPolicy = approvalPolicy,
            onBack = { showSettings = false },
            onAddModel = { /* TODO */ },
            onEditModel = { /* TODO */ },
            onSetDefaultModel = { viewModel.setDefaultModel(it) },
            onToggleTool = { tool, enabled -> viewModel.toggleTool(tool, enabled) },
            onChangeApprovalPolicy = { /* TODO */ }
        )
    } else {
        // 主界面（带抽屉）
        ModalNavigationDrawer(
            drawerState = drawerState,
            drawerContent = {
                DrawerContent(
                    currentWorkspace = currentWorkspace,
                    workspaces = workspaces,
                    onCreateSession = {
                        scope.launch { drawerState.close() }
                        // TODO: 创建新会话
                    },
                    onOpenSettings = {
                        scope.launch { drawerState.close() }
                        showSettings = true
                    },
                    onOpenPlugins = {
                        scope.launch { drawerState.close() }
                        // TODO: 打开插件管理
                    },
                    onSelectWorkspace = { workspaceId ->
                        viewModel.selectWorkspace(workspaceId)
                    },
                    onSelectSession = { sessionId ->
                        scope.launch { drawerState.close() }
                        // TODO: 切换到指定会话
                    }
                )
            }
        ) {
            Scaffold(
                topBar = {
                    TopAppBar(
                        title = {
                            // 模型快速切换选择器
                            ModelSelector(
                                currentModelId = currentModel,
                                models = modelOptions,
                                onSelectModel = { viewModel.selectModel(it) }
                            )
                        }
                    )
                }
            ) { paddingValues ->
                // 主内容区（暂时显示紧凑 UI 演示）
                CompactChatDemo(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(paddingValues)
                )
            }
        }
    }
}
