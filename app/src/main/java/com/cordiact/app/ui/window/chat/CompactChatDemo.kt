package com.cordiact.app.ui.window.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 紧凑 UI 组件演示界面
 * 演示：
 * 1. 代码块（6dp 圆角 + 12sp 字号 + 8dp 内边距）
 * 2. 表格（6dp 行间距 + 无框细线）
 * 3. 进度状态卡（长任务状态覆盖显示）
 * 4. 流式文本（墨染渐出效果）
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CompactChatDemo(
    viewModel: ChatViewModel = androidx.lifecycle.viewmodel.compose.viewModel()
) {
    val currentSteps by viewModel.currentSteps.collectAsState()
    val isStreaming by viewModel.isStreaming.collectAsState()
    val scope = rememberCoroutineScope()
    
    var streamingText by remember { mutableStateOf("") }
    
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("紧凑 UI 演示") }
            )
        }
    ) { paddingValues ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // 标题
            item {
                Text(
                    text = "CordiAct Agent 紧凑 UI 组件",
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.padding(vertical = 8.dp)
                )
            }
            
            // 演示 1: 代码块
            item {
                Text(
                    text = "1. 紧凑代码块",
                    style = MaterialTheme.typography.titleMedium
                )
            }
            item {
                CodeBlock(
                    code = """
                        fun main() {
                            println("Hello Agent")
                            val result = compute()
                        }
                    """.trimIndent(),
                    language = "Kotlin"
                )
            }
            
            item { HorizontalDivider() }
            
            // 演示 2: 表格
            item {
                Text(
                    text = "2. 无框细线表格",
                    style = MaterialTheme.typography.titleMedium
                )
            }
            item {
                TableView(
                    headers = listOf("服务商", "支付宝", "微信支付"),
                    rows = listOf(
                        listOf("搬瓦工", "✓ 支持", "✓ 支持"),
                        listOf("DMIT", "✓ 支持", "✗ 不支持"),
                        listOf("V.PS", "✓ 支持", "✓ 支持")
                    )
                )
            }
            
            item { HorizontalDivider() }
            
            // 演示 3: 进度状态卡
            item {
                Text(
                    text = "3. 长任务状态覆盖显示",
                    style = MaterialTheme.typography.titleMedium
                )
            }
            item {
                Button(
                    onClick = {
                        scope.launch {
                            viewModel.resetSteps()
                            
                            viewModel.onToolStart("ssh_exec", "首尔: git pull")
                            delay(1000)
                            viewModel.onToolComplete(true)
                            
                            viewModel.onToolStart("workspace_read_file", "/workspace/config.json")
                            delay(800)
                            viewModel.onToolComplete(true)
                            
                            viewModel.onToolStart("ssh_upload", "首尔: /tmp/data.txt")
                            delay(1200)
                            viewModel.onToolComplete(true)
                            
                            viewModel.onToolStart("ssh_exec", "首尔: systemctl restart app")
                            delay(1500)
                            viewModel.onToolComplete(true)
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("模拟长任务（4 步）")
                }
            }
            item {
                if (currentSteps.isNotEmpty()) {
                    ProgressStatusCard(steps = currentSteps)
                }
            }
            
            item { HorizontalDivider() }
            
            // 演示 4: 流式文本
            item {
                Text(
                    text = "4. 墨染渐出效果",
                    style = MaterialTheme.typography.titleMedium
                )
            }
            item {
                Button(
                    onClick = {
                        scope.launch {
                            streamingText = ""
                            viewModel.startStreaming()
                            
                            val fullText = "CordiAct 是一个移动端 Agent 开发平台。它支持 SSH 远程执行、Termux 本地终端、屏幕自动化、浏览器控制、子 Agent 并发编排等能力。紧凑 UI 设计让一屏可以显示 5-8 条消息，工具细节可见但不拥挤。"
                            
                            fullText.forEach { char ->
                                streamingText += char
                                delay(30)
                            }
                            
                            viewModel.stopStreaming()
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("模拟流式输出")
                }
            }
            item {
                if (streamingText.isNotEmpty()) {
                    StreamingText(
                        text = streamingText,
                        isStreaming = isStreaming
                    )
                }
            }
            
            item { HorizontalDivider() }
            
            // 信息密度对比
            item {
                Text(
                    text = "信息密度对比",
                    style = MaterialTheme.typography.titleMedium
                )
            }
            item {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = "大厂聊天 App：16sp 正文 + 16dp 卡片间距 → 一屏 2-3 条消息",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = "CordiAct 紧凑 UI：14sp 正文 + 8dp 卡片间距 → 一屏 5-8 条消息",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }
    }
}
