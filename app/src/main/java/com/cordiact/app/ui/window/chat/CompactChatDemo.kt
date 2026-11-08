package com.cordiact.app.ui.window.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 紧凑 UI 对话窗口界面（集成真实 ChatViewModel 消息流 + 工具活动聚合 + 智能贴底）
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CompactChatDemo(
    viewModel: ChatViewModel = androidx.lifecycle.viewmodel.compose.viewModel(),
    modifier: Modifier = Modifier
) {
    val scope = rememberCoroutineScope()
    val messages by viewModel.messages.collectAsState()
    val currentSteps by viewModel.currentSteps.collectAsState()

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text("对话流") }
            )
        },
        bottomBar = {
            Column(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (currentSteps.isNotEmpty()) {
                    ProgressStatusCard(steps = currentSteps)
                }

                Button(
                    onClick = {
                        scope.launch {
                            repeat(3) { idx ->
                                delay(400)
                                viewModel.sendMessage("测试流式消息 #${idx + 1}")
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("模拟流式输出（测试贴底）")
                }
            }
        }
    ) { paddingValues ->
        ChatTranscriptList(
            messages = messages,
            onApprove = { id, rememberChoice ->
                viewModel.approveRequest(id, rememberChoice)
            },
            onReject = { id ->
                viewModel.rejectRequest(id)
            },
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        )
    }
}
