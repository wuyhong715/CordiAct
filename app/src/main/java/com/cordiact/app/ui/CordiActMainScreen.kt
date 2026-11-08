package com.cordiact.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.cordiact.app.CordiActApplication
import com.cordiact.app.ui.composer.*
import com.cordiact.app.ui.drawer.DrawerContent
import com.cordiact.app.ui.settings.SettingsScreen
import com.cordiact.app.ui.window.*
import com.cordiact.app.ui.window.chat.ChatViewModel
import com.cordiact.app.ui.window.preview.PreviewWindow
import com.cordiact.app.ui.window.trajectory.TrajectoryWindow
import kotlinx.coroutines.launch

/**
 * CordiAct 主界面（三层架构 + 真实数据源对接）
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CordiActMainScreen(
    application: CordiActApplication
) {
    val viewModel: MainViewModel = androidx.lifecycle.viewmodel.compose.viewModel(
        factory = MainViewModelFactory(application)
    )
    val chatViewModel: ChatViewModel = androidx.lifecycle.viewmodel.compose.viewModel(
        factory = ChatViewModel.factory(application)
    )

    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()

    val currentWorkspace by viewModel.currentWorkspace.collectAsState()
    val workspaces by viewModel.workspaces.collectAsState()
    val models by viewModel.models.collectAsState()
    val currentModel by viewModel.currentModel.collectAsState()
    val toolStates by viewModel.toolStates.collectAsState()
    val approvalPolicy by viewModel.approvalPolicy.collectAsState()

    val isStreaming by chatViewModel.isStreaming.collectAsState()
    val pendingApprovals by chatViewModel.pendingApprovals.collectAsState()

    val currentWorkspaceName = workspaces.find { it.id == currentWorkspace }?.name
        ?: currentWorkspace.ifBlank { "拟态" }

    var showSettings by remember { mutableStateOf(false) }
    var selectedWindowId by remember { mutableStateOf("chat") }

    // 输入栏状态
    var inputText by remember { mutableStateOf("") }
    var contextTokens by remember { mutableStateOf<List<ContextToken>>(emptyList()) }

    // 动态微胶囊状态（联动真实流式状态与审批网关）
    var activityState by remember { mutableStateOf<ActivityState?>(null) }

    LaunchedEffect(pendingApprovals) {
        val firstApproval = pendingApprovals.firstOrNull()
        if (firstApproval != null) {
            activityState = ActivityState(
                type = ActivityType.APPROVAL,
                message = "待确认高危操作：${firstApproval.toolName} (${firstApproval.summary})",
                requiresInteraction = true,
                targetWindow = "chat"
            )
        }
    }

    // 注册窗口
    val windows = remember(chatViewModel) {
        listOf(
            ChatWindow(chatViewModel),
            TrajectoryWindow(),
            PreviewWindow()
        )
    }

    if (showSettings) {
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
        ModalNavigationDrawer(
            drawerState = drawerState,
            drawerContent = {
                DrawerContent(
                    currentWorkspace = currentWorkspace,
                    workspaces = workspaces,
                    onCreateSession = {
                        scope.launch { drawerState.close() }
                    },
                    onOpenSettings = {
                        scope.launch { drawerState.close() }
                        showSettings = true
                    },
                    onOpenPlugins = {
                        scope.launch { drawerState.close() }
                    },
                    onSelectWorkspace = { viewModel.selectWorkspace(it) },
                    onSelectSession = { sessionId ->
                        chatViewModel.loadSessionMessages(sessionId)
                        scope.launch { drawerState.close() }
                    }
                )
            }
        ) {
            Scaffold(
                topBar = {
                    Column {
                        // 顶部导航条（冷区）
                        TopAppBar(
                            title = {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = currentWorkspaceName,
                                        style = MaterialTheme.typography.titleMedium
                                    )

                                    // 运行状态心跳点（绿点微呼吸/蓝点思考）
                                    Box(
                                        modifier = Modifier
                                            .size(12.dp)
                                            .padding(4.dp)
                                    ) {
                                        Surface(
                                            shape = androidx.compose.foundation.shape.CircleShape,
                                            color = if (isStreaming)
                                                MaterialTheme.colorScheme.tertiary
                                            else
                                                MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.fillMaxSize()
                                        ) {}
                                    }
                                }
                            },
                            navigationIcon = {
                                IconButton(onClick = { scope.launch { drawerState.open() } }) {
                                    Icon(Icons.Default.Menu, contentDescription = "打开抽屉")
                                }
                            }
                        )

                        // 窗口 Tab 栏
                        WindowTabBar(
                            windows = windows,
                            selectedWindowId = selectedWindowId,
                            sessionId = currentWorkspace,
                            onSelectWindow = { selectedWindowId = it }
                        )
                    }
                },
                bottomBar = {
                    Column {
                        // 动态微胶囊（悬浮任务进度 & 审批通知）
                        DynamicActivityPill(
                            activityState = activityState,
                            onDismiss = { activityState = null },
                            onNavigate = { windowId ->
                                selectedWindowId = windowId
                            },
                            modifier = Modifier.padding(bottom = 4.dp)
                        )

                        // 智能输入栏
                        AgentComposer(
                            input = inputText,
                            onInputChange = { inputText = it },
                            onSend = {
                                val textToSend = inputText
                                inputText = ""
                                activityState = ActivityState(
                                    type = ActivityType.RUNNING,
                                    message = "正在执行：${textToSend.take(20)}...",
                                    progress = 0.5f,
                                    targetWindow = "trajectory"
                                )
                                chatViewModel.sendMessage(
                                    input = textToSend,
                                    modelIdString = currentModel,
                                    onEventUpdate = { statusText ->
                                        activityState = ActivityState(
                                            type = ActivityType.RUNNING,
                                            message = statusText,
                                            targetWindow = "trajectory"
                                        )
                                    }
                                )
                            },
                            selectedModel = currentModel,
                            onSelectModel = { /* TODO: 打开模型选择器 */ },
                            contextTokens = contextTokens,
                            onAddContextToken = { type ->
                                contextTokens = contextTokens + ContextToken(
                                    id = System.currentTimeMillis().toString(),
                                    type = type,
                                    label = when (type) {
                                        TokenType.CONTEXT -> "示例文件.txt"
                                        TokenType.SECRET -> "API_KEY"
                                        TokenType.COMMAND -> "build"
                                    },
                                    value = "示例值"
                                )
                            },
                            onRemoveContextToken = { token ->
                                contextTokens = contextTokens - token
                            },
                            isProcessing = isStreaming
                        )
                    }
                }
            ) { paddingValues ->
                // 沉浸式窗口视口（中间主内容区）
                WindowPager(
                    windows = windows,
                    selectedWindowId = selectedWindowId,
                    sessionId = currentWorkspace,
                    onSelectWindow = { selectedWindowId = it },
                    onNavigateToWindow = { selectedWindowId = it },
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(paddingValues)
                )
            }
        }
    }
}
