package com.cordiact.app.ui.window

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.cordiact.app.ui.window.chat.ChatViewModel
import com.cordiact.app.ui.window.chat.CompactChatDemo

/**
 * 对话窗口
 *
 * 展示结构化会话流（Transcript）：
 * - 回合制边界清晰
 * - 思维链折叠
 * - 工具活动聚合
 * - 智能贴底防打扰
 */
class ChatWindow(
    private val chatViewModel: ChatViewModel? = null
) : WindowDescriptor {
    override val id: String = "chat"
    override val title: String = "对话"
    override val icon: ImageVector = Icons.AutoMirrored.Filled.Chat

    @Composable
    override fun Content(
        sessionId: String,
        onNavigateToWindow: (windowId: String) -> Unit
    ) {
        if (chatViewModel != null) {
            CompactChatDemo(
                viewModel = chatViewModel,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(8.dp)
            )
        } else {
            CompactChatDemo(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(8.dp)
            )
        }
    }
}
